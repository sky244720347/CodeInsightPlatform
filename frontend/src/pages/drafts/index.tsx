import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Button,
  Card,
  Empty,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  EditOutlined,
  EyeOutlined,
  LoadingOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { listReviewableTasks } from '../../api/draft';
import { listSystems } from '../../api/system';
import type { System, Task } from '../../types';
import PageHelpHint from '../../components/PageHelpHint';
import { draftReviewHelp } from '../../constants/reviewPageHelp';

const { Text } = Typography;

const TASK_STATUS_META: Record<string, { color: string; label: string }> = {
  PENDING_REVIEW: { color: 'magenta', label: '待复核' },
  REVIEWING: { color: 'processing', label: '复核中' },
  CONFIRMED: { color: 'green', label: '已确认' },
  PUSHING: { color: 'gold', label: '推送中' },
  PUSHED: { color: 'green', label: '已推送' },
};

/**
 * 知识复核任务列表：展示待复核 / 复核中 / 已确认任务，点击进入复核详情页。
 */
const DraftReviewListPage: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [tasks, setTasks] = useState<Task[]>([]);
  const [systems, setSystems] = useState<System[]>([]);
  const [loading, setLoading] = useState(false);
  const [systemFilter, setSystemFilter] = useState<number | undefined>();
  const [showHistory, setShowHistory] = useState(false);

  // 兼容旧链接 /drafts?taskId=123
  useEffect(() => {
    const taskId = Number(searchParams.get('taskId'));
    if (Number.isFinite(taskId) && taskId > 0) {
      navigate(`/drafts/${taskId}`, { replace: true });
    }
  }, [navigate, searchParams]);

  const fetchTasks = useCallback(async () => {
    setLoading(true);
    try {
      const status = showHistory
        ? 'PENDING_REVIEW,REVIEWING,CONFIRMED,PUSHING,PUSHED'
        : 'PENDING_REVIEW,REVIEWING';
      const list = await listReviewableTasks({
        systemId: systemFilter,
        status,
      });
      setTasks(list.sort((a, b) => b.id - a.id));
    } catch {
      message.error('加载复核任务失败');
    } finally {
      setLoading(false);
    }
  }, [showHistory, systemFilter]);

  useEffect(() => {
    fetchTasks();
  }, [fetchTasks]);

  useEffect(() => {
    listSystems({ current: 1, size: 200 }).then((data) => setSystems(data.records));
  }, []);

  const pendingCount = useMemo(
    () => tasks.filter((t) => t.status === 'PENDING_REVIEW' || t.status === 'REVIEWING').length,
    [tasks],
  );

  const columns = [
    {
      title: '任务',
      dataIndex: 'id',
      key: 'id',
      width: 90,
      render: (id: number) => <Text code>#{id}</Text>,
    },
    {
      title: '系统',
      dataIndex: 'systemId',
      key: 'systemName',
      width: 180,
      render: (sysId: number) => systems.find((s) => s.id === sysId)?.name ?? `系统 #${sysId}`,
    },
    {
      title: '类型',
      dataIndex: 'type',
      key: 'type',
      width: 110,
      render: (type: Task['type']) => (
        <Tag color={type === 'INITIAL' ? 'geekblue' : 'green'}>
          {type === 'INITIAL' ? '全量' : '增量'}
        </Tag>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 140,
      render: (status: string) => {
        const meta = TASK_STATUS_META[status] ?? { color: 'default', label: status };
        const reviewing = status === 'PENDING_REVIEW' || status === 'REVIEWING';
        return (
          <Tag color={meta.color} icon={reviewing ? <LoadingOutlined /> : undefined}>
            {meta.label}
          </Tag>
        );
      },
    },
    {
      title: '进度',
      dataIndex: 'progress',
      key: 'progress',
      width: 100,
      render: (p: number) => `${p ?? 0}%`,
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 170,
      render: (t: string) => (t ? new Date(t).toLocaleString() : '-'),
    },
    {
      title: '操作',
      key: 'action',
      width: 220,
      render: (_: unknown, record: Task) => (
        <Space>
          <Button size="small" icon={<EyeOutlined />} onClick={() => navigate(`/tasks/${record.id}`)}>
            详情
          </Button>
          {(record.status === 'PENDING_REVIEW' || record.status === 'REVIEWING' || record.status === 'CONFIRMED') && (
            <Tooltip title="进入知识复核工作区">
              <Button
                size="small"
                type="primary"
                icon={<EditOutlined />}
                onClick={() => navigate(`/drafts/${record.id}`)}
              >
                开始复核
              </Button>
            </Tooltip>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div className="ci-page ci-draft-review-list-page">
      <Card
        title={
          <Space size={8} align="center">
            <span>待复核任务</span>
            <PageHelpHint title={draftReviewHelp.title} content={draftReviewHelp.content} />
            <Tag color="magenta">待处理 {pendingCount}</Tag>
          </Space>
        }
        extra={
          <Space wrap>
            <Select
              allowClear
              placeholder="按系统筛选"
              style={{ width: 200 }}
              value={systemFilter}
              onChange={(v) => setSystemFilter(v)}
              options={systems.map((s) => ({ value: s.id, label: s.name }))}
            />
            <Button icon={<ReloadOutlined />} loading={loading} onClick={fetchTasks}>
              刷新
            </Button>
            <Button type={showHistory ? 'primary' : 'default'} onClick={() => setShowHistory((v) => !v)}>
              {showHistory ? '仅看待复核' : '查看近期历史'}
            </Button>
          </Space>
        }
      >
        {tasks.length === 0 && !loading ? (
          <Empty description="暂无需要复核的任务" />
        ) : (
          <Table
            dataSource={tasks}
            columns={columns}
            rowKey="id"
            loading={loading}
            pagination={{ pageSize: 20, showSizeChanger: true }}
          />
        )}
      </Card>
    </div>
  );
};

export default DraftReviewListPage;
