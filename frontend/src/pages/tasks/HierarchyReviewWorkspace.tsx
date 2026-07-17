import React, { useEffect, useState } from 'react';
import {
  Button,
  Card,
  Popconfirm,
  Segmented,
  Space,
  Spin,
  Tag,
  Typography,
  message,
} from 'antd';
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  LoadingOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import {
  getModuleHierarchy,
  getModuleHierarchyDiff,
  getTask,
  replaceModuleHierarchy,
  resumeModuleHierarchyReview,
} from '../../api/task';
import type { ModuleHierarchy, Task } from '../../types';
import PageHelpHint from '../../components/PageHelpHint';
import ModuleHierarchyEditor from '../../components/ModuleHierarchyEditor';
import { hierarchyReviewDetailHelp } from '../../constants/reviewPageHelp';

const { Text } = Typography;

export interface HierarchyReviewWorkspaceProps {
  taskId: number;
  /** 提交成功后的回调（如刷新父级列表）。一般不需要,工作区内部会自行 navigate 回列表页。 */
  onSubmitted?: () => void;
}

/**
 * 模块层级复核工作区（独立全屏页）：
 * - 自管理数据加载：`getModuleHierarchy(taskId)` + `getTask(taskId)`
 * - 自管理提交流水线：`replaceModuleHierarchy(taskId)` + `resumeModuleHierarchyReview(taskId)`
 * - 把受控后的 `ModuleHierarchyEditor` 渲染为整页内容,带顶栏(返回/状态/保存并继续)
 * - 替代原先的 `ModuleHierarchyEditorDrawer` 抽屉模式
 */
const HierarchyReviewWorkspace: React.FC<HierarchyReviewWorkspaceProps> = ({
  taskId,
  onSubmitted,
}) => {
  const navigate = useNavigate();
  const [task, setTask] = useState<Task | null>(null);
  const [taskLoading, setTaskLoading] = useState(false);
  const [hierarchy, setHierarchy] = useState<ModuleHierarchy | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  /** v1: Phase 4 diff 视图模式（INITIAL 任务默认 full，INCREMENTAL 任务默认 diff） */
  const [displayMode, setDisplayMode] = useState<'full' | 'diff'>('full');
  const [diffData, setDiffData] = useState<import('../../api/task').ModuleHierarchyDiffDto | null>(null);

  // 任务元信息
  useEffect(() => {
    setTaskLoading(true);
    getTask(taskId)
      .then((t) => {
        setTask(t);
        if (t?.type === 'INCREMENTAL') setDisplayMode('diff');
      })
      .catch(() => setTask(null))
      .finally(() => setTaskLoading(false));
  }, [taskId]);

  // 模块层级数据 + INCREMENTAL 任务的 diff 数据
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    if (task?.type === 'INCREMENTAL') {
      Promise.all([getModuleHierarchy(taskId), getModuleHierarchyDiff(taskId)])
        .then(([hier, diff]) => {
          if (cancelled) return;
          setHierarchy(hier ?? { taskId, modules: {} });
          setDiffData(diff);
        })
        .catch(() => { if (!cancelled) { setHierarchy({ taskId, modules: {} }); setDiffData(null); } })
        .finally(() => { if (!cancelled) setLoading(false); });
    } else {
      getModuleHierarchy(taskId)
        .then((data) => {
          if (!cancelled) { setHierarchy(data ?? { taskId, modules: {} }); setDiffData(null); }
        })
        .catch(() => { if (!cancelled) { setHierarchy({ taskId, modules: {} }); setDiffData(null); } })
        .finally(() => { if (!cancelled) setLoading(false); });
    }
    return () => { cancelled = true; };
  }, [taskId, task?.type]);

  const handleSubmit = async () => {
    if (!hierarchy) return;
    setSaving(true);
    try {
      await replaceModuleHierarchy(taskId, hierarchy);
      await resumeModuleHierarchyReview(taskId);
      message.success('已保存并提交,任务继续生成文档');
      onSubmitted?.();
      navigate('/tasks/hierarchy-review');
    } finally {
      setSaving(false);
    }
  };

  const canReview = task?.status === 'MODULE_HIERARCHY_REVIEW';

  return (
    <div className="ci-page ci-hierarchy-review-detail-page">
      <Card
        title={
          <Space wrap>
            <Button
              type="text"
              icon={<ArrowLeftOutlined />}
              onClick={() => navigate('/tasks/hierarchy-review')}
            >
              返回任务列表
            </Button>
            <Text strong>模块层级复核 · 任务 #{taskId}</Text>
            <PageHelpHint
              title={hierarchyReviewDetailHelp.title}
              content={hierarchyReviewDetailHelp.content}
            />
            {task && (
              <>
                <Tag color={task.type === 'INITIAL' ? 'geekblue' : 'green'}>
                  {task.type === 'INITIAL' ? '全量' : '增量'}
                </Tag>
                <Tag color="geekblue">{task.status}</Tag>
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
                options={[
                  { value: 'full', label: '全量视图' },
                  { value: 'diff', label: 'DIFF 视图' },
                ]}
                value={displayMode}
                onChange={(v) => setDisplayMode(v as 'full' | 'diff')}
              />
            )}
            {canReview ? (
            <Space>
              <Popconfirm
                title="确认提交并继续生成文档？"
                description="保存当前修改后将进入 GENERATING_DOC,无法再返回调试状态。"
                okText="确认并继续"
                cancelText="再检查下"
                onConfirm={handleSubmit}
              >
                <Button
                  type="primary"
                  icon={<CheckCircleOutlined />}
                  loading={saving}
                >
                  保存并继续
                </Button>
              </Popconfirm>
            </Space>
          ) : (
            <Text type="secondary">当前任务不在模块层级复核状态,仅可浏览</Text>
          )}
          </Space>
        }
      >
        {loading ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <Spin indicator={<LoadingOutlined />} /> 正在加载模块层级…
          </div>
        ) : displayMode === 'diff' && diffData ? (
          <HierarchyDiffView diffData={diffData} />
        ) : (
          <ModuleHierarchyEditor
            value={hierarchy}
            loading={loading}
            saving={saving}
            taskType={task?.type}
            onChange={setHierarchy}
            renderAlert={() => null}
          />
        )}
      </Card>
    </div>
  );
};

