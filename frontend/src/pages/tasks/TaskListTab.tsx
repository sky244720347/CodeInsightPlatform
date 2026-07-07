import React, { useCallback, useEffect, useState } from 'react';
import {
  Button,
  Card,
  Col,
  DatePicker,
  Form,
  Input,
  Popconfirm,
  Progress,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  DeleteOutlined,
  EditOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  ApartmentOutlined,
  EyeOutlined,
  LoadingOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import { type Dayjs } from 'dayjs';
import {
  getQueueSummary,
  getTaskSummary,
  listTasks,
  retryTask,
  startTask,
  terminateTask,
  deleteTask,
  type TaskStatusSummary,
} from '../../api/task';
import { listSystems } from '../../api/system';
import type { System, Task } from '../../types';
import { isTaskDeletable } from '../../utils/taskActions';

const { Text } = Typography;
const { RangePicker } = DatePicker;

const runningStatuses = [
  'PENDING',
  'PULLING_CODE',
  'PARSING_CODE',
  'ENTRYPOINT_REVIEW',
  'AI_ANALYZING',
  'MODULE_HIERARCHY_REVIEW',
  'PENDING_REVIEW',
  'REVIEWING',
  'GENERATING_DOC',
  'PUSHING',
];

const statusMeta: Record<string, { color: string; label: string; loading?: boolean }> = {
  DRAFT: { color: 'default', label: '草稿' },
  PENDING: { color: 'blue', label: '排队中', loading: true },
  PULLING_CODE: { color: 'blue', label: '拉取代码', loading: true },
  PARSING_CODE: { color: 'cyan', label: '解析代码', loading: true },
  /** @deprecated 历史任务 */
  SPLITTING_TASK: { color: 'default', label: '任务切片（已废弃）' },
  AI_ANALYZING: { color: 'orange', label: 'AI 分析中', loading: true },
  MODULE_HIERARCHY: { color: 'gold', label: '模块层级提炼' },
  ENTRYPOINT_REVIEW: { color: 'cyan', label: '入口复核' },
  MODULE_HIERARCHY_REVIEW: { color: 'geekblue', label: '模块层级调试' },
  GENERATING_DOC: { color: 'gold', label: '生成文档', loading: true },
  PENDING_REVIEW: { color: 'magenta', label: '待复核' },
  REVIEWING: { color: 'geekblue', label: '复核中' },
  CONFIRMED: { color: 'green', label: '已确认' },
  PUSHING: { color: 'purple', label: '推送中', loading: true },
  PUSHED: { color: 'green', label: '已推送' },
  FAILED: { color: 'red', label: '失败' },
  CANCELLED: { color: 'default', label: '已取消' },
};

type GroupKey = 'ALL' | 'RUNNING' | 'PENDING_REVIEW' | 'CONFIRMED' | 'CLOSED';

const GROUP_STATUSES: Record<GroupKey, string[] | null> = {
  ALL: null,
  RUNNING: ['PENDING', 'PULLING_CODE', 'PARSING_CODE', 'ENTRYPOINT_REVIEW', 'AI_ANALYZING', 'GENERATING_DOC', 'PUSHING'],
  PENDING_REVIEW: ['PENDING_REVIEW', 'REVIEWING'],
  CONFIRMED: ['CONFIRMED', 'PUSHED'],
  CLOSED: ['FAILED', 'CANCELLED', 'ARCHIVED'],
};

const GROUP_LABELS: Record<GroupKey, { label: string; hint: string }> = {
  ALL: { label: '全部', hint: '所有任务' },
  RUNNING: { label: '进行中', hint: '拉取/解析/切片/AI/推送' },
  PENDING_REVIEW: { label: '待处理', hint: '待复核 + 复核中（含驳回待重跑）' },
  CONFIRMED: { label: '已确认', hint: '已通过 + 已推送' },
  CLOSED: { label: '已终止', hint: '失败 / 取消 / 归档' },
};

/**
 * 「任务查询」页签
 *
 * 简单搜索：按系统筛选
 * 精准搜索：可展开的高级过滤面板（状态/类型/触发源/模型名/创建时间）
 * 状态分组 chips：与搜索条件正交叠加；列表默认按创建时间倒序
 */
const TaskListTab: React.FC = () => {
  const navigate = useNavigate();

  // 任务表格状态
  const [tasks, setTasks] = useState<Task[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [current, setCurrent] = useState(1);
  const [size, setSize] = useState(10);

  // 简单搜索：按系统筛选
  const [filterSystemId, setFilterSystemId] = useState<number | undefined>();

  // 精准搜索条件
  const [filterStatus, setFilterStatus] = useState<string | undefined>();
  const [filterType, setFilterType] = useState<string | undefined>();
  const [filterTriggerSource, setFilterTriggerSource] = useState<string | undefined>();
  const [filterModelName, setFilterModelName] = useState<string | undefined>();
  const [filterCreatedRange, setFilterCreatedRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);
  const [advancedOpen, setAdvancedOpen] = useState(false);

  // 状态分组 chips
  const [activeGroup, setActiveGroup] = useState<GroupKey>('ALL');
  const [taskSummary, setTaskSummary] = useState<TaskStatusSummary>({
    ALL: 0,
    RUNNING: 0,
    PENDING_REVIEW: 0,
    CONFIRMED: 0,
    CLOSED: 0,
  });
  const [queueCount, setQueueCount] = useState(0);

  // 系统下拉
  const [systems, setSystems] = useState<System[]>([]);

  const fetchTasks = useCallback(
    async (page = current, pageSize = size) => {
      setLoading(true);
      try {
        const groupStatuses = GROUP_STATUSES[activeGroup];
        const data = await listTasks({
          current: page,
          size: pageSize,
          systemId: filterSystemId,
          status: filterStatus,
          statuses: groupStatuses ?? undefined,
          type: filterType,
          triggerSource: filterTriggerSource,
          modelName: filterModelName,
          createdAtStart: filterCreatedRange?.[0]?.startOf('day').toISOString(),
          createdAtEnd: filterCreatedRange?.[1]?.endOf('day').toISOString(),
        });
        setTasks(data.records);
        setTotal(data.total);
      } finally {
        setLoading(false);
      }
    },
    [
      current,
      size,
      filterSystemId,
      filterStatus,
      filterType,
      filterTriggerSource,
      filterModelName,
      filterCreatedRange,
      activeGroup,
    ],
  );

  const fetchSummary = useCallback(async () => {
    try {
      const data = await getTaskSummary({ systemId: filterSystemId });
      setTaskSummary(data);
    } catch {
      // ignore
    }
  }, [filterSystemId]);

  const loadOptions = useCallback(async () => {
    try {
      const sysData = await listSystems({ current: 1, size: 200 });
      setSystems(sysData.records);
    } catch {
      // ignore
    }
  }, []);

  useEffect(() => {
    loadOptions();
  }, [loadOptions]);

  useEffect(() => {
    fetchTasks();
  }, [fetchTasks]);

  useEffect(() => {
    fetchSummary();
  }, [fetchSummary]);

  // 队列数量（顶栏提示用）
  useEffect(() => {
    getQueueSummary().then((s) => setQueueCount(s.total)).catch(() => {});
  }, []);

  // 运行时轮询
  useEffect(() => {
    const hasRunning = tasks.some((task) => runningStatuses.includes(task.status));
    if (!hasRunning) return;
    const timer = window.setInterval(() => {
      fetchTasks(current, size);
    }, 2500);
    return () => window.clearInterval(timer);
  }, [current, fetchTasks, size, tasks]);

  const handleGroupChange = (next: GroupKey) => {
    setActiveGroup(next);
    setFilterStatus(undefined);
    setCurrent(1);
  };

  const handleResetAdvanced = () => {
    setFilterSystemId(undefined);
    setFilterStatus(undefined);
    setFilterType(undefined);
    setFilterTriggerSource(undefined);
    setFilterModelName(undefined);
    setFilterCreatedRange(null);
    setActiveGroup('ALL');
    setCurrent(1);
  };

  const handleStart = async (id: number) => {
    await startTask(id);
    message.success('任务已启动');
    fetchTasks();
  };

  const handleTerminate = async (id: number) => {
    await terminateTask(id);
    message.success('终止请求已发送');
    fetchTasks();
  };

  const handleRetry = async (id: number) => {
    await retryTask(id);
    message.success('任务已重新启动');
    fetchTasks();
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteTask(id);
      message.success('任务已删除');
      fetchTasks();
      fetchSummary();
    } catch {
      // request 拦截器已提示
    }
  };

  const getStatusTag = (status: string) => {
    const meta = statusMeta[status] ?? { color: 'default', label: status };
    return (
      <Tag color={meta.color} icon={meta.loading ? <LoadingOutlined /> : undefined}>
        {meta.label}
      </Tag>
    );
  };

  const columns = [
    {
      title: '任务',
      dataIndex: 'id',
      key: 'id',
      width: 100,
      render: (id: number) => <Text code>#{id}</Text>,
    },
    {
      title: '系统',
      dataIndex: 'systemId',
      key: 'systemName',
      width: 180,
      render: (sysId: number) => systems.find((system) => system.id === sysId)?.name ?? `系统 #${sysId}`,
    },
    {
      title: '类型',
      dataIndex: 'type',
      key: 'type',
      width: 140,
      render: (type: Task['type']) => (
        <Tag color={type === 'INITIAL' ? 'geekblue' : 'green'}>
          {type === 'INITIAL' ? '全量扫描' : '增量扫描'}
        </Tag>
      ),
    },
    {
      title: '来源',
      dataIndex: 'triggerSource',
      key: 'triggerSource',
      width: 130,
      render: (source: string | undefined, record: Task) => {
        if (source === 'SCHEDULED') {
          return (
            <Tooltip title="由定时任务触发">
              <Tag color="blue">
                <Link to={`/tasks/jobs/${record.scheduleId}`} onClick={(e) => e.stopPropagation()}>
                  定时 #{record.scheduleId}
                </Link>
              </Tag>
            </Tooltip>
          );
        }
        return <Tag>手动</Tag>;
      },
    },
    {
      title: 'AI模型',
      dataIndex: 'modelName',
      key: 'modelName',
      width: 150,
      render: (modelName: string) =>
        modelName ? <Tag color="orange">{modelName}</Tag> : '-',
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 190,
      render: (status: string, record: Task) => (
        <Space size={4} direction="vertical" style={{ lineHeight: 1.2 }}>
          {getStatusTag(status)}
          {record.status === 'REVIEWING' && (
            <Tag color="warning" style={{ fontSize: 11, margin: 0, lineHeight: '16px', padding: '0 6px' }}>
              待重跑
            </Tag>
          )}
        </Space>
      ),
    },
    {
      title: '进度',
      key: 'progress',
      width: 180,
      render: (_: unknown, record: Task) => (
        <Progress
          percent={record.progress}
          size="small"
          status={
            record.status === 'FAILED'
              ? 'exception'
              : record.progress === 100
                ? 'success'
                : 'active'
          }
        />
      ),
    },
    {
      title: '执行耗时',
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 120,
      render: (ms: number) => (ms ? `${(ms / 1000).toFixed(1)}s` : '-'),
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 190,
      render: (time: string) => (time ? new Date(time).toLocaleString() : '-'),
    },
    {
      title: '操作',
      key: 'action',
      width: 340,
      fixed: 'right' as const,
      render: (_: unknown, record: Task) => (
        <Space size={8} wrap>
          <Button size="small" icon={<EyeOutlined />} onClick={() => navigate(`/tasks/${record.id}`)}>
            详情
          </Button>
          {record.status === 'DRAFT' && (
            <Button
              size="small"
              type="primary"
              icon={<PlayCircleOutlined />}
              onClick={() => handleStart(record.id)}
            >
              启动
            </Button>
          )}
          {record.status === 'ENTRYPOINT_REVIEW' && (
            <Button
              size="small"
              type="primary"
              icon={<ApartmentOutlined />}
              onClick={() => navigate('/tasks/entrypoint-review')}
            >
              入口复核
            </Button>
          )}
          {record.status === 'MODULE_HIERARCHY_REVIEW' && (

            <Button
              size="small"
              type="primary"
              icon={<EyeOutlined />}
              onClick={() => navigate(`/tasks/hierarchy-review/${record.id}`)}
            >
              开始调试
            </Button>
          )}
          {(['PENDING_REVIEW', 'REVIEWING'].includes(record.status)) && (
            <Button
              size="small"
              type="primary"
              icon={<EditOutlined />}
              onClick={() => navigate('/drafts')}
            >
              知识复核
            </Button>
          )}
          {runningStatuses.includes(record.status) && (

            <Button size="small" danger icon={<CloseCircleOutlined />} onClick={() => handleTerminate(record.id)}>
              终止
            </Button>
          )}
          {['FAILED', 'CANCELLED'].includes(record.status) && (
            <Button size="small" icon={<ReloadOutlined />} onClick={() => handleRetry(record.id)}>
              重试
            </Button>
          )}
          {isTaskDeletable(record.status) && (
            <Popconfirm
              title="确认删除该任务？"
              description="删除后不可恢复，关联扫描/草稿等中间数据将一并清除。"
              okText="删除"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              onConfirm={() => handleDelete(record.id)}
            >
              <Button size="small" danger icon={<DeleteOutlined />}>
                删除
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div className="ci-page ci-tasklist-tab">
      {/* 队列状态提示 */}
      {queueCount > 0 && (
        <Card className="ci-filter-card" style={{ marginBottom: 8, background: '#fffbe6' }}>
          <Space style={{ justifyContent: 'space-between', width: '100%' }}>
            <span>
              队列中 <strong>{queueCount}</strong> 个任务待处理
            </span>
            <Button type="link" onClick={() => navigate('/tasks/queue')}>
              查看队列 →
            </Button>
          </Space>
        </Card>
      )}

      {/* 状态分组 chips */}
      <Card className="ci-filter-card" style={{ marginBottom: 12 }}>
        <Space wrap size={8}>
          {([
            { key: 'ALL', icon: <UnorderedListOutlined /> },
            { key: 'RUNNING', icon: <LoadingOutlined /> },
            { key: 'PENDING_REVIEW', icon: <EyeOutlined /> },
            { key: 'CONFIRMED', icon: <CheckCircleOutlined /> },
            { key: 'CLOSED', icon: <CloseCircleOutlined /> },
          ] as { key: GroupKey; icon: React.ReactNode }[]).map((chip) => {
            const active = activeGroup === chip.key;
            const count = taskSummary[chip.key] ?? 0;
            const meta = GROUP_LABELS[chip.key];
            return (
              <Tooltip key={chip.key} title={meta.hint}>
                <Button
                  shape="round"
                  type={active ? 'primary' : 'default'}
                  onClick={() => handleGroupChange(chip.key)}
                  className={`ci-status-chip ${active ? 'is-active' : ''}`}
                >
                  <span className="ci-status-chip-icon">{chip.icon}</span>
                  <span>{meta.label}</span>
                  <span className={`ci-status-chip-count ${active ? 'is-active' : 'is-zero'}`}>{count}</span>
                </Button>
              </Tooltip>
            );
          })}
        </Space>
      </Card>

      {/* 简单搜索 + 操作栏 */}
      <Card className="ci-filter-card">
        <Row gutter={[12, 12]} align="middle">
          <Col xs={24} md={10}>
            <Select
              allowClear
              showSearch
              placeholder="简单搜索：选择系统"
              style={{ width: '100%' }}
              value={filterSystemId}
              optionFilterProp="label"
              onChange={(v) => {
                setFilterSystemId(v);
                setCurrent(1);
              }}
              options={systems.map((s) => ({
                value: s.id,
                label: s.nameCn ? `${s.name}（${s.nameCn}）` : s.name,
              }))}
            />
          </Col>
          <Col xs={24} md={14}>
            <Space className="ci-toolbar-actions" wrap style={{ width: '100%', justifyContent: 'flex-end' }}>
              <Button
                onClick={() => setAdvancedOpen((v) => !v)}
                type={advancedOpen ? 'primary' : 'default'}
              >
                {advancedOpen ? '收起高级搜索' : '高级搜索'}
              </Button>
              <Button
                icon={<ReloadOutlined />}
                onClick={() => {
                  setFilterSystemId(undefined);
                  handleResetAdvanced();
                }}
              >
                重置
              </Button>
              <Button
                type="primary"
                icon={<PlusOutlined />}
                onClick={() => navigate('/tasks/dispatch')}
              >
                新建任务
              </Button>
            </Space>
          </Col>
        </Row>

        {/* 精准搜索：高级过滤面板 */}
        {advancedOpen && (
          <div className="ci-advanced-filter-panel">
            <Form layout="vertical" requiredMark={false} className="ci-advanced-filter-form">
              <Row gutter={[12, 12]}>
                <Col xs={24} sm={12} md={6}>
                  <Form.Item label="状态">
                    <Select
                      placeholder="全部状态"
                      allowClear
                      style={{ width: '100%' }}
                      value={filterStatus}
                      onChange={(v) => {
                        setFilterStatus(v);
                        setCurrent(1);
                      }}
                      options={Object.entries(statusMeta).map(([value, meta]) => ({ value, label: meta.label }))}
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} sm={12} md={6}>
                  <Form.Item label="任务类型">
                    <Select
                      placeholder="全部类型"
                      allowClear
                      style={{ width: '100%' }}
                      value={filterType}
                      onChange={(v) => {
                        setFilterType(v);
                        setCurrent(1);
                      }}
                      options={[
                        { value: 'INITIAL', label: '全量扫描' },
                        { value: 'INCREMENTAL', label: '增量扫描' },
                      ]}
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} sm={12} md={6}>
                  <Form.Item label="触发来源">
                    <Select
                      placeholder="全部来源"
                      allowClear
                      style={{ width: '100%' }}
                      value={filterTriggerSource}
                      onChange={(v) => {
                        setFilterTriggerSource(v);
                        setCurrent(1);
                      }}
                      options={[
                        { value: 'MANUAL', label: '手动触发' },
                        { value: 'SCHEDULED', label: '定时触发' },
                      ]}
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} sm={12} md={6}>
                  <Form.Item label="AI 模型">
                    <Input
                      allowClear
                      placeholder="模型名（精确匹配）"
                      style={{ width: '100%' }}
                      value={filterModelName ?? ''}
                      onChange={(e) => {
                        setFilterModelName(e.target.value || undefined);
                        setCurrent(1);
                      }}
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} sm={12} md={12} lg={8}>
                  <Form.Item label="创建时间">
                    <RangePicker
                      style={{ width: '100%' }}
                      placeholder={['开始日期', '结束日期']}
                      value={filterCreatedRange as never}
                      onChange={(v) => {
                        setFilterCreatedRange(v as [Dayjs | null, Dayjs | null] | null);
                        setCurrent(1);
                      }}
                      allowClear
                    />
                  </Form.Item>
                </Col>
              </Row>
            </Form>
          </div>
        )}
      </Card>

      {/* 任务表 */}
      <Card title="任务列表" style={{ marginTop: 12 }}>
        <Table
          dataSource={tasks}
          columns={columns}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1300 }}
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
    </div>
  );
};

export default TaskListTab;
