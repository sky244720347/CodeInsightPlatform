import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Steps,
  Tag,
  Typography,
  message,
} from 'antd';
import {
  ArrowLeftOutlined,
  ArrowRightOutlined,
  CheckCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import { createSystem, updateSystem } from '../../api/system';
import {
  createRepository,
  listRepositories,
  updateRepository,
  testRepositoryConnection,
} from '../../api/repository';
import { listPrompts, testRunPrompt } from '../../api/prompt';
import type { Repository, System, EntryScanConfig, Prompt } from '../../types';
import EntryScanConfigEditor from '../../components/EntryScanConfigEditor';
import PromptTrialModal from '../../components/PromptTrialModal';
import RepositoryScanConfigModal from './RepositoryScanConfigModal';
import { buildScanConfigWithDefaults } from '../../utils/scanConfigDefaults';
import SystemPromptEditorModal from './SystemPromptEditorModal';
import { applyPromptCreated } from './promptSelect';

const { Text, Paragraph } = Typography;

const DEFAULT_SCAN_VALUES = buildScanConfigWithDefaults(undefined);

const DEFAULT_PROMPTS: Record<'MODULARIZE' | 'DOCUMENT_GENERATION', { key: string; label: string }> = {
  MODULARIZE: { key: 'MODULARIZE', label: '模块提取提示词' },
  DOCUMENT_GENERATION: { key: 'DOCUMENT_GENERATION', label: '文档生成提示词' },
};

interface Props {
  open: boolean;
  /** 已有系统 ID（空 = 新建） */
  initialSystemId?: number | null;
  onClose: () => void;
  onCompleted: (systemId: number) => void;
  /**
   * 阶段性保存(Steps 1-3 落库后)回调,用于父页面刷新列表。
   * 调用时机:Step 1 / 2 / 3 各自 save 成功后,以及 handleCancel 关闭弹窗时(若有新建数据)。
   * 父页面应该在此回调内重新拉取列表,使用户在向导关闭后能立刻看到中途落库的草稿数据。
   */
  onPartialSave?: () => void;
}

interface SystemFormValues {
  name: string;
  nameCn?: string;
  owner: string;
  description?: string;
}

interface RepositoryFormValues {
  gitUrl: string;
  branch: string;
  scanRoot: string;
  username?: string;
  password?: string;
  excludeDirs?: string;
  excludeFileTypes?: string;
}

/**
 * 「新建系统」4 步向导：
 *  Step 1 基本信息  →  POST /systems
 *  Step 2 配置仓库  →  POST /repositories
 *  Step 3 入口扫描  →  PUT /repositories/{id} {entryScanConfig}
 *  Step 4 提示词    →  PUT /systems/{id} {modularizePromptId, documentPromptId}
 *
 *  <p>系统级启停状态机已删除：每步只提交必要参数即可，提交后系统可立即在列表中查看并使用。</p>
 */
const SystemWizardModal: React.FC<Props> = ({
  open,
  initialSystemId,
  onClose,
  onCompleted,
  onPartialSave,
}) => {
  const [currentStep, setCurrentStep] = useState(0);
  const [submitting, setSubmitting] = useState(false);

  const [systemId, setSystemId] = useState<number | null>(initialSystemId ?? null);
  const [systemName, setSystemName] = useState<string>('');
  const [repoId, setRepoId] = useState<number | null>(null);

  const [systemForm] = Form.useForm<SystemFormValues>();
  const [repoForm] = Form.useForm<RepositoryFormValues>();
  const [scanForm] = Form.useForm<{ entryScanConfig: EntryScanConfig }>();
  const [promptForm] = Form.useForm<{
    modularizePromptId?: number | null;
    documentPromptId?: number | null;
  }>();

  const [prompts, setPrompts] = useState<Prompt[]>([]);
  const [promptsLoading, setPromptsLoading] = useState(false);

  /** Step 4:提示词编辑器弹窗状态(自定义/复制默认) */
  const [editorState, setEditorState] = useState<{
    open: boolean;
    mode: 'custom' | 'clone-default';
    promptType: 'MODULARIZE' | 'DOCUMENT_GENERATION';
  } | null>(null);
  /** 试跑弹窗:用已选中的 prompt 直接试跑 */
  const [trialPrompt, setTrialPrompt] = useState<Prompt | null>(null);
  const [trialOpen, setTrialOpen] = useState(false);
  /** Step 3: 入口扫描试跑（复用 RepositoryScanConfigModal trialOnly） */
  const [scanTrialOpen, setScanTrialOpen] = useState(false);
  /* 下拉选择先暂存，由底部「完成配置」统一提交；null 表示用户主动清空 */
  const [pendingModularizeId, setPendingModularizeId] = useState<number | null | undefined>(undefined);
  const [pendingDocumentId, setPendingDocumentId] = useState<number | null | undefined>(undefined);

  // 重置 wizard 状态
  useEffect(() => {
    if (!open) {
      setScanTrialOpen(false);
      setPendingModularizeId(undefined);
      setPendingDocumentId(undefined);
      return;
    }
    setCurrentStep(0);
    setSystemId(initialSystemId ?? null);
    setRepoId(null);
    systemForm.resetFields();
    repoForm.resetFields();
    scanForm.resetFields();
    promptForm.resetFields();
    setPendingModularizeId(undefined);
    setPendingDocumentId(undefined);
  }, [open, initialSystemId, systemForm, repoForm, scanForm, promptForm]);

  // Step 4：拉取可用提示词（DEFAULT 全局默认 + 系统下所有仓库的 USER 自定义）
  // 提示词 scope_id = repository.id,下拉框需要看到系统下所有仓库的 USER 提示词（共享）
  // 实现方式:先 listRepositories 拿到系统下所有仓库 id,再对每个 id 调 listPrompts
  const fetchPrompts = useCallback(async () => {
    setPromptsLoading(true);
    try {
      const all: Prompt[] = [];

      // 1) 拉系统下所有仓库的 id
      let repoIds: number[] = [];
      if (systemId != null) {
        try {
          const reposRes = await listRepositories({
            current: 1,
            size: 1000,
            systemId,
          });
          repoIds = (reposRes.records || []).map((r) => r.id);
        } catch {
          repoIds = [];
        }
      }
      // 当前 wizard 创建的仓库一定要包含在内（listRepositories 可能还没同步到）
      if (repoId != null && !repoIds.includes(repoId)) {
        repoIds = [repoId, ...repoIds];
      }

      for (const t of Object.values(DEFAULT_PROMPTS)) {
        // 2) 拉 DEFAULT 类别 is_default=1 的
        const defRes = await listPrompts({
          current: 1,
          size: 200,
          lifecycle: 'RELEASED',
          promptType: t.key,
          category: 'DEFAULT',
          isDefault: 1,
        });
        all.push(...defRes.records);
        // 3) 对系统下每个仓库,拉 USER 自定义并合并
        for (const rid of repoIds) {
          const userRes = await listPrompts({
            current: 1,
            size: 200,
            promptType: t.key,
            category: 'USER',
            scopeId: rid,
          });
          all.push(...userRes.records);
        }
      }
      // 按 id 去重
      const dedup = Array.from(new Map(all.map((p) => [p.id, p])).values());
      setPrompts(dedup);
    } catch {
      // 拦截器已提示
    } finally {
      setPromptsLoading(false);
    }
  }, [systemId, repoId]);

  useEffect(() => {
    if (open && currentStep === 3) fetchPrompts();
  }, [open, currentStep, fetchPrompts]);

  const defaultModularize = useMemo(
    () => prompts.find((p) => p.promptType === 'MODULARIZE' && p.isDefault === 1) || null,
    [prompts],
  );
  const defaultDocument = useMemo(
    () => prompts.find((p) => p.promptType === 'DOCUMENT_GENERATION' && p.isDefault === 1) || null,
    [prompts],
  );

  const modularizeOptions = useMemo(
    () => prompts
      .filter((p) => p.promptType === 'MODULARIZE')
      .map((p) => ({
        value: p.id,
        label: `${p.name} (v${p.version})${p.isDefault === 1 ? ' · 默认' : ''}`,
      })),
    [prompts],
  );
  const documentOptions = useMemo(
    () => prompts
      .filter((p) => p.promptType === 'DOCUMENT_GENERATION')
      .map((p) => ({
        value: p.id,
        label: `${p.name} (v${p.version})${p.isDefault === 1 ? ' · 默认' : ''}`,
      })),
    [prompts],
  );

  // 当前已选中的 prompt(根据 form 字段实时计算);进入 Step 4 时若未选则自动用全局默认
  const selectedModularize = useMemo(() => {
    const id = promptForm.getFieldValue('modularizePromptId') as number | undefined | null;
    if (!id) return null;
    return prompts.find((p) => p.id === id) ?? null;
  }, [prompts, /* form 字段变化触发 */ editorState, trialOpen]);
  const selectedDocument = useMemo(() => {
    const id = promptForm.getFieldValue('documentPromptId') as number | undefined | null;
    if (!id) return null;
    return prompts.find((p) => p.id === id) ?? null;
  }, [prompts, editorState, trialOpen]);

  // 进入 Step 4:拉取完默认提示词后,自动填充 form(若用户尚未选过)
  useEffect(() => {
    if (currentStep !== 3) return;
    if (prompts.length === 0) return;
    if (!promptForm.getFieldValue('modularizePromptId') && defaultModularize) {
      promptForm.setFieldValue('modularizePromptId', defaultModularize.id);
    }
    if (!promptForm.getFieldValue('documentPromptId') && defaultDocument) {
      promptForm.setFieldValue('documentPromptId', defaultDocument.id);
    }
  }, [currentStep, prompts, defaultModularize, defaultDocument, promptForm]);

  /** Step 4 交互处理 */
  const handleSelectExisting = (promptType: 'MODULARIZE' | 'DOCUMENT_GENERATION', id: number | undefined) => {
    if (promptType === 'MODULARIZE') {
      setPendingModularizeId(id ?? null);
    } else {
      setPendingDocumentId(id ?? null);
    }
  };

  const openCustomPrompt = (promptType: 'MODULARIZE' | 'DOCUMENT_GENERATION') => {
    setEditorState({ open: true, mode: 'custom', promptType });
  };
  const openTrial = (p: Prompt) => {
    setTrialPrompt(p);
    setTrialOpen(true);
  };
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
  /** 提示词创建成功 → 仅 stage 到 pending + 推入本地列表,不调后端绑定 */
  const handlePromptCreated = (p: Prompt) => {
    applyPromptCreated(p, {
      setPrompts,
      setPendingModularizeId,
      setPendingDocumentId,
      form: promptForm,
      promptType: p.promptType as 'MODULARIZE' | 'DOCUMENT_GENERATION',
    });
    message.success(`已创建自定义提示词:${p.name}（点击底部「完成配置」生效）`);
  };

  /** Step 1: 基本信息 */
  const handleStep1Submit = async () => {
    const values = await systemForm.validateFields();
    setSubmitting(true);
    try {
      let sys: System;
      if (systemId) {
        sys = await updateSystem(systemId, values);
      } else {
        sys = await createSystem(values);
        setSystemId(sys.id);
      }
      setSystemId(sys.id);
      // 同步记录系统名称,后续 Step 4 创建自定义提示词时用作命名前缀
      setSystemName(sys.name ?? values.name ?? '');
      message.success('基本信息已保存');
      setCurrentStep(1);
      // 阶段性保存:通知父页面刷新列表,使用户关闭向导后能看到新建的系统草稿
      onPartialSave?.();
    } finally {
      setSubmitting(false);
    }
  };

  /** Step 2: 配置仓库 */
  const handleStep2Submit = async () => {
    const values = await repoForm.validateFields();
    setSubmitting(true);
    try {
      const repo: Repository = await createRepository({
        systemId: systemId!,
        ...values,
      } as Partial<Repository>);
      setRepoId(repo.id);
      message.success('仓库已添加');
      setCurrentStep(2);
      // 阶段性保存:刷新父列表,用户在向导内可看到仓库已挂载
      onPartialSave?.();
    } finally {
      setSubmitting(false);
    }
  };

  const handleTestConnection = async () => {
    const values = await repoForm.validateFields(['gitUrl', 'branch', 'username', 'password']);
    try {
      const ok = await testRepositoryConnection(values);
      if (ok) message.success('Git 连接测试成功');
      else message.error('Git 连接测试失败');
    } catch {
      // 拦截器已处理
    }
  };

  /** Step 3: 入口扫描 */
  const handleFillDefaultScan = () => {
    scanForm.setFieldsValue({ entryScanConfig: buildScanConfigWithDefaults(undefined) });
  };

  const handleStep3Submit = async () => {
    const values = await scanForm.validateFields();
    setSubmitting(true);
    try {
      await updateRepository(repoId!, {
        id: repoId!,
        entryScanConfig: values.entryScanConfig ? JSON.stringify(values.entryScanConfig) : null,
      } as Partial<Repository>);
      message.success('入口扫描规则已保存');
      setCurrentStep(3);
      // 阶段性保存:刷新父列表,使状态机推进(SCAN_CONFIGURED)可见
      onPartialSave?.();
    } finally {
      setSubmitting(false);
    }
  };

  /** Step 4:提示词(必须两项都选,不再兜底默认) */
  const handleStep4Submit = async () => {
    if (!repoId) {
      message.error('缺少仓库上下文，无法绑定提示词');
      return;
    }
    const formValues = await promptForm.validateFields();
    // 最终提交值:
    // - pending === undefined → 用户未改,沿用 form 字段(useEffect 自动填的默认)
    // - pending 是 number    → 用户选择的新值
    // - pending 是 null      → 用户主动清空 → 视为未选,阻止提交
    const modularizePromptId =
      pendingModularizeId === undefined ? formValues.modularizePromptId : pendingModularizeId;
    const documentPromptId =
      pendingDocumentId === undefined ? formValues.documentPromptId : pendingDocumentId;
    if (!modularizePromptId || !documentPromptId) {
      message.error('请为模块提取 / 文档生成提示词各选择一个提示词');
      return;
    }
    setSubmitting(true);
    try {
      // 提示词绑定已迁移到仓库级(ci_repository.modularize_prompt_id / document_prompt_id)
      // 调用 updateRepository 而不是 updateSystem,后端会按 Repository 维度落表并推动状态机
      await updateRepository(repoId, {
        id: repoId,
        modularizePromptId,
        documentPromptId,
      } as Partial<Repository>);
      message.success('提示词已绑定，系统进入「已配提示词」状态');
      onCompleted(systemId!);
    } finally {
      setSubmitting(false);
    }
  };

  const handleNext = () => {
    if (currentStep === 0) handleStep1Submit();
    else if (currentStep === 1) handleStep2Submit();
    else if (currentStep === 2) handleStep3Submit();
  };

  const handleCancel = () => {
    if (currentStep > 0 && systemId) {
      Modal.confirm({
        title: '确认关闭？',
        content: '已配置的步骤会保留，下次打开可继续。',
        okText: '关闭',
        cancelText: '继续配置',
        onOk: onClose,
      });
    } else {
      onClose();
    }
  };

  return (
    <Modal
      title="新建系统"
      open={open}
      onCancel={handleCancel}
      footer={null}
      width={780}
      destroyOnHidden
    >
      <Steps
        current={currentStep}
        size="small"
        style={{ marginBottom: 24 }}
        items={[
          { title: '基本信息' },
          { title: '配置仓库' },
          { title: '入口扫描' },
          { title: '提示词' },
        ]}
      />

      {/* Step 1 */}
      {currentStep === 0 && (
        <>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="填写系统基本信息。提交后系统状态将进入「草稿 (DRAFT)」。"
          />
          <Form<SystemFormValues>
            form={systemForm}
            layout="vertical"
            initialValues={{ name: '', owner: '' }}
          >
            <Form.Item name="name" label="系统名称" rules={[{ required: true, message: '请输入系统标识（如 order-service）' }]}>
              <Input placeholder="order-service" />
            </Form.Item>
            <Form.Item name="nameCn" label="中文名称">
              <Input placeholder="订单服务系统" />
            </Form.Item>
            <Form.Item name="owner" label="负责人" rules={[{ required: true, message: '请输入负责人' }]}>
              <Input placeholder="开发负责人" />
            </Form.Item>
            <Form.Item name="description" label="描述">
              <Input.TextArea rows={3} placeholder="核心业务职责和技术范围" />
            </Form.Item>
          </Form>
        </>
      )}

      {/* Step 2 */}
      {currentStep === 1 && (
        <>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="添加第一个 Git 仓库。提交后系统状态将进入「已配仓库 (REPO_CONFIGURED)」。"
          />
          <Form<RepositoryFormValues>
            form={repoForm}
            layout="vertical"
            initialValues={{ branch: 'main', scanRoot: '/' }}
          >
            <Form.Item name="gitUrl" label="Git 地址" rules={[{ required: true, message: '请输入 Git 地址' }]}>
              <Input placeholder="https://github.com/xxx/yyy.git" />
            </Form.Item>
            <Form.Item name="branch" label="分支" rules={[{ required: true, message: '请输入分支' }]}>
              <Input placeholder="main" />
            </Form.Item>
            <Form.Item name="scanRoot" label="扫描根目录" rules={[{ required: true, message: '请输入扫描根目录' }]}>
              <Input placeholder="/" />
            </Form.Item>
            <Form.Item name="username" label="用户名">
              <Input placeholder="可选" />
            </Form.Item>
            <Form.Item name="password" label="密码 / 访问令牌">
              <Input.Password placeholder="可选" />
            </Form.Item>
            <Form.Item name="excludeDirs" label="排除目录">
              <Input placeholder=".git,target,test,bin" />
            </Form.Item>
            <Form.Item name="excludeFileTypes" label="排除文件类型">
              <Input placeholder=".md,.txt,.xml,.json" />
            </Form.Item>
            <Space>
              <Button icon={<SyncOutlined />} onClick={handleTestConnection}>
                测试 Git 连接
              </Button>
            </Space>
          </Form>
        </>
      )}

      {/* Step 3 */}
      {currentStep === 2 && (
        <>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="配置入口扫描规则。提交后系统状态将进入「已配扫描 (SCAN_CONFIGURED)」。"
          />
          <Form<{ entryScanConfig: EntryScanConfig }>
            form={scanForm}
            layout="vertical"
            initialValues={{ entryScanConfig: DEFAULT_SCAN_VALUES }}
          >
            <Space style={{ marginBottom: 12 }}>
              <Button icon={<SyncOutlined />} onClick={handleFillDefaultScan}>
                重置
              </Button>
              <Button
                icon={<PlayCircleOutlined />}
                disabled={!repoId || !systemId}
                onClick={() => setScanTrialOpen(true)}
              >
                试跑
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
        </>
      )}

      {/* Step 4 */}
      {currentStep === 3 && (
        <>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={
              <Space direction="vertical" size={4}>
                <Text>系统绑定模块提取 / 文档生成提示词，初始默认绑定到「全局默认提示词」。</Text>
                <Text type="secondary">
                  点击「自定义」可基于全局默认提示词复制修改并保存为该系统专属的提示词(自动以「系统名 - 类型 - 时间戳」命名)。
                </Text>
              </Space>
            }
          />

          {/* 模块提取提示词 */}
          <Card
            size="small"
            style={{ marginBottom: 12 }}
            title={
              <Space>
                <span>{DEFAULT_PROMPTS.MODULARIZE.label}</span>
                {selectedModularize && (
                  <Tag color="green">
                    {selectedModularize.name} (v{selectedModularize.version})
                    {selectedModularize.isDefault === 1 ? ' · 默认' : ' · 自定义'}
                  </Tag>
                )}
              </Space>
            }
            extra={
              <Space size={4} wrap>
                {/* 用 Form.Item 包裹使字段被注册,validateFields 才能拿到值 */}
                <Form.Item name="modularizePromptId" noStyle>
                  <Select
                    /* key 跟随 pending 变化,新建自定义提示词后强制重渲染,避免 Ant Design Select 缓存的高亮遗漏 */
                    key={`mod:${pendingModularizeId ?? 'x'}`}
                    showSearch
                    optionFilterProp="label"
                    placeholder="选择已有提示词"
                    style={{ width: 320 }}
                    value={pendingModularizeId ?? undefined}
                    onChange={(id) => handleSelectExisting('MODULARIZE', id)}
                    options={modularizeOptions}
                    loading={promptsLoading}
                    allowClear
                  />
                </Form.Item>
                <Button
                  icon={<PlusOutlined />}
                  onClick={() => openCustomPrompt('MODULARIZE')}
                >
                  自定义
                </Button>
                {selectedModularize && (
                  <Button
                    size="small"
                    type="link"
                    onClick={() => openTrial(selectedModularize)}
                  >
                    试跑
                  </Button>
                )}
              </Space>
            }
          >
            {!selectedModularize && (
              <Text type="secondary">未选择。点击右上角「自定义」创建该系统专属的提示词。</Text>
            )}
          </Card>

          {/* 文档生成提示词 */}
          <Card
            size="small"
            style={{ marginBottom: 12 }}
            title={
              <Space>
                <span>{DEFAULT_PROMPTS.DOCUMENT_GENERATION.label}</span>
                {selectedDocument && (
                  <Tag color="green">
                    {selectedDocument.name} (v{selectedDocument.version})
                    {selectedDocument.isDefault === 1 ? ' · 默认' : ' · 自定义'}
                  </Tag>
                )}
              </Space>
            }
            extra={
              <Space size={4} wrap>
                {/* 用 Form.Item 包裹使字段被注册,validateFields 才能拿到值 */}
                <Form.Item name="documentPromptId" noStyle>
                  <Select
                    /* key 跟随 pending 变化,新建自定义提示词后强制重渲染 */
                    key={`doc:${pendingDocumentId ?? 'x'}`}
                    showSearch
                    optionFilterProp="label"
                    placeholder="选择已有提示词"
                    style={{ width: 320 }}
                    value={pendingDocumentId ?? undefined}
                    onChange={(id) => handleSelectExisting('DOCUMENT_GENERATION', id)}
                    options={documentOptions}
                    loading={promptsLoading}
                    allowClear
                  />
                </Form.Item>
                <Button
                  icon={<PlusOutlined />}
                  onClick={() => openCustomPrompt('DOCUMENT_GENERATION')}
                >
                  自定义
                </Button>
                {selectedDocument && (
                  <Button
                    size="small"
                    type="link"
                    onClick={() => openTrial(selectedDocument)}
                  >
                    试跑
                  </Button>
                )}
              </Space>
            }
          >
            {!selectedDocument && (
              <Text type="secondary">未选择。请从右上角的下拉框选择已有提示词，或「自定义 / 复制默认后修改」创建新提示词。</Text>
            )}
          </Card>

          <Paragraph type="secondary" style={{ marginTop: 8 }}>
            提交后系统可立即在系统列表查看与使用。系统级启停状态已下线，无需额外启用。
          </Paragraph>
        </>
      )}

      <div
        style={{
          marginTop: 24,
          display: 'flex',
          justifyContent: 'space-between',
          borderTop: '1px solid #f0f0f0',
          paddingTop: 16,
        }}
      >
        <Button
          icon={<ArrowLeftOutlined />}
          disabled={currentStep === 0}
          onClick={() => setCurrentStep((s) => s - 1)}
        >
          上一步
        </Button>
        {currentStep < 3 ? (
          <Button type="primary" loading={submitting} onClick={handleNext} icon={<ArrowRightOutlined />}>
            下一步
          </Button>
        ) : (
          <Button
            type="primary"
            loading={submitting}
            onClick={handleStep4Submit}
            icon={<CheckCircleOutlined />}
          >
            完成配置
          </Button>
        )}
      </div>

      {/* Step 4:提示词编辑器弹窗(自定义 / 复制默认后修改) */}
      {editorState && (
        <SystemPromptEditorModal
          open={editorState.open}
          mode={editorState.mode}
          sourcePrompt={
            editorState.mode === 'clone-default'
              ? editorState.promptType === 'MODULARIZE'
                ? defaultModularize
                : defaultDocument
              : null
          }
          defaultPrompt={
            editorState.promptType === 'MODULARIZE' ? defaultModularize : defaultDocument
          }
          systemName={systemName}
          scopeId={repoId}
          promptType={editorState.promptType}
          promptTypeLabel={
            editorState.promptType === 'MODULARIZE'
              ? DEFAULT_PROMPTS.MODULARIZE.label
              : DEFAULT_PROMPTS.DOCUMENT_GENERATION.label
          }
          onClose={() => setEditorState(null)}
          onCreated={handlePromptCreated}
        />
      )}

      {/* 试跑子弹窗(对已绑定 prompt) */}
      {trialPrompt && (
        <PromptTrialModal
          open={trialOpen}
          prompt={trialPrompt}
          promptTypeLabel={
            trialPrompt.promptType === 'MODULARIZE'
              ? DEFAULT_PROMPTS.MODULARIZE.label
              : DEFAULT_PROMPTS.DOCUMENT_GENERATION.label
          }
          onClose={() => setTrialOpen(false)}
          onRun={handleTrialRun}
        />
      )}

      <RepositoryScanConfigModal
        open={scanTrialOpen}
        trialOnly
        form={scanForm}
        repoId={repoId ?? undefined}
        systemId={systemId ?? undefined}
        onCancel={() => setScanTrialOpen(false)}
        onSubmit={() => setScanTrialOpen(false)}
      />
    </Modal>
  );
};

export default SystemWizardModal;
