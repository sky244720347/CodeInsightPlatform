import React, { useCallback, useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { Alert, Button, Card, Descriptions, Form, Input, Modal, Select, Space, Table, Tag, Tooltip, Typography, message } from 'antd';
import { CloudUploadOutlined, DownloadOutlined, HistoryOutlined, PlusOutlined, PullRequestOutlined, ReloadOutlined, RollbackOutlined } from '@ant-design/icons';
import {
  createVersion,
  listVersions,
  pushVersion,
  listPushTasks,
  rollbackRepositoryPublish,
  listRepositoryPublishSnapshots,
  type KnowledgeVersion,
  type PushTask,
  type RepositoryPublishSnapshotView,
} from '../../api/knowledge';
import { listSystems } from '../../api/system';
import { listRepositories } from '../../api/repository';
import { listTasks } from '../../api/task';
import type { Repository, System, Task } from '../../types';
import { getCurrentOperator } from '../../api/auth';

const { Text } = Typography;

const statusMeta: Record<string, { color: string; label: string }> = {
  DRAFT: { color: 'default', label: '待推送' },
  PUSHING: { color: 'processing', label: '推送中' },
  PUSHED: { color: 'success', label: '已推送' },
  FAILED: { color: 'error', label: '失败' },
};

const pushTaskStatusMeta: Record<string, { color: string; label: string }> = {
  PENDING: { color: 'default', label: '排队中' },
  PROCESSING: { color: 'processing', label: '执行中' },
  SUCCESS: { color: 'success', label: '成功' },
  FAILED: { color: 'error', label: '失败' },
};

const pushMethodLabel: Record<string, string> = {
  NAS: 'NAS 发布',
  GIT: 'Git 推送',
  S3: 'S3 推送',
};

const DEFAULT_PUSH_METHOD = 'NAS';

const taskStatusLabel: Record<string, string> = {
  PENDING_REVIEW: '待复核',
  REVIEWING: '复核中',
  CONFIRMED: '已确认',
};

const Push: React.FC = () => {
  const location = useLocation();
  const [versions, setVersions] = useState<KnowledgeVersion[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [current, setCurrent] = useState(1);
  const [size, setSize] = useState(10);

  const [systems, setSystems] = useState<System[]>([]);
  const [selectedSystemId, setSelectedSystemId] = useState<number | undefined>(
    location.state?.systemId ? Number(location.state.systemId) : undefined
  );
  const [selectedRepositoryId, setSelectedRepositoryId] = useState<number | undefined>(
    location.state?.repositoryId ? Number(location.state.repositoryId) : undefined
  );
  const [repositories, setRepositories] = useState<Repository[]>([]);
  const [publishSnapshots, setPublishSnapshots] = useState<RepositoryPublishSnapshotView[]>([]);
  const [tasks, setTasks] = useState<Task[]>([]);

  const [versionModalOpen, setVersionModalOpen] = useState(false);
  const [versionForm] = Form.useForm();
  const [mrModalOpen, setMrModalOpen] = useState(false);
  const [activeVersion, setActiveVersion] = useState<KnowledgeVersion | null>(null);
  const [mrForm] = Form.useForm();
  const [pushTasks, setPushTasks] = useState<Map<number, PushTask[]>>(new Map());
  const [pushingVersions, setPushingVersions] = useState<Set<number>>(new Set());

  useEffect(() => {
    listSystems({ current: 1, size: 100, status: 1 }).then((data) => setSystems(data.records));
  }, []);

  useEffect(() => {
    if (selectedSystemId == null) {
      setRepositories([]);
      setSelectedRepositoryId(undefined);
      return;
    }
    listRepositories({ current: 1, size: 200, systemId: selectedSystemId })
      .then((data) => setRepositories(data.records ?? []))
      .catch(() => setRepositories([]));
  }, [selectedSystemId]);

  useEffect(() => {
    if (selectedRepositoryId == null) {
      setPublishSnapshots([]);
      return;
    }
    listRepositoryPublishSnapshots(selectedRepositoryId)
      .then(setPublishSnapshots)
      .catch(() => setPublishSnapshots([]));
  }, [selectedRepositoryId]);

  const snapshotByVersionId = React.useMemo(() => {
    const map = new Map<number, RepositoryPublishSnapshotView>();
    publishSnapshots.forEach((s) => map.set(s.versionId, s));
    return map;
  }, [publishSnapshots]);

  const fetchVersions = useCallback(
    async (page = current, pageSize = size) => {
      setLoading(true);
      try {
        const data = await listVersions({
          current: page,
          size: pageSize,
          systemId: selectedSystemId,
          repositoryId: selectedRepositoryId,
        });
        setVersions(data.records);
        setTotal(data.total);
      } finally {
        setLoading(false);
      }
    },
    [current, selectedRepositoryId, selectedSystemId, size],
  );

  useEffect(() => {
    fetchVersions();
  }, [fetchVersions]);

  // Auto-refresh push tasks for versions currently in PUSHING status
  useEffect(() => {
    if (pushingVersions.size === 0) return;
    const interval = setInterval(async () => {
      const newTasks = new Map(pushTasks);
      let hasActive = false;
      for (const versionId of pushingVersions) {
        try {
          const tasks = await listPushTasks(versionId);
          newTasks.set(versionId, tasks);
          // Check if any task is still active
          const hasPending = tasks.some(
            (t) => t.status === 'PENDING' || t.status === 'PROCESSING'
          );
          if (hasPending) hasActive = true;
        } catch {
          // ignore fetch errors
        }
      }
      setPushTasks(newTasks);
      if (!hasActive) {
        setPushingVersions(new Set());
        fetchVersions(); // refresh version list to get final status
      }
    }, 3000);
    return () => clearInterval(interval);
  }, [pushingVersions, pushTasks, fetchVersions]);

  const openVersionModal = async () => {
    if (!selectedSystemId) {
      message.warning('请先选择系统再创建版本');
      return;
    }
    const data = await listTasks({ current: 1, size: 100, systemId: selectedSystemId });
    setTasks(data.records.filter((task) => ['PENDING_REVIEW', 'CONFIRMED'].includes(task.status)));
    setVersionModalOpen(true);
  };

  const handleCreateVersion = async () => {
    const values = await versionForm.validateFields();
    await createVersion(values.taskId, values.versionNum, getCurrentOperator());
    message.success('知识版本已创建');
    setVersionModalOpen(false);
    versionForm.resetFields();
    fetchVersions();
  };

  const handlePush = async (versionId: number, method: string = DEFAULT_PUSH_METHOD) => {
    const version = versions.find((v) => v.id === versionId);
    const methodLabel = pushMethodLabel[method] || method;
    Modal.confirm({
      title: `发布版本（${methodLabel}）？`,
      content: (
        <div>
          <p style={{ marginBottom: 6 }}>
            即将通过 <Text strong>{methodLabel}</Text> 发布版本{' '}
            <Text strong>{version?.versionNum ?? `#${versionId}`}</Text>。
          </p>
          <p style={{ marginBottom: 6, fontSize: 13 }}>
            发布成功后将<strong>强制同步</strong>到仓库：扫描配置快照、提示词绑定、入口复核结果、模块层级复核结果。
          </p>
          <p style={{ marginBottom: 0, fontSize: 12, color: '#818aa0' }}>
            任务将进入队列异步执行；成功后任务锁定。可通过「回滚到该版本」切换仓库生效配置与知识浏览版本。
          </p>
        </div>
      ),
      okText: '加入发布队列',
      cancelText: '取消',
      onOk: async () => {
        message.loading({ content: '推送任务加入队列...', key: 'pushing' });
        try {
          await pushVersion(versionId, method);
          message.success({ content: '推送任务已加入队列，后台异步执行中', key: 'pushing', duration: 3 });
          setPushingVersions((prev) => new Set(prev).add(versionId));
          fetchVersions();
        } catch {
          message.error({ content: '推送任务提交失败', key: 'pushing' });
        }
      },
    });
  };

  const handleRollbackRepository = (versionId: number, versionNum?: string) => {
    Modal.confirm({
      title: '回滚到该版本？',
      content: (
        <div>
          <p style={{ marginBottom: 6 }}>
            将把仓库的扫描配置、提示词、入口清单、模块层级，以及知识浏览的生效版本指针恢复为版本{' '}
            <Text strong>{versionNum ?? `#${versionId}`}</Text> 发布时的状态。
          </p>
          <p style={{ marginBottom: 0, fontSize: 12, color: '#818aa0' }}>
            NAS 上各版本的发布产物文件不会被删除；回滚仅切换当前生效指针，知识查看将读取该版本目录。
          </p>
        </div>
      ),
      okText: '确认回滚',
      okButtonProps: { danger: true },
      onOk: async () => {
        await rollbackRepositoryPublish(versionId);
        message.success('已回滚到该版本，仓库配置与知识浏览生效版本已切换');
        if (selectedRepositoryId != null) {
          listRepositoryPublishSnapshots(selectedRepositoryId).then(setPublishSnapshots).catch(() => undefined);
        }
        fetchVersions();
      },
    });
  };

  const handleDownloadZip = (versionId: number) => {
    const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api';
    window.open(`${baseUrl}/knowledge/${versionId}/export`);
    message.success('ZIP 导出已开始');
  };

  const handleCreateMr = async () => {
    await mrForm.validateFields();
    message.success(`合并请求已为 ${activeVersion?.versionNum} 准备完成`);
    setMrModalOpen(false);
    mrForm.resetFields();
  };

  const rollbackDisabledReason = (record: KnowledgeVersion): string | null => {
    if (record.status !== 'PUSHED') return null;
    if (record.activePublished) return '已是当前生效版本';
    const snap = snapshotByVersionId.get(record.id);
    if (selectedRepositoryId != null) {
      if (!snap) return '未找到该版本的发布快照';
      if (!snap.releaseDirExists) return 'NAS 发布产物目录缺失，无法回滚';
    }
    return null;
  };

  const columns = [
    {
      title: '版本',
      dataIndex: 'versionNum',
      key: 'versionNum',
      width: 120,
      render: (text: string, record: KnowledgeVersion) => (
        <Space size={4}>
          <Text strong>{text}</Text>
          {record.activePublished && (
            <Tag color="processing">当前生效</Tag>
          )}
        </Space>
      ),
    },
    {
      title: '版本状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (status: string) => {
        const meta = statusMeta[status] ?? { color: 'default', label: status };
        return <Tag color={meta.color}>{meta.label}</Tag>;
      },
    },
    {
      title: '推送方式',
      dataIndex: 'pushMethod',
      key: 'pushMethod',
      width: 90,
      render: (method: string) => pushMethodLabel[method] || method || DEFAULT_PUSH_METHOD,
    },
    {
      title: '队列状态',
      key: 'pushTaskStatus',
      width: 100,
      render: (_: unknown, record: KnowledgeVersion) => {
        const tasks = pushTasks.get(record.id);
        if (!tasks || tasks.length === 0) return <Text type="secondary">-</Text>;
        const latest = tasks[0];
        const meta = pushTaskStatusMeta[latest.status] ?? { color: 'default', label: latest.status };
        return (
          <Space size={4}>
            <Tag color={meta.color}>{meta.label}</Tag>
            {latest.retryCount > 0 && (
              <Text type="secondary" style={{ fontSize: 11 }}>
                ({latest.retryCount}/{latest.maxRetries})
              </Text>
            )}
          </Space>
        );
      },
    },
    { title: '源分支', dataIndex: 'sourceBranch', key: 'sourceBranch', width: 90 },
    {
      title: '源 Commit',
      dataIndex: 'sourceCommit',
      key: 'sourceCommit',
      width: 100,
      render: (commit: string) => <Text code>{commit?.substring(0, 8) || '-'}</Text>,
    },
    {
      title: '目标 Commit',
      dataIndex: 'targetCommit',
      key: 'targetCommit',
      width: 100,
      render: (commit: string | null) => (commit ? <Text code>{commit.substring(0, 8)}</Text> : <Text type="secondary">-</Text>),
    },
    { title: '确认人', dataIndex: 'confirmedBy', key: 'confirmedBy', width: 80 },
    {
      title: '推送时间',
      dataIndex: 'pushedAt',
      key: 'pushedAt',
      width: 150,
      render: (text: string | null) => (text ? new Date(text).toLocaleString() : '-'),
    },
    {
      title: '操作',
      key: 'action',
      width: 340,
      fixed: 'right' as const,
      render: (_: unknown, record: KnowledgeVersion) => {
        const isPushing = pushingVersions.has(record.id);
        return (
          <Space size={8} wrap>
            {record.status === 'DRAFT' || record.status === 'FAILED' ? (
              <>
                <Button
                  type="primary"
                  size="small"
                  icon={<CloudUploadOutlined />}
                  loading={isPushing}
                  onClick={() => handlePush(record.id, DEFAULT_PUSH_METHOD)}
                >
                  发布到 NAS
                </Button>
                <Button
                  size="small"
                  icon={<HistoryOutlined />}
                  loading={isPushing}
                  onClick={() => handlePush(record.id, 'GIT')}
                >
                  Git
                </Button>
              </>
            ) : record.status === 'PUSHING' ? (
              <Button size="small" loading disabled>
                发布中...
              </Button>
            ) : record.status === 'PUSHED' ? (
              <>
                {record.activePublished ? (
                  <Tag color="processing">当前生效</Tag>
                ) : (
                  <Tooltip title={rollbackDisabledReason(record) ?? undefined}>
                    <Button
                      size="small"
                      danger
                      icon={<RollbackOutlined />}
                      disabled={rollbackDisabledReason(record) != null}
                      onClick={() => handleRollbackRepository(record.id, record.versionNum)}
                    >
                      回滚到该版本
                    </Button>
                  </Tooltip>
                )}
                <Button
                  size="small"
                  icon={<PullRequestOutlined />}
                  onClick={() => {
                    setActiveVersion(record);
                    setMrModalOpen(true);
                  }}
                >
                  创建 MR
                </Button>
              </>
            ) : (
              <Button
                size="small"
                icon={<PullRequestOutlined />}
                onClick={() => {
                  setActiveVersion(record);
                  setMrModalOpen(true);
                }}
              >
                创建 MR
              </Button>
            )}
            <Button size="small" icon={<DownloadOutlined />} onClick={() => handleDownloadZip(record.id)}>
              ZIP
            </Button>
          </Space>
        );
      },
    },
  ];

  return (
    <div className="ci-page ci-push-page">
      <Alert
        className="ci-guardrail-alert"
        type="info"
        showIcon
        message="知识发布说明"
        description="发布成功后将知识文档写入 NAS（或 Git），并强制把任务的扫描配置、提示词、入口复核、模块层级同步到仓库维度。已发布版本支持「回滚到该版本」切换生效配置与知识浏览版本。"
      />

      <Card
        className="ci-workspace-card ci-push-console"
        title="知识发布控制台"
        extra={
          <Space wrap>
            <Select
              style={{ width: 220 }}
              placeholder="筛选系统"
              value={selectedSystemId}
              onChange={(v) => {
                setSelectedSystemId(v);
                setSelectedRepositoryId(undefined);
                setCurrent(1);
              }}
              allowClear
              options={systems.map((system) => ({ value: system.id, label: system.name }))}
            />
            <Select
              style={{ width: 240 }}
              placeholder="筛选仓库"
              value={selectedRepositoryId}
              onChange={(v) => {
                setSelectedRepositoryId(v);
                setCurrent(1);
              }}
              allowClear
              disabled={selectedSystemId == null}
              options={repositories.map((repo) => {
                const base = repo.gitUrl?.split('/').pop()?.replace(/\.git$/, '') ?? `仓库 #${repo.id}`;
                return { value: repo.id, label: `${base} (${repo.branch})` };
              })}
            />
            <Button type="primary" icon={<PlusOutlined />} onClick={openVersionModal}>
              新建版本
            </Button>
            <Button icon={<ReloadOutlined />} onClick={() => fetchVersions()}>
              刷新
            </Button>
          </Space>
        }
      >
        <Table
          dataSource={versions}
          columns={columns}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1280 }}
          pagination={{
            current,
            pageSize: size,
            total,
            showSizeChanger: true,
            onChange: (page, pageSize) => {
              setCurrent(page);
              setSize(pageSize);
            },
          }}
        />
      </Card>

      <Modal
        title="创建知识版本"
        open={versionModalOpen}
        onOk={handleCreateVersion}
        onCancel={() => {
          setVersionModalOpen(false);
          versionForm.resetFields();
        }}
        destroyOnHidden
      >
        <Form form={versionForm} layout="vertical">
          <Form.Item name="taskId" label="已确认任务" rules={[{ required: true, message: '请选择任务' }]}>
            <Select
              placeholder="请选择任务"
              options={tasks.map((task) => ({
                value: task.id,
                label: `任务 #${task.id} / ${task.type === 'INITIAL' ? '全量' : '增量'} / ${taskStatusLabel[task.status] ?? task.status}`,
              }))}
            />
          </Form.Item>
          <Form.Item
            name="versionNum"
            label="版本号"
            dependencies={['taskId']}
            rules={[
              { required: true, message: '请输入版本号' },
              { pattern: /^v\d+\.\d+\.\d+$/, message: '请使用语义化版本格式，例如 v1.0.0' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  const taskId = getFieldValue('taskId');
                  const task = tasks.find((item) => item.id === taskId);
                  const trimmed = value?.trim();
                  if (!task || !trimmed) {
                    return Promise.resolve();
                  }
                  const duplicate = versions.some(
                    (item) => item.repositoryId === task.repositoryId && item.versionNum === trimmed,
                  );
                  if (duplicate) {
                    return Promise.reject(new Error('该仓库已存在相同版本号，请使用不同的 versionNum'));
                  }
                  return Promise.resolve();
                },
              }),
            ]}
          >
            <Input placeholder="v1.0.0" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="创建合并请求"
        open={mrModalOpen}
        onOk={handleCreateMr}
        onCancel={() => {
          setMrModalOpen(false);
          mrForm.resetFields();
        }}
        destroyOnHidden
      >
        <Descriptions bordered column={1} size="small" style={{ marginBottom: 16 }}>
          <Descriptions.Item label="版本">{activeVersion?.versionNum}</Descriptions.Item>
          <Descriptions.Item label="源分支">{activeVersion?.targetBranch}</Descriptions.Item>
          <Descriptions.Item label="提交">{activeVersion?.targetCommit?.substring(0, 8) || '-'}</Descriptions.Item>
        </Descriptions>
        <Form form={mrForm} layout="vertical">
          <Form.Item name="targetBranch" label="目标分支" initialValue="main" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'main', label: 'main' },
                { value: 'master', label: 'master' },
                { value: 'develop', label: 'develop' },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="mrTitle"
            label="合并请求标题"
            initialValue={`docs: 合并代码洞察知识 ${activeVersion?.versionNum || ''}`}
            rules={[{ required: true }]}
          >
            <Input />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default Push;