const DIFF_GROUPS = [
  { key: 'newHierarchy',       label: '本次新增', badge: '[+]', color: '#1890ff', bg: '#e6f7ff', defaultOpen: true },
  { key: 'modifiedHierarchy',  label: '本次变更', badge: '[~]', color: '#fa8c16', bg: '#fff7e6', defaultOpen: true },
  { key: 'deletedHierarchy',   label: '本次删除', badge: '[-]', color: '#ff4d4f', bg: '#fff1f0', defaultOpen: true },
  { key: 'inheritedHierarchy', label: '基线继承', badge: null,    color: '#52c41a', bg: '#f6ffed', defaultOpen: false },
] as const;

const HierarchyDiffView: React.FC<{ diffData: import('../../api/task').ModuleHierarchyDiffDto }> = ({ diffData }) => {
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Space size={4} wrap>
        <Tag color="geekblue">+{Object.keys(diffData.newHierarchy?.modules ?? {}).length} 新增</Tag>
        <Tag color="orange">~{Object.keys(diffData.modifiedHierarchy?.modules ?? {}).length} 变更</Tag>
        <Tag color="red">-{Object.keys(diffData.deletedHierarchy?.modules ?? {}).length} 删除</Tag>
        <Tag color="green">={Object.keys(diffData.inheritedHierarchy?.modules ?? {}).length} 基线</Tag>
      </Space>
      {DIFF_GROUPS.map(({ key, label, badge, color, bg, defaultOpen }) => {
        const hier = (diffData as unknown as Record<string, ModuleHierarchy | undefined>)[key];
        if (!hier || !hier.modules) return null;
        const moduleEntries = Object.entries(hier.modules);
        if (moduleEntries.length === 0) return null;
        const isOpen = collapsed[key] === undefined ? defaultOpen : !!collapsed[key];
        const tagColor = color === '#ff4d4f' ? 'red' : color === '#fa8c16' ? 'orange' : color === '#1890ff' ? 'blue' : 'green';
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
                {isOpen ? '▼' : '▸'} {label} ({moduleEntries.length})
              </Text>
              {badge && <Tag color={tagColor}>{badge}</Tag>}
            </div>
            {isOpen && (
              <Space direction="vertical" size={8} style={{ width: '100%' }}>
                {moduleEntries.map(([mid, m]) => (
                  <div key={mid} style={{
                    borderLeft: `3px solid ${color}`, background: bg,
                    borderRadius: '0 6px 6px 0', padding: '8px 12px',
                  }}>
                    <Space size={6} wrap>
                      {badge && <Tag color={tagColor} style={{ fontSize: 11 }}>{badge}</Tag>}
                      <Text strong delete={key === 'deletedHierarchy'} style={{ fontSize: 13 }}>{mid}</Text>
                      <Text style={{ fontSize: 13 }}>{m.moduleName}</Text>
                    </Space>
                    {m.subModules && Object.keys(m.subModules).length > 0 && (
                      <div style={{ marginTop: 4, paddingLeft: 16 }}>
                        {Object.entries(m.subModules).map(([sid, sm]) => {
                          const functions = (sm as any).functions || {};
                          const hasNew = Object.values(functions).some((f: any) => f.diffStatus === 'new');
                          const hasMod = Object.values(functions).some((f: any) => f.diffStatus === 'modified');
                          const hasDel = Object.values(functions).some((f: any) => f.diffStatus === 'deleted');
                          return (
                            <div key={sid} style={{ marginBottom: 4 }}>
                              <div style={{ fontSize: 12, color: '#666' }}>
                                └─ {sid} {sm.subModuleName}
                                {hasNew && <Tag color="blue" style={{ fontSize: 10, marginLeft: 4 }}>+新</Tag>}
                                {hasMod && <Tag color="orange" style={{ fontSize: 10, marginLeft: 4 }}>~改</Tag>}
                                {hasDel && <Tag color="red" style={{ fontSize: 10, marginLeft: 4 }}>-删</Tag>}
                              </div>
                              {Object.keys(functions).length > 0 && (
                                <div style={{ paddingLeft: 16, marginTop: 2 }}>
                                  {Object.values(functions).map((f: any) => {
                                    const s = f.diffStatus;
                                    const colors: Record<string, string> = {
                                      new: '#1890ff', modified: '#fa8c16', deleted: '#ff4d4f',
                                    };
                                    const prefix: Record<string, string> = {
                                      new: '+ ', modified: '~ ', deleted: '- ',
                                    };
                                    const name = f.functionName || f.id || '(未命名功能)';
                                    const sigs: string[] = Array.isArray(f.methodSignatures)
                                      ? f.methodSignatures.filter(Boolean)
                                      : [];
                                    const sigHint = sigs.length === 0
                                      ? ''
                                      : sigs.length === 1
                                        ? ` · ${sigs[0]}`
                                        : ` · ${sigs.length} 个方法`;
                                    return (
                                      <div key={f.id || f.functionName} style={{
                                        fontSize: 11,
                                        color: s && s !== 'unchanged' ? colors[s] : '#999',
                                        textDecoration: s === 'deleted' ? 'line-through' : 'none',
                                      }}>
                                        <span style={{ fontFamily: 'monospace' }}>{s && prefix[s]}</span>
                                        {name}
                                        {sigHint && (
                                          <span style={{ color: '#bbb', fontFamily: 'monospace' }}>{sigHint}</span>
                                        )}
                                      </div>
                                    );
                                  })}
                                </div>
                              )}
                            </div>
                          );
                        })}
                      </div>
                    )}
                  </div>
                ))}
              </Space>
            )}
          </div>
        );
      })}
    </Space>
  );
};

export default HierarchyReviewWorkspace;