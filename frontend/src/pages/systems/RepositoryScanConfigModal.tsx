import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Drawer,
  Form,
  List,
  Modal,
  Space,
  Spin,
  Tabs,
  Tag,
  Tooltip,
  Tree,
  Typography,
  message,
} from 'antd';
import type { FormInstance } from 'antd';
import type { DataNode } from 'antd/es/tree';
import {
  HistoryOutlined,
  NodeCollapseOutlined,
  NodeExpandOutlined,
  PlayCircleOutlined,
  StopOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import EntryScanConfigEditor from '../../components/EntryScanConfigEditor';
import type { EntryScanConfig } from '../../types';
import { buildScanConfigWithDefaults } from '../../utils/scanConfigDefaults';
import {
  cancelTrial,
  getActiveTrial,
  getLatestTrial,
  getTrial,
  getTrialEntries,
  listTrialHistory,
  triggerTrial,
  type EntryScanTrialSummary,
} from '../../api/trialRun';
import { collectExpandableKeys } from '../../utils/treeExpandKeys';

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

export interface ScanConfigFormValues {
  entryScanConfig: EntryScanConfig;
}

interface Props {
  open: boolean;
  form: FormInstance<ScanConfigFormValues>;
  repoId?: number;
  systemId?: number;
  submitting?: boolean;
  /** 仅试跑：不展示扫描表单与保存，读取外部 form 当前值 */
  trialOnly?: boolean;
  onCancel: () => void;
  onSubmit: () => void;
}

const DEFAULT_SCAN_CONFIG: EntryScanConfig = buildScanConfigWithDefaults(undefined);

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

/** 编辑仓库入口扫描规则 Modal（含试跑功能） */
const RepositoryScanConfigModal: React.FC<Props> = ({
  open,
  form,
  repoId,
  systemId,
  submitting = false,
  trialOnly = false,
  onCancel,
  onSubmit,
}) => {
  const [trying, setTrying] = useState(false);
  const [trialLocked, setTrialLocked] = useState(false);
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

  const clearPoll = useCallback(() => {
    if (pollTimerRef.current) {
      clearTimeout(pollTimerRef.current);
      pollTimerRef.current = null;
    }
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
          setTrialLocked(false);
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
            setTrialLocked(false);
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
            setTrialLocked(false);
            refreshHistory();
          } else {
            setTrialLocked(true);
            pollTimerRef.current = setTimeout(poll, POLL_INTERVAL_MS);
          }
        } catch {
          pollTimerRef.current = setTimeout(poll, POLL_INTERVAL_MS);
        }
      };
      poll();
    },
    [repoId, clearPoll, refreshHistory],
  );

  const refreshTrialContext = useCallback(async () => {
    if (!repoId) return;
    const [active, latest] = await Promise.all([
      getActiveTrial(repoId).catch(() => null),
      getLatestTrial(repoId).catch(() => null),
    ]);
    setTrialLocked(!!active);
    setLatestTrial(latest);
    await refreshHistory();

    if (active) {
      setDrawerTab('current');
      setDrawerOpen(true);
      await loadTrialDetail(active.id);
      startPoll(active.id);
    }
  }, [repoId, refreshHistory, loadTrialDetail, startPoll]);

  useEffect(() => {
    if (open) {
      if (repoId) {
        refreshTrialContext();
      }
    } else {
      clearPoll();
      setDrawerOpen(false);
      setViewingTrialId(null);
      setTrialStatus(null);
      setTrialEntries([]);
      setTrialError('');
      setTrialLocked(false);
    }
    return clearPoll;
  }, [open, repoId, refreshTrialContext, clearPoll]);

  const handleFillDefault = () => {
    form.setFieldsValue({ entryScanConfig: DEFAULT_SCAN_CONFIG });
  };

  const handleTrial = async () => {
    if (!repoId || !systemId) {
      message.warning('需要已有仓库（已保存）才能试跑');
      return;
    }
    const config = form.getFieldValue('entryScanConfig');
    setTrying(true);
    try {
      const trial = await triggerTrial(repoId, systemId, config);
      setViewingTrialId(trial.id);
      setTrialStatus(trial.status);
      setTrialStartedAt(trial.startedAt);
      setTrialError('');
      setTrialEntries([]);
      setTrialLocked(true);
      setDrawerTab('current');
      setDrawerOpen(true);
      startPoll(trial.id);
    } catch (e: unknown) {
      const err = e as { response?: { data?: { message?: string } }; message?: string };
      message.error(err?.response?.data?.message || err?.message || '试跑触发失败');
    } finally {
      setTrying(false);
    }
  };

  const handleCancelTrial = async () => {
    if (!repoId || !viewingTrialId) return;
    setCancelling(true);
    try {
      const ok = await cancelTrial(repoId, viewingTrialId);
      if (ok) {
        message.success('已取消试跑');
        clearPoll();
        setTrialStatus('CANCELLED');
        setTrialLocked(false);
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

  const handleViewLatest = async () => {
    if (!latestTrial) return;
    setDrawerTab('current');
    setDrawerOpen(true);
    await loadTrialDetail(latestTrial.id);
    if (latestTrial.status === 'PENDING' || latestTrial.status === 'RUNNING') {
      startPoll(latestTrial.id);
    }
  };

  const handleViewHistoryItem = async (item: EntryScanTrialSummary) => {
    setDrawerTab('current');
    await loadTrialDetail(item.id);
  };

  const trialTreeData = useMemo(() => buildTrialTreeData(trialEntries), [trialEntries]);
  const allExpandableKeys = useMemo(() => collectExpandableKeys(trialTreeData), [trialTreeData]);
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

  const trialFooter = [
    <Button key="cancel" onClick={onCancel}>
      {trialOnly ? '关闭' : '取消'}
    </Button>,
    <Button
      key="history"
      icon={<HistoryOutlined />}
      disabled={!repoId}
      onClick={() => {
        setDrawerTab('history');
        setDrawerOpen(true);
        refreshHistory();
      }}
    >
      试跑历史
    </Button>,
    <Button
      key="trial"
      type={trialOnly ? 'primary' : 'default'}
      icon={<PlayCircleOutlined />}
      loading={trying}
      disabled={trialLocked || !repoId}
      onClick={handleTrial}
    >
      {trialLocked ? '试跑执行中…' : '试跑'}
    </Button>,
  ];

  return (
    <>
      <Modal
        title={trialOnly ? '入口扫描试跑' : '入口扫描规则'}
        open={open}
        onCancel={onCancel}
        width={trialOnly ? 520 : 720}
        footer={
          trialOnly
            ? trialFooter
            : [
                ...trialFooter,
                <Button key="submit" type="primary" loading={submitting} onClick={onSubmit}>
                  保存
                </Button>,
              ]
        }
        destroyOnHidden
      >
        {trialOnly && (
          <Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
            将使用向导中当前未保存的扫描规则发起试跑；关闭后可在本步继续编辑并点「下一步」保存。
          </Text>
        )}
        {latestTrial && (
          <Alert
            type={latestTrial.status === 'SUCCESS' ? 'success' : latestTrial.status === 'FAILED' ? 'error' : 'info'}
            showIcon
            style={{ marginBottom: 16 }}
            message={
              <Space wrap>
                <Text>上次试跑：{formatDateTime(latestTrial.startedAt)}</Text>
                {statusTag(latestTrial.status)}
                {latestTrial.status === 'SUCCESS' && (
                  <Text>识别 {latestTrial.entryCount ?? 0} 个入口类</Text>
                )}
                {latestTrial.userId && <Text type="secondary">操作人 {latestTrial.userId}</Text>}
              </Space>
            }
            action={
              <Button size="small" type="link" onClick={handleViewLatest}>
                查看结果
              </Button>
            }
          />
        )}
        {!trialOnly && (
          <Form<ScanConfigFormValues> form={form} layout="vertical" style={{ marginTop: 16 }}>
            <Space style={{ marginBottom: 12 }}>
              <Button icon={<SyncOutlined />} onClick={handleFillDefault}>
                重置
              </Button>
            </Space>
            <Form.Item
              name="entryScanConfig"
              label="入口扫描配置"
              tooltip="不配置入口识别时，运行期会走 Controller/JOB/MQ 兜底"
            >
              <EntryScanConfigEditor />
            </Form.Item>
          </Form>
        )}
      </Modal>

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
        onClose={() => setDrawerOpen(false)}
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
                        <Button key="view" type="link" size="small" onClick={() => handleViewHistoryItem(item)}>
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
    </>
  );
};

export default RepositoryScanConfigModal;
