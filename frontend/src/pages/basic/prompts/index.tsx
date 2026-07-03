import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Button,
  Card,
  Col,
  Form,
  Input,
  Row,
  Segmented,
  Select,
  Space,
  Statistic,
  Table,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  InboxOutlined,
  PlusOutlined,
  ReloadOutlined,
  StarFilled,
  StarOutlined,
} from '@ant-design/icons';
import {
  archivePrompt,
  clonePrompt,
  createPrompt,
  deletePrompt,
  listPrompts,
  publishPrompt,
  setDefaultPrompt,
  testRunPrompt,
  updatePrompt,
} from '../../../api/prompt';
import type { Prompt } from '../../../types';
import type { PromptType } from './constants';
import { PROMPT_TYPE_TABS } from './constants';
import { createPromptColumns, renderPromptExpandedRow } from './columns';
import PromptFormModal from './PromptFormModal';
import PromptTrialModal from '../../../components/PromptTrialModal';
import './prompts.css';

const { Text, Paragraph } = Typography;

type LifecycleTab = 'DRAFT' | 'RELEASED' | 'ARCHIVED';

const LIFECYCLE_LABEL: Record<LifecycleTab, string> = {
  DRAFT: '草稿',
  RELEASED: '已发布',
  ARCHIVED: '已归档',
};

const LIFECYCLE_TONE: Record<LifecycleTab, string> = {
  DRAFT: '#faad14',
  RELEASED: '#52c41a',
  ARCHIVED: '#8c8c8c',
};

const toPromptType = (type: string | undefined, fallback: PromptType): PromptType =>
  type === 'DOCUMENT_GENERATION' || type === 'MODULARIZE' ? type : fallback;

/**
 * 「提示词」单页(基础配置 — 基础配置 — 提示词)
 *
 * 集中管理所有提示词:
 *  - 顶部:lifecycle 切换(草稿/已发布/已归档)
 *  - 顶部:promptType 切换(模块提取/文档生成)+ 名称搜索
 *  - 顶部:3 张统计卡(当前 lifecycle 数 / 当前 type 总数 / 当前默认)
 *  - 表格:按 lifecycle 动态显示操作
 *    - DRAFT:编辑 / 试跑 / 发布 / 复制 / 删除
 *    - RELEASED:试跑 / 设为默认 / 归档 / 复制
 *    - ARCHIVED:只读(可复制产生新草稿)
 *  - 顶部 "+ 新建" → 创建 DRAFT 草稿
 */
