import { Button, Popconfirm, Space, Tag, Typography } from 'antd';
import { BookOutlined, DeleteOutlined, EditOutlined, SettingOutlined } from '@ant-design/icons';
import type { System } from '../../types';

const { Text } = Typography;

/** 系统表各操作列的事件回调 */
export interface SystemColumnHandlers {
  onEdit: (system: System) => void;
  onOpenDetail: (system: System) => void;
  onDelete: (system: System) => void;
  /** 修改系统提示词绑定 */
  onEditPrompts: (system: System) => void;
  /** 维护系统业务知识（喂给 {business_knowledge.md} 占位符） */
  onEditBusinessKnowledge: (system: System) => void;
}

/**
 * 系统主表列定义工厂
 * 把 handlers 注入后返回 columns 数组（避免在组件里写大段 render）
 *
 * <p>系统级启停状态机已删除：不再有"状态"列与"启停"列。</p>
 */
export const getSystemColumns = (handlers: SystemColumnHandlers) => [
  {
    title: '系统',
    dataIndex: 'name',
    key: 'name',
    width: 200,
    fixed: 'left' as const,
    render: (text: string, record: System) => (
      <Button type="link" className="ci-table-link" onClick={() => handlers.onOpenDetail(record)}>
        {text}
      </Button>
    ),
  },
  {
    title: '中文名称',
    dataIndex: 'nameCn',
    key: 'nameCn',
    width: 180,
    render: (nameCn?: string) => nameCn || <Text type="secondary">未填写</Text>,
  },
  {
    title: '负责人',
    dataIndex: 'owner',
    key: 'owner',
    width: 110,
    render: (owner: string) => <Tag color="blue">{owner || '未分配'}</Tag>,
  },
  {
    title: '代码库数',
    dataIndex: 'repositoryCount',
    key: 'repositoryCount',
    width: 100,
    render: (n?: number) =>
      typeof n === 'number' ? <Tag color={n > 0 ? 'geekblue' : 'default'}>{n}</Tag> : '-',
  },
  {
    title: '知识版本',
    dataIndex: 'knowledgeVersionCount',
    key: 'knowledgeVersionCount',
    width: 100,
    render: (n?: number) =>
      typeof n === 'number' ? <Tag color={n > 0 ? 'green' : 'default'}>{n}</Tag> : '-',
  },
  {
    title: '最近扫描',
    dataIndex: 'lastDecompileAt',
    key: 'lastDecompileAt',
    width: 160,
    render: (time?: string) =>
      time ? new Date(time).toLocaleString() : <Text type="secondary">未扫描</Text>,
  },
  {
    title: '创建时间',
    dataIndex: 'createdDate',
    key: 'createdDate',
    width: 170,
    render: (time: string) => (time ? new Date(time).toLocaleString() : '-'),
  },
  {
    title: '操作',
    key: 'action',
    width: 280,
    fixed: 'right' as const,
    render: (_: unknown, record: System) => (
      <Space size={6} wrap>
        <Button size="small" icon={<EditOutlined />} onClick={() => handlers.onEdit(record)}>
          编辑
        </Button>
        <Button
          size="small"
          icon={<SettingOutlined />}
          onClick={() => handlers.onOpenDetail(record)}
        >
          仓库
        </Button>
        <Button
          size="small"
          icon={<BookOutlined />}
          onClick={() => handlers.onEditBusinessKnowledge(record)}
        >
          业务知识
        </Button>
        <Popconfirm
          title={`删除系统【${record.name}】？`}
          description="将级联软删除该系统下所有代码库，存在未完成任务时会拒绝。"
          okText="确认删除"
          cancelText="取消"
          okButtonProps={{ danger: true }}
          onConfirm={() => handlers.onDelete(record)}
        >
          <Button size="small" danger icon={<DeleteOutlined />} />
        </Popconfirm>
      </Space>
    ),
  },
];
