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
import { testRepositoryConnectionDetailed } from '../../api/repository';
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
  /** 行内测试 Git 成功后刷新列表 */
  onAfterGitTest?: () => void;
}

function renderGitReachable(repo: Repository) {
  const flag = repo.gitReachable;
  const checkedHint = repo.gitCheckedAt
    ? `最近检测：${new Date(repo.gitCheckedAt).toLocaleString()}`
    : '尚未检测';
  const failHint = repo.gitCheckMsg ? `\n${repo.gitCheckMsg}` : '';
  if (flag === 1) {
    return (
      <Tooltip title={checkedHint}>
        <Text style={{ color: '#52c41a', fontWeight: 500 }}>连通</Text>
      </Tooltip>
    );
  }
  if (flag === 0) {
    return (
      <Tooltip title={`${checkedHint}${failHint}`}>
        <Text style={{ color: '#ff4d4f', fontWeight: 500 }}>不通</Text>
      </Tooltip>
    );
  }
  return (
    <Tooltip title={checkedHint}>
      <Text type="secondary">未检测</Text>
    </Tooltip>
  );
}

/**
 * 仓库表列定义工厂
 */
export function getRepositoryColumns(handlers: RepositoryColumnHandlers) {
  const handleTest = async (repo: Repository) => {
    message.loading({ content: '正在测试 Git 连接...', key: 'test-conn' });
    try {
      const res = await testRepositoryConnectionDetailed(repo);
      if (res?.reachable) {
        message.success({ content: 'Git 连接测试成功', key: 'test-conn' });
      } else {
        message.error({
          content: res?.message ? `Git 连接测试失败：${res.message}` : 'Git 连接测试失败',
          key: 'test-conn',
        });
      }
      handlers.onAfterGitTest?.();
    } catch {
      message.error({ content: 'Git 连接测试失败', key: 'test-conn' });
    }
  };

  return [
    {
      title: 'Git 地址',
      dataIndex: 'gitUrl',
      key: 'gitUrl',
      width: 260,
      render: (url: string) => (
        <Tooltip title={url}>
          <Text code style={{ whiteSpace: 'nowrap' }}>
            {url?.length > 48 ? `${url.substring(0, 45)}...` : url}
          </Text>
        </Tooltip>
      ),
    },
    {
      title: 'Git 连通',
      key: 'gitReachable',
      width: 88,
      render: (_: unknown, repo: Repository) => renderGitReachable(repo),
    },
    {
      title: '类型',
      dataIndex: 'repoType',
      key: 'repoType',
      width: 72,
      render: (v?: string | null) => (v ? <Tag>{v}</Tag> : <Text type="secondary">-</Text>),
    },
    {
      title: '技术栈',
      dataIndex: 'techStack',
      key: 'techStack',
      width: 88,
      render: (v?: string | null) => (v ? <Tag color="blue">{v}</Tag> : <Text type="secondary">-</Text>),
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
          <Tooltip title="测试 Git">
            <Button size="small" icon={<GlobalOutlined />} onClick={() => handleTest(repo)} />
          </Tooltip>
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

export function useRepositoryColumns(handlers: RepositoryColumnHandlers) {
  return useMemo(() => getRepositoryColumns(handlers), [
    handlers.onEdit,
    handlers.onDelete,
    handlers.onScan,
    handlers.onScanConfig,
    handlers.onBindPrompts,
    handlers.onScanWindow,
    handlers.onAfterGitTest,
  ]);
}
