import React, { useCallback, useEffect, useState } from 'react';
import {
  Button,
  Card,
  DatePicker,
  Input,
  Modal,
  Progress,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import { EditOutlined, ReloadOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useNavigate } from 'react-router-dom';
import {
  getScanOrchestrationSummary,
  listScanProbeRecords,
  updateScanOrchestrationCron,
  updateScanOrchestrationEnabled,
  type ScanOrchestrationSummary,
  type ScanProbeRecord,
} from '../../api/scan-orchestration';

const { Text, Title } = Typography;

const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'SUCCESS', label: 'SUCCESS' },
  { value: 'FAILED', label: 'FAILED' },
  { value: 'INCONCLUSIVE', label: 'INCONCLUSIVE' },
  { value: 'DEFERRED_DISPATCH', label: 'DEFERRED_DISPATCH' },
  { value: 'DISPATCH_FAILED', label: 'DISPATCH_FAILED' },
  { value: 'SKIPPED_LOCAL', label: 'SKIPPED_LOCAL' },
];

function statusTag(status?: string) {
  switch (status) {
    case 'SUCCESS':
      return <Tag color="success">{status}</Tag>;
    case 'FAILED':
      return <Tag color="error">{status}</Tag>;
    case 'INCONCLUSIVE':
      return <Tag color="warning">{status}</Tag>;
    case 'DEFERRED_DISPATCH':
    case 'DISPATCH_FAILED':
      return <Tag color="orange">{status}</Tag>;
    case 'SKIPPED_LOCAL':
      return <Tag>{status}</Tag>;
    default:
      return <Tag>{status ?? '-'}</Tag>;
  }
}

const shortSha = (v?: string) => (v && v.length > 12 ? `${v.slice(0, 10)}…` : v || '-');

