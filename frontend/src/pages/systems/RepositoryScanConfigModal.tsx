import React, { useEffect, useRef, useState } from 'react';
import { Alert, Button, Form, Modal, Space, Tag } from 'antd';
import type { FormInstance } from 'antd';
import { PlayCircleOutlined, SyncOutlined } from '@ant-design/icons';
import EntryScanConfigEditor from '../../components/EntryScanConfigEditor';
import EntryScanTrialDrawer, {
  type EntryScanTrialDrawerRef,
} from '../../components/EntryScanTrialDrawer';
import type { EntryScanConfig } from '../../types';
import { getLatestTrial, type EntryScanTrialSummary } from '../../api/trialRun';
import { buildScanConfigWithDefaults } from '../../utils/scanConfigDefaults';

export interface ScanConfigFormValues {
  entryScanConfig: EntryScanConfig;
}

interface Props {
  open: boolean;
  form: FormInstance<ScanConfigFormValues>;
  repoId?: number;
  systemId?: number;
  submitting?: boolean;
  onCancel: () => void;
  onSubmit: () => void;
}

const DEFAULT_SCAN_CONFIG: EntryScanConfig = buildScanConfigWithDefaults(undefined);

const formatDateTime = (value?: string): string =>
  value ? new Date(value).toLocaleString() : '-';

const STATUS_LABEL: Record<string, { text: string; color: string }> = {
  PENDING: { text: '准备中', color: 'processing' },
  RUNNING: { text: '执行中', color: 'processing' },
  SUCCESS: { text: '成功', color: 'success' },
  FAILED: { text: '失败', color: 'error' },
  CANCELLED: { text: '已取消', color: 'default' },
};

/** 编辑仓库入口扫描规则 Modal（试跑结果由独立 Drawer 承载） */
const RepositoryScanConfigModal: React.FC<Props> = ({
  open,
  form,
  repoId,
  systemId,
  submitting = false,
  onCancel,
  onSubmit,
}) => {
  const [latestTrial, setLatestTrial] = useState<EntryScanTrialSummary | null>(null);
  const [trialDrawerOpen, setTrialDrawerOpen] = useState(false);
  const [trialLocked, setTrialLocked] = useState(false);
  const trialDrawerRef = useRef<EntryScanTrialDrawerRef>(null);

  const handleFillDefault = () => {
    form.setFieldsValue({ entryScanConfig: DEFAULT_SCAN_CONFIG });
  };

  const handleTrial = () => {
    if (!repoId) return;
    const config = form.getFieldValue('entryScanConfig');
    if (!config) return;
    setTrialDrawerOpen(true);
    void trialDrawerRef.current?.triggerTrial(config);
  };

  const handleOpenHistory = () => {
    if (!repoId) return;
    setTrialDrawerOpen(true);
    void trialDrawerRef.current?.openHistory();
  };

  const handleViewLatest = () => {
    if (!repoId || !latestTrial) return;
    setTrialDrawerOpen(true);
    void trialDrawerRef.current?.viewLatest();
  };

  const reloadLatestTrial = async () => {
    if (!repoId) return;
    try {
      const latest = await getLatestTrial(repoId).catch(() => null);
      setLatestTrial(latest);
    } catch {
      setLatestTrial(null);
    }
  };

  // Modal 打开时拉一次 latest，用于顶部 Alert
  useEffect(() => {
    if (!open) {
      setTrialDrawerOpen(false);
      return;
    }
    void reloadLatestTrial();
  }, [open, repoId]);

  const statusTag = (status: string) => {
    const meta = STATUS_LABEL[status] || { text: status, color: 'default' };
    return <Tag color={meta.color}>{meta.text}</Tag>;
  };

  return (
    <>
      <Modal
        title="入口扫描规则"
        open={open}
        onCancel={onCancel}
        width={720}
        footer={[
          <Button key="cancel" onClick={onCancel}>
            取消
          </Button>,
          <Button
            key="history"
            disabled={!repoId}
            onClick={handleOpenHistory}
          >
            试跑历史
          </Button>,
          <Button
            key="trial"
            icon={<PlayCircleOutlined />}
            disabled={trialLocked || !repoId}
            onClick={handleTrial}
          >
            {trialLocked ? '试跑执行中…' : '试跑'}
          </Button>,
          <Button
            key="submit"
            type="primary"
            loading={submitting}
            onClick={onSubmit}
          >
            保存
          </Button>,
        ]}
        destroyOnHidden
      >
        {latestTrial && (
          <Alert
            type={latestTrial.status === 'SUCCESS' ? 'success' : latestTrial.status === 'FAILED' ? 'error' : 'info'}
            showIcon
            style={{ marginBottom: 16 }}
            message={
              <Space wrap>
                <span>上次试跑：{formatDateTime(latestTrial.startedAt)}</span>
                {statusTag(latestTrial.status)}
                {latestTrial.status === 'SUCCESS' && (
                  <span>识别 {latestTrial.entryCount ?? 0} 个入口类</span>
                )}
                {latestTrial.userId && <span>操作人 {latestTrial.userId}</span>}
              </Space>
            }
            action={
              <Button size="small" type="link" onClick={handleViewLatest}>
                查看结果
              </Button>
            }
          />
        )}
        <Form<ScanConfigFormValues> form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Space style={{ marginBottom: 12 }}>
            <Button icon={<SyncOutlined />} onClick={handleFillDefault}>
              重置
            </Button>
          </Space>
          <Form.Item
            name="entryScanConfig"
            label="入口扫描配置"
            tooltip="不配置入口识别时，运行期会走 Controller/JOB/MQ 兜底"
          >
            <EntryScanConfigEditor />
          </Form.Item>
        </Form>
      </Modal>

      <EntryScanTrialDrawer
        ref={trialDrawerRef}
        open={trialDrawerOpen}
        repoId={repoId}
        systemId={systemId}
        onClose={() => setTrialDrawerOpen(false)}
        onLockChange={setTrialLocked}
      />
    </>
  );
};

export default RepositoryScanConfigModal;