const PromptsPage: React.FC = () => {
  const [form] = Form.useForm();

  // 筛选
  const [lifecycle, setLifecycle] = useState<LifecycleTab>('RELEASED');
  const [promptType, setPromptType] = useState<PromptType>('MODULARIZE');
  const [searchName, setSearchName] = useState('');

  // 列表
  const [prompts, setPrompts] = useState<Prompt[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [current, setCurrent] = useState(1);
  const [size, setSize] = useState(10);

  /** 当前 type 维度聚合统计（与 lifecycle 筛选无关） */
  const [typeStats, setTypeStats] = useState({
    totalAll: 0,
    defaultPrompt: null as Prompt | null,
  });

  // 模态框
  const [formModalOpen, setFormModalOpen] = useState(false);
  const [editingPrompt, setEditingPrompt] = useState<Prompt | null>(null);
  const [formModalType, setFormModalType] = useState<PromptType>('MODULARIZE');
  const [trialOpen, setTrialOpen] = useState(false);
  const [trialPrompt, setTrialPrompt] = useState<Prompt | null>(null);

  /** 拉取指定 promptType + lifecycle 的提示词（仅 DEFAULT 类别） */
  const fetchPrompts = useCallback(async () => {
    setLoading(true);
    try {
      const data = await listPrompts({
        current,
        size,
        name: searchName || undefined,
        promptType,
        lifecycle,
        category: 'DEFAULT',
      });
      setPrompts(data.records);
      setTotal(data.total);
    } finally {
      setLoading(false);
    }
  }, [current, size, searchName, promptType, lifecycle]);

  /** 拉取当前 type 的总数 / 当前默认（不受 lifecycle Tab 影响） */
  const fetchTypeStats = useCallback(async () => {
    try {
      const [allRes, releasedRes] = await Promise.all([
        listPrompts({ current: 1, size: 1, promptType }),
        listPrompts({ current: 1, size: 200, promptType, lifecycle: 'RELEASED' }),
      ]);
      const defaultPrompt = releasedRes.records.find((p) => p.isDefault === 1) ?? null;
      setTypeStats({
        totalAll: allRes.total,
        defaultPrompt,
      });
    } catch {
      // ignore
    }
  }, [promptType]);

  const refreshAll = useCallback(() => {
    fetchPrompts();
    fetchTypeStats();
  }, [fetchPrompts, fetchTypeStats]);

  useEffect(() => {
    fetchPrompts();
  }, [fetchPrompts]);

  useEffect(() => {
    fetchTypeStats();
  }, [fetchTypeStats]);

  /** 切换 lifecycle / promptType 时重置到第一页 */
  useEffect(() => {
    setCurrent(1);
  }, [lifecycle, promptType]);

  /** 编辑弹窗打开时回填表单字段 */
  useEffect(() => {
    if (formModalOpen && editingPrompt) {
      form.setFieldsValue({
        name: editingPrompt.name,
        content: editingPrompt.content,
      });
    } else if (formModalOpen && !editingPrompt) {
      form.resetFields();
    }
  }, [formModalOpen, editingPrompt, form]);

  // ========== 表单模态框 ==========
  const openCreateModal = () => {
    setEditingPrompt(null);
    setFormModalType(promptType);
    setFormModalOpen(true);
  };
  const openEditModal = useCallback(async (p: Prompt) => {
    // 已发布/已归档的提示词:先克隆为新 DRAFT(版本+1),再打开编辑弹窗
    // 注意:即使后端已 clone 一条 DRAFT,前端也不要切 tab/重置分页,保持用户当前所在 lifecycle 视图不变;
    // 用户取消编辑时,留在原 tab;DRAFT 列表里的新草稿可由用户后续手动查看或删除。
    if (p.lifecycle === 'RELEASED' || p.lifecycle === 'ARCHIVED') {
      try {
        const cloned = await clonePrompt(p.id);
        const clonedType = toPromptType(cloned.promptType, promptType);
        setEditingPrompt(cloned);
        setFormModalType(clonedType);
        setPromptType(clonedType);
        setFormModalOpen(true);
        return;
      } catch {
        // 拦截器已提示
        return;
      }
    }
    // DRAFT 提示词:直接打开编辑弹窗
    const draftType = toPromptType(p.promptType, promptType);
    setEditingPrompt(p);
    setFormModalType(draftType);
    setPromptType(draftType);
    setFormModalOpen(true);
  }, [promptType]);
  const handleFormSubmit = async () => {
    try {
      const values = await form.validateFields();
      if (editingPrompt) {
        await updatePrompt(editingPrompt.id, {
          name: values.name,
          content: values.content,
        });
        message.success('已更新(版本号 +1)');
        setPromptType(toPromptType(editingPrompt.promptType, formModalType));
        setLifecycle('DRAFT');
      } else {
        // 新建默认是 DRAFT 草稿；是否为「当前默认」由发布后单独「设为默认」操作决定
        await createPrompt({
          name: values.name,
          content: values.content,
          promptType: formModalType,
          isDefault: 0,
          lifecycle: 'DRAFT',
        });
        message.success('已创建草稿');
        setPromptType(formModalType);
        setLifecycle('DRAFT');
        setCurrent(1);
      }
      setFormModalOpen(false);
      fetchTypeStats();
    } catch {
      // 拦截器已提示
    }
  };

  // ========== 试跑模态框 ==========
  const openTrial = useCallback((p: Prompt) => {
    setTrialPrompt(p);
    setTrialOpen(true);
  }, []);
  /** 试跑回调：通用 PromptTrialModal 自管 state，本函数只负责发请求并返回结果。 */
  const handleTrialRun = async (params: {
    sampleCode: string;
    variables: Record<string, string>;
    resolvedContent: string;
  }) => {
    if (!trialPrompt) {
      return {
        inputTokens: 0,
        outputTokens: 0,
        durationMs: 0,
        result: '',
        errorReason: '未选择提示词',
      };
    }
    try {
      return await testRunPrompt(
        trialPrompt.id,
        params.sampleCode,
        undefined,
        params.resolvedContent,
      );
    } catch (e) {
      return {
        inputTokens: 0,
        outputTokens: 0,
        durationMs: 0,
        result: '',
        errorReason: e instanceof Error ? e.message : '试跑请求失败',
      };
    }
  };

  // ========== 行内操作 ==========
  // onClone 接 Prompt,其余接 id
  const handleClone = useCallback(async (p: Prompt) => {
    try {
      await clonePrompt(p.id);
      message.success('已复制(产生新草稿)');
      setPromptType(toPromptType(p.promptType, promptType));
      setLifecycle('DRAFT');
      setCurrent(1);
      fetchTypeStats();
    } catch {
      // 拦截器已提示
    }
  }, [fetchTypeStats, promptType]);
  const handleDelete = useCallback(async (id: number) => {
    try {
      await deletePrompt(id);
      message.success('已删除');
      refreshAll();
    } catch {
      // 拦截器已提示
    }
  }, [refreshAll]);
  const handlePublish = useCallback(async (p: Prompt) => {
    try {
      await publishPrompt(p.id);
      message.success('已发布');
      setPromptType(toPromptType(p.promptType, promptType));
      setLifecycle('RELEASED');
      setCurrent(1);
      fetchTypeStats();
    } catch {
      // 拦截器已提示
    }
  }, [fetchTypeStats, promptType]);
  const handleArchive = useCallback(async (p: Prompt) => {
    try {
      await archivePrompt(p.id);
      message.success('已归档');
      setPromptType(toPromptType(p.promptType, promptType));
      setLifecycle('ARCHIVED');
      setCurrent(1);
      fetchTypeStats();
    } catch {
      // 拦截器已提示
    }
  }, [fetchTypeStats, promptType]);
  const handleSetDefault = useCallback(async (id: number) => {
    try {
      await setDefaultPrompt(id);
      message.success('已设为当前默认');
      refreshAll();
    } catch {
      // 拦截器已提示
    }
  }, [refreshAll]);

  const columns = useMemo(
    () =>
      createPromptColumns({
        onOpenTrial: openTrial,
        onEdit: openEditModal,
        onClone: handleClone,
        onDelete: handleDelete,
        onPublish: handlePublish,
        onArchive: handleArchive,
        onSetDefault: handleSetDefault,
      }),
    [openTrial, openEditModal, handleClone, handleDelete, handlePublish, handleArchive, handleSetDefault],
  );

  const promptTypeLabel = PROMPT_TYPE_TABS.find((t) => t.key === promptType)?.label ?? '';
  const defaultPromptName = typeStats.defaultPrompt?.name ?? '未设置';

  return (
    <div className="ci-page ci-prompts-page">
      {/* 顶部 KPI 卡片 */}
      <Row gutter={[16, 16]} className="ci-prompt-kpi-row" style={{ marginBottom: 16 }}>
        <Col xs={24} md={8}>
          <Card size="small" className="ci-prompt-kpi-card">
            <Statistic
              className="ci-prompt-kpi-stat"
              title={`${LIFECYCLE_LABEL[lifecycle]} · 总数`}
              value={total}
              prefix={<InboxOutlined />}
              valueStyle={{ color: LIFECYCLE_TONE[lifecycle] }}
            />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" className="ci-prompt-kpi-card">
            <Statistic
              className="ci-prompt-kpi-stat"
              title={`${promptTypeLabel} · 总数`}
              value={typeStats.totalAll}
              prefix={<InboxOutlined />}
            />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" className="ci-prompt-kpi-card">
            <Tooltip title={typeStats.defaultPrompt ? defaultPromptName : '当前类型下已发布且标记为默认的提示词'}>
              <Statistic
                className="ci-prompt-kpi-stat ci-prompt-kpi-stat--text"
                title={`${promptTypeLabel} · 当前默认`}
                value={defaultPromptName}
                prefix={
                  typeStats.defaultPrompt ? (
                    <StarFilled style={{ color: '#faad14' }} />
                  ) : (
                    <StarOutlined style={{ color: '#bfbfbf' }} />
                  )
                }
                valueStyle={typeStats.defaultPrompt ? undefined : { color: '#bfbfbf' }}
              />
            </Tooltip>
          </Card>
        </Col>
      </Row>

      {/* 顶部筛选条 */}
      <Card
        size="small"
        className="ci-filter-card"
        style={{ marginBottom: 12 }}
        title={
          <Space>
            <Segmented
              value={lifecycle}
              onChange={(v) => setLifecycle(v as LifecycleTab)}
              options={[
                { value: 'DRAFT', label: '草稿' },
                { value: 'RELEASED', label: '已发布' },
                { value: 'ARCHIVED', label: '已归档' },
              ]}
            />
            <Select
              value={promptType}
              onChange={setPromptType}
              style={{ width: 180 }}
              options={PROMPT_TYPE_TABS.map((t) => ({ value: t.key, label: t.label }))}
            />
            <Input.Search
              placeholder="按名称模糊搜索"
              allowClear
              value={searchName}
              onChange={(e) => setSearchName(e.target.value)}
              onSearch={() => {
                setCurrent(1);
                fetchPrompts();
              }}
              style={{ width: 240 }}
            />
          </Space>
        }
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={refreshAll}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreateModal}>
              新建草稿
            </Button>
          </Space>
        }
      >
        <Paragraph type="secondary" style={{ marginBottom: 0 }}>
          提示词的生命周期:<b>草稿</b>(可编辑/试跑/发布) → <b>已发布</b>(锁定,只读,试跑/可设为默认/可复制/可归档) → <b>已归档</b>(历史保留)。
          每种类型支持多个发布版本,只有 1 条"当前默认"被运行时采用。
        </Paragraph>
      </Card>

      {/* 表格 */}
      <Card>
        <Table<Prompt>
          dataSource={prompts}
          columns={columns}
          rowKey="id"
          loading={loading}
          expandable={{ expandedRowRender: renderPromptExpandedRow }}
          scroll={{ x: 1300 }}
          pagination={{
            current,
            pageSize: size,
            total,
            showSizeChanger: true,
            onChange: (p, ps) => {
              setCurrent(p);
              setSize(ps);
            },
          }}
          locale={{
            emptyText:
              lifecycle === 'DRAFT' ? (
                <Space direction="vertical" style={{ padding: 24 }}>
                  <Text type="secondary">暂无{`${LIFECYCLE_LABEL[lifecycle]}`}提示词</Text>
                  <Button type="primary" icon={<PlusOutlined />} onClick={openCreateModal}>
                    新建草稿
                  </Button>
                </Space>
              ) : lifecycle === 'RELEASED' ? (
                <Space direction="vertical" style={{ padding: 24 }}>
                  <Text type="secondary">该类型暂无已发布提示词,先去「草稿」页发布一个</Text>
                </Space>
              ) : (
                <Text type="secondary" style={{ padding: 24, display: 'block' }}>暂无归档</Text>
              ),
          }}
        />
      </Card>

      {/* 新建/编辑表单 */}
      <PromptFormModal
        open={formModalOpen}
        editingPrompt={editingPrompt}
        form={form}
        promptTypeLabel={
          PROMPT_TYPE_TABS.find((t) => t.key === formModalType)?.label ?? ''
        }
        onCancel={() => setFormModalOpen(false)}
        onSubmit={handleFormSubmit}
      />

      {/* 试跑 */}
      <PromptTrialModal
        open={trialOpen}
        prompt={trialPrompt}
        promptTypeLabel={
          PROMPT_TYPE_TABS.find((t) => t.key === trialPrompt?.promptType)?.label ?? ''
        }
        onClose={() => setTrialOpen(false)}
        onRun={handleTrialRun}
      />
    </div>
  );
};

export default PromptsPage;
