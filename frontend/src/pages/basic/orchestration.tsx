import React, { useEffect, useState } from 'react';
import { Button, Card, Input, Modal, Segmented, Space, Switch, Table, Tag, Typography, message } from 'antd';
import { EditOutlined, ReloadOutlined } from '@ant-design/icons';
import { listScanWindows, upsertScanWindow, getSchedulerCron, updateSchedulerCron, updateSchedulerEnabled } from '../../api/scan-window';
import { listRepositories } from '../../api/repository';
import { listSystems } from '../../api/system';
import type { ScanWindow } from '../../types';
import ScanWindowModal from '../systems/ScanWindowModal';
import ScanWindowHeatmap from './ScanWindowHeatmap';

const { Text, Title } = Typography;

const WEEK_LABELS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];

function bitsToLabel(bits: number): string {
  const parts: string[] = [];
  for (let i = 0; i < 7; i++) if ((bits & (1 << i)) !== 0) parts.push(WEEK_LABELS[i]);
  return parts.length === 0 ? '未设置' : parts.join('、');
}

function timeLabel(h: number, m: number): string {
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

const TaskOrchestration: React.FC = () => {
  const [viewMode, setViewMode] = useState<'list' | 'heatmap'>('list');
  const [windows, setWindows] = useState<ScanWindow[]>([]);
  const [loading, setLoading] = useState(false);
  const [repoMap, setRepoMap] = useState<Map<number, string>>(new Map());
  const [sysMap, setSysMap] = useState<Map<number, string>>(new Map());
  const [editId, setEditId] = useState<number | null>(null);
  const [editOpen, setEditOpen] = useState(false);

  // 全局 cron
  const [cron, setCron] = useState('');
  const [enabled, setEnabled] = useState(true);
  const [nextRuns, setNextRuns] = useState<string[]>([]);
  const [cronEditOpen, setCronEditOpen] = useState(false);
  const [cronInput, setCronInput] = useState('');

  const fetch = async () => {
    setLoading(true);
    try {
      const [wins, repos, systems, cronData] = await Promise.all([
        listScanWindows(),
        listRepositories({ current: 1, size: 2000 }),
        listSystems({ current: 1, size: 200 }),
        getSchedulerCron().catch(() => null),
      ]);
      setRepoMap(new Map(repos.records.map((r) => [r.id, r.gitUrl ?? `#${r.id}`])));
      setSysMap(new Map(systems.records.map((s) => [s.id, s.name])));
      setWindows(wins);
      if (cronData) { setCron(cronData.cron); setEnabled(cronData.enabled); setNextRuns(cronData.nextRuns ?? []); }
    } finally { setLoading(false); }
  };

  useEffect(() => { fetch(); }, []);

  const handleToggle = async (w: ScanWindow) => {
    await upsertScanWindow({ ...w, enabled: !w.enabled });
    message.success(w.enabled ? '已停用' : '已启用');
    fetch();
  };

  const handleGlobalToggle = async (v: boolean) => {
    await updateSchedulerEnabled(v);
    setEnabled(v);
    message.success(v ? '扫描调度已启用' : '扫描调度已暂停');
  };

  const handleCronSave = async () => {
    try {
      const res = await updateSchedulerCron(cronInput);
      setCron(res.cron);
      setNextRuns(res.nextRuns ?? []);
      setCronEditOpen(false);
      message.success('cron 已更新');
    } catch { message.error('cron 格式错误或更新失败'); }
  };

  return (
    <div className="ci-page ci-orchestration-page">
      <Card
        title={<Title level={4} style={{ margin: 0 }}>任务编排</Title>}
        extra={<Button icon={<ReloadOutlined />} loading={loading} onClick={fetch}>刷新</Button>}
      >
        {/* 全局调度配置 */}
        <Space size={16} wrap style={{ marginBottom: 16 }}>
          <Space size={4}>
            <Text type="secondary">扫描调度：</Text>
            <Switch checked={enabled} onChange={handleGlobalToggle} />
            <Tag color={enabled ? 'green' : 'default'}>{enabled ? '运行中' : '已暂停'}</Tag>
          </Space>
          {cron && (
            <Space size={4}>
              <Tag color="geekblue" style={{ cursor: 'pointer' }} onClick={() => { setCronInput(cron); setCronEditOpen(true); }}>
                {cron}
              </Tag>
              <Button size="small" icon={<EditOutlined />} onClick={() => { setCronInput(cron); setCronEditOpen(true); }} />
              <Text type="secondary" style={{ fontSize: 11 }}>
                下 5 次：{nextRuns.slice(0, 3).join(' · ')}
              </Text>
            </Space>
          )}
        </Space>

        {/* 视图切换 */}
        <Segmented
          options={[{ value: 'list', label: '列表' }, { value: 'heatmap', label: '热力图' }]}
          value={viewMode}
          onChange={(v) => setViewMode(v as 'list' | 'heatmap')}
          style={{ marginBottom: 16 }}
        />

        {viewMode === 'heatmap' ? (
          <ScanWindowHeatmap data={windows} repoMap={repoMap} sysMap={sysMap} onRefresh={fetch} />
        ) : (
          <Table
            dataSource={windows}
            rowKey="id"
            loading={loading}
            pagination={{ pageSize: 20, showSizeChanger: true }}
            columns={[
              { title: '仓库', dataIndex: 'repositoryId', key: 'repo', ellipsis: true, width: 260,
                render: (id: number) => <Text code style={{ fontSize: 12 }}>{repoMap.get(id) ?? `#${id}`}</Text> },
              { title: '周几', dataIndex: 'weekDays', key: 'weekDays', width: 180,
                render: (v: number) => <Text>{bitsToLabel(v)}</Text> },
              { title: '时间', key: 'time', width: 90,
                render: (_: unknown, r: ScanWindow) => <Tag color="geekblue">{timeLabel(r.hour, r.minute)}</Tag> },
              { title: '启用', dataIndex: 'enabled', key: 'enabled', width: 70,
                render: (v: boolean, r: ScanWindow) => <Switch size="small" checked={v} onChange={() => handleToggle(r)} /> },
              { title: '最近触发', dataIndex: 'lastFiredAt', key: 'lastFiredAt', width: 170,
                render: (v?: string) => v ? new Date(v).toLocaleString() : <Text type="secondary">未触发</Text> },
              { title: '操作', key: 'action', width: 80,
                render: (_: unknown, r: ScanWindow) => (
                  <Button size="small" icon={<EditOutlined />} onClick={() => { setEditId(r.repositoryId); setEditOpen(true); }} />
                ),
              },
            ]}
          />
        )}
      </Card>

      {/* cron 编辑 Modal */}
      <Modal
        title="编辑扫描 cron 表达式"
        open={cronEditOpen}
        onCancel={() => setCronEditOpen(false)}
        onOk={handleCronSave}
        width={480}
      >
        <Input value={cronInput} onChange={(e) => setCronInput(e.target.value)} placeholder="0 */1 * * * *" />
        <Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
          格式：秒 分 时 日 月 周（Spring Cron）。修改后即时生效无需重启。
        </Text>
      </Modal>

      {/* 单个窗口编辑 Modal */}
      {editId != null && (
        <ScanWindowModal
          open={editOpen}
          repositoryId={editId}
          onClose={() => { setEditOpen(false); setEditId(null); }}
          onSaved={fetch}
        />
      )}
    </div>
  );
};

export default TaskOrchestration;
