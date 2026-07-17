import React, { useEffect, useState } from 'react';
import { Button, Card, Col, Row, Select, Space, Statistic, Table, Tag, Typography } from 'antd';
import { BarChartOutlined, DollarCircleOutlined, LineChartOutlined, PieChartOutlined, ReloadOutlined } from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { getTokenPage, getTokenStats, type TokenStats, type TokenUsageAudit } from '../../api/token';
import { listSystems } from '../../api/system';
import type { System } from '../../types';

const { Text } = Typography;

const chartColors = {
  primary: '#2F5FAD',
  info: '#0C7584',
  green: '#16835F',
  gold: '#B7791F',
  red: '#B4233B',
  axis: '#6B7687',
  line: '#D8E2EE',
};

const chartTooltip = {
  backgroundColor: 'rgba(255, 255, 255, 0.96)',
  borderColor: chartColors.line,
  textStyle: { color: '#172033' },
  extraCssText: 'box-shadow: 0 10px 28px rgba(18, 34, 58, 0.10); border-radius: 8px;',
};

const TokenAudit: React.FC = () => {
  const [stats, setStats] = useState<TokenStats | null>(null);
  const [audits, setAudits] = useState<TokenUsageAudit[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [current, setCurrent] = useState(1);
  const [size, setSize] = useState(10);
  const [refreshKey, setRefreshKey] = useState(0);

  const [systems, setSystems] = useState<System[]>([]);
  const [selectedSystemId, setSelectedSystemId] = useState<number | undefined>();
  const [selectedModel, setSelectedModel] = useState<string | undefined>();
  const [selectedType, setSelectedType] = useState<string | undefined>();

  useEffect(() => {
    listSystems({ current: 1, size: 100 }).then((data) => setSystems(data.records));
  }, []);

  useEffect(() => {
    getTokenStats(selectedSystemId).then(setStats);
  }, [refreshKey, selectedSystemId]);

  useEffect(() => {
    setLoading(true);
    getTokenPage({
      current,
      size,
      systemId: selectedSystemId,
      modelName: selectedModel,
      type: selectedType,
    })
      .then((data) => {
        setAudits(data.records);
        setTotal(data.total);
      })
      .finally(() => setLoading(false));
  }, [current, refreshKey, selectedModel, selectedSystemId, selectedType, size]);

  const lineOption = {
    color: [chartColors.primary],
    tooltip: { trigger: 'axis', ...chartTooltip },
    axisPointer: {
      lineStyle: { color: chartColors.info, width: 1, type: 'dashed' },
    },
    grid: { top: 28, right: 22, bottom: 30, left: 52 },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: stats?.dailyTrends?.map((item) => item.date) ?? [],
      axisLine: { lineStyle: { color: chartColors.line } },
      axisTick: { lineStyle: { color: chartColors.line } },
      axisLabel: { color: chartColors.axis },
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: chartColors.axis },
      splitLine: { lineStyle: { color: '#ECF2F7' } },
    },
    series: [
      {
        name: 'Token',
        type: 'line',
        data: stats?.dailyTrends?.map((item) => item.tokens) ?? [],
        smooth: true,
        symbolSize: 6,
        lineStyle: { width: 2.5 },
        areaStyle: { color: 'rgba(47, 95, 173, 0.08)' },
      },
    ],
  };

  const ranking = [...(stats?.systemRanking ?? [])].reverse();
  const barOption = {
    color: [chartColors.info],
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, ...chartTooltip },
    grid: { top: 22, right: 22, bottom: 30, left: 82 },
    xAxis: {
      type: 'value',
      axisLine: { lineStyle: { color: chartColors.line } },
      splitLine: { lineStyle: { color: '#ECF2F7' } },
      axisLabel: { color: chartColors.axis },
    },
    yAxis: {
      type: 'category',
      data: ranking.map((item) => item.name),
      axisLine: { lineStyle: { color: chartColors.line } },
      axisTick: { show: false },
      axisLabel: { color: chartColors.axis },
    },
    series: [
      {
        name: 'Token',
        type: 'bar',
        data: ranking.map((item) => item.tokens),
        barWidth: 14,
        itemStyle: { borderRadius: [0, 4, 4, 0] },
      },
    ],
  };

  const donutOption = {
    color: [chartColors.primary, chartColors.info, chartColors.green, chartColors.gold, chartColors.red],
    title: {
      text: '模型',
      subtext: 'Token 占比',
      left: 'center',
      top: '36%',
      textStyle: { color: chartColors.axis, fontSize: 12, fontWeight: 700 },
      subtextStyle: { color: '#9AA7B8', fontSize: 10, fontWeight: 700 },
      itemGap: 2,
    },
    tooltip: { trigger: 'item', ...chartTooltip },
    legend: { bottom: 0, icon: 'circle', textStyle: { color: chartColors.axis } },
    series: [
      {
        name: '模型占比',
        type: 'pie',
        radius: ['56%', '72%'],
        center: ['50%', '43%'],
        label: { show: false },
        itemStyle: { borderColor: '#fff', borderWidth: 4 },
        data: stats?.modelRatio ?? [],
      },
    ],
  };

  const columns = [
    { title: '任务', dataIndex: 'taskId', key: 'taskId', render: (id: number) => <Text code>#{id}</Text> },
    { title: '模型', dataIndex: 'modelName', key: 'modelName' },
    {
      title: '类型',
      dataIndex: 'type',
      key: 'type',
      render: (type: string) => <Tag color={type === 'INITIAL' ? 'blue' : 'green'}>{type === 'INITIAL' ? '全量' : '增量'}</Tag>,
    },
    { title: '输入', dataIndex: 'inputTokens', key: 'inputTokens' },
    { title: '输出', dataIndex: 'outputTokens', key: 'outputTokens' },
    { title: '总量', dataIndex: 'totalTokens', key: 'totalTokens' },
    {
      title: '成本',
      dataIndex: 'cost',
      key: 'cost',
      render: (cost: number) => <Text strong>${Number(cost).toFixed(5)}</Text>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (status: number) => <Tag color={status === 1 ? 'success' : 'error'}>{status === 1 ? '成功' : '失败'}</Tag>,
    },
    {
      title: '创建时间',
      dataIndex: 'createdDate',
      key: 'createdDate',
      render: (date: string) => new Date(date).toLocaleString(),
    },
  ];

  const resetFilters = () => {
    setSelectedSystemId(undefined);
    setSelectedModel(undefined);
    setSelectedType(undefined);
    setCurrent(1);
  };

  return (
    <div className="ci-page ci-token-page">
      <div className="ci-kpi-grid">
        <Card className="ci-stat-card">
          <Statistic title="Token 总量" value={stats?.totalTokens || 0} prefix={<LineChartOutlined />} />
        </Card>
        <Card className="ci-stat-card">
          <Statistic title="输入 Token" value={stats?.totalInputTokens || 0} prefix={<PieChartOutlined />} />
        </Card>
        <Card className="ci-stat-card">
          <Statistic title="输出 Token" value={stats?.totalOutputTokens || 0} prefix={<BarChartOutlined />} />
        </Card>
        <Card className="ci-stat-card">
          <Statistic title="预估成本" value={stats?.totalCost || 0} precision={4} prefix={<DollarCircleOutlined />} />
        </Card>
      </div>

      <Row gutter={[16, 16]} className="ci-chart-grid">
        <Col xs={24} xl={10}>
          <Card title="Token 趋势">
            <ReactECharts option={lineOption} style={{ height: 300 }} />
          </Card>
        </Col>
        <Col xs={24} xl={8}>
          <Card title="系统消耗排行">
            <ReactECharts option={barOption} style={{ height: 300 }} />
          </Card>
        </Col>
        <Col xs={24} xl={6}>
          <Card title="模型占比">
            <ReactECharts option={donutOption} style={{ height: 300 }} />
          </Card>
        </Col>
      </Row>

      <Card
        className="ci-workspace-card ci-token-console"
        title="Token 审计记录"
        extra={
          <Space wrap>
            <Select
              style={{ width: 180 }}
              placeholder="系统"
              value={selectedSystemId}
              onChange={setSelectedSystemId}
              allowClear
              options={systems.map((system) => ({ value: system.id, label: system.name }))}
            />
            <Select
              style={{ width: 150 }}
              placeholder="模型"
              value={selectedModel}
              onChange={setSelectedModel}
              allowClear
              options={[
                { value: 'MiniMax-M3', label: 'MiniMax-M3' },
                { value: 'MiniMax-M2.5', label: 'MiniMax-M2.5' },
              ]}
            />
            <Select
              style={{ width: 150 }}
              placeholder="类型"
              value={selectedType}
              onChange={setSelectedType}
              allowClear
              options={[
                { value: 'INITIAL', label: '全量' },
                { value: 'INCREMENTAL', label: '增量' },
              ]}
            />
            <Button onClick={resetFilters}>重置</Button>
            <Button type="primary" icon={<ReloadOutlined />} onClick={() => setRefreshKey((key) => key + 1)}>
              刷新
            </Button>
          </Space>
        }
      >
        <Table
          dataSource={audits}
          columns={columns}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1000 }}
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

export default TokenAudit;
