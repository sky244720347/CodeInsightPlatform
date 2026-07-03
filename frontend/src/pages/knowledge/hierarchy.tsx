import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Card,
  Empty,
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
import KnowledgeHierarchyRemediationDrawer from '../../components/KnowledgeHierarchyRemediationDrawer';
import type { FunctionNode, ModuleHierarchy, ModuleNode, SubModuleNode } from '../../types';
import { renderFunctionMetaTags } from '../../utils/hierarchyReadOnlyTags';
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
      title: (
        <Space size={4}>
          <Tag color={NODE_TYPE_TAG.SUB_MODULE.color}>{NODE_TYPE_TAG.SUB_MODULE.label}</Tag>
          <Text>{sm.subModuleName}</Text>
          <Text type="secondary" code style={{ fontSize: 11 }}>
            {sm.id}
          </Text>
        </Space>
      ),
      children: Object.values(sm.functions ?? {}).map((fn: FunctionNode) => ({
        key: `fn-${module.id}-${sm.id}-${fn.id}`,
        isLeaf: true,
        title: (
          <Space size={4} wrap>
            <Tag color={NODE_TYPE_TAG.FUNCTION.color}>{NODE_TYPE_TAG.FUNCTION.label}</Tag>
            <Text>{fn.functionName}</Text>
            <Text type="secondary" code style={{ fontSize: 11 }}>
              {fn.id}
            </Text>
            {renderFunctionMetaTags(fn)}
          </Space>
        ),
      })),
    })),
  }));
}

const KnowledgeHierarchyPage: React.FC = () => {
  const navigate = useNavigate();
  const ctx = useKnowledgeQueryContext();
  const [loading, setLoading] = useState(false);
  const [hierarchy, setHierarchy] = useState<ModuleHierarchy | null>(null);
  const [editOpen, setEditOpen] = useState(false);

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
    let totalClassPaths = 0;
    let totalMethodSignatures = 0;
    Object.values(hierarchy?.modules ?? {}).forEach((m) => {
      modules += 1;
      Object.values(m.subModules ?? {}).forEach((sm) => {
        Object.values(sm.functions ?? {}).forEach((fn) => {
          functions += 1;
          totalClassPaths += fn.classPaths?.length ?? 0;
          totalMethodSignatures += fn.methodSignatures?.length ?? 0;
        });
      });
    });
    return { modules, functions, totalClassPaths, totalMethodSignatures };
  }, [hierarchy]);

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
        onRemediate={() => setEditOpen(true)}
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
            <Space size={24} style={{ marginBottom: 16 }} wrap>
              <Statistic title="模块" value={counts.modules} />
              <Statistic title="功能" value={counts.functions} />
              <Statistic title="类路径" value={counts.totalClassPaths} />
              <Statistic title="方法签名" value={counts.totalMethodSignatures} />
            </Space>
            <Tree showLine defaultExpandAll treeData={buildTreeData(hierarchy)} />
          </>
        )}
      </Card>

      <KnowledgeHierarchyRemediationDrawer
        open={editOpen}
        initialHierarchy={hierarchy}
        moduleOptions={moduleOptions}
        onClose={() => setEditOpen(false)}
        onSubmit={async ({ hierarchy: nextHierarchy, moduleIds }) => {
          if (!ctx.repositoryId || !nextHierarchy) return;
          try {
            const resp = await remediateHierarchy({
              repositoryId: ctx.repositoryId,
              systemId: ctx.systemId,
              hierarchy: nextHierarchy,
              moduleIds,
              operator: getCurrentOperator(),
            });
            message.success(`纠错任务已创建 #${resp.taskId}`);
            navigate(`/tasks/${resp.taskId}`);
          } catch {
            // request.ts 拦截器已弹错；抽屉保留供用户继续编辑
            throw new Error('remediation failed');
          }
        }}
      />
    </div>
  );
};

export default KnowledgeHierarchyPage;
