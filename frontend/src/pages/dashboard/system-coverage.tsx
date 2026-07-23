import React, { useEffect, useState } from 'react';
import { Card, Skeleton, Table, Tag, Typography } from 'antd';
import {
  CheckCircleOutlined,
  MinusCircleOutlined,
} from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { getSystemCoverage, type SystemCoverageItem } from '../../api/dashboard';
import { renderComponentCell } from '../../utils/systemSelect';

const { Text, Title } = Typography;

const SystemCoverage: React.FC = () => {
  const [data, setData] = useState<SystemCoverageItem[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    getSystemCoverage().then(setData).finally(() => setLoading(false));
  }, []);

  if (loading) return <Skeleton active paragraph={{ rows: 12 }} />;

  // 各系统任务数柱状图
  const taskBarOption = {
    tooltip: { trigger: 'axis' },
    grid: { top: 28, right: 22, bottom: 60, left: 60 },
    xAxis: {
      type: 'category',
      data: data.map((d) => d.systemName),
      axisLabel: { color: '#667085', rotate: 30, fontSize: 10 },
    },
    yAxis: { type: 'value', axisLabel: { color: '#667085' } },
    series: [
      { name: '任务数', type: 'bar', data: data.map((d) => d.taskCount), color: '#2563eb', barMaxWidth: 40 },
      { name: '草稿数', type: 'bar', data: data.map((d) => d.draftCount), color: '#16a34a', barMaxWidth: 40 },
      { name: '版本数', type: 'bar', data: data.map((d) => d.versionCount), color: '#f59e0b', barMaxWidth: 40 },
    ],
  };

  const columns = [
    { title: '系统', dataIndex: 'systemName', key: 'systemName', render: (v: string) => <Text strong>{v}</Text> },
    {
      title: '组件',
      dataIndex: 'component',
      key: 'component',
      width: 140,
      render: (v: string | undefined) => renderComponentCell(v),
    },
    { title: '任务数', dataIndex: 'taskCount', key: 'taskCount', sorter: (a: SystemCoverageItem, b: SystemCoverageItem) => a.taskCount - b.taskCount },
    { title: '草稿数', dataIndex: 'draftCount', key: 'draftCount', sorter: (a: SystemCoverageItem, b: SystemCoverageItem) => a.draftCount - b.draftCount },
    { title: '推送版本数', dataIndex: 'versionCount', key: 'versionCount', sorter: (a: SystemCoverageItem, b: SystemCoverageItem) => a.versionCount - b.versionCount },
    {
      title: '最近反编译',
      dataIndex: 'lastDecompileAt',
      key: 'lastDecompileAt',
      render: (v: string | null) => {
        if (!v) return <Tag icon={<MinusCircleOutlined />} color="default">未执行</Tag>;
        return <Tag icon={<CheckCircleOutlined />} color="green">{v}</Tag>;
      },
    },
  ];

  return (
    <div>
      <Title level={4} style={{ marginBottom: 16 }}>系统覆盖报表</Title>

      <Card title="各系统任务 / 草稿 / 版本数" size="small" style={{ marginBottom: 16 }}>
        <ReactECharts option={taskBarOption} style={{ height: 300 }} />
      </Card>

      <Card title="系统明细" size="small">
        <Table
          dataSource={data}
          columns={columns}
          rowKey="systemId"
          pagination={false}
          size="small"
        />
      </Card>
    </div>
  );
};

export default SystemCoverage;
