import React, { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Empty,
  Input,
  Popconfirm,
  Segmented,
  Space,
  Spin,
  Statistic,
  Table,
  Tag,
  Tree,
  Typography,
  message,
} from 'antd';
import type { DataNode } from 'antd/es/tree';
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  LoadingOutlined,
  CloseOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import PageHelpHint from '../../components/PageHelpHint';
import { entrypointReviewDetailHelp } from '../../constants/reviewPageHelp';
import {
  getEntrypointDiff,
  getEntrypointReview,
  getTask,
  rejectEntrypointReview,
  resumeEntrypointReview,
} from '../../api/task';
import type { EntrypointReviewItem, ExcludeTarget, Task } from '../../types';

const { Text } = Typography;

const ENTRY_TYPE_LABEL: Record<string, { color: string; label: string }> = {
  CONTROLLER: { color: 'cyan', label: 'Controller' },
  SCHEDULED_JOB: { color: 'purple', label: 'Job' },
  MQ_LISTENER: { color: 'gold', label: 'MQ' },
  OTHER: { color: 'default', label: '其他' },
  COMPONENT: { color: 'blue', label: '组件' },
  APPLICATION: { color: 'magenta', label: '应用入口' },
  MAIN: { color: 'magenta', label: 'Main 入口' },
  CUSTOM: { color: 'default', label: '自定义' },
};

function targetKey(t: ExcludeTarget): string {
  return `${t.className}#${t.methodSignature?.trim() || ''}`;
}

function isPendingExcluded(
  pending: ExcludeTarget[],
  className: string,
  methodSignature?: string,
): boolean {
  return pending.some((t) => {
    if (t.className !== className) return false;
    if (!t.methodSignature?.trim()) return true;
    return !!methodSignature && t.methodSignature.trim() === methodSignature.trim();
  });
}

/** 从全限定类名中提取简短类名（如 com.demo.UserController → UserController） */
function shortClassName(fq: string): string {
  const idx = fq.lastIndexOf('.');
  return idx >= 0 ? fq.slice(idx + 1) : fq;
}

export interface EntrypointReviewWorkspaceProps {
  taskId: number;
  /** 确认/驳回成功后的回调（如刷新父级列表） */
  onSubmitted?: () => void;
}

/**
 * 入口复核详情：只读展示入口类与方法，支持确认继续或驳回任务。
 */
