import { useMemo } from 'react';
import {
  Button,
  Popconfirm,
  Space,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  ClockCircleOutlined,
  DeleteOutlined,
  FileTextOutlined,
  GlobalOutlined,
  ScanOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { testRepositoryConnection } from '../../api/repository';
import type { Repository } from '../../types';

const { Text } = Typography;

/** 仓库表行内操作回调 */
export interface RepositoryColumnHandlers {
  onEdit: (repo: Repository) => void;
  onDelete: (repo: Repository) => void;
  onScan: (repo: Repository) => void;
  onScanConfig?: (repo: Repository) => void;
  onBindPrompts?: (repo: Repository) => void;
  onScanWindow?: (repo: Repository) => void;
}

/**
 * 仓库表列定义工厂
 * 抽出 columns 主体（test 连接函数也用 useCallback 缓存，避免每次 render 重建）
 */
export function getRepositoryColumns(handlers: RepositoryColumnHandlers) {
  // 行内测试 Git：闭包缓存，避免 columns 反复重建
  const handleTest = async (repo: Repository) => {
    message.loading({ content: '正在测试 Git 连接...', key: 'test-conn' });
    const connected = await testRepositoryConnection(repo);
    if (connected) {
      message.success({ content: 'Git 连接测试成功', key: 'test-conn' });
    } else {
      message.error({ content: 'Git 连接测试失败', key: 'test-conn' });
    }
  };

  return [
    {
      title: 'Git 地址',
      dataIndex: 'gitUrl',
      key: 'gitUrl',
      width: 280,
      render: (url: string) => (
        <Tooltip title={url}>
          <Text code style={{ whiteSpace: 'nowrap' }}>
            {url?.length > 48 ? `${url.substring(0, 45)}...` : url}
          </Text>
        </Tooltip>
      ),
    },
    {
      title: '分支',
      dataIndex: 'branch',
      key: 'branch',
      width: 100,
      render: (branch: string) => <Tag color="geekblue">{branch}</Tag>,
    },
    {
      title: '扫描根目录',
      dataIndex: 'scanRoot',
      key: 'scanRoot',
      width: 110,
      render: (root: string) => <Text code>{root}</Text>,
    },
    {
      title: '最近运行',
      dataIndex: 'lastDecompileAt',
      key: 'lastDecompileAt',
      width: 160,
      render: (time: string) => (time ? new Date(time).toLocaleString() : '-'),
    },
    {
      title: '操作',
      key: 'action',
      width: 300,
      render: (_: unknown, repo: Repository) => (
        <Space size={6} wrap>
          <Button
            size="small"
            icon={<ScanOutlined />}
            type="primary"
            onClick={() => handlers.onScan(repo)}
          >
            扫描
          </Button>
          {handlers.onBindPrompts && (
            <Button
              size="small"
              icon={<FileTextOutlined />}
              onClick={() => handlers.onBindPrompts!(repo)}
            >
              提示词
            </Button>
          )}
          <Button size="small" onClick={() => handlers.onEdit(repo)}>
            编辑
          </Button>
          {handlers.onScanConfig && (
            <Button size="small" icon={<SettingOutlined />} onClick={() => handlers.onScanConfig!(repo)}>
              扫描规则
            </Button>
          )}
          {handlers.onScanWindow && (
            <Button size="small" icon={<ClockCircleOutlined />} onClick={() => handlers.onScanWindow!(repo)}>
              时间窗口
            </Button>
          )}
          <Button
            size="small"
            icon={<GlobalOutlined />}
            onClick={() => handleTest(repo)}
          />
          <Popconfirm
            title="删除该代码库？"
            description="存在未完成任务时会拒绝删除。"
            okText="确认"
            cancelText="取消"
            okButtonProps={{ danger: true }}
            onConfirm={() => handlers.onDelete(repo)}
          >
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];
}

/** 跟 columns.tsx 一样，对外暴露 useMemo 版本的便捷 hook（带 deps 缓存） */
export function useRepositoryColumns(handlers: RepositoryColumnHandlers) {
  return useMemo(() => getRepositoryColumns(handlers), [
    handlers.onEdit,
    handlers.onDelete,
    handlers.onScan,
    handlers.onScanConfig,
    handlers.onBindPrompts,
  ]);
}
