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
import PageHelpHint from '../../components/PageHelpHint';
import { entrypointReviewHelp } from '../../constants/reviewPageHelp';
import {
  filterSystemSelectOption,
  renderComponentCell,
  renderSystemSelectLabel,
  renderSystemSelectOption,
  toSystemSelectOptions,
} from '../../utils/systemSelect';

const { Text } = Typography;

const statusMeta: Record<string, { color: string; label: string }> = {
  ENTRYPOINT_REVIEW: { color: 'cyan', label: '知识入口复核' },
  AI_ANALYZING:      { color: 'orange', label: 'AI 分析中' },
  MODULE_HIERARCHY:  { color: 'gold', label: '模块层级提炼' },
  MODULE_HIERARCHY_REVIEW: { color: 'geekblue', label: '模块层级复核' },
  BASELINE_DOC_INHERIT: { color: 'cyan', label: '基线文档继承' },
  GENERATING_DOC:    { color: 'gold', label: '生成文档' },
  PENDING_REVIEW:    { color: 'magenta', label: '已生成文档' },
  FAILED:            { color: 'red', label: '调试中失败' },
  CANCELLED:         { color: 'default', label: '已驳回' },
};

/**
 * 入口复核任务列表：展示处于 ENTRYPOINT_REVIEW 的任务，点击进入复核详情页。
 */
const EntrypointReview: React.FC = () => {
  const navigate = useNavigate();
  const [tasks, setTasks] = useState<Task[]>([]);
  const [systems, setSystems] = useState<System[]>([]);
  const [loading, setLoading] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [systemId, setSystemId] = useState<number | undefined>(undefined);

  const fetchReviewTasks = useCallback(async () => {
    setLoading(true);
    try {
      const inProgress = await listTasks({ current: 1, size: 200, status: 'ENTRYPOINT_REVIEW', systemId });
      let records: Task[] = inProgress.records;
      if (showHistory) {
        const extraStatuses: string[] = systemId
          ? [] // 按系统筛选时不拉历史(避免跨系统干扰)
          : ['AI_ANALYZING', 'PENDING_REVIEW', 'CANCELLED'];
        const fetches = extraStatuses.map((s) => listTasks({ current: 1, size: 50, status: s, systemId }));
        const results = await Promise.all(fetches);
        results.forEach((r) => records.push(...r.records));
        // 去重 + 按 id 降序
        const map = new Map<number, Task>();
        records.forEach((t) => map.set(t.id, t));
        records = Array.from(map.values()).sort((a, b) => b.id - a.id);
      }
      setTasks(records);
    } finally {
      setLoading(false);
    }
  }, [showHistory, systemId]);

  useEffect(() => {
    fetchReviewTasks();
  }, [fetchReviewTasks]);

  useEffect(() => {
    listSystems({ current: 1, size: 200 }).then((data) => setSystems(data.records));
  }, []);

  const reviewCount = useMemo(
    () => tasks.filter((t) => t.status === 'ENTRYPOINT_REVIEW').length,
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
      width: 160,
      render: (sysId: number) => systems.find((s) => s.id === sysId)?.name ?? `系统 #${sysId}`,
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
      width: 160,
      render: (status: string) => {
        const meta = statusMeta[status] ?? { color: 'default', label: status };
        return (
          <Tag
            color={meta.color}
            icon={status === 'ENTRYPOINT_REVIEW' ? <LoadingOutlined /> : undefined}
          >
            {meta.label}
          </Tag>
        );
      },
    },
    {
      title: '知识入口复核',
      dataIndex: 'requireEntrypointReview',
      key: 'requireEntrypointReview',
      width: 140,
      render: (v: boolean | undefined) =>
        v === false ? <Tag>跳过</Tag> : <Tag color="cyan">启用</Tag>,
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
      dataIndex: 'createdDate',
      key: 'createdDate',
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
          {record.status === 'ENTRYPOINT_REVIEW' && (
            <Tooltip title="进入入口复核详情">
              <Button
                size="small"
                type="primary"
                icon={<EditOutlined />}
                onClick={() => navigate(`/tasks/entrypoint-review/${record.id}`)}
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
    <div className="ci-page ci-entrypoint-review-page">
      <Card
        title={
          <Space size={8} align="center">
            <span>待复核任务</span>
            <PageHelpHint title={entrypointReviewHelp.title} content={entrypointReviewHelp.content} />
            <Tag color="cyan">等待复核 {reviewCount}</Tag>
          </Space>
        }
        extra={
          <Space>
            <Select
              allowClear
              showSearch
              placeholder="按系统筛选"
              style={{ width: 240 }}
              value={systemId}
              onChange={(v) => setSystemId(v)}
              filterOption={filterSystemSelectOption}
              optionRender={renderSystemSelectOption}
              labelRender={(props) => renderSystemSelectLabel(props, systems)}
              options={toSystemSelectOptions(systems)}
            />
            <Button icon={<ReloadOutlined />} loading={loading} onClick={fetchReviewTasks}>
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

export default EntrypointReview;