const EntrypointReviewWorkspace: React.FC<EntrypointReviewWorkspaceProps> = ({
  taskId,
  onSubmitted,
}) => {
  const navigate = useNavigate();
  const [task, setTask] = useState<Task | null>(null);
  const [taskLoading, setTaskLoading] = useState(false);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState<'resume' | 'reject' | null>(null);
  const [items, setItems] = useState<EntrypointReviewItem[]>([]);
  const [rejectReason, setRejectReason] = useState('');
  const [viewMode, setViewMode] = useState<'list' | 'tree'>('list');
  /** v1: Phase 4 diff 视图模式（INITIAL 任务默认 full，INCREMENTAL 任务默认 diff） */
  const [displayMode, setDisplayMode] = useState<'full' | 'diff'>('full');
  const [diffData, setDiffData] = useState<import('../../api/task').EntrypointDiffDto | null>(null);
  const [pendingExcludes, setPendingExcludes] = useState<ExcludeTarget[]>([]);

  useEffect(() => {
    setTaskLoading(true);
    getTask(taskId)
      .then((t) => {
        setTask(t);
        // v1: INCREMENTAL 任务默认 diff 视图
        if (t?.type === 'INCREMENTAL') setDisplayMode('diff');
      })
      .catch(() => setTask(null))
      .finally(() => setTaskLoading(false));
  }, [taskId]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    // v1: INCREMENTAL 任务加载 diff 数据；INITIAL 走全量
    if (task?.type === 'INCREMENTAL') {
      Promise.all([
        getEntrypointReview(taskId),
        getEntrypointDiff(taskId),
      ]).then(([allItems, diff]) => {
        if (!cancelled) {
          setItems(Array.isArray(allItems) ? allItems : []);
          setDiffData(diff);
        }
      }).finally(() => { if (!cancelled) setLoading(false); });
    } else {
      getEntrypointReview(taskId)
        .then((data) => {
          if (!cancelled) setItems(Array.isArray(data) ? data : []);
        })
        .finally(() => {
          if (!cancelled) setLoading(false);
        });
    }
    return () => {
      cancelled = true;
    };
  }, [taskId, task?.type]);  // v1: 依赖 task.type，确保 task 加载后重新判定 INCREMENTAL

  const visibleItems = useMemo(() => {
    return items
      .filter((it) => !isPendingExcluded(pendingExcludes, it.className))
      .map((it) => ({
        ...it,
        methods: (it.methods || []).filter(
          (m) => !isPendingExcluded(pendingExcludes, it.className, m.methodSignature || m.methodName),
        ),
      }))
      .filter((it) => (it.methods?.length ?? 0) > 0 || !items.find((x) => x.id === it.id)?.methods?.length);
  }, [items, pendingExcludes]);

  const stats = useMemo(() => {
    const totalClasses = visibleItems.length;
    const totalMethods = visibleItems.reduce((sum, it) => sum + (it.methods?.length ?? 0), 0);
    const typeCounts: Record<string, number> = {};
    visibleItems.forEach((it) => {
      const t = it.entryType || 'UNKNOWN';
      typeCounts[t] = (typeCounts[t] || 0) + 1;
    });
    return { totalClasses, totalMethods, typeCounts };
  }, [visibleItems]);

  const addExclude = (target: ExcludeTarget) => {
    setPendingExcludes((prev) => {
      const key = targetKey(target);
      if (prev.some((p) => targetKey(p) === key)) return prev;
      return [...prev, target];
    });
  };

  const removeExclude = (target: ExcludeTarget) => {
    const key = targetKey(target);
    setPendingExcludes((prev) => prev.filter((p) => targetKey(p) !== key));
  };

  const handleResume = async () => {
    setSubmitting('resume');
    try {
      await resumeEntrypointReview(taskId, pendingExcludes.length > 0 ? pendingExcludes : undefined);
      message.success('已确认，任务继续执行 AI 阶段');
      onSubmitted?.();
      navigate('/tasks/entrypoint-review');
    } finally {
      setSubmitting(null);
    }
  };

  const handleReject = async () => {
    if (!rejectReason.trim()) {
      message.warning('请填写驳回理由');
      return;
    }
    setSubmitting('reject');
    try {
      await rejectEntrypointReview(taskId, rejectReason.trim());
      message.success('已驳回，任务已终止');
      onSubmitted?.();
      navigate('/tasks/entrypoint-review');
    } finally {
      setSubmitting(null);
    }
  };

  const canReview = task?.status === 'ENTRYPOINT_REVIEW';

  /** 树形视图数据：按入口类型分组 → 类 → 方法 */
  const treeData = useMemo<DataNode[]>(() => {
    const groups: Record<string, EntrypointReviewItem[]> = {};
    visibleItems.forEach((it) => {
      const t = it.entryType || 'UNKNOWN';
      if (!groups[t]) groups[t] = [];
      groups[t].push(it);
    });
    // 按组内数量降序排列
    const sortedGroups = Object.entries(groups).sort(([, a], [, b]) => b.length - a.length);
    return sortedGroups.map(([typeKey, classList]) => {
      const typeMeta = ENTRY_TYPE_LABEL[typeKey] || { color: 'default', label: typeKey };
      return {
        key: `type-${typeKey}`,
        title: (
          <Space size={4}>
            <Tag color={typeMeta.color} style={{ marginRight: 0 }}>{typeMeta.label}</Tag>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {classList.length} 类 · {classList.reduce((s, c) => s + (c.methods?.length ?? 0), 0)} 方法
            </Text>
          </Space>
        ),
        selectable: false,
        children: classList.map((cls) => ({
          key: `class-${cls.id}`,
          title: (
            <Space size={4}>
              <Text strong>{shortClassName(cls.className)}</Text>
              {canReview && (
                <Button
                  type="text"
                  size="small"
                  danger
                  icon={<CloseOutlined />}
                  onClick={(e) => {
                    e.stopPropagation();
                    addExclude({ className: cls.className });
                  }}
                />
              )}
              {cls.annotation && (
                <Tag style={{ fontSize: 11 }}>
                  {cls.annotation.length > 24 ? cls.annotation.slice(0, 22) + '…' : cls.annotation}
                </Tag>
              )}
              {cls.remark && (
                <Text type="secondary" style={{ fontSize: 11 }}>
                  {cls.remark.length > 40 ? cls.remark.slice(0, 38) + '…' : cls.remark}
                </Text>
              )}
            </Space>
          ),
          children: (cls.methods || []).map((m, mi) => ({
            key: `method-${cls.id}-${mi}`,
            isLeaf: true,
            title: (
              <Space size={4} style={{ fontSize: 12 }}>
                {m.httpMethod && <Tag color="geekblue" style={{ fontSize: 11, lineHeight: '16px' }}>{m.httpMethod}</Tag>}
                {m.httpPath && <Text code style={{ fontSize: 11 }}>{m.httpPath}</Text>}
                {m.annotation && !m.httpMethod && (
                  <Tag style={{ fontSize: 11, lineHeight: '16px' }}>{m.annotation}</Tag>
                )}
                <Text style={{ fontSize: 12 }}>{m.methodSignature || m.methodName}</Text>
                {canReview && (
                  <Button
                    type="text"
                    size="small"
                    danger
                    icon={<CloseOutlined />}
                    onClick={(e) => {
                      e.stopPropagation();
                      addExclude({
                        className: cls.className,
                        methodSignature: m.methodSignature || m.methodName,
                      });
                    }}
                  />
                )}
              </Space>
            ),
          })),
        })),
      };
    });
  }, [visibleItems, canReview]);

  /* v1: diff 模式的树形数据 — 按 4 类分组，节点带颜色 Badge + 方法级 diff 前缀 */
  const diffTreeData = useMemo<DataNode[]>(() => {
    if (!diffData || displayMode !== 'diff') return [];
    return DIFF_GROUPS.filter(g => {
      const rows = (diffData as unknown as Record<string, EntrypointReviewItem[]>)[g.key];
      return rows && rows.length > 0;
    }).map(g => {
      const rows = (diffData as unknown as Record<string, EntrypointReviewItem[]>)[g.key]!;
      return {
        key: `diff-${g.key}`,
        title: <Space size={4}><Text strong style={{ color: g.color }}>{g.badge || ''} {g.label} ({rows.length})</Text></Space>,
        selectable: false,
        children: rows.map(cls => ({
          key: `diff-${g.key}-class-${cls.id}`,
          title: <Space size={4}>
            {g.badge && <Tag color={g.color === '#ff4d4f' ? 'red' : g.color === '#fa8c16' ? 'orange' : 'blue'} style={{ fontSize: 11 }}>{g.badge}</Tag>}
            <Text strong delete={g.key === 'deletedRows'}>{shortClassName(cls.className)}</Text>
          </Space>,
          children: (cls.methods || []).map((m, mi) => ({
            key: `diff-${g.key}-m-${cls.id}-${mi}`, isLeaf: true,
            title: <Space size={4} style={{ fontSize: 12 }}>
              {m.diffStatus === 'new' && <Tag color="blue" style={{ fontSize: 10, lineHeight: '14px' }}>+</Tag>}
              {m.diffStatus === 'modified' && <Tag color="orange" style={{ fontSize: 10, lineHeight: '14px' }}>~</Tag>}
              {m.diffStatus === 'deleted' && <Tag color="red" style={{ fontSize: 10, lineHeight: '14px' }}>-</Tag>}
              <Text delete={m.diffStatus === 'deleted'}
                style={{ fontSize: 12, color: m.diffStatus === 'new' ? '#1890ff' : m.diffStatus === 'modified' ? '#fa8c16' : m.diffStatus === 'deleted' ? '#ff4d4f' : undefined }}>
                {m.methodSignature || m.methodName}
              </Text>
            </Space>,
          })),
        })),
      };
    });
  }, [diffData, displayMode]);

  return (
    <div className="ci-page ci-entrypoint-review-detail-page">
      <Card
        title={
          <Space wrap>
            <Button
              type="text"
              icon={<ArrowLeftOutlined />}
              onClick={() => navigate('/tasks/entrypoint-review')}
            >
              返回任务列表
            </Button>
            <Text strong>入口复核 · 任务 #{taskId}</Text>
            <PageHelpHint
              title={entrypointReviewDetailHelp.title}
              content={entrypointReviewDetailHelp.content}
            />
            {task && (
              <>
                <Tag color={task.type === 'INITIAL' ? 'geekblue' : 'green'}>
                  {task.type === 'INITIAL' ? '全量' : '增量'}
                </Tag>
                <Tag color="cyan">{task.status}</Tag>
              </>
            )}
            {taskLoading && <Text type="secondary">加载中…</Text>}
          </Space>
        }
        extra={
          <Space size={12}>
            {/* v1: Phase 4 diff 视图切换（INCREMENTAL 任务可用） */}
            {task?.type === 'INCREMENTAL' && (
              <Segmented
                size="large"
                options={[
                  { value: 'full', label: '全量视图' },
                  { value: 'diff', label: 'DIFF 视图' },
                ]}
                value={displayMode}
                onChange={(v) => setDisplayMode(v as 'full' | 'diff')}
              />
            )}
            <Segmented
              size="large"
              options={[
                { value: 'list', label: '列表视图' },
                { value: 'tree', label: '树形视图' },
              ]}
              value={viewMode}
              onChange={(v) => setViewMode(v as 'list' | 'tree')}
            />
            {canReview ? (
              <Space>
                <Popconfirm
                title="确认驳回任务？"
                description={
                  <div style={{ width: 280 }}>
                    <div style={{ marginBottom: 8 }}>
                      任务将被终止，不会进入 AI 阶段。请填写驳回理由：
                    </div>
                    <Input.TextArea
                      rows={3}
                      placeholder="例如：扫描配置有误，请调整 entry_scan_config 后重跑"
                      value={rejectReason}
                      onChange={(e) => setRejectReason(e.target.value)}
                    />
                  </div>
                }
                okText="确认驳回"
                cancelText="再检查下"
                okButtonProps={{ danger: true, loading: submitting === 'reject' }}
                onConfirm={handleReject}
              >
                <Button danger icon={<CloseCircleOutlined />} loading={submitting === 'reject'}>
                  驳回任务
                </Button>
              </Popconfirm>
              <Button
                type="primary"
                icon={<CheckCircleOutlined />}
                loading={submitting === 'resume'}
                onClick={handleResume}
              >
                确认并继续
              </Button>
            </Space>
          ) : (
            <Text type="secondary">当前任务不在入口复核状态，仅可浏览历史清单</Text>
          )}
          </Space>
        }
      >
        {loading ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <Spin indicator={<LoadingOutlined />} /> 正在加载入口清单...
          </div>
        ) : (
          <>
            {pendingExcludes.length > 0 && (
              <Alert
                type="warning"
                showIcon
                style={{ marginBottom: 16 }}
                message={`已排除 ${pendingExcludes.length} 项（确认继续时写入任务配置）`}
                description={
                  <Space wrap size={[8, 4]}>
                    {pendingExcludes.map((t) => (
                      <Tag
                        key={targetKey(t)}
                        closable
                        onClose={() => removeExclude(t)}
                      >
                        {t.className.split('.').pop()}
                        {t.methodSignature ? `#${t.methodSignature}` : ' (整类)'}
                      </Tag>
                    ))}
                  </Space>
                }
              />
            )}

            {/* v1: Phase 4 diff 统计（INCREMENTAL 任务下） */}
            {displayMode === 'diff' && diffData && (
              <Space size={4} style={{ marginBottom: 16 }}>
                <Tag color="geekblue">+{diffData.newRows?.length ?? 0} 新增</Tag>
                <Tag color="orange">~{diffData.modifiedRows?.length ?? 0} 变更</Tag>
                <Tag color="red">-{diffData.deletedRows?.length ?? 0} 删除</Tag>
                <Tag color="green">={diffData.inheritedRows?.length ?? 0} 基线</Tag>
              </Space>
            )}

            <Space size="large" style={{ marginBottom: 16 }}>
              <Statistic title="入口类数" value={stats.totalClasses} suffix="个" />
              <Statistic title="方法总数" value={stats.totalMethods} suffix="个" />
              <Space size={4} wrap>
                {Object.entries(stats.typeCounts).map(([t, n]) => {
                  const meta = ENTRY_TYPE_LABEL[t] || { color: 'default', label: t };
                  return (
                    <Tag color={meta.color} key={t}>
                      {meta.label} {n}
                    </Tag>
                  );
                })}
              </Space>
            </Space>

            {visibleItems.length === 0 ? (
              <Empty description="无可见入口（已全部排除或未识别到入口）。" />
            ) : viewMode === 'tree' ? (
              displayMode === 'diff' && diffData ? (
                /* v1: DIFF 模式 + 树形视图 — diff 分组树 */
                <div style={{ border: '1px solid #f0f0f0', borderRadius: 6, padding: 12, background: '#fafafa' }}>
                  <Tree
                    treeData={diffTreeData}
                    defaultExpandAll
                    showLine={{ showLeafIcon: false }}
                    blockNode
                    style={{ fontSize: 13 }}
                  />
                </div>
              ) : (
                /* 全量模式 + 树形视图 — 与今天一致 */
                <div style={{ border: '1px solid #f0f0f0', borderRadius: 6, padding: 12, background: '#fafafa' }}>
                  <Tree
                    treeData={treeData}
                    defaultExpandAll
                    showLine={{ showLeafIcon: false }}
                    blockNode
                    style={{ fontSize: 13 }}
                  />
                </div>
              )
            ) : displayMode === 'diff' && diffData ? (
              /* v1: DIFF 模式 — git 风格分组渲染 */
              <DiffEntryList
                diffData={diffData}
                canReview={canReview}
                addExclude={addExclude}
              />
            ) : (
              /* 全量模式 — 与今天一致 */
              <Space direction="vertical" size={16} style={{ width: '100%' }}>
                {visibleItems.map((it) => {
                  const typeMeta = ENTRY_TYPE_LABEL[it.entryType || ''] || {
                    color: 'default',
                    label: it.entryType || 'UNKNOWN',
                  };
                  return (
                    <div
                      key={it.id}
                      style={{
                        border: '1px solid #f0f0f0',
                        borderRadius: 6,
                        padding: 12,
                        background: '#fafafa',
                      }}
                    >
                      <Space size={8} wrap style={{ marginBottom: 4 }}>
                        <Tag color={typeMeta.color}>{typeMeta.label}</Tag>
                        {/* v1: Phase 4 diff 标识（INCREMENTAL 任务下） */}
                        {task?.type === 'INCREMENTAL' && it.baselineTaskId && (
                          <Tag color="green">基线继承</Tag>
                        )}
                        {task?.type === 'INCREMENTAL' && !it.baselineTaskId && (
                          <Tag color="geekblue">本次新增</Tag>
                        )}
                        <Text strong>{it.className}</Text>
                        {canReview && (
                          <Button
                            type="text"
                            size="small"
                            danger
                            icon={<CloseOutlined />}
                            title="排除整类"
                            onClick={() => addExclude({ className: it.className })}
                          />
                        )}
                        {it.annotation && (
                          <Tag>
                            <Text type="secondary" style={{ fontSize: 12 }}>
                              触发注解：{it.annotation}
                            </Text>
                          </Tag>
                        )}
                        {it.remark && (
                          <Text type="secondary" style={{ fontSize: 12 }}>
                            路径：{it.remark}
                          </Text>
                        )}
                      </Space>
                      {it.filePath && (
                        <div style={{ marginBottom: 8 }}>
                          <Text type="secondary" style={{ fontSize: 12 }}>
                            {it.filePath}
                          </Text>
                        </div>
                      )}
                      {(it.methods?.length ?? 0) === 0 ? (
                        <Text type="secondary">（该入口未识别到方法）</Text>
                      ) : (
                        <Table<NonNullable<EntrypointReviewItem['methods'][number]>>
                          size="small"
                          rowKey={(r, idx) => `${it.id}-${idx}-${r.methodName}`}
                          dataSource={it.methods}
                          pagination={false}
                          columns={[
                            {
                              title: '方法签名',
                              dataIndex: 'methodSignature',
                              key: 'methodSignature',
                              width: 280,
                              render: (v?: string) => <Text code>{v}</Text>,
                            },
                            {
                              title: '方法名',
                              dataIndex: 'methodName',
                              key: 'methodName',
                              width: 160,
                            },
                            {
                              title: '注解',
                              dataIndex: 'annotation',
                              key: 'annotation',
                              width: 200,
                              render: (v?: string) => (v ? <Tag>{v}</Tag> : '-'),
                            },
                            {
                              title: 'HTTP 路径 / 方法',
                              key: 'http',
                              render: (_: unknown, r) =>
                                r.httpPath ? (
                                  <Space size={4}>
                                    {r.httpMethod && <Tag color="geekblue">{r.httpMethod}</Tag>}
                                    <Text code style={{ fontSize: 12 }}>
                                      {r.httpPath}
                                    </Text>
                                  </Space>
                                ) : (
                                  <Text type="secondary">-</Text>
                                ),
                            },
                            ...(canReview
                              ? [
                                  {
                                    title: '操作',
                                    key: 'action',
                                    width: 72,
                                    render: (_: unknown, r: NonNullable<EntrypointReviewItem['methods']>[number]) => (
                                      <Button
                                        type="text"
                                        size="small"
                                        danger
                                        icon={<CloseOutlined />}
                                        title="排除此方法"
                                        onClick={() =>
                                          addExclude({
                                            className: it.className,
                                            methodSignature: r.methodSignature || r.methodName,
                                          })
                                        }
                                      />
                                    ),
                                  },
                                ]
                              : []),
                          ]}
                        />
                      )}
                    </div>
                  );
                })}
              </Space>
            )}
          </>
        )}
      </Card>
    </div>
  );
};

