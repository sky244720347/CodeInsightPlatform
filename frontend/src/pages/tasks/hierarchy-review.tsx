import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
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
import { useNavigate } from 'react-router-dom';
import { listTasks } from '../../api/task';
import { listSystems } from '../../api/system';
import type { System, Task } from '../../types';
import ModuleHierarchyEditorDrawer from '../../components/ModuleHierarchyEditorDrawer';

const { Text } = Typography;

const statusMeta: Record<string, { color: string; label: string }> = {
  MODULE_HIERARCHY_REVIEW: { color: 'geekblue', label: '模块层级调试' },
  MODULE_HIERARCHY: { color: 'gold', label: '模块层级提炼' },
  AI_ANALYZING: { color: 'orange', label: 'AI 分析中' },
  GENERATING_DOC: { color: 'gold', label: '生成文档' },
  PENDING_REVIEW: { color: 'magenta', label: '已生成文档' },
  FAILED: { color: 'red', label: '调试中失败' },
  CANCELLED: { color: 'default', label: '已取消' },
};

/**
 * 模块层级调试专用页：列出所有处于或曾经处于 MODULE_HIERARCHY_REVIEW 的任务，
 * 用户可点击行进入 ModuleHierarchyEditorDrawer 编辑模块层级。
 *
 * 主要场景：跨多个任务的统一调试入口；列表默认仅展示「需要调试」的任务（INCLUDE_HISTORY=false 时仅展示 MODULE_HIERARCHY_REVIEW）。
 */
const HierarchyReview: React.FC = () => {
  const navigate = useNavigate();
  const [tasks, setTasks] = useState<Task[]>([]);
  const [systems, setSystems] = useState<System[]>([]);
  const [loading, setLoading] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [systemId, setSystemId] = useState<number | undefined>(undefined);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [currentTaskId, setCurrentTaskId] = useState<number | null>(null);

  const fetchDebugTasks = useCallback(async () => {
    setLoading(true);
    try {
      const inProgress = await listTasks({ current: 1, size: 200, status: 'MODULE_HIERARCHY_REVIEW', systemId });
      let records: Task[] = inProgress.records;
      if (showHistory) {
        const [generating, finished] = await Promise.all([
          listTasks({ current: 1, size: 50, status: 'MODULE_HIERARCHY', systemId }),
          listTasks({ current: 1, size: 50, status: 'PENDING_REVIEW', systemId }),
        ]);
        const map = new Map<number, Task>();
        [...records, ...generating.records, ...finished.records].forEach((t) => map.set(t.id, t));
        records = Array.from(map.values()).sort((a, b) => b.id - a.id);
      }
      setTasks(records);
    } finally {
      setLoading(false);
    }
  }, [showHistory, systemId]);

  useEffect(() => {
    fetchDebugTasks();
  }, [fetchDebugTasks]);

  useEffect(() => {
    listSystems({ current: 1, size: 200 }).then((data) => setSystems(data.records));
  }, []);

  const openDrawer = (taskId: number) => {
    setCurrentTaskId(taskId);
    setDrawerOpen(true);
  };
  const closeDrawer = () => {
    setDrawerOpen(false);
    setCurrentTaskId(null);
  };

  const debugCount = useMemo(
    () => tasks.filter((t) => t.status === 'MODULE_HIERARCHY_REVIEW').length,
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
      render: (type: Task['type']) => <Tag color={type === 'INITIAL' ? 'geekblue' : 'green'}>{type === 'INITIAL' ? '全量' : '增量'}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 160,
      render: (status: string) => {
        const meta = statusMeta[status] ?? { color: 'default', label: status };
        return (
          <Tag color={meta.color} icon={status === 'MODULE_HIERARCHY_REVIEW' ? <LoadingOutlined /> : undefined}>
            {meta.label}
          </Tag>
        );
      },
    },
    {
      title: '模块层级调试',
      dataIndex: 'requireHierarchyReview',
      key: 'requireHierarchyReview',
      width: 140,
      render: (v: boolean | undefined) => (v === false ? <Tag>跳过</Tag> : <Tag color="geekblue">启用</Tag>),
    },
    {
      title: '进度',
      dataIndex: 'progress',
      key: 'progress',
      width: 100,
      render: (p: number) => `${p}%`,
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
          {record.status === 'MODULE_HIERARCHY_REVIEW' && (
            <Tooltip title="进入模块层级调试抽屉">
              <Button size="small" type="primary" icon={<EditOutlined />} onClick={() => openDrawer(record.id)}>
                开始调试
              </Button>
            </Tooltip>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div className="ci-page ci-hierarchy-review-page">
      <Card
        title={
          <Space>
            <span>待调试任务</span>
            <Tag color="geekblue">等待调试 {debugCount}</Tag>
          </Space>
        }
        extra={
          <Space>
            <Select
              allowClear
              placeholder="按系统筛选"
              style={{ width: 180 }}
              value={systemId}
              onChange={(v) => setSystemId(v)}
              options={systems.map((s) => ({ value: s.id, label: s.name }))}
            />
            <Button
              icon={<ReloadOutlined />}
              loading={loading}
              onClick={fetchDebugTasks}
            >
              刷新
            </Button>
            <Button
              type={showHistory ? 'primary' : 'default'}
              onClick={() => setShowHistory((v) => !v)}
            >
              {showHistory ? '仅看待调试' : '查看近期历史'}
            </Button>
          </Space>
        }
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="模块层级调试说明"
          description={
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              <li>仅启用「模块层级调试」的任务会停在此状态等待人工确认。</li>
              <li>点击「开始调试」进入抽屉，可对模块/子模块/功能树进行增删改；功能节点的类路径随其它字段一起落表 ci_module_hierarchy，重启不丢失。</li>
              <li>「类路径」仅在调用 AI 时被剥离，不会出现在 analyze / module_doc 提示词中。</li>
              <li>提交后将进入 GENERATING_DOC → PENDING_REVIEW，无法再返回调试状态。</li>
            </ul>
          }
        />

        {tasks.length === 0 && !loading ? (
          <Empty description="暂无需要调试的任务" />
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

      <ModuleHierarchyEditorDrawer
        open={drawerOpen}
        taskId={currentTaskId}
        onClose={closeDrawer}
        onSubmitted={() => {
          message.success('已提交，任务继续生成文档');
          fetchDebugTasks();
        }}
      />
    </div>
  );
};

export default HierarchyReview;