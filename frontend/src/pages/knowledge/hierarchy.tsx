import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Drawer,
  Empty,
  Modal,
  Select,
  Space,
  Spin,
  Statistic,
  Tag,
  Tree,
  Typography,
  message,
} from 'antd';
import type { DataNode } from 'antd/es/tree';
import { LoadingOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { getCurrentOperator } from '../../api/auth';
import { getPublishedHierarchy } from '../../api/knowledge-query';
import { remediateHierarchy } from '../../api/knowledge-remediation';
import ModuleHierarchyJsonEditor from '../../components/ModuleHierarchyJsonEditor';
import type { FunctionNode, ModuleHierarchy, ModuleNode, SubModuleNode } from '../../types';
import KnowledgeContextBar from './KnowledgeContextBar';
import { useKnowledgeQueryContext } from './useKnowledgeQueryContext';

const { Text } = Typography;

const NODE_TYPE_TAG = {
  MODULE: { color: 'geekblue', label: '模块' },
  SUB_MODULE: { color: 'cyan', label: '子模块' },
  FUNCTION: { color: 'green', label: '功能' },
} as const;

function buildTreeData(hierarchy: ModuleHierarchy | null): DataNode[] {
  const mods = Object.values(hierarchy?.modules ?? {});
  return mods.map((module: ModuleNode) => ({
    key: `module-${module.id}`,
    title: (
      <Space size={4}>
        <Tag color={NODE_TYPE_TAG.MODULE.color}>{NODE_TYPE_TAG.MODULE.label}</Tag>
        <Text strong>{module.moduleName}</Text>
        <Text type="secondary" code style={{ fontSize: 11 }}>
          {module.id}
        </Text>
      </Space>
    ),
    children: Object.values(module.subModules ?? {}).map((sm: SubModuleNode) => ({
      key: `sub-${module.id}-${sm.id}`,
      title: <Text>{sm.subModuleName}</Text>,
      children: Object.values(sm.functions ?? {}).map((fn: FunctionNode) => ({
        key: `fn-${module.id}-${sm.id}-${fn.id}`,
        isLeaf: true,
        title: <Text>{fn.functionName}</Text>,
      })),
    })),
  }));
}

const KnowledgeHierarchyPage: React.FC = () => {
  const navigate = useNavigate();
  const ctx = useKnowledgeQueryContext();
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [hierarchy, setHierarchy] = useState<ModuleHierarchy | null>(null);
  const [editOpen, setEditOpen] = useState(false);
  const [draftHierarchy, setDraftHierarchy] = useState<ModuleHierarchy | null>(null);
  const [scopeModuleIds, setScopeModuleIds] = useState<string[]>([]);

  const remediationReady =
    ctx.repositoryId != null && ctx.context?.versionId != null && ctx.context.releaseDirExists;

  const moduleOptions = useMemo(
    () =>
      Object.values(hierarchy?.modules ?? {}).map((m) => ({
        value: m.id,
        label: `${m.moduleName} (${m.id})`,
      })),
    [hierarchy],
  );

  const fetchHierarchy = useCallback(async () => {
    if (ctx.repositoryId == null) {
      setHierarchy(null);
      return;
    }
    setLoading(true);
    try {
      const data = await getPublishedHierarchy(ctx.repositoryId, ctx.systemId);
      setHierarchy(data);
    } catch {
      setHierarchy(null);
    } finally {
      setLoading(false);
    }
  }, [ctx.repositoryId, ctx.systemId]);

  useEffect(() => {
    fetchHierarchy();
  }, [fetchHierarchy]);

  const counts = useMemo(() => {
    let modules = 0;
    let functions = 0;
    Object.values(hierarchy?.modules ?? {}).forEach((m) => {
      modules += 1;
      Object.values(m.subModules ?? {}).forEach((sm) => {
        functions += Object.keys(sm.functions ?? {}).length;
      });
    });
    return { modules, functions };
  }, [hierarchy]);

  const openEdit = () => {
    if (!hierarchy) return;
    setDraftHierarchy(structuredClone(hierarchy));
    setScopeModuleIds([]);
    setEditOpen(true);
  };

  const submitRemediation = () => {
    if (!ctx.repositoryId || !draftHierarchy) return;
    if (scopeModuleIds.length === 0) {
      message.warning('请选择需要重生成文档的模块');
      return;
    }
    Modal.confirm({
      title: '确认从文档生成阶段重跑？',
      content: `将仅对选中的 ${scopeModuleIds.length} 个模块重跑 AI 文档生成。`,
      okText: '确认重跑',
      onOk: async () => {
        setSubmitting(true);
        try {
          const resp = await remediateHierarchy({
            repositoryId: ctx.repositoryId!,
            systemId: ctx.systemId,
            hierarchy: draftHierarchy,
            moduleIds: scopeModuleIds,
            operator: getCurrentOperator(),
          });
          message.success(`纠错任务已创建 #${resp.taskId}`);
          setEditOpen(false);
          navigate(`/tasks/${resp.taskId}`);
        } finally {
          setSubmitting(false);
        }
      },
    });
  };

  return (
    <div className="ci-page ci-knowledge-hierarchy-page">
      <KnowledgeContextBar
        pageTitle="模块层级"
        pageDescription="查看并调整当前生效发布版的模块层级。"
        remediationHint="调整层级后，可指定模块范围从文档生成阶段重跑。"
        systems={ctx.systems}
        repositories={ctx.repositories}
        systemId={ctx.systemId}
        repositoryId={ctx.repositoryId}
        onSystemChange={ctx.setSystemId}
        onRepositoryChange={ctx.setRepositoryId}
        context={ctx.context}
        contextLoading={ctx.contextLoading}
        remediationEnabled={remediationReady}
        onRemediate={openEdit}
        onRefresh={() => {
          ctx.refreshContext();
          fetchHierarchy();
        }}
      />

      <Card>
        {ctx.repositoryId == null ? (
          <Empty description="请先选择系统与仓库" />
        ) : loading ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <Spin indicator={<LoadingOutlined />} tip="加载模块层级…" />
          </div>
        ) : counts.modules === 0 ? (
          <Empty description="该仓库尚无已发布的模块层级数据" />
        ) : (
          <>
            <Space size={24} style={{ marginBottom: 16 }}>
              <Statistic title="模块" value={counts.modules} />
              <Statistic title="功能" value={counts.functions} />
            </Space>
            <Tree showLine defaultExpandAll treeData={buildTreeData(hierarchy)} />
          </>
        )}
      </Card>

      <Drawer
        title="调整模块层级并重跑"
        width={960}
        open={editOpen}
        onClose={() => setEditOpen(false)}
        destroyOnHidden
        extra={
          <Button type="primary" loading={submitting} onClick={submitRemediation}>
            确认并重跑
          </Button>
        }
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="在 JSON 编辑器中调整层级结构，并选择需要重生成文档的模块（默认仅 scope 内模块调 AI）。"
        />
        <Space direction="vertical" style={{ width: '100%', marginBottom: 16 }}>
          <Text type="secondary">重跑文档的模块范围</Text>
          <Select
            mode="multiple"
            style={{ width: '100%' }}
            placeholder="选择 moduleId"
            value={scopeModuleIds}
            onChange={setScopeModuleIds}
            options={moduleOptions}
          />
        </Space>
        {draftHierarchy && (
          <ModuleHierarchyJsonEditor value={draftHierarchy} onChange={setDraftHierarchy} />
        )}
      </Drawer>
    </div>
  );
};

export default KnowledgeHierarchyPage;
