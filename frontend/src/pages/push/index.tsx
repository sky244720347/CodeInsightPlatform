import React, { useCallback, useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { Button, Select, Space, Table, Tag, Typography, message } from 'antd';
import { DownloadOutlined, HistoryOutlined, ReloadOutlined, RollbackOutlined } from '@ant-design/icons';
import {
  listVersions,
  listPushTasks,
  rollbackRepositoryPublish,
  downloadVersionZip,
  type KnowledgeVersion,
  type PushTask,
} from '../../api/knowledge';
import { listSystems } from '../../api/system';
import { listRepositories } from '../../api/repository';
import type { Repository, System } from '../../types';
import PageHelpHint from '../../components/PageHelpHint';
import { knowledgePushHelp } from '../../constants/pushPageHelp';
import {
  filterSystemSelectOption,
  renderSystemSelectLabel,
  renderSystemSelectOption,
  toSystemSelectOptions,
} from '../../utils/systemSelect';

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

/**
 * 推送记录：只读查看知识版本与 NAS 推送历史，支持 ZIP 下载与回滚生效。
 * 建版/推送由任务知识确认后自动完成，本页不提供新建或入队操作。
 */
const PushRecords: React.FC = () => {
  const location = useLocation();
  const [versions, setVersions] = useState<KnowledgeVersion[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [current, setCurrent] = useState(1);
  const [size, setSize] = useState(10);

  const [systems, setSystems] = useState<System[]>([]);
  const [selectedSystemId, setSelectedSystemId] = useState<number | undefined>(
    location.state?.systemId ? Number(location.state.systemId) : undefined,
  );
  const [selectedRepositoryId, setSelectedRepositoryId] = useState<number | undefined>(
    location.state?.repositoryId ? Number(location.state.repositoryId) : undefined,
  );
  const [repositories, setRepositories] = useState<Repository[]>([]);
  const [pushTasks, setPushTasks] = useState<Map<number, PushTask[]>>(new Map());

  useEffect(() => {
    listSystems({ current: 1, size: 100 }).then((data) => setSystems(data.records));
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

  const fetchVersions = useCallback(async () => {
    setLoading(true);
    try {
      const data = await listVersions({
        current,
        size,
        systemId: selectedSystemId,
        repositoryId: selectedRepositoryId,
      });
      setVersions(data.records);
      setTotal(data.total);
      const map = new Map<number, PushTask[]>();
      await Promise.all(
        data.records.map(async (v) => {
          try {
            map.set(v.id, await listPushTasks(v.id));
          } catch {
            map.set(v.id, []);
          }
        }),
      );
      setPushTasks(map);
    } finally {
      setLoading(false);
    }
  }, [current, size, selectedSystemId, selectedRepositoryId]);

  useEffect(() => {
    fetchVersions();
  }, [fetchVersions]);

  const handleRollback = async (versionId: number) => {
    await rollbackRepositoryPublish(versionId);
    message.success('已回滚仓库生效版本指针');
    fetchVersions();
  };

  const handleDownloadZip = (versionId: number) => {
    downloadVersionZip(versionId);
    message.success('ZIP 导出已开始');
  };

  const columns = [
    {
      title: '版本号',
      dataIndex: 'versionNum',
      key: 'versionNum',
      width: 100,
      render: (v: string, row: KnowledgeVersion) => (
        <Space>
          <Text strong>{v}</Text>
          {row.activePublished ? <Tag color="blue">当前生效</Tag> : null}
        </Space>
      ),
    },
    {
      title: '任务',
      dataIndex: 'taskId',
      key: 'taskId',
      width: 90,
      render: (id: number) => `#${id}`,
    },
    {
      title: '版本状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (s: string) => {
        const meta = statusMeta[s] ?? { color: 'default', label: s };
        return <Tag color={meta.color}>{meta.label}</Tag>;
      },
    },
    {
      title: '推送方式',
      dataIndex: 'pushMethod',
      key: 'pushMethod',
      width: 90,
      render: () => 'NAS',
    },
    {
      title: '确认人 / 时间',
      key: 'confirm',
      render: (_: unknown, row: KnowledgeVersion) => (
        <Text type="secondary">
          {row.confirmedBy || '—'}
          {row.confirmedAt ? ` · ${new Date(row.confirmedAt).toLocaleString()}` : ''}
        </Text>
      ),
    },
    {
      title: '最近推送记录',
      key: 'pushTasks',
      render: (_: unknown, row: KnowledgeVersion) => {
        const tasks = pushTasks.get(row.id) ?? [];
        if (tasks.length === 0) return <Text type="secondary">无</Text>;
        const latest = tasks[0];
        const meta = pushTaskStatusMeta[latest.status] ?? { color: 'default', label: latest.status };
        return (
          <Space direction="vertical" size={0}>
            <Tag color={meta.color}>{meta.label}</Tag>
            {latest.errorMessage ? (
              <Text type="danger" style={{ fontSize: 12 }}>
                {latest.errorMessage}
              </Text>
            ) : null}
          </Space>
        );
      },
    },
    {
      title: '操作',
      key: 'actions',
      width: 180,
      render: (_: unknown, row: KnowledgeVersion) => (
        <Space size={0}>
          <Button
            type="link"
            size="small"
            icon={<DownloadOutlined />}
            onClick={() => handleDownloadZip(row.id)}
          >
            ZIP
          </Button>
          {row.status === 'PUSHED' && !row.activePublished ? (
            <Button
              type="link"
              size="small"
              icon={<RollbackOutlined />}
              onClick={() => handleRollback(row.id)}
            >
              回滚生效
            </Button>
          ) : null}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Space>
          <HistoryOutlined />
          <Text strong>推送记录</Text>
          <PageHelpHint {...knowledgePushHelp} />
        </Space>
        <Button icon={<ReloadOutlined />} onClick={fetchVersions}>
          刷新
        </Button>
      </div>

      <Space wrap style={{ marginBottom: 16 }}>
        <Select
          allowClear
          placeholder="系统"
          style={{ minWidth: 200 }}
          value={selectedSystemId}
          showSearch
          filterOption={filterSystemSelectOption}
          optionRender={renderSystemSelectOption}
          labelRender={(props) => renderSystemSelectLabel(props, systems)}
          options={toSystemSelectOptions(systems)}
          onChange={(v) => {
            setSelectedSystemId(v);
            setSelectedRepositoryId(undefined);
            setCurrent(1);
          }}
        />
        <Select
          allowClear
          placeholder="仓库"
          style={{ minWidth: 260 }}
          value={selectedRepositoryId}
          disabled={selectedSystemId == null}
          options={repositories.map((r) => ({ value: r.id, label: r.gitUrl || `仓库 #${r.id}` }))}
          onChange={(v) => {
            setSelectedRepositoryId(v);
            setCurrent(1);
          }}
        />
      </Space>

      <Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
        知识版本由任务确认后自动按 v1/v2/v3… 创建并 NAS 推送；本页可查询、下载 ZIP、回滚生效指针。
      </Text>

      <Table
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={versions}
        pagination={{
          current,
          pageSize: size,
          total,
          onChange: (c, s) => {
            setCurrent(c);
            setSize(s);
          },
        }}
      />
    </div>
  );
};

export default PushRecords;
