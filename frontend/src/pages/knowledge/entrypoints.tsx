import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Empty,
  Modal,
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
import { CloseOutlined, LoadingOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { getCurrentOperator } from '../../api/auth';
import { listPublishedEntrypoints } from '../../api/knowledge-query';
import { remediateEntrypoints } from '../../api/knowledge-remediation';
import type { EntrypointReviewItem, ExcludeTarget } from '../../types';
import KnowledgeContextBar from './KnowledgeContextBar';
import { useKnowledgeQueryContext } from './useKnowledgeQueryContext';

const { Text } = Typography;

const ENTRY_TYPE_LABEL: Record<string, { color: string; label: string }> = {
  CONTROLLER: { color: 'cyan', label: 'Controller' },
  SCHEDULED_JOB: { color: 'purple', label: 'Job' },
  MQ_LISTENER: { color: 'gold', label: 'MQ' },
  OTHER: { color: 'default', label: '其他' },
};

function shortClassName(fq: string): string {
  const idx = fq.lastIndexOf('.');
  return idx >= 0 ? fq.slice(idx + 1) : fq;
}

function targetKey(t: ExcludeTarget): string {
  return `${t.className}#${t.methodSignature?.trim() || ''}`;
}

const KnowledgeEntrypointsPage: React.FC = () => {
  const navigate = useNavigate();
  const ctx = useKnowledgeQueryContext();
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [items, setItems] = useState<EntrypointReviewItem[]>([]);
  const [viewMode, setViewMode] = useState<'list' | 'tree'>('list');
  const [editMode, setEditMode] = useState(false);
  const [pendingExcludes, setPendingExcludes] = useState<ExcludeTarget[]>([]);

  const remediationReady =
    ctx.repositoryId != null && ctx.context?.versionId != null && ctx.context.releaseDirExists;

  const fetchItems = useCallback(async () => {
    if (ctx.repositoryId == null) {
      setItems([]);
      return;
    }
    setLoading(true);
    try {
      const data = await listPublishedEntrypoints(ctx.repositoryId, ctx.systemId);
      setItems(data);
    } catch {
      setItems([]);
    } finally {
      setLoading(false);
    }
  }, [ctx.repositoryId, ctx.systemId]);

  useEffect(() => {
    fetchItems();
  }, [fetchItems]);

  const visibleItems = useMemo(() => {
    if (!editMode || pendingExcludes.length === 0) return items;
    return items.filter((it) => {
      const classExcluded = pendingExcludes.some(
        (t) => t.className === it.className && !t.methodSignature?.trim(),
      );
      return !classExcluded;
    });
  }, [items, editMode, pendingExcludes]);

  const addExclude = (target: ExcludeTarget) => {
    setPendingExcludes((prev) => {
      const key = targetKey(target);
      if (prev.some((t) => targetKey(t) === key)) return prev;
      return [...prev, target];
    });
  };

  const handleSubmitRemediation = () => {
    if (!ctx.repositoryId) return;
    Modal.confirm({
      title: '确认从模块层级阶段重跑？',
      content:
        '将克隆当前生效任务的工作区与 AST 产物，应用入口调整后全量重算模块层级与文档（跳过拉取/扫描）。',
      okText: '确认重跑',
      onOk: async () => {
        setSubmitting(true);
        try {
          const resp = await remediateEntrypoints({
            repositoryId: ctx.repositoryId!,
            systemId: ctx.systemId,
            excludeTargets: pendingExcludes,
            operator: getCurrentOperator(),
          });
          message.success(`纠错任务已创建 #${resp.taskId}`);
          setEditMode(false);
          setPendingExcludes([]);
          navigate(`/tasks/${resp.taskId}`);
        } finally {
          setSubmitting(false);
        }
      },
    });
  };

  const treeData = useMemo<DataNode[]>(() => {
    const groups: Record<string, EntrypointReviewItem[]> = {};
    visibleItems.forEach((it) => {
      const t = it.entryType || 'UNKNOWN';
      if (!groups[t]) groups[t] = [];
      groups[t].push(it);
    });
    return Object.entries(groups).map(([typeKey, classList]) => {
      const typeMeta = ENTRY_TYPE_LABEL[typeKey] || { color: 'default', label: typeKey };
      return {
        key: `type-${typeKey}`,
        selectable: false,
        title: (
          <Space size={4}>
            <Tag color={typeMeta.color}>{typeMeta.label}</Tag>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {classList.length} 类
            </Text>
          </Space>
        ),
        children: classList.map((cls) => ({
          key: `class-${cls.id}`,
          title: (
            <Space size={4} wrap>
              <Text strong>{shortClassName(cls.className)}</Text>
              {editMode && (
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
            </Space>
          ),
          children: (cls.methods || []).map((m, mi) => ({
            key: `method-${cls.id}-${mi}`,
            isLeaf: true,
            title: (
              <Space size={4} style={{ fontSize: 12 }}>
                <Text>{m.methodSignature || m.methodName}</Text>
                {editMode && (
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
  }, [visibleItems, editMode]);

  return (
    <div className="ci-page ci-knowledge-entrypoints-page">
      <KnowledgeContextBar
        pageTitle="扫描入口"
        pageDescription="查看并调整当前生效发布版的仓库级入口清单。"
        remediationHint="排除入口类/方法后，将从模块层级 AI 阶段重跑（全量重算层级）。"
        systems={ctx.systems}
        repositories={ctx.repositories}
        systemId={ctx.systemId}
        repositoryId={ctx.repositoryId}
        onSystemChange={ctx.setSystemId}
        onRepositoryChange={ctx.setRepositoryId}
        context={ctx.context}
        contextLoading={ctx.contextLoading}
        remediationEnabled={remediationReady && !editMode}
        onRemediate={() => setEditMode(true)}
        onRefresh={() => {
          ctx.refreshContext();
          fetchItems();
        }}
      />

      <Card>
        {editMode && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 16 }}
            message="编辑模式：点击类/方法旁的 × 将其排除；确认后将创建纠错任务。"
            action={
              <Space>
                <Button
                  onClick={() => {
                    setEditMode(false);
                    setPendingExcludes([]);
                  }}
                >
                  取消
                </Button>
                <Button type="primary" loading={submitting} onClick={handleSubmitRemediation}>
                  确认并重跑
                </Button>
              </Space>
            }
          />
        )}
        {pendingExcludes.length > 0 && (
          <Space wrap style={{ marginBottom: 12 }}>
            {pendingExcludes.map((t) => (
              <Tag
                key={targetKey(t)}
                closable
                onClose={() =>
                  setPendingExcludes((prev) => prev.filter((x) => targetKey(x) !== targetKey(t)))
                }
              >
                {shortClassName(t.className)}
                {t.methodSignature ? `#${t.methodSignature}` : ' (整类)'}
              </Tag>
            ))}
          </Space>
        )}

        {ctx.repositoryId == null ? (
          <Empty description="请先选择系统与仓库" />
        ) : loading ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <Spin indicator={<LoadingOutlined />} tip="加载入口清单…" />
          </div>
        ) : items.length === 0 ? (
          <Empty description="该仓库尚无已发布的扫描入口数据" />
        ) : (
          <>
            <Space wrap style={{ marginBottom: 16, justifyContent: 'space-between', width: '100%' }}>
              <Statistic title="入口类" value={visibleItems.length} />
              <Segmented
                value={viewMode}
                onChange={(v) => setViewMode(v as 'list' | 'tree')}
                options={[
                  { value: 'list', label: '列表' },
                  { value: 'tree', label: '树形' },
                ]}
              />
            </Space>
            {viewMode === 'list' ? (
              <Table<EntrypointReviewItem>
                rowKey="id"
                dataSource={visibleItems}
                pagination={{ pageSize: 20 }}
                columns={[
                  { title: '类型', dataIndex: 'entryType', width: 110 },
                  { title: '类名', dataIndex: 'className', ellipsis: true },
                  { title: '文件', dataIndex: 'filePath', ellipsis: true },
                ]}
              />
            ) : (
              <Tree showLine defaultExpandAll treeData={treeData} />
            )}
          </>
        )}
      </Card>
    </div>
  );
};

export default KnowledgeEntrypointsPage;