const TaskOrchestration: React.FC = () => {
  const navigate = useNavigate();
  const [date, setDate] = useState<Dayjs>(dayjs());
  const [summary, setSummary] = useState<ScanOrchestrationSummary | null>(null);
  const [records, setRecords] = useState<ScanProbeRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [current, setCurrent] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [status, setStatus] = useState<string>('');
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(false);
  const [cronEditOpen, setCronEditOpen] = useState(false);
  const [cronInput, setCronInput] = useState('');

  const dateStr = date.format('YYYY-MM-DD');

  const fetchAll = useCallback(async () => {
    setLoading(true);
    try {
      const [s, page] = await Promise.all([
        getScanOrchestrationSummary(dateStr),
        listScanProbeRecords({
          date: dateStr,
          status: status || undefined,
          keyword: keyword.trim() || undefined,
          current,
          size: pageSize,
        }),
      ]);
      setSummary(s);
      setRecords(page.records ?? []);
      setTotal(page.total ?? 0);
    } catch {
      message.error('加载探测数据失败');
    } finally {
      setLoading(false);
    }
  }, [dateStr, status, keyword, current, pageSize]);

  useEffect(() => {
    fetchAll();
  }, [fetchAll]);

  useEffect(() => {
    const t = window.setInterval(() => {
      getScanOrchestrationSummary(dateStr)
        .then(setSummary)
        .catch(() => undefined);
    }, 30000);
    return () => window.clearInterval(t);
  }, [dateStr]);

  const handleEnabled = async (v: boolean) => {
    try {
      await updateScanOrchestrationEnabled(v);
      setSummary((prev) => (prev ? { ...prev, schedulerEnabled: v } : prev));
      message.success(v ? '扫描调度已启用' : '扫描调度已暂停');
    } catch {
      message.error('更新调度开关失败');
    }
  };

  const handleCronSave = async () => {
    try {
      const res = await updateScanOrchestrationCron(cronInput);
      setSummary((prev) =>
        prev ? { ...prev, cron: res.cron, nextRuns: res.nextRuns ?? [] } : prev,
      );
      setCronEditOpen(false);
      message.success('cron 已更新');
    } catch {
      message.error('cron 格式错误或更新失败');
    }
  };

  const pct =
    summary && summary.probeTargetTotal > 0
      ? Math.min(100, Math.round((summary.probedCount / summary.probeTargetTotal) * 100))
      : 0;

  return (
    <div className="ci-page ci-orchestration-page">
      <Card
        title={<Title level={4} style={{ margin: 0 }}>任务编排</Title>}
        extra={
          <Button icon={<ReloadOutlined />} loading={loading} onClick={fetchAll}>
            刷新
          </Button>
        }
      >
        <Space size={16} wrap style={{ marginBottom: 16 }}>
          <Space size={4}>
            <Text type="secondary">扫描调度：</Text>
            <Switch
              checked={!!summary?.schedulerEnabled}
              onChange={handleEnabled}
              disabled={!summary}
            />
            <Tag color={summary?.schedulerEnabled ? 'green' : 'default'}>
              {summary?.schedulerEnabled ? '运行中' : '已暂停'}
            </Tag>
          </Space>
          {summary?.cron && (
            <Space size={4}>
              <Tag
                color="geekblue"
                style={{ cursor: 'pointer' }}
                onClick={() => {
                  setCronInput(summary.cron);
                  setCronEditOpen(true);
                }}
              >
                {summary.cron}
              </Tag>
              <Button
                size="small"
                icon={<EditOutlined />}
                onClick={() => {
                  setCronInput(summary.cron);
                  setCronEditOpen(true);
                }}
              />
              <Text type="secondary" style={{ fontSize: 11 }}>
                下 5 次：{(summary.nextRuns ?? []).slice(0, 3).join(' · ')}
              </Text>
            </Space>
          )}
          <Space size={4} wrap>
            <Text type="secondary" style={{ fontSize: 12 }}>
              只读配置：
            </Text>
            <Tag>全局轮询 {summary?.globalPollEnabled ? '开' : '关'}</Tag>
            <Tag>日覆盖 {summary?.dailyCoverageEnabled ? '开' : '关'}</Tag>
            <Tag>验证全量 {summary?.forceFullOnUnchanged ? '开' : '关'}</Tag>
          </Space>
        </Space>

        <Space size={16} wrap style={{ marginBottom: 12, width: '100%' }}>
          <Space size={4}>
            <Text type="secondary">日期：</Text>
            <DatePicker
              value={date}
              allowClear={false}
              onChange={(d) => {
                if (d) {
                  setDate(d);
                  setCurrent(1);
                }
              }}
            />
          </Space>
          <Text>
            探测总数 <Text strong>{summary?.probeTargetTotal ?? 0}</Text>
            {' · 已探测 '}
            <Text strong>{summary?.probedCount ?? 0}</Text>
            <Text type="secondary">
              （下发成功 {summary?.settledSuccessCount ?? 0} + 待重试 {summary?.retryPendingCount ?? 0}）
            </Text>
            {' · 未探测 '}
            <Text strong>{summary?.unprobedCount ?? 0}</Text>
          </Text>
          <Text type="secondary" style={{ fontSize: 12 }}>
            流水尝试 {summary?.attemptCount ?? 0} · 其中已建任务 {summary?.dispatchedCount ?? 0}
          </Text>
        </Space>
        <Progress
          percent={pct}
          status={pct >= 100 ? 'success' : 'active'}
          style={{ marginBottom: 16, maxWidth: 480 }}
        />

        <Space size={8} wrap style={{ marginBottom: 12 }}>
          <Select
            style={{ width: 180 }}
            options={STATUS_OPTIONS}
            value={status}
            onChange={(v) => {
              setStatus(v);
              setCurrent(1);
            }}
          />
          <Input.Search
            allowClear
            placeholder="仓库 URL / ID"
            style={{ width: 260 }}
            onSearch={(v) => {
              setKeyword(v);
              setCurrent(1);
            }}
          />
        </Space>

        <Table<ScanProbeRecord>
          rowKey="id"
          loading={loading}
          dataSource={records}
          size="small"
          pagination={{
            current,
            pageSize,
            total,
            showSizeChanger: true,
            onChange: (c, s) => {
              setCurrent(c);
              setPageSize(s);
            },
          }}
          columns={[
            { title: '时间', dataIndex: 'probedAt', width: 170 },
            {
              title: '仓库',
              dataIndex: 'gitUrl',
              ellipsis: true,
              render: (v: string, r) => v || `#${r.repositoryId}`,
            },
            { title: '尝试', dataIndex: 'attemptNo', width: 60 },
            {
              title: '状态',
              dataIndex: 'status',
              width: 140,
              render: (v: string) => statusTag(v),
            },
            {
              title: 'HEAD',
              dataIndex: 'remoteHead',
              width: 120,
              render: shortSha,
            },
            {
              title: '基线',
              dataIndex: 'baselineCommit',
              width: 120,
              render: shortSha,
            },
            {
              title: '下发',
              dataIndex: 'dispatchAction',
              width: 110,
              render: (v?: string) => v || '-',
            },
            {
              title: '任务',
              dataIndex: 'taskId',
              width: 90,
              render: (id?: number) =>
                id ? (
                  <Button type="link" size="small" onClick={() => navigate(`/tasks/${id}`)}>
                    #{id}
                  </Button>
                ) : (
                  '-'
                ),
            },
            { title: '说明', dataIndex: 'message', ellipsis: true },
          ]}
        />
      </Card>

      <Modal
        title="编辑扫描 cron 表达式"
        open={cronEditOpen}
        onCancel={() => setCronEditOpen(false)}
        onOk={handleCronSave}
        width={480}
      >
        <Input
          value={cronInput}
          onChange={(e) => setCronInput(e.target.value)}
          placeholder="0 */5 * * * *"
        />
        <Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
          6 段 cron（含秒）。全局轮询 / 日覆盖 / 批次等请改配置文件或阿波罗。
        </Text>
      </Modal>
    </div>
  );
};

export default TaskOrchestration;
