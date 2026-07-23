import React, { useEffect, useState } from 'react';
import { Card, Col, Row, Skeleton, Select, Space, Statistic, Table, Typography } from 'antd';
import {
  ThunderboltOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  ClockCircleOutlined,
} from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { getAiUsageStats, type AiUsageStats } from '../../api/dashboard';
import { listSystems } from '../../api/system';
import type { System } from '../../types';
import {
  filterSystemSelectOption,
  renderSystemSelectLabel,
  renderSystemSelectOption,
  toSystemSelectOptions,
} from '../../utils/systemSelect';

const { Text, Title } = Typography;

const AiUsage: React.FC = () => {
  const [stats, setStats] = useState<AiUsageStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [systems, setSystems] = useState<System[]>([]);
  const [systemId, setSystemId] = useState<number | undefined>();

  useEffect(() => {
    listSystems({ current: 1, size: 200 }).then((data) => setSystems(data.records ?? []));
  }, []);

  useEffect(() => {
    setLoading(true);
    getAiUsageStats(systemId).then(setStats).finally(() => setLoading(false));
  }, [systemId]);

  if (loading || !stats) return <Skeleton active paragraph={{ rows: 12 }} />;

  // 模型调用次数饼图
  const modelPieOption = {
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0, textStyle: { fontSize: 11 } },
    series: [{
      type: 'pie',
      radius: ['40%', '65%'],
      label: { show: false },
      data: (stats.byModel ?? []).map((m) => ({ name: m.name, value: m.calls })),
    }],
  };

  // 阶段 Token 分布
  const stageBarOption = {
    tooltip: { trigger: 'axis' },
    grid: { top: 28, right: 22, bottom: 30, left: 60 },
    xAxis: { type: 'category', data: (stats.byStage ?? []).map((s) => s.stage), axisLabel: { color: '#667085', fontSize: 10 } },
    yAxis: { type: 'value', axisLabel: { color: '#667085' } },
    series: [
      { name: 'Input Tokens', type: 'bar', stack: 'total', data: (stats.byStage ?? []).map((s) => s.inputTokens), color: '#2563eb' },
      { name: 'Output Tokens', type: 'bar', stack: 'total', data: (stats.byStage ?? []).map((s) => s.outputTokens), color: '#16a34a' },
    ],
  };

  const modelColumns = [
    { title: '模型', dataIndex: 'name', key: 'name', render: (v: string) => <Text code>{v}</Text> },
    { title: '调用次数', dataIndex: 'calls', key: 'calls' },
    { title: 'Input Tokens', dataIndex: 'inputTokens', key: 'inputTokens', render: (v: number) => v.toLocaleString() },
    { title: 'Output Tokens', dataIndex: 'outputTokens', key: 'outputTokens', render: (v: number) => v.toLocaleString() },
    { title: '成功', dataIndex: 'success', key: 'success', render: (v: number) => <Text style={{ color: '#16a34a' }}>{v}</Text> },
    { title: '失败', dataIndex: 'failed', key: 'failed', render: (v: number) => <Text style={{ color: '#dc2626' }}>{v}</Text> },
  ];

  return (
    <div>
      <Title level={4} style={{ marginBottom: 16 }}>AI 模型用量</Title>

      <Space style={{ marginBottom: 16 }}>
        <Select
          placeholder="全部系统"
          value={systemId}
          onChange={(v) => setSystemId(v)}
          allowClear
          showSearch
          style={{ width: 260 }}
          filterOption={filterSystemSelectOption}
          optionRender={renderSystemSelectOption}
          labelRender={(props) => renderSystemSelectLabel(props, systems)}
          options={toSystemSelectOptions(systems)}
        />
      </Space>

      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={12} sm={6}>
          <Card><Statistic title="总调用次数" value={stats.totalCalls} prefix={<ThunderboltOutlined />} /></Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card><Statistic title="Input Tokens" value={stats.totalInputTokens} prefix={<ClockCircleOutlined />} /></Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card><Statistic title="成功率" value={stats.successRate} suffix="%" prefix={<CheckCircleOutlined />} valueStyle={{ color: '#16a34a' }} /></Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card><Statistic title="超时/失败" value={stats.totalCalls - stats.successCount} prefix={<CloseCircleOutlined />} valueStyle={{ color: '#dc2626' }} /></Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={12}>
          <Card title="模型调用占比" size="small">
            <ReactECharts option={modelPieOption} style={{ height: 280 }} />
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="各阶段 Token 分布" size="small">
            <ReactECharts option={stageBarOption} style={{ height: 280 }} />
          </Card>
        </Col>
      </Row>

      <Card title="模型明细" size="small" style={{ marginTop: 16 }}>
        <Table
          dataSource={stats.byModel ?? []}
          columns={modelColumns}
          rowKey="name"
          pagination={false}
          size="small"
        />
      </Card>
    </div>
  );
};

export default AiUsage;
