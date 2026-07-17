import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  Form,
  Input,
  InputNumber,
  Modal,
  Row,
  Select,
  Space,
  Spin,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import { EditOutlined, PlusOutlined, ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import {
  createUserQuota,
  deleteUserQuota,
  listUserQuotas,
  updateUserQuota,
  type UserQuota,
  type UserQuotaRequest,
} from '../../../api/user-quota';
import {
  listSystemConfig,
  putSystemConfig,
  type SystemConfig,
} from '../../../api/system-config';

const { Text, Paragraph } = Typography;

interface QuotaFormValues {
  userId?: number;
  dailyTokenLimit: number;
  monthlyTokenLimit: number;
  enabled: boolean;
  remark?: string;
}

/**
 * 「流量管控」页面：
 *  1) 全局限流配置（4 个 key）
 *  2) AI 调用并发配置
 *  3) 用户额度表（CRUD）
 */
const QuotaControlPage: React.FC = () => {
  const [configs, setConfigs] = useState<SystemConfig[]>([]);
  const [configsLoading, setConfigsLoading] = useState(false);
  const [configSaving, setConfigSaving] = useState<string | null>(null);

  const [quotas, setQuotas] = useState<UserQuota[]>([]);
  const [quotasLoading, setQuotasLoading] = useState(false);
  const [quotaTotal, setQuotaTotal] = useState(0);
  const [current, setCurrent] = useState(1);
  const [size, setSize] = useState(10);
  const [filterUsername, setFilterUsername] = useState('');
  const [filterEnabled, setFilterEnabled] = useState<number | undefined>();

  const [quotaModalOpen, setQuotaModalOpen] = useState(false);
  const [editingQuota, setEditingQuota] = useState<UserQuota | null>(null);
  const [quotaForm] = Form.useForm<QuotaFormValues>();

  const fetchConfigs = useCallback(async () => {
    setConfigsLoading(true);
    try {
      const list = await listSystemConfig();
      setConfigs(list);
    } catch {
      // ignore
    } finally {
      setConfigsLoading(false);
    }
  }, []);

  const fetchQuotas = useCallback(async () => {
    setQuotasLoading(true);
    try {
      const res = await listUserQuotas({
        current,
        size,
        username: filterUsername || undefined,
        enabled: filterEnabled,
      });
      setQuotas(res.records);
      setQuotaTotal(res.total);
    } catch {
      // ignore
    } finally {
      setQuotasLoading(false);
    }
  }, [current, size, filterUsername, filterEnabled]);

  useEffect(() => {
    fetchConfigs();
  }, [fetchConfigs]);

  useEffect(() => {
    fetchQuotas();
  }, [fetchQuotas]);

  const handleConfigSave = async (key: string, value: string, description?: string) => {
    setConfigSaving(key);
    try {
      await putSystemConfig(key, { value, description });
      message.success(`已保存：${key} = ${value}`);
      await fetchConfigs();
    } catch {
      // ignore
    } finally {
      setConfigSaving(null);
    }
  };

  const openCreateQuota = () => {
    setEditingQuota(null);
    quotaForm.resetFields();
    quotaForm.setFieldsValue({ enabled: true, dailyTokenLimit: 0, monthlyTokenLimit: 0 } as QuotaFormValues);
    setQuotaModalOpen(true);
  };

  const openEditQuota = (q: UserQuota) => {
    setEditingQuota(q);
    quotaForm.setFieldsValue({
      userId: q.userId,
      dailyTokenLimit: q.dailyTokenLimit,
      monthlyTokenLimit: q.monthlyTokenLimit,
      enabled: q.enabled === 1,
      remark: q.remark,
    });
    setQuotaModalOpen(true);
  };

  const handleSaveQuota = async () => {
    try {
      const values = await quotaForm.validateFields();
      // 表单 enabled 是 boolean，提交前转成 0/1
      const payload: UserQuotaRequest = {
        userId: values.userId,
        dailyTokenLimit: values.dailyTokenLimit,
        monthlyTokenLimit: values.monthlyTokenLimit,
        enabled: values.enabled ? 1 : 0,
        remark: values.remark,
      };
      if (editingQuota) {
        await updateUserQuota(editingQuota.id, payload);
        message.success('已更新');
      } else {
        await createUserQuota(payload);
        message.success('已创建');
      }
      setQuotaModalOpen(false);
      await fetchQuotas();
    } catch {
      // ignore
    }
  };

  const handleDeleteQuota = async (q: UserQuota) => {
    try {
      await deleteUserQuota(q.id);
      message.success('已删除');
      await fetchQuotas();
    } catch {
      // ignore
    }
  };

  // 全局限流配置项
  const GLOBAL_CONFIG_KEYS: { key: string; label: string; help: string; type: 'switch' | 'int' }[] = [
    {
      key: 'token.limit-enabled',
      label: '启用 Token 限额检查',
      help: '关闭后所有额度检查跳过（兜底）',
      type: 'switch',
    },
    {
      key: 'token.task-limit',
      label: '单任务 Token 上限',
      help: '单个知识构建任务累计 Token 上限',
      type: 'int',
    },
    {
      key: 'token.system-monthly-limit',
      label: '单系统月度 Token 上限',
      help: '单个系统每月累计 Token 上限',
      type: 'int',
    },
    {
      key: 'ai.concurrency',
      label: 'AI 调用最大并发数',
      help: 'Semaphore 容量；超出后立即抛错',
      type: 'int',
    },
  ];

  const renderConfigRow = (item: typeof GLOBAL_CONFIG_KEYS[number]) => {
    const cfg = configs.find((c) => c.key === item.key);
    if (item.type === 'switch') {
      const enabled = cfg?.value === 'true';
      return (
        <Row key={item.key} align="middle" gutter={12} style={{ padding: '8px 0' }}>
          <Col flex="200px">
            <Text strong>{item.label}</Text>
          </Col>
          <Col flex="auto">
            <Space>
              <Switch
                checked={enabled}
                onChange={(v) => handleConfigSave(item.key, v ? 'true' : 'false', item.help)}
                loading={configSaving === item.key}
              />
              <Text type="secondary">{item.help}</Text>
            </Space>
          </Col>
        </Row>
      );
    }
    return (
      <Row key={item.key} align="middle" gutter={12} style={{ padding: '8px 0' }}>
        <Col flex="200px">
          <Text strong>{item.label}</Text>
        </Col>
        <Col flex="auto">
          <Space>
            <InputNumber
              min={0}
              value={Number(cfg?.value ?? 0)}
              disabled={configSaving === item.key}
              onChange={(v) => {
                if (v == null) return;
                handleConfigSave(item.key, String(v), item.help);
              }}
              style={{ width: 200 }}
            />
            <Text type="secondary">{item.help}</Text>
          </Space>
        </Col>
      </Row>
    );
  };

  return (
    <div className="ci-page ci-quota-control-page">
      <Row gutter={[16, 16]}>
        <Col span={24}>
          <Card
            title={
              <Space>
                <ThunderboltOutlined />
                <span>全局限流配置</span>
              </Space>
            }
            extra={
              <Tooltip title="刷新">
                <Button icon={<ReloadOutlined />} onClick={fetchConfigs} />
              </Tooltip>
            }
          >
            <Spin spinning={configsLoading}>
              <Paragraph type="secondary" style={{ marginBottom: 0 }}>
                修改后即时生效；写库后由 AiSummaryServiceImpl 实时拉取。
              </Paragraph>
              {GLOBAL_CONFIG_KEYS.map(renderConfigRow)}
            </Spin>
          </Card>
        </Col>

        <Col span={24}>
          <Card
            title={
              <Space>
                <span>用户额度</span>
                <Tag>0 = 不限</Tag>
              </Space>
            }
            extra={
              <Space>
                <Input.Search
                  placeholder="按 username 过滤"
                  allowClear
                  value={filterUsername}
                  onChange={(e) => setFilterUsername(e.target.value)}
                  onSearch={() => {
                    setCurrent(1);
                  }}
                  style={{ width: 200 }}
                />
                <Select
                  allowClear
                  placeholder="启用状态"
                  style={{ width: 130 }}
                  value={filterEnabled}
                  onChange={(v) => {
                    setFilterEnabled(v);
                    setCurrent(1);
                  }}
                  options={[
                    { value: 1, label: '启用' },
                    { value: 0, label: '禁用' },
                  ]}
                />
                <Button icon={<ReloadOutlined />} onClick={fetchQuotas}>
                  刷新
                </Button>
                <Button type="primary" icon={<PlusOutlined />} onClick={openCreateQuota}>
                  新建额度
                </Button>
              </Space>
            }
          >
            <Table<UserQuota>
              rowKey="id"
              loading={quotasLoading}
              dataSource={quotas}
              pagination={{
                current,
                pageSize: size,
                total: quotaTotal,
                showSizeChanger: true,
                onChange: (p, ps) => {
                  setCurrent(p);
                  setSize(ps);
                },
              }}
              columns={[
                { title: 'ID', dataIndex: 'id', width: 70 },
                { title: '用户 ID', dataIndex: 'userId', width: 100 },
                {
                  title: '日 Token 上限',
                  dataIndex: 'dailyTokenLimit',
                  width: 130,
                  render: (v: number) => (v > 0 ? v.toLocaleString() : <Tag>不限</Tag>),
                },
                {
                  title: '月 Token 上限',
                  dataIndex: 'monthlyTokenLimit',
                  width: 130,
                  render: (v: number) => (v > 0 ? v.toLocaleString() : <Tag>不限</Tag>),
                },
                {
                  title: '启用',
                  dataIndex: 'enabled',
                  width: 90,
                  render: (v: number) =>
                    v === 1 ? <Tag color="green">启用</Tag> : <Tag>禁用</Tag>,
                },
                { title: '备注', dataIndex: 'remark', ellipsis: true },
                {
                  title: '更新时间',
                  dataIndex: 'updatedDate',
                  width: 170,
                  render: (t: string) => (t ? new Date(t).toLocaleString() : '-'),
                },
                {
                  title: '操作',
                  width: 180,
                  fixed: 'right' as const,
                  render: (_: unknown, r: UserQuota) => (
                    <Space>
                      <Button size="small" icon={<EditOutlined />} onClick={() => openEditQuota(r)}>
                        编辑
                      </Button>
                      <Button
                        size="small"
                        danger
                        onClick={() => {
                          if (window.confirm(`确认删除 userId=${r.userId} 的额度配置？`)) {
                            handleDeleteQuota(r);
                          }
                        }}
                      >
                        删除
                      </Button>
                    </Space>
                  ),
                },
              ]}
            />
          </Card>
        </Col>
      </Row>

      <Modal
        title={editingQuota ? `编辑额度 #${editingQuota.id}` : '新建用户额度'}
        open={quotaModalOpen}
        onCancel={() => setQuotaModalOpen(false)}
        onOk={handleSaveQuota}
        okText="保存"
        cancelText="取消"
        destroyOnHidden
      >
        <Form<QuotaFormValues> form={quotaForm} layout="vertical">
          {!editingQuota && (
            <Form.Item
              name="userId"
              label="用户 ID"
              rules={[{ required: true, message: '请输入用户 ID（ci_user.id）' }]}
            >
              <InputNumber min={1} style={{ width: '100%' }} placeholder="如 1（admin）" />
            </Form.Item>
          )}
          <Form.Item name="dailyTokenLimit" label="日 Token 上限（0=不限）" rules={[{ required: true }]}>
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="monthlyTokenLimit" label="月 Token 上限（0=不限）" rules={[{ required: true }]}>
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch checkedChildren="启用" unCheckedChildren="禁用" />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input placeholder="可选" />
          </Form.Item>
          {!editingQuota && (
            <Alert
              type="warning"
              showIcon
              message="MVP 阶段 ci_user 表只有 admin 一条；后续扩展多账号时按 user_id 创建额度。"
            />
          )}
        </Form>
      </Modal>
    </div>
  );
};

export default QuotaControlPage;
