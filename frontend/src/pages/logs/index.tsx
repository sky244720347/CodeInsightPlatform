import React, { useEffect, useState } from 'react';
import { Button, Card, Descriptions, Form, Input, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import { EyeOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { useSearchParams } from 'react-router-dom';
import { getLogDetail, listLogs, type OperationLog } from '../../api/log';
import { listSystems } from '../../api/system';
import type { System } from '../../types';

const { Text } = Typography;

const actionMeta: Record<string, { color: string; label: string }> = {
  CREATE_SYSTEM: { color: 'blue', label: '创建系统' },
  UPDATE_SYSTEM: { color: 'cyan', label: '更新系统' },
  CHANGE_SYSTEM_STATUS: { color: 'purple', label: '变更状态' },
  CREATE_REPO: { color: 'geekblue', label: '创建仓库' },
  UPDATE_REPO: { color: 'indigo', label: '更新仓库' },
  TEST_CONNECTION: { color: 'magenta', label: '测试连接' },
  TASK_TRANSIT: { color: 'orange', label: '任务流转' },
  SAVE_DRAFT: { color: 'gold', label: '保存草稿' },
  CONFIRM_KNOWLEDGE: { color: 'green', label: '确认知识' },
  PUSH_GIT: { color: 'lime', label: '推送 Git' },
};

const Logs: React.FC = () => {
  const [searchParams] = useSearchParams();
  const urlTaskId = searchParams.get('taskId') || '';

  const [logs, setLogs] = useState<OperationLog[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [current, setCurrent] = useState(1);
  const [size, setSize] = useState(10);

  const [systems, setSystems] = useState<System[]>([]);
  const [selectedSystemId, setSelectedSystemId] = useState<number | undefined>();
  const [searchTaskId, setSearchTaskId] = useState(urlTaskId);
  const [searchUsername, setSearchUsername] = useState('');
  const [searchActionType, setSearchActionType] = useState('');
  const [searchSuccess, setSearchSuccess] = useState<number | undefined>();

  const [detailOpen, setDetailOpen] = useState(false);
  const [detailLog, setDetailLog] = useState<OperationLog | null>(null);

  const fetchLogs = async (page = current, pageSize = size) => {
    setLoading(true);
    try {
      const data = await listLogs({
        current: page,
        size: pageSize,
        systemId: selectedSystemId,
        taskId: searchTaskId ? Number(searchTaskId) : undefined,
        username: searchUsername || undefined,
        actionType: searchActionType || undefined,
        isSuccess: searchSuccess,
      });
      setLogs(data.records);
      setTotal(data.total);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    listSystems({ current: 1, size: 100 }).then((data) => setSystems(data.records));
  }, []);

  // 从 URL 参数带出 taskId 时，自动查询
  useEffect(() => {
    if (urlTaskId) {
      setCurrent(1);
      fetchLogs(1, size);
    }
  }, []);

  useEffect(() => {
    fetchLogs();
  }, [current, size]);

  const handleSearch = () => {
    setCurrent(1);
    fetchLogs(1, size);
  };

  const handleReset = () => {
    setSelectedSystemId(undefined);
    setSearchTaskId('');
    setSearchUsername('');
    setSearchActionType('');
    setSearchSuccess(undefined);
    setCurrent(1);
    setTimeout(() => fetchLogs(1, size), 0);
  };

  const handleViewDetail = async (id: number) => {
    const detail = await getLogDetail(id);
    setDetailLog(detail);
    setDetailOpen(true);
  };

  const getActionTag = (action: string) => {
    const meta = actionMeta[action] ?? { color: 'default', label: action };
    return <Tag color={meta.color}>{meta.label}</Tag>;
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 90, render: (id: number) => <Text code>#{id}</Text> },
    { title: '用户', dataIndex: 'username', key: 'username', width: 140 },
    {
      title: '操作类型',
      dataIndex: 'actionType',
      key: 'actionType',
      width: 180,
      render: (action: string) => getActionTag(action),
    },
    { title: '详情', dataIndex: 'detail', key: 'detail', ellipsis: true },
    {
      title: '结果',
      dataIndex: 'isSuccess',
      key: 'isSuccess',
      width: 110,
      render: (ok: number) => <Tag color={ok === 1 ? 'success' : 'error'}>{ok === 1 ? '成功' : '失败'}</Tag>,
    },
    {
      title: 'IP',
      dataIndex: 'ipAddress',
      key: 'ipAddress',
      width: 140,
      render: (ip: string | null) => ip || <Text type="secondary">-</Text>,
    },
    {
      title: '创建时间',
      dataIndex: 'createdDate',
      key: 'createdDate',
      width: 190,
      render: (date: string) => new Date(date).toLocaleString(),
    },
    {
      title: '操作',
      key: 'action',
      width: 110,
      fixed: 'right' as const,
      render: (_: unknown, record: OperationLog) => (
        <Button size="small" icon={<EyeOutlined />} onClick={() => handleViewDetail(record.id)}>
          详情
        </Button>
      ),
    },
  ];

  return (
    <div className="ci-page ci-logs-page">
      <Card className="ci-filter-card">
        <Form layout="inline" className="ci-inline-form">
          <Form.Item label="系统">
            <Select
              style={{ width: 180 }}
              placeholder="全部系统"
              value={selectedSystemId}
              onChange={setSelectedSystemId}
              allowClear
              options={systems.map((system) => ({ value: system.id, label: system.name }))}
            />
          </Form.Item>
          <Form.Item label="任务">
            <Input style={{ width: 110 }} placeholder="任务 ID" value={searchTaskId} onChange={(event) => setSearchTaskId(event.target.value)} />
          </Form.Item>
          <Form.Item label="用户">
            <Input style={{ width: 140 }} placeholder="用户名" value={searchUsername} onChange={(event) => setSearchUsername(event.target.value)} />
          </Form.Item>
          <Form.Item label="操作">
            <Input
              style={{ width: 180 }}
              placeholder="CREATE_SYSTEM"
              value={searchActionType}
              onChange={(event) => setSearchActionType(event.target.value)}
            />
          </Form.Item>
          <Form.Item label="结果">
            <Select
              style={{ width: 120 }}
              placeholder="任意"
              value={searchSuccess}
              onChange={setSearchSuccess}
              allowClear
              options={[
                { value: 1, label: '成功' },
                { value: 0, label: '失败' },
              ]}
            />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>
                查询
              </Button>
              <Button icon={<ReloadOutlined />} onClick={handleReset}>
                重置
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      <Card title="审计轨迹">
        <Table
          dataSource={logs}
          columns={columns}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1120 }}
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
        title="操作日志详情"
        open={detailOpen}
        onOk={() => setDetailOpen(false)}
        onCancel={() => setDetailOpen(false)}
        width={760}
        destroyOnHidden
      >
        {detailLog && (
          <Descriptions bordered column={2} size="small" layout="vertical">
            <Descriptions.Item label="日志 ID">{detailLog.id}</Descriptions.Item>
            <Descriptions.Item label="用户">{detailLog.username}</Descriptions.Item>
            <Descriptions.Item label="系统 ID">{detailLog.systemId || '-'}</Descriptions.Item>
            <Descriptions.Item label="任务 ID">{detailLog.taskId || '-'}</Descriptions.Item>
            <Descriptions.Item label="操作">{getActionTag(detailLog.actionType)}</Descriptions.Item>
            <Descriptions.Item label="结果">
              <Tag color={detailLog.isSuccess === 1 ? 'success' : 'error'}>{detailLog.isSuccess === 1 ? '成功' : '失败'}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="创建时间" span={2}>
              {new Date(detailLog.createdDate).toLocaleString()}
            </Descriptions.Item>
            <Descriptions.Item label="详情" span={2}>
              {detailLog.detail}
            </Descriptions.Item>
            {detailLog.isSuccess === 0 && detailLog.exceptionMsg && (
              <Descriptions.Item label="异常" span={2}>
                <pre className="ci-error-preview">{detailLog.exceptionMsg}</pre>
              </Descriptions.Item>
            )}
          </Descriptions>
        )}
      </Modal>
    </div>
  );
};

export default Logs;
