import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  Button,
  Drawer,
  List,
  Space,
  Spin,
  Tabs,
  Tag,
  Tooltip,
  Tree,
  Typography,
  message,
} from 'antd';
import type { DataNode } from 'antd/es/tree';
import {
  NodeCollapseOutlined,
  NodeExpandOutlined,
  PlayCircleOutlined,
  StopOutlined,
} from '@ant-design/icons';
import type { EntryScanConfig } from '../types';
import {
  cancelTrial,
  getActiveTrial,
  getLatestTrial,
  getTrial,
  getTrialEntries,
  listTrialHistory,
  triggerTrial,
  type EntryScanTrialSummary,
} from '../api/trialRun';
import { collectExpandableKeys } from '../utils/treeExpandKeys';

const { Text } = Typography;

const ENTRY_TYPE_TREE_LABEL: Record<string, string> = {
  CONTROLLER: 'Controller',
  SCHEDULED_JOB: 'Job',
  MQ_LISTENER: 'MQ',
  OTHER: '其他',
  COMPONENT: '组件',
  CUSTOM: '自定义',
  APPLICATION: '应用入口',
  MAIN: 'Main',
};

const POLL_INTERVAL_MS = 2000;
const POLL_MAX_MS = 30 * 60 * 1000;

const STATUS_LABEL: Record<string, { text: string; color: string }> = {
  PENDING: { text: '准备中', color: 'processing' },
  RUNNING: { text: '执行中', color: 'processing' },
  SUCCESS: { text: '成功', color: 'success' },
  FAILED: { text: '失败', color: 'error' },
  CANCELLED: { text: '已取消', color: 'default' },
};

const formatDateTime = (value?: string): string =>
  value ? new Date(value).toLocaleString() : '-';

function buildTrialTreeData(trialEntries: unknown[]): DataNode[] {
  type NormalizedEntry = {
    className: string;
    entryType?: string;
    remark?: string;
    methods: Record<string, string>[];
  };
  const normalized: NormalizedEntry[] = trialEntries.map((e: unknown) => {
    const row = e as Record<string, unknown>;
    const base = (row.base ?? row) as Record<string, unknown>;
    return {
      className: String(base.className ?? ''),
      entryType: base.entryType as string | undefined,
      remark: base.remark as string | undefined,
      methods: (row.methods ?? base.methods ?? []) as Record<string, string>[],
    };
  });
  const groups: Record<string, typeof normalized> = {};
  normalized.forEach((cls) => {
    const t = (cls.entryType as string) || 'UNKNOWN';
    if (!groups[t]) groups[t] = [];
    groups[t].push(cls);
  });
  return Object.entries(groups)
    .sort(([, a], [, b]) => b.length - a.length)
    .map(([k, items]) => ({
      key: k,
      title: (
        <Space>
          <Tag color="cyan">{ENTRY_TYPE_TREE_LABEL[k] ?? k}</Tag>
          <Text type="secondary">{items.length} 类</Text>
        </Space>
      ),
      selectable: false,
      children: items.map((cls) => ({
        key: cls.className as string,
        title: (
          <Space size={4}>
            <Text strong>{(cls.className as string).split('.').pop()}</Text>
            {cls.remark && (
              <Text type="secondary" style={{ fontSize: 11 }}>
                {cls.remark as string}
              </Text>
            )}
          </Space>
        ),
        children: ((cls.methods as Record<string, string>[]) || []).map((method, i) => ({
          key: `${cls.className}-${i}`,
          isLeaf: true,
          title: (
            <Space size={4} style={{ fontSize: 12 }}>
              {method.httpMethod && (
                <Tag color="geekblue" style={{ fontSize: 11 }}>
                  {method.httpMethod}
                </Tag>
              )}
              {method.httpPath && (
                <Text code style={{ fontSize: 11 }}>
                  {method.httpPath}
                </Text>
              )}
              {!method.httpMethod && method.annotation && (
                <Tag style={{ fontSize: 11 }}>{method.annotation}</Tag>
              )}
              <Text style={{ fontSize: 12 }}>
                {method.methodSignature || method.methodName}
              </Text>
            </Space>
          ),
        })),
      })),
    }));
}

