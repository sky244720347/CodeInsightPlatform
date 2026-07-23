import React, { useMemo, useState } from 'react';
import { Button, Drawer, Switch, Table, Typography, message } from 'antd';
import { EditOutlined, DeleteOutlined } from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { upsertScanWindow, deleteScanWindow } from '../../api/scan-window';
import type { ScanWindow } from '../../types';
import ScanWindowModal from '../systems/ScanWindowModal';
import { renderComponentCell } from '../../utils/systemSelect';

const WEEK_LABELS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];

interface Props {
  data: ScanWindow[];
  repoMap: Map<number, string>;
  /** repositoryId → 系统名 */
  sysMap: Map<number, string>;
  /** repositoryId → 组件 */
  componentMap: Map<number, string>;
  onRefresh: () => void;
}

const ScanWindowHeatmap: React.FC<Props> = ({ data, repoMap, sysMap, componentMap, onRefresh }) => {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [drawerSlot, setDrawerSlot] = useState<{ weekDay: number; hour: number; minute: number } | null>(null);
  const [editOpen, setEditOpen] = useState(false);
  const [editRepoId, setEditRepoId] = useState<number | null>(null);

  // 按 (weekDay, hour, minute) 分组
  const heatData = useMemo(() => {
    const timeSet = new Set<string>();
    const groups = new Map<string, { repos: ScanWindow[]; count: number }>();
    data.forEach((w) => {
      for (let i = 0; i < 7; i++) {
        if ((w.weekDays & (1 << i)) === 0) continue;
        // 把 hour 也 pad 成两位，与下方 heatmapData / drawerRepos 的 lookup 保持一致——
        // 否则单数 hour（"2:00"）在 lookup 时变 "02:00" → col=-1 → 整张热图空。
        const hh = String(w.hour).padStart(2, '0');
        const mm = String(w.minute).padStart(2, '0');
        const timeLabel = `${hh}:${mm}`;
        const key = `${i}|${timeLabel}`;
        timeSet.add(timeLabel);
        const g = groups.get(key) || { repos: [], count: 0 };
        g.repos.push(w);
        g.count++;
        groups.set(key, g);
      }
    });
    const timeSlots = Array.from(timeSet).sort((a, b) => {
      const [ah, am] = a.split(':').map(Number);
      const [bh, bm] = b.split(':').map(Number);
      return ah - bh || am - bm;
    });
    const maxCount = Math.max(1, ...Array.from(groups.values()).map((g) => g.count));
    return { timeSlots, groups, maxCount };
  }, [data]);

  const heatmapData = useMemo(() => {
    const pts: [number, number, number][] = [];
    heatData.groups.forEach((g, key) => {
      const [wd, time] = key.split('|');
      const [h, m] = time.split(':').map(Number);
      const col = heatData.timeSlots.indexOf(`${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`);
      if (col >= 0) pts.push([col, Number(wd), g.count]);
    });
    return pts;
  }, [heatData]);

  const drawerRepos = useMemo(() => {
    if (!drawerSlot) return [];
    const key = `${drawerSlot.weekDay}|${String(drawerSlot.hour).padStart(2, '0')}:${String(drawerSlot.minute).padStart(2, '0')}`;
    return heatData.groups.get(key)?.repos ?? [];
  }, [drawerSlot, heatData]);

  const option = useMemo(() => ({
    tooltip: {
      formatter: (p: any) => {
        const [col, row, count] = p.value;
        if (!count) return '';
        return `${heatData.timeSlots[col]} · ${WEEK_LABELS[row]}<br/><b>${count} 个仓库</b>`;
      },
    },
    grid: { top: 20, right: 40, bottom: 40, left: 60 },
    xAxis: { type: 'category', data: heatData.timeSlots, axisLabel: { fontSize: 11 }, name: '时间' },
    yAxis: { type: 'category', data: WEEK_LABELS, axisLabel: { fontSize: 11 } },
    visualMap: {
      min: 1, max: heatData.maxCount,
      inRange: { color: ['#e0f2fe', '#2563eb'] },
      show: false,
    },
    series: [{
      type: 'heatmap',
      data: heatmapData,
      label: { show: true, formatter: (p: any) => p.value[2] > 0 ? p.value[2] : '' },
      emphasis: { itemStyle: { shadowBlur: 10, shadowColor: 'rgba(0,0,0,0.3)' } },
    }],
  }), [heatData, heatmapData]);

  const onChartClick = (params: any) => {
    const [col, row] = params.value;
    if (params.value[2] === 0) return;
    const time = heatData.timeSlots[col].split(':').map(Number);
    setDrawerSlot({ weekDay: row, hour: time[0], minute: time[1] });
    setDrawerOpen(true);
  };

  return (
    <>
      <ReactECharts
        option={option}
        style={{ height: 380 }}
        onEvents={{ click: onChartClick }}
      />
      <Drawer
        title={
          drawerSlot
            ? `${WEEK_LABELS[drawerSlot.weekDay]} ${String(drawerSlot.hour).padStart(2, '0')}:${String(drawerSlot.minute).padStart(2, '0')} · ${drawerRepos.length} 个仓库`
            : ''
        }
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        width={580}
      >
        <Table
          dataSource={drawerRepos}
          rowKey="repositoryId"
          size="small"
          pagination={false}
          columns={[
            { title: '仓库', dataIndex: 'repositoryId', key: 'repo', ellipsis: true,
              render: (id: number) => <Typography.Text code style={{ fontSize: 12 }}>{repoMap.get(id) ?? `#${id}`}</Typography.Text> },
            { title: '系统', dataIndex: 'repositoryId', key: 'sys', width: 120,
              render: (id: number) => sysMap.get(id) ?? '-' },
            { title: '组件', dataIndex: 'repositoryId', key: 'component', width: 100,
              render: (id: number) => renderComponentCell(componentMap.get(id)) },
            {
              title: '启用', dataIndex: 'enabled', key: 'enabled', width: 70,
              render: (v: boolean, r: ScanWindow) => (
                <Switch size="small" checked={v} onChange={async (c) => {
                  await upsertScanWindow({ ...r, enabled: c });
                  message.success(c ? '已启用' : '已停用');
                  onRefresh();
                }} />
              ),
            },
            {
              title: '操作', key: 'action', width: 130,
              render: (_: unknown, r: ScanWindow) => (
                <>
                  <Button size="small" type="link" icon={<EditOutlined />} onClick={() => { setEditRepoId(r.repositoryId); setEditOpen(true); }} />
                  <Button size="small" type="link" danger icon={<DeleteOutlined />} onClick={async () => {
                    await deleteScanWindow(r.repositoryId);
                    message.success('已删除');
                    onRefresh();
                  }} />
                </>
              ),
            },
          ]}
        />
      </Drawer>
      {editRepoId != null && (
        <ScanWindowModal
          open={editOpen}
          repositoryId={editRepoId}
          onClose={() => { setEditOpen(false); setEditRepoId(null); }}
          onSaved={onRefresh}
        />
      )}
    </>
  );
};

export default ScanWindowHeatmap;
