import React, { useCallback, useEffect, useState } from 'react';
import {
  Button,
  Card,
  Col,
  InputNumber,
  Popconfirm,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  CloseCircleOutlined,
  
  EditOutlined,
  InfoCircleOutlined,
  ReloadOutlined,
  SortDescendingOutlined,
} from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import { cancelQueuedTask, getQueueSummary, listQueuedTasks, setTaskPriority } from '../../api/task';
import { listSystems } from '../../api/system';
import type { System, Task } from '../../types';
import {
  filterSystemSelectOption,
  renderComponentCell,
  renderSystemSelectLabel,
  renderSystemSelectOption,
  toSystemSelectOptions,
} from '../../utils/systemSelect';
const { Text } = Typography;
/**
 * 「任务队列」页面
 *
 * 展示所有 PENDING 任务，按 priority DESC + created_at ASC 排序。
 * 支持：
 *  - KPI 卡片：总数 + 平均等待时长
 *  - 按系统筛选
 *  - 行内调整优先级
 *  - 行内取消（PENDING → CANCELLED）
 */
const TaskQueuePage: React.FC = () => {
  const navigate = useNavigate();
  const [tasks, setTasks] = useState<Task[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [current, setCurrent] = useState(1);
  const [size, setSize] = useState(10);
  const [summary, setSummary] = useState<{ total: number; avgWaitSeconds: number }>({
    total: 0,
    avgWaitSeconds: 0,
  });
  const [filterSystemId, setFilterSystemId] = useState<number | undefined>();
  const [systems, setSystems] = useState<System[]>([]);
  // 正在编辑优先级的行
  const [editingPriority, setEditingPriority] = useState<Record<number, number>>({});
  const fetchQueue = useCallback(async () => {
    setLoading(true);
    try {
      const data = await listQueuedTasks({ current, size, systemId: filterSystemId });
      setTasks(data.records);
      setTotal(data.total);
    } finally {
      setLoading(false);
    }
  }, [current, size, filterSystemId]);
  const fetchSummary = useCallback(async () => {
    try {
      const data = await getQueueSummary();
      setSummary(data);
    } catch {
      // ignore
    }
  }, []);
  const fetchSystems = useCallback(async () => {
    try {
      const data = await listSystems({ current: 1, size: 200 });
      setSystems(data.records);
    } catch {
      // ignore
    }
  }, []);
  useEffect(() => {
    fetchSystems();
  }, [fetchSystems]);
  useEffect(() => {
    fetchQueue();
  }, [fetchQueue]);
  useEffect(() => {
    fetchSummary();
  }, [fetchSummary]);
  const handleCancel = async (id: number) => {
    try {
      await cancelQueuedTask(id);
      message.success('任务已取消');
      fetchQueue();
      fetchSummary();
    } catch {
      // 拦截器已提示
    }
  };
  const handleSavePriority = async (id: number) => {
    const newPriority = editingPriority[id];
    if (newPriority == null || newPriority < 0 || newPriority > 100) {
      message.warning('优先级范围 0-100');
      return;
    }
    try {
      await setTaskPriority(id, newPriority);
      message.success('优先级已更新');
      setEditingPriority((prev) => {
        const next = { ...prev };
        delete next[id];
        return next;
      });
      fetchQueue();
    } catch {
      // 拦截器已提示
    }
  };
  const isEditing = (id: number) => editingPriority[id] !== undefined;
  const getPriorityTagColor = (priority?: number) => {
    if (priority == null) return 'default';
    if (priority >= 80) return 'red';
    if (priority >= 60) return 'orange';
    if (priority >= 40) return 'blue';
    if (priority >= 20) return 'cyan';
    return 'default';
  };
  const columns = [
    {
      title: '优先级',
      dataIndex: 'priority',
      key: 'priority',
      width: 160,
      render: (priority: number | undefined, record: Task) => {
        if (isEditing(record.id)) {
          return (
            <Space size={4}>
              <InputNumber
                min={0}
                max={100}
                size="small"
                style={{ width: 70 }}
                value={editingPriority[record.id]}
                onChange={(v) =>
                  setEditingPriority((p) => ({
                    ...p,
                    [record.id]: v ?? 50,
                  }))
                }
              />
              <Button size="small" type="primary" onClick={() => handleSavePriority(record.id)}>
                保存
              </Button>
              <Button
                size="small"
                onClick={() =>
                  setEditingPriority((p) => {
                    const next = { ...p };
                    delete next[record.id];
                    return next;
                  })
                }
              >
                取消
              </Button>
            </Space>
          );
        }
        return (
          <Space size={6}>
            <Tag color={getPriorityTagColor(priority)} style={{ minWidth: 36, textAlign: 'center' }}>
              {priority ?? 50}
            </Tag>
            <Tooltip title="点击调整优先级">
              <Button
                size="small"
                type="text"
                icon={<EditOutlined />}
                onClick={() => setEditingPriority((p) => ({ ...p, [record.id]: record.priority ?? 50 }))}
              />
            </Tooltip>
          </Space>
        );
      },
      sorter: (a: Task, b: Task) => (b.priority ?? 50) - (a.priority ?? 50),
    },
    {
      title: '系统',
      dataIndex: 'systemId',
      key: 'systemName',
      width: 160,
      render: (sysId: number) =>
        systems.find((s) => s.id === sysId)?.name ?? (
          <Text type="secondary">系统 #{sysId}</Text>
        ),
    },
    {
      title: '组件',
      dataIndex: 'systemId',
      key: 'component',
      width: 120,
      render: (sysId: number) =>
        renderComponentCell(systems.find((s) => s.id === sysId)?.component),
    },
    {
      title: '类型',
      dataIndex: 'type',
      key: 'type',
      width: 100,
      render: (type: Task['type']) => (
        <Tag color={type === 'INITIAL' ? 'geekblue' : 'green'}>
          {type === 'INITIAL' ? '全量' : '增量'}
        </Tag>
      ),
    },
    {
      title: '来源',
      dataIndex: 'triggerSource',
      key: 'triggerSource',
      width: 120,
      render: (source: string | undefined, record: Task) => {
        if (source === 'SCHEDULED') {
          return (
            <Link to={`/tasks/jobs/${record.scheduleId}`}>
              <Tag color="blue">定时 #{record.scheduleId}</Tag>
            </Link>
          );
        }
        return <Tag>手动</Tag>;
      },
    },
    {
      title: '排队时长',
      width: 160,
      render: (_: unknown, record: Task) => {
        if (!record.createdDate) return '-';
        const ms = Date.now() - new Date(record.createdDate).getTime();
        const minutes = Math.floor(ms / 60000);
        const seconds = Math.floor((ms % 60000) / 1000);
        if (minutes > 0) return `${minutes} 分钟 ${seconds} 秒`;
        return `${seconds} 秒`;
      },
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
      width: 120,
      fixed: 'right' as const,
      render: (_: unknown, record: Task) => (
        <Space size={4}>
          <Tooltip title="查看详情">
            <Button size="small" onClick={() => navigate(`/tasks/${record.id}`)}>
              详情
            </Button>
          </Tooltip>
          <Popconfirm
            title="确定取消该任务？"
            description="任务将转入 CANCELLED 状态（无 in-flight 线程）"
            okText="取消任务"
            cancelText="取消"
            onConfirm={() => handleCancel(record.id)}
            okButtonProps={{ danger: true }}
          >
            <Button size="small" danger icon={<CloseCircleOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];
  return (
    <div className="ci-page ci-task-queue-page">
      {/* KPI 卡片 */}
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={12} md={6}>
          <Card size="small">
            <Statistic
              title="队列中任务"
              value={summary.total}
              prefix={<SortDescendingOutlined />}
              valueStyle={{ color: summary.total > 0 ? '#faad14' : undefined }}
            />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card size="small">
            <Statistic
              title="平均排队等待"
              value={summary.avgWaitSeconds}
              suffix="秒"
              valueStyle={summary.avgWaitSeconds > 30 ? { color: '#ff4d4f' } : undefined}
            />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card size="small">
            <Statistic title="涉及系统" value={new Set(tasks.map((t) => t.systemId)).size} />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card size="small">
            <Statistic
              title="在跑任务"
              value={total > 0 ? `${summary.total <= 2 ? '正常' : '繁忙'}` : '无'}
              valueStyle={{ color: summary.total <= 2 ? '#52c41a' : '#faad14' }}
            />
          </Card>
        </Col>
      </Row>
      {/* 筛选 */}
      <Card className="ci-filter-card" style={{ marginBottom: 12 }}>
        <Space wrap size={12}>
          <Select
            allowClear
            showSearch
            placeholder="按系统过滤"
            style={{ width: 240 }}
            value={filterSystemId}
            filterOption={filterSystemSelectOption}
            optionRender={renderSystemSelectOption}
            labelRender={(props) => renderSystemSelectLabel(props, systems)}
            onChange={(v) => {
              setFilterSystemId(v);
              setCurrent(1);
            }}
            options={toSystemSelectOptions(systems)}
          />
          <Button icon={<ReloadOutlined />} onClick={() => { fetchQueue(); fetchSummary(); }}>
            刷新
          </Button>
          <Text type="secondary" style={{ fontSize: 12 }}>
            <InfoCircleOutlined style={{ marginRight: 4 }} />
            PENDING 任务按优先级+创建时间排序，全局上限 {summary.total <= 2 ? summary.total : 2} 个同时在跑
          </Text>
        </Space>
      </Card>
      {/* 列表 */}
      <Card>
        <Table<Task>
          dataSource={tasks}
          columns={columns}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1100 }}
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
          locale={{
            emptyText: (
              <Space direction="vertical" style={{ padding: 24 }}>
                <Text type="secondary">队列为空，所有任务正在正常处理</Text>
              </Space>
            ),
          }}
        />
      </Card>
    </div>
  );
};
export default TaskQueuePage;
