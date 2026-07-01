import React, { useEffect, useMemo, useState } from 'react';
import { Button, Drawer, Form, Modal, Space, Spin, Tag, Tree, Typography, message } from 'antd';
import type { FormInstance } from 'antd';
import type { DataNode } from 'antd/es/tree';
import { PlayCircleOutlined, SyncOutlined } from '@ant-design/icons';
import request from '../../api/request';
import EntryScanConfigEditor from '../../components/EntryScanConfigEditor';
import type { EntryScanConfig } from '../../types';

const { Text } = Typography;

const DEFAULT_EXCLUDE = ['**/*Test', '**/*Tests', '**/*TestCase'];

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

/** 编辑仓库入口扫描规则 Modal（含试跑功能） */
const RepositoryScanConfigModal: React.FC<Props> = ({
  open,
  form,
  repoId,
  systemId,
  submitting = false,
  onCancel,
  onSubmit,
}) => {
  const DEFAULT_SCAN_CONFIG = {
    includeAnnotations: ['RestController', 'Controller', 'RequestMapping'],
    includeClasspaths: [],
    includeExtends: [],
    excludeClasspaths: DEFAULT_EXCLUDE,
    excludePackages: [],
    excludeAnnotations: ['Deprecated'],
  };

  // 试跑状态
  const [trying, setTrying] = useState(false);
  const [trialLocked, setTrialLocked] = useState(false);
  const [trialId, setTrialId] = useState<number | null>(null);
  const [trialStatus, setTrialStatus] = useState<string | null>(null);
  const [trialEntries, setTrialEntries] = useState<any[]>([]);
  const [trialError, setTrialError] = useState('');
  const [drawerOpen, setDrawerOpen] = useState(false);

  // 打开时自动填入默认扫描规则 + 检查试跑锁状态
  useEffect(() => {
    if (open) {
      form.setFieldsValue({ entryScanConfig: DEFAULT_SCAN_CONFIG });
      if (repoId) {
        request.get(`/repositories/${repoId}/trial-run/lock`).then((d: any) => setTrialLocked(!!d)).catch(() => setTrialLocked(false));
      }
    }
  }, [open, form, repoId]);

  const handleFillDefault = () => {
    form.setFieldsValue({ entryScanConfig: DEFAULT_SCAN_CONFIG });
  };

  /** 触发试跑 */
  const handleTrial = async () => {
    if (!repoId || !systemId) { message.warning('需要已有仓库（已保存）才能试跑'); return; }
    const config = form.getFieldValue('entryScanConfig');
    setTrying(true);
    try {
      const trial: any = await request.post(`/repositories/${repoId}/trial-run`, config, {
        params: { systemId },
      });
      setTrialId(trial.id);
      setTrialStatus(trial.status);
      setTrialError('');
      setTrialEntries([]);
      setDrawerOpen(true);
      pollTrial(trial.id);
    } catch (e: any) {
      if (e?.response?.data?.message) message.error(e.response.data.message);
      else message.error('试跑触发失败');
    } finally {
      setTrying(false);
    }
  };

  /** 轮询试跑结果 */
  const pollTrial = async (id: number) => {
    const poll = async () => {
      const t: any = await request.get(`/repositories/${repoId}/trial-run/${id}`);
      setTrialStatus(t.status);
      if (t.status === 'SUCCESS') {
        const entries: any[] = await request.get(`/repositories/${repoId}/trial-run/${id}/entries`);
        setTrialEntries(entries || []);
      } else if (t.status === 'FAILED') {
        setTrialError(t.errorMessage || '试跑失败');
      } else {
        // PENDING / RUNNING → 继续轮询
        setTimeout(poll, 2000);
      }
    };
    poll();
  };

  /** 构建试跑结果树（按 entryType 分组 → 类 → 方法） */
  const trialTreeData = useMemo<DataNode[]>(() => {
    const normalized = trialEntries.map((e: any) => {
      const base = e.base ?? e;
      return { ...base, methods: e.methods ?? [] };
    });
    const groups: Record<string, typeof normalized> = {};
    normalized.forEach((cls) => {
      const t = cls.entryType || 'UNKNOWN';
      if (!groups[t]) groups[t] = [];
      groups[t].push(cls);
    });
    return Object.entries(groups).sort(([, a], [, b]) => b.length - a.length).map(([k, items]) => ({
      key: k,
      title: <Space><Tag color="cyan">{k}</Tag><Text type="secondary">{items.length} 类</Text></Space>,
      selectable: false,
      children: items.map((cls) => ({
        key: cls.className,
        title: (
          <Space size={4}>
            <Text strong>{cls.className.split('.').pop()}</Text>
            {cls.remark && <Text type="secondary" style={{ fontSize: 11 }}>{cls.remark}</Text>}
          </Space>
        ),
        children: (cls.methods || []).map((m: any, i: number) => ({
          key: `${cls.className}-${i}`,
          isLeaf: true,
          title: (
            <Space size={4} style={{ fontSize: 12 }}>
              {m.httpMethod && <Tag color="geekblue" style={{ fontSize: 11 }}>{m.httpMethod}</Tag>}
              {m.httpPath && <Text code style={{ fontSize: 11 }}>{m.httpPath}</Text>}
              {!m.httpMethod && m.annotation && <Tag style={{ fontSize: 11 }}>{m.annotation}</Tag>}
              <Text style={{ fontSize: 12 }}>{m.methodSignature || m.methodName}</Text>
            </Space>
          ),
        })),
      })),
    }));
  }, [trialEntries]);

  return (
    <>
      <Modal
        title="入口扫描规则"
        open={open}
        onCancel={onCancel}
        width={720}
        footer={[
          <Button key="cancel" onClick={onCancel}>取消</Button>,
          <Button key="trial" icon={<PlayCircleOutlined />} loading={trying} disabled={trialLocked || !repoId}
            onClick={handleTrial}
          >
            {trialLocked ? '试跑执行中…' : '试跑'}
          </Button>,
          <Button key="submit" type="primary" loading={submitting} onClick={onSubmit}>保存</Button>,
        ]}
        destroyOnHidden
      >
        <Form<ScanConfigFormValues> form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Space style={{ marginBottom: 12 }}>
            <Button icon={<SyncOutlined />} onClick={handleFillDefault}>重置</Button>
          </Space>
          <Form.Item name="entryScanConfig" label="入口扫描配置"
            tooltip="不配置入口识别时，运行期会走 Controller/JOB/MQ 兜底"
          >
            <EntryScanConfigEditor />
          </Form.Item>
        </Form>
      </Modal>

      {/* 试跑结果 Drawer */}
      <Drawer
        title={<Space><PlayCircleOutlined />试跑结果 #{trialId}</Space>}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        width={620}
      >
        {trialStatus === 'PENDING' || trialStatus === 'RUNNING' ? (
          <div style={{ textAlign: 'center', padding: 48 }}>
            <Spin size="large" />
            <Text type="secondary" style={{ display: 'block', marginTop: 16 }}>
              {trialStatus === 'PENDING' ? '准备中…' : '正在拉代码 + AST 解析 + 入口识别…'}
            </Text>
          </div>
        ) : trialStatus === 'FAILED' ? (
          <Text type="danger">{trialError || '试跑失败，请查看后端日志。'}</Text>
        ) : trialStatus === 'SUCCESS' && trialEntries.length === 0 ? (
          <Text type="secondary">未识别到任何入口。请调整扫描规则后重试。</Text>
        ) : (
          <Tree treeData={trialTreeData} defaultExpandAll showLine={{ showLeafIcon: false }} blockNode style={{ fontSize: 13 }} />
        )}
      </Drawer>
    </>
  );
};

export default RepositoryScanConfigModal;
