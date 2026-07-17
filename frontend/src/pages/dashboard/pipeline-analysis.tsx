import React, { useEffect, useState } from 'react';
import { Card, Skeleton, Table, Tag, Typography } from 'antd';
import ReactECharts from 'echarts-for-react';
import { getPipelineStats, type PipelineStageStat } from '../../api/dashboard';

const { Text, Title } = Typography;

const STAGE_LABELS: Record<string, string> = {
  PULLING_CODE: '代码拉取',
  PARSING_CODE: '静态解析',
  ENTRYPOINT_DISCOVERY: '入口识别',
  ENTRYPOINT_REVIEW: '知识入口复核',
  AI_ANALYZING: 'AI 分析',
  MODULE_HIERARCHY: '模块层级提炼',
  MODULE_HIERARCHY_REVIEW: '模块层级复核',
  BASELINE_DOC_INHERIT: '基线文档继承',
  GENERATING_DOC: '文档生成',
  PENDING_REVIEW: '待复核',
  CONFIRMED: '已确认',
  PUSHED: '已推送',
  FAILED: '失败',
};

const PipelineAnalysis: React.FC = () => {
  const [stats, setStats] = useState<PipelineStageStat[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    getPipelineStats().then(setStats).finally(() => setLoading(false));
  }, []);

  if (loading) return <Skeleton active paragraph={{ rows: 12 }} />;

  // 耗时柱状图
  const durationBarOption = {
    tooltip: { trigger: 'axis', formatter: (params: any) => {
      const p = params[0];
      return `${p.name}<br/>平均耗时: ${p.value} ms`;
    }},
    grid: { top: 28, right: 22, bottom: 50, left: 70 },
    xAxis: {
      type: 'category',
      data: stats.map((s) => STAGE_LABELS[s.status] ?? s.status),
      axisLabel: { color: '#667085', fontSize: 10, rotate: 25 },
    },
    yAxis: { type: 'value', name: '毫秒 (ms)', axisLabel: { color: '#667085' } },
    series: [{
      type: 'bar',
      data: stats.map((s) => ({
        value: s.avgDurationMs,
        itemStyle: s.status === 'FAILED' ? { color: '#dc2626' } : undefined,
      })),
    }],
  };

  const columns = [
    {
      title: '阶段',
      dataIndex: 'status',
      key: 'status',
      render: (v: string) => {
        const label = STAGE_LABELS[v] ?? v;
        const colorMap: Record<string, string> = {
          PULLING_CODE: 'blue', PARSING_CODE: 'cyan', ENTRYPOINT_DISCOVERY: 'geekblue', ENTRYPOINT_REVIEW: 'gold',
          AI_ANALYZING: 'orange', MODULE_HIERARCHY: 'gold',
          MODULE_HIERARCHY_REVIEW: 'geekblue', BASELINE_DOC_INHERIT: 'cyan', GENERATING_DOC: 'lime',
          PENDING_REVIEW: 'magenta', CONFIRMED: 'green', PUSHED: 'green', FAILED: 'red',
        };
        return <Tag color={colorMap[v] ?? 'default'}>{label}</Tag>;
      },
    },
    { title: '任务数', dataIndex: 'count', key: 'count' },
    {
      title: '平均耗时 (ms)',
      dataIndex: 'avgDurationMs',
      key: 'avgDurationMs',
      render: (v: number) => v?.toLocaleString() ?? '-',
      sorter: (a: PipelineStageStat, b: PipelineStageStat) => a.avgDurationMs - b.avgDurationMs,
    },
    {
      title: '瓶颈等级',
      key: 'bottleneck',
      render: (_: unknown, r: PipelineStageStat) => {
        if (!r.avgDurationMs) return <Text type="secondary">-</Text>;
        const max = Math.max(...stats.map((s) => s.avgDurationMs));
        const ratio = max > 0 ? r.avgDurationMs / max : 0;
        if (ratio > 0.7) return <Tag color="red">高</Tag>;
        if (ratio > 0.4) return <Tag color="orange">中</Tag>;
        return <Tag color="green">低</Tag>;
      },
    },
  ];

  return (
    <div>
      <Title level={4} style={{ marginBottom: 16 }}>流水线分析</Title>

      <Card title="各阶段平均耗时" size="small" style={{ marginBottom: 16 }}>
        <ReactECharts option={durationBarOption} style={{ height: 300 }} />
      </Card>

      <Card title="阶段明细" size="small">
        <Table
          dataSource={stats}
          columns={columns}
          rowKey="status"
          pagination={false}
          size="small"
        />
      </Card>
    </div>
  );
};

export default PipelineAnalysis;