export interface EntryScanTrialDrawerProps {
  open: boolean;
  repoId?: number;
  systemId?: number;
  /** 关闭 Drawer 回调 */
  onClose: () => void;
  /** 父级在调用 triggerTrial() 时被通知应把 open 置为 true */
  onOpenRequest?: () => void;
  /** 试跑锁定状态变化通知（用于父级按钮 loading / disabled） */
  onLockChange?: (locked: boolean) => void;
}

export interface EntryScanTrialDrawerRef {
  /** 使用给定配置触发一次试跑，并打开 Drawer 展示结果；返回触发的 trialId，无效时返回 null */
  triggerTrial: (config: EntryScanConfig) => Promise<number | null>;
  /** 把 Drawer 切到 history tab 并确保打开 */
  openHistory: () => Promise<void>;
  /** 直接把最近一次试跑详情加载到 current tab 并打开 Drawer；若无 latest 则无副作用 */
  viewLatest: () => Promise<void>;
}

/** 入口扫描试跑结果 Drawer（含触发、轮询、历史）。可独立使用，也可嵌入外层 Modal */
const EntryScanTrialDrawer = forwardRef<EntryScanTrialDrawerRef, EntryScanTrialDrawerProps>(
  ({ open, repoId, systemId, onClose, onOpenRequest, onLockChange }, ref) => {
    const [latestTrial, setLatestTrial] = useState<EntryScanTrialSummary | null>(null);
    const [history, setHistory] = useState<EntryScanTrialSummary[]>([]);
    const [historyTotal, setHistoryTotal] = useState(0);
    const [historyLoading, setHistoryLoading] = useState(false);

    const [viewingTrialId, setViewingTrialId] = useState<number | null>(null);
    const [trialStatus, setTrialStatus] = useState<string | null>(null);
    const [trialStartedAt, setTrialStartedAt] = useState<string | undefined>();
    const [trialEntries, setTrialEntries] = useState<unknown[]>([]);
    const [trialError, setTrialError] = useState('');
    const [drawerOpen, setDrawerOpen] = useState(false);
    const [drawerTab, setDrawerTab] = useState<'current' | 'history'>('current');
    const [cancelling, setCancelling] = useState(false);

    const pollTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const pollStartedRef = useRef(0);
    const pollRepoRef = useRef<number | undefined>(undefined);

    // 把父级 callback 放进 ref，避免父级组件每次 render 引用变化触发内部 useEffect/useCallback 重建
    const onLockChangeRef = useRef(onLockChange);
    const onOpenRequestRef = useRef(onOpenRequest);
    useEffect(() => {
      onLockChangeRef.current = onLockChange;
    }, [onLockChange]);
    useEffect(() => {
      onOpenRequestRef.current = onOpenRequest;
    }, [onOpenRequest]);

    const clearPoll = useCallback(() => {
      if (pollTimerRef.current) {
        clearTimeout(pollTimerRef.current);
        pollTimerRef.current = null;
      }
    }, []);

    const notifyLock = useCallback((locked: boolean) => {
      onLockChangeRef.current?.(locked);
    }, []);

    const loadTrialDetail = useCallback(
      async (id: number) => {
        if (!repoId) return;
        const t = await getTrial(repoId, id);
        setViewingTrialId(t.id);
        setTrialStatus(t.status);
        setTrialStartedAt(t.startedAt);
        setTrialError(t.errorMessage || '');
        if (t.status === 'SUCCESS') {
          const entries = await getTrialEntries(repoId, id);
          setTrialEntries(entries || []);
        } else {
          setTrialEntries([]);
        }
      },
      [repoId],
    );

    const refreshHistory = useCallback(async () => {
      if (!repoId) return;
      setHistoryLoading(true);
      try {
        const page = await listTrialHistory(repoId, { current: 1, size: 20 });
        setHistory(page.records || []);
        setHistoryTotal(page.total || 0);
      } catch {
        setHistory([]);
        setHistoryTotal(0);
      } finally {
        setHistoryLoading(false);
      }
    }, [repoId]);

    const startPoll = useCallback(
      (id: number) => {
        if (!repoId) return;
        clearPoll();
        pollStartedRef.current = Date.now();
        pollRepoRef.current = repoId;

        const poll = async () => {
          if (pollRepoRef.current !== repoId) return;
          if (Date.now() - pollStartedRef.current > POLL_MAX_MS) {
            setTrialError('试跑轮询超时，请取消后重试或查看历史记录');
            notifyLock(false);
            return;
          }
          try {
            const t = await getTrial(repoId, id);
            setTrialStatus(t.status);
            setTrialStartedAt(t.startedAt);
            if (t.status === 'SUCCESS') {
              const entries = await getTrialEntries(repoId, id);
              setTrialEntries(entries || []);
              setTrialError('');
              notifyLock(false);
              setLatestTrial({
                id: t.id,
                repositoryId: t.repositoryId,
                userId: t.userId,
                status: t.status,
                startedAt: t.startedAt,
                finishedAt: t.finishedAt,
                entryCount: entries?.length ?? 0,
              });
              refreshHistory();
            } else if (t.status === 'FAILED' || t.status === 'CANCELLED') {
              setTrialError(t.errorMessage || (t.status === 'CANCELLED' ? '试跑已取消' : '试跑失败'));
              setTrialEntries([]);
              notifyLock(false);
              refreshHistory();
            } else {
              notifyLock(true);
              pollTimerRef.current = setTimeout(poll, POLL_INTERVAL_MS);
            }
          } catch {
            pollTimerRef.current = setTimeout(poll, POLL_INTERVAL_MS);
          }
        };
        poll();
      },
      [repoId, clearPoll, refreshHistory, notifyLock],
    );

    const refreshTrialContext = useCallback(async () => {
      if (!repoId) return;
      const [active, latest] = await Promise.all([
        getActiveTrial(repoId).catch(() => null),
        getLatestTrial(repoId).catch(() => null),
      ]);
      notifyLock(!!active);
      setLatestTrial(latest);
      await refreshHistory();

      if (active) {
        setDrawerTab('current');
        setDrawerOpen(true);
        await loadTrialDetail(active.id);
        startPoll(active.id);
      }
    }, [repoId, refreshHistory, loadTrialDetail, startPoll, notifyLock]);

    // 父级 open 变化时的副作用：true 时拉上下文（含 active 自动开 drawer + 轮询），
    // false 时只停止轮询，不重置内部状态，保证下次 open=true 时可继续展示。
    useEffect(() => {
      if (open) {
        if (repoId) refreshTrialContext();
      } else {
        clearPoll();
      }
      return clearPoll;
    }, [open, repoId, refreshTrialContext, clearPoll]);

    const handleTriggerTrial = useCallback(
      async (config: EntryScanConfig): Promise<number | null> => {
        if (!repoId || !systemId) {
          message.warning('需要已有仓库（已保存）才能试跑');
          return null;
        }
        try {
          const trial = await triggerTrial(repoId, systemId, config);
          setViewingTrialId(trial.id);
          setTrialStatus(trial.status);
          setTrialStartedAt(trial.startedAt);
          setTrialError('');
          setTrialEntries([]);
          notifyLock(true);
          setDrawerTab('current');
          setDrawerOpen(true);
          onOpenRequestRef.current?.();
          startPoll(trial.id);
          return trial.id;
        } catch (e: unknown) {
          const err = e as { response?: { data?: { message?: string } }; message?: string };
          message.error(err?.response?.data?.message || err?.message || '试跑触发失败');
          return null;
        }
      },
      [repoId, systemId, startPoll, notifyLock],
    );

    const handleCancelTrial = async () => {
      if (!repoId || !viewingTrialId) return;
      setCancelling(true);
      try {
        const ok = await cancelTrial(repoId, viewingTrialId);
        if (ok) {
          message.success('已取消试跑');
          clearPoll();
          setTrialStatus('CANCELLED');
          notifyLock(false);
          setTrialError('试跑已取消');
          refreshHistory();
        } else {
          message.warning('试跑已结束，无法取消');
          await loadTrialDetail(viewingTrialId);
        }
      } catch {
        message.error('取消试跑失败');
      } finally {
        setCancelling(false);
      }
    };

    const handleViewHistoryItem = async (item: EntryScanTrialSummary) => {
      setDrawerTab('current');
      await loadTrialDetail(item.id);
    };

    const handleOpenHistory = useCallback(async () => {
      setDrawerTab('history');
      setDrawerOpen(true);
      onOpenRequestRef.current?.();
      await refreshHistory();
    }, [refreshHistory]);

    const handleViewLatest = useCallback(async () => {
      if (!latestTrial) {
        // 没有 latest 时退化为拉取一次
        if (!repoId) return;
        const latest = await getLatestTrial(repoId).catch(() => null);
        if (!latest) {
          message.info('暂无试跑记录');
          return;
        }
        setLatestTrial(latest);
        setDrawerTab('current');
        setDrawerOpen(true);
        onOpenRequestRef.current?.();
        await loadTrialDetail(latest.id);
        if (latest.status === 'PENDING' || latest.status === 'RUNNING') {
          startPoll(latest.id);
        }
        return;
      }
      setDrawerTab('current');
      setDrawerOpen(true);
      onOpenRequestRef.current?.();
      await loadTrialDetail(latestTrial.id);
      if (latestTrial.status === 'PENDING' || latestTrial.status === 'RUNNING') {
        startPoll(latestTrial.id);
      }
    }, [latestTrial, repoId, loadTrialDetail, startPoll]);

    useImperativeHandle(
      ref,
      () => ({
        triggerTrial: async (config) => handleTriggerTrial(config),
        openHistory: handleOpenHistory,
        viewLatest: handleViewLatest,
      }),
      [handleTriggerTrial, handleOpenHistory, handleViewLatest],
    );

    const trialTreeData = useMemo(() => buildTrialTreeData(trialEntries), [trialEntries]);
    const allExpandableKeys = useMemo(
      () => collectExpandableKeys(trialTreeData),
      [trialTreeData],
    );
    const [expandedKeys, setExpandedKeys] = useState<string[]>([]);

    useEffect(() => {
      setExpandedKeys(allExpandableKeys);
    }, [allExpandableKeys]);

    const isRunning = trialStatus === 'PENDING' || trialStatus === 'RUNNING';

    const renderTrialContent = () => {
      if (isRunning) {
        return (
          <div style={{ textAlign: 'center', padding: 48 }}>
            <Spin size="large" />
            <Text type="secondary" style={{ display: 'block', marginTop: 16 }}>
              {trialStatus === 'PENDING' ? '准备中…' : '正在拉代码 + AST 解析 + 入口识别…'}
            </Text>
            {trialStartedAt && (
              <Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12 }}>
                开始于 {formatDateTime(trialStartedAt)}
              </Text>
            )}
            <Button
              danger
              icon={<StopOutlined />}
              loading={cancelling}
              style={{ marginTop: 24 }}
              onClick={handleCancelTrial}
            >
              取消试跑
            </Button>
          </div>
        );
      }
      if (trialStatus === 'FAILED' || trialStatus === 'CANCELLED') {
        return <Text type="danger">{trialError || '试跑失败，请查看后端日志。'}</Text>;
      }
      if (trialStatus === 'SUCCESS' && trialEntries.length === 0) {
        return <Text type="secondary">未识别到任何入口。请调整扫描规则后重试。</Text>;
      }
      if (trialEntries.length > 0) {
        return (
          <>
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 8 }}>
              <Space size={2}>
                <Tooltip title="全部展开">
                  <Button
                    size="small"
                    type="text"
                    icon={<NodeExpandOutlined />}
                    disabled={trialTreeData.length === 0}
                    onClick={() => setExpandedKeys(allExpandableKeys)}
                  />
                </Tooltip>
                <Tooltip title="全部折叠">
                  <Button
                    size="small"
                    type="text"
                    icon={<NodeCollapseOutlined />}
                    disabled={trialTreeData.length === 0}
                    onClick={() => setExpandedKeys([])}
                  />
                </Tooltip>
              </Space>
            </div>
            <Tree
              treeData={trialTreeData}
              expandedKeys={expandedKeys}
              onExpand={(keys) => setExpandedKeys(keys.map(String))}
              showLine={{ showLeafIcon: false }}
              blockNode
              style={{ fontSize: 13 }}
            />
          </>
        );
      }
      return <Text type="secondary">选择一条历史记录查看详情</Text>;
    };

    const statusTag = (status: string) => {
      const meta = STATUS_LABEL[status] || { text: status, color: 'default' };
      return <Tag color={meta.color}>{meta.text}</Tag>;
    };

    const handleDrawerClose = () => {
      setDrawerOpen(false);
      onClose();
    };

    return (
      <Drawer
        title={
          <Space>
            <PlayCircleOutlined />
            试跑结果 {viewingTrialId ? `#${viewingTrialId}` : ''}
            {trialStartedAt && (
              <Text type="secondary" style={{ fontSize: 13 }}>
                {formatDateTime(trialStartedAt)}
              </Text>
            )}
          </Space>
        }
        open={drawerOpen}
        onClose={handleDrawerClose}
        width={640}
      >
        <Tabs
          activeKey={drawerTab}
          onChange={(k) => setDrawerTab(k as 'current' | 'history')}
          items={[
            {
              key: 'current',
              label: '当前查看',
              children: renderTrialContent(),
            },
            {
              key: 'history',
              label: `历史记录${historyTotal ? ` (${historyTotal})` : ''}`,
              children: (
                <List
                  loading={historyLoading}
                  dataSource={history}
                  locale={{ emptyText: '暂无试跑记录' }}
                  renderItem={(item) => (
                    <List.Item
                      actions={[
                        <Button
                          key="view"
                          type="link"
                          size="small"
                          onClick={() => handleViewHistoryItem(item)}
                        >
                          查看
                        </Button>,
                      ]}
                    >
                      <List.Item.Meta
                        title={
                          <Space>
                            <Text>#{item.id}</Text>
                            {statusTag(item.status)}
                            {item.status === 'SUCCESS' && (
                              <Text type="secondary">{item.entryCount ?? 0} 类</Text>
                            )}
                          </Space>
                        }
                        description={
                          <Space direction="vertical" size={0}>
                            <Text type="secondary" style={{ fontSize: 12 }}>
                              触发 {formatDateTime(item.startedAt)}
                              {item.finishedAt ? ` · 结束 ${formatDateTime(item.finishedAt)}` : ''}
                            </Text>
                            {item.userId && (
                              <Text type="secondary" style={{ fontSize: 12 }}>
                                操作人 {item.userId}
                              </Text>
                            )}
                            {item.errorMessage && item.status === 'FAILED' && (
                              <Text type="danger" style={{ fontSize: 12 }}>
                                {item.errorMessage}
                              </Text>
                            )}
                          </Space>
                        }
                      />
                    </List.Item>
                  )}
                />
              ),
            },
          ]}
        />
      </Drawer>
    );
  },
);

EntryScanTrialDrawer.displayName = 'EntryScanTrialDrawer';

export default EntryScanTrialDrawer;