/* ================================================================
 * v1: DIFF 模式 — git 风格分组渲染
 * ================================================================ */

const DIFF_GROUPS = [
  { key: 'newRows',       label: '本次新增',   badge: '[+]', color: '#1890ff', bg: '#e6f7ff', defaultOpen: true },
  { key: 'modifiedRows',  label: '本次变更',   badge: '[~]', color: '#fa8c16', bg: '#fff7e6', defaultOpen: true },
  { key: 'deletedRows',   label: '本次删除',   badge: '[-]', color: '#ff4d4f', bg: '#fff1f0', defaultOpen: true },
  { key: 'inheritedRows', label: '基线继承',   badge: null,  color: '#52c41a', bg: '#f6ffed', defaultOpen: false },
] as const;

const DiffEntryList: React.FC<{
  diffData: import('../../api/task').EntrypointDiffDto;
  canReview: boolean;
  addExclude: (t: ExcludeTarget) => void;
}> = ({ diffData, canReview, addExclude }) => {
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});

  return (
    <Space direction="vertical" size={24} style={{ width: '100%' }}>
      {DIFF_GROUPS.map(({ key, label, badge, color, bg, defaultOpen }) => {
        const rows = diffData[key as keyof typeof diffData] as EntrypointReviewItem[];
        if (!rows || rows.length === 0) return null;
        const isOpen = collapsed[key] === undefined ? defaultOpen : !!collapsed[key];
        return (
          <div key={key}>
            <div
              onClick={() => setCollapsed((p) => ({ ...p, [key]: !isOpen }))}
              style={{
                cursor: 'pointer', userSelect: 'none', padding: '8px 0',
                borderBottom: `2px solid ${color}`, marginBottom: 12,
                display: 'flex', alignItems: 'center', gap: 8,
              }}
            >
              <Text strong style={{ color, fontSize: 14 }}>
                {isOpen ? '▼' : '▸'} {label} ({rows.length})
              </Text>
              {badge && <Tag color={color === '#fa8c16' ? 'orange' : color === '#1890ff' ? 'blue' : 'red'}>{badge}</Tag>}
            </div>
            {isOpen && (
              <Space direction="vertical" size={12} style={{ width: '100%' }}>
                {rows.map((it) => (
                  <DiffEntryRow key={it.id} item={it} canReview={canReview}
                    addExclude={addExclude} color={color} bg={bg} badge={badge} />
                ))}
              </Space>
            )}
          </div>
        );
      })}
    </Space>
  );
};

