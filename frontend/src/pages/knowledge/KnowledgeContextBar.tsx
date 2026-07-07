import React from 'react';
import { Button, Card, Select, Space, Tag, Tooltip, Typography } from 'antd';
import { ReloadOutlined, ToolOutlined } from '@ant-design/icons';
import type { KnowledgeContextView } from '../../api/knowledge-query';
import type { Repository, System } from '../../types';
import PageHelpHint from '../../components/PageHelpHint';

const { Text } = Typography;

export interface KnowledgeContextBarProps {
  pageTitle: string;
  /** 标题旁感叹号悬停说明 */
  pageHelp?: { title?: string; content: React.ReactNode };
  systems: System[];
  repositories: Repository[];
  systemId?: number;
  repositoryId?: number;
  onSystemChange: (id?: number) => void;
  onRepositoryChange: (id?: number) => void;
  context: KnowledgeContextView | null;
  contextLoading?: boolean;
  onRefresh?: () => void;
  requireRepository?: boolean;
  remediationEnabled?: boolean;
  onRemediate?: () => void;
}

const KnowledgeContextBar: React.FC<KnowledgeContextBarProps> = ({
  pageTitle,
  pageHelp,
  systems,
  repositories,
  systemId,
  repositoryId,
  onSystemChange,
  onRepositoryChange,
  context,
  contextLoading,
  onRefresh,
  requireRepository = true,
  remediationEnabled = false,
  onRemediate,
}) => {
  const systemOptions = systems.map((s) => ({ value: s.id, label: s.name }));
  const repoOptions = repositories.map((r) => {
    const base = r.gitUrl?.split('/').pop()?.replace(/\.git$/, '') ?? `仓库 #${r.id}`;
    return { value: r.id, label: `${base} (${r.branch})` };
  });

  const hasActiveVersion = context?.versionId != null && context.releaseDirExists;

  return (
    <div className="ci-knowledge-query-shell">
      <Card className="ci-workspace-card" style={{ marginBottom: 16 }}>
        <Space wrap style={{ width: '100%', justifyContent: 'space-between' }} size={12}>
          {/* 左侧：标题 + 生效版本 Tag */}
          <Space size={8} align="center" wrap>
            <Text strong style={{ fontSize: 16 }}>
              {pageTitle}
            </Text>
            {pageHelp && (
              <PageHelpHint title={pageHelp.title} content={pageHelp.content} />
            )}
            {hasActiveVersion && (
              <Tag color="processing">
                当前生效：{context?.versionNum}
                {context?.taskId != null ? ` · 任务 #${context.taskId}` : ''}
              </Tag>
            )}
            {repositoryId != null && !hasActiveVersion && !contextLoading && (
              <Tag color="warning">尚无生效发布版</Tag>
            )}
          </Space>

          {/* 右侧：系统 + 仓库下拉 + 调整并重跑 + 刷新（与 push 页风格一致） */}
          <Space wrap size={12}>
            <Space size={4}>
              <Text type="secondary">系统</Text>
              <Select
                placeholder="请选择系统"
                value={systemId}
                onChange={onSystemChange}
                style={{ width: 200 }}
                showSearch
                optionFilterProp="label"
                options={systemOptions}
                allowClear={!requireRepository}
              />
            </Space>
            <Space size={4}>
              <Text type="secondary">仓库</Text>
              <Select
                placeholder="请选择仓库"
                value={repositoryId}
                onChange={onRepositoryChange}
                style={{ width: 240 }}
                showSearch
                optionFilterProp="label"
                options={repoOptions}
                disabled={systemId == null}
                allowClear={!requireRepository}
              />
            </Space>
            {remediationEnabled && onRemediate ? (
              <Button type="primary" icon={<ToolOutlined />} onClick={onRemediate}>
                调整并重跑
              </Button>
            ) : (
              <Tooltip title="请先选择仓库并确保有生效发布版">
                <Button icon={<ToolOutlined />} disabled>
                  调整并重跑
                </Button>
              </Tooltip>
            )}
            {onRefresh && (
              <Button icon={<ReloadOutlined />} loading={contextLoading} onClick={onRefresh}>
                刷新
              </Button>
            )}
          </Space>
        </Space>
      </Card>
    </div>
  );
};

export default KnowledgeContextBar;
