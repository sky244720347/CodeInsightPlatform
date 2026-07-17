import { Button, Popconfirm, Space, Tag, Tooltip, Typography } from 'antd';
import type { TableProps } from 'antd';
import {
  CopyOutlined,
  DeleteOutlined,
  EditOutlined,
  InboxOutlined,
  PlayCircleOutlined,
  RocketOutlined,
  StarFilled,
  StarOutlined,
} from '@ant-design/icons';
import type { Prompt } from '../../../types';
import { formatDateTime } from './utils';

const { Text } = Typography;

export type PromptLifecycle = 'DRAFT' | 'RELEASED' | 'ARCHIVED';

interface PromptColumnHandlers {
  onOpenTrial: (prompt: Prompt) => void;
  onEdit: (prompt: Prompt) => void;
  onClone: (prompt: Prompt) => void;
  onDelete: (id: number) => void;
  onPublish: (prompt: Prompt) => void;
  onArchive: (prompt: Prompt) => void;
  onSetDefault: (id: number) => void;
}

const LIFECYCLE_META: Record<PromptLifecycle, { color: string; label: string }> = {
  DRAFT: { color: 'gold', label: '草稿' },
  RELEASED: { color: 'green', label: '已发布' },
  ARCHIVED: { color: 'default', label: '已归档' },
};

const lifecycleTag = (lc: PromptLifecycle | string | undefined) => {
  const key = (lc as PromptLifecycle) ?? 'RELEASED';
  const meta = LIFECYCLE_META[key] ?? LIFECYCLE_META.RELEASED;
  return <Tag color={meta.color}>{meta.label}</Tag>;
};

/**
 * 提示词表格列定义
 * - 名称 + 默认标记
 * - 生命周期徽章
 * - 版本号
 * - 创建时间
 * - 操作:按 lifecycle 动态显示可用动作
 *   - DRAFT:编辑 / 试跑 / 发布 / 复制 / 删除
 *   - RELEASED:试跑 / 设为默认(取消) / 复制 / 归档
 *   - ARCHIVED:只读(可选"恢复为草稿"= 复制)
 */
export const createPromptColumns = ({
  onOpenTrial,
  onEdit,
  onClone,
  onDelete,
  onPublish,
  onArchive,
  onSetDefault,
}: PromptColumnHandlers): TableProps<Prompt>['columns'] => [
  {
    title: '提示词',
    dataIndex: 'name',
    key: 'name',
    width: 280,
    render: (text: string, record) => (
      <div className="ci-prompt-name-cell">
        <Tooltip title={text} placement="topLeft">
          <Button
            type="link"
            className="ci-table-link ci-prompt-name-link"
            onClick={() => onOpenTrial(record)}
          >
            <span>{text}</span>
          </Button>
        </Tooltip>
        {record.isDefault === 1 && (
          <Tag icon={<StarFilled />} color="gold">
            当前默认
          </Tag>
        )}
      </div>
    ),
  },
  {
    title: '类型',
    dataIndex: 'promptType',
    key: 'promptType',
    width: 130,
    render: (type: string | undefined) => {
      const map: Record<string, { color: string; label: string }> = {
        MODULARIZE: { color: 'geekblue', label: '模块提取' },
        DOCUMENT_GENERATION: { color: 'purple', label: '文档生成' },
      };
      const m = map[type ?? ''] ?? { color: 'default', label: type ?? '-' };
      return <Tag color={m.color}>{m.label}</Tag>;
    },
  },
  {
    title: '生命周期',
    dataIndex: 'lifecycle',
    key: 'lifecycle',
    width: 100,
    render: (lc) => lifecycleTag(lc),
  },
  {
    title: '版本',
    dataIndex: 'version',
    key: 'version',
    width: 72,
    render: (version: number) => <Tag color="purple">v{version}</Tag>,
  },
  {
    title: '创建时间',
    dataIndex: 'createdDate',
    key: 'createdDate',
    width: 166,
    render: (time: string) => formatDateTime(time),
  },
  {
    title: '操作',
    key: 'action',
    width: 360,
    fixed: 'right',
    render: (_: unknown, record) => {
      const lc = (record.lifecycle ?? 'RELEASED') as PromptLifecycle;
      const isDefault = record.isDefault === 1;
      return (
        <Space size={6} className="ci-prompt-row-actions" wrap>
          {/* 试跑 — 所有 lifecycle 都支持 */}
          <Button
            size="small"
            type="primary"
            ghost
            icon={<PlayCircleOutlined />}
            onClick={() => onOpenTrial(record)}
          >
            试跑
          </Button>

          {lc === 'DRAFT' && (
            <>
              <Button size="small" onClick={() => onEdit(record)}>
                编辑
              </Button>
              <Button
                size="small"
                type="primary"
                icon={<RocketOutlined />}
                onClick={() => onPublish(record)}
              >
                发布
              </Button>
              <Popconfirm
                title="确定删除该草稿？"
                onConfirm={() => onDelete(record.id)}
                okText="删除"
                cancelText="取消"
                okButtonProps={{ danger: true }}
              >
                <Button size="small" danger icon={<DeleteOutlined />}>
                  删除
                </Button>
              </Popconfirm>
            </>
          )}

          {lc === 'RELEASED' && (
            <>
              <Tooltip title="生成新版本草稿">
                <Button size="small" icon={<EditOutlined />} onClick={() => onEdit(record)}>
                  编辑
                </Button>
              </Tooltip>
              <Button
                size="small"
                icon={isDefault ? <StarFilled /> : <StarOutlined />}
                onClick={() => onSetDefault(record.id)}
                disabled={isDefault}
                title={isDefault ? '已是当前默认' : '设为当前默认'}
              >
                {isDefault ? '当前默认' : '设为默认'}
              </Button>
              <Button size="small" icon={<InboxOutlined />} onClick={() => onArchive(record)}>
                归档
              </Button>
            </>
          )}

          {lc === 'ARCHIVED' && (
            <Tooltip title="已归档,可通过复制产生新草稿修改">
              <Button size="small" icon={<CopyOutlined />} onClick={() => onClone(record)}>
                复制
              </Button>
            </Tooltip>
          )}

          {/* 复制 — DRAFT/RELEASED/ARCHIVED 都可复制,产生新草稿 */}
          {lc !== 'ARCHIVED' && (
            <Button size="small" icon={<CopyOutlined />} onClick={() => onClone(record)}>
              复制
            </Button>
          )}
        </Space>
      );
    },
  },
];

export const renderPromptExpandedRow = (record: Prompt) => (
  <div className="ci-prompt-expanded">
    <div className="ci-prompt-expanded-title">
      <Text type="secondary" strong>
        提示词正文模板：
      </Text>
    </div>
    <pre className="ci-prompt-expanded-content">{record.content}</pre>
  </div>
);