const DiffEntryRow: React.FC<{
  item: EntrypointReviewItem;
  canReview: boolean; addExclude: (t: ExcludeTarget) => void;
  color: string; bg: string; badge: string | null;
}> = ({ item, canReview, addExclude, color, bg, badge }) => {
  const typeMeta = ENTRY_TYPE_LABEL[item.entryType || ''] || {
    color: 'default', label: item.entryType || 'UNKNOWN',
  };
  const isDeleted = badge === '[-]';
  return (
    <div style={{
      borderLeft: `3px solid ${color}`, background: bg,
      borderRadius: '0 6px 6px 0', padding: 12,
    }}>
      <Space size={8} wrap style={{ marginBottom: 4 }}>
        {badge && <Tag color={color === '#ff4d4f' ? 'red' : color === '#fa8c16' ? 'orange' : color === '#1890ff' ? 'blue' : 'green'}>{badge}</Tag>}
        <Tag color={typeMeta.color}>{typeMeta.label}</Tag>
        <Text strong delete={isDeleted}>{item.className}</Text>
        {canReview && !isDeleted && (
          <Button type="text" size="small" danger icon={<CloseOutlined />}
            title="排除整类" onClick={() => addExclude({ className: item.className })} />
        )}
        {item.annotation && (
          <Tag><Text type="secondary" style={{ fontSize: 12 }}>触发注解：{item.annotation}</Text></Tag>
        )}
        {item.remark && (
          <Text type="secondary" style={{ fontSize: 12 }}>路径：{item.remark}</Text>
        )}
      </Space>
      {item.filePath && (
        <div style={{ marginBottom: 8 }}>
          <Text type="secondary" style={{ fontSize: 12 }}>{item.filePath}</Text>
        </div>
      )}
      {(item.methods?.length ?? 0) === 0 ? (
        <Text type="secondary">（该入口未识别到方法）</Text>
      ) : (
        <Table<NonNullable<EntrypointReviewItem['methods'][number]>>
          size="small"
          rowKey={(r, idx) => `${item.id}-${idx}-${r.methodName}`}
          dataSource={item.methods}
          pagination={false}
          columns={[
            {
              title: '方法签名', dataIndex: 'methodSignature', key: 'methodSignature', width: 200,
              render: (v?: string, r?: NonNullable<EntrypointReviewItem['methods'][number]>) => {
                const prefix = r?.diffStatus === 'new' ? '+ '
                  : r?.diffStatus === 'modified' ? '~ '
                  : r?.diffStatus === 'deleted' ? '- ' : '';
                const style = r?.diffStatus === 'new' ? { color: '#1890ff', fontWeight: 'bold' } as const
                  : r?.diffStatus === 'modified' ? { color: '#fa8c16', fontWeight: 'bold' } as const
                  : r?.diffStatus === 'deleted' ? { color: '#ff4d4f', textDecoration: 'line-through' } as const
                  : undefined;
                return <Text code delete={r?.diffStatus === 'deleted'} style={style}>{prefix}{v}</Text>;
              },
            },
            { title: '方法名', dataIndex: 'methodName', key: 'methodName', width: 160 },
            {
              title: '注解', dataIndex: 'annotation', key: 'annotation', width: 140,
              render: (v?: string) => (v ? <Tag>{v}</Tag> : '-'),
            },
            {
              title: 'HTTP', key: 'http',
              render: (_: unknown, r) =>
                r.httpPath ? (
                  <Space size={4}>
                    {r.httpMethod && <Tag color="geekblue">{r.httpMethod}</Tag>}
                    <Text code style={{ fontSize: 12 }}>{r.httpPath}</Text>
                  </Space>
                ) : null,
            },
            ...(canReview ? [
              { title: '', key: 'actions', width: 60,
                render: (_: unknown, r: NonNullable<EntrypointReviewItem['methods'][number]>) =>
                  <Button type="text" size="small" danger icon={<CloseOutlined />}
                    title="排除方法" onClick={() => addExclude({ className: item.className, methodSignature: r.methodSignature })} />
              }
            ] : []),
          ]}
        />
      )}
    </div>
  );
};

export default EntrypointReviewWorkspace;
