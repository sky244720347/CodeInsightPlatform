import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  Form,
  Modal,
  Row,
  Select,
  Space,
  Steps,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  ArrowLeftOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  createIncrementalTask,
  createInitialTask,
  getRepositoryReadiness,
  type RepositoryReadiness,
} from '../../api/task';
import { listPrompts } from '../../api/prompt';
import { listRepositories, getRepository } from '../../api/repository';
import { listSystems } from '../../api/system';
import {
  filterSystemSelectOption,
  renderSystemSelectLabel,
  renderSystemSelectOption,
  toSystemSelectOptions,
} from '../../utils/systemSelect';
import { listModels } from '../../api/model';
import {
  buildScanConfigWithDefaults,
  parseRepoEntryScanConfig,
} from '../systems/repositoryUtils';
import EntryScanConfigEditor from '../../components/EntryScanConfigEditor';
import type {
  AiModel,
  EntryScanConfig,
  Prompt,
  Repository,
  System,
} from '../../types';

const { Text } = Typography;

/**
 * 「手动下发」全屏页签
 *
 *  - 步骤 0：选择系统 + 代码库
 *  - 步骤 1：选择任务类型、AI 模型、扫描规则、是否启用入口复核 / 模块层级复核
 *          （提示词由系统级绑定 + 默认提示词提供，任务不再单独选择）
 *  - 步骤 2：确认 + 提交
 *
 * 通过 ?systemId=&repositoryId= 预填来源（来自系统页"立即扫描"按钮）。
 *
 * 后端约束：系统必须处于 ACTIVE 状态；提示词由后端按
 *   任务显式 ID → 系统绑定 → 默认提示词 的顺序自动解析。
 */
const TaskDispatchPage: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [form] = Form.useForm();

  const [currentStep, setCurrentStep] = useState(0);
  const [submitting, setSubmitting] = useState(false);

  // 下拉选项
  const [systems, setSystems] = useState<System[]>([]);
  const [taskSourceSystems, setTaskSourceSystems] = useState<System[]>([]);
  const [repositories, setRepositories] = useState<Repository[]>([]);
  /** 用于只读展示系统绑定的提示词（按需懒加载） */
  const [modularizeById, setModularizeById] = useState<Record<number, Prompt>>({});
  const [documentById, setDocumentById] = useState<Record<number, Prompt>>({});
  const [models, setModels] = useState<AiModel[]>([]);

  // 就绪度拦截
  const [readiness, setReadiness] = useState<RepositoryReadiness | null>(null);
  const [readinessLoading, setReadinessLoading] = useState(false);
  const [readinessModalOpen, setReadinessModalOpen] = useState(false);

  const selectedSystemId = Form.useWatch('systemId', form);
  const selectedRepositoryId = Form.useWatch('repositoryId', form);

  /** 从仓库详情拉取 entryScanConfig 并写入表单（避免列表接口/加载时序导致未带出） */
  const applyRepositoryScanConfig = useCallback(
    async (repositoryId?: number) => {
      if (!repositoryId) {
        form.setFieldsValue({ entryScanConfig: buildScanConfigWithDefaults(undefined) });
        return;
      }
      try {
        const repo = await getRepository(repositoryId);
        form.setFieldsValue({ entryScanConfig: parseRepoEntryScanConfig(repo) });
      } catch {
        form.setFieldsValue({ entryScanConfig: buildScanConfigWithDefaults(undefined) });
      }
    },
    [form],
  );

  // 选中的系统对象（用于只读展示绑定的提示词）
  const selectedSystem: System | undefined = systems.find((s) => s.id === selectedSystemId);
  const selectedRepository: Repository | undefined = repositories.find((r) => r.id === selectedRepositoryId);

  /** 把 system.modularizePromptId / documentPromptId 转成名称展示 */
  const resolveBoundName = (
    boundId: number | null | undefined,
    map: Record<number, Prompt>,
  ): { name: string; isBound: boolean; prompt?: Prompt } => {
    if (boundId == null) return { name: '未绑定', isBound: false };
    const p = map[boundId];
    if (p) return { name: `${p.name} (v${p.version})`, isBound: true, prompt: p };
    return { name: `#${boundId}（详情待加载）`, isBound: true };
  };

  // 拉系统/模型列表
  const loadOptions = useCallback(async () => {
    try {
      const [sysData, repositoryData, modelData] = await Promise.all([
        listSystems({ current: 1, size: 100 }),
        listRepositories({ current: 1, size: 1000 }),
        listModels(),
      ]);
      const configuredSystemIds = new Set(repositoryData.records.map((r) => r.systemId));
      setSystems(sysData.records);
      setTaskSourceSystems(sysData.records.filter((s) => configuredSystemIds.has(s.id)));
      setModels(modelData);
    } catch {
      // 拦截器已提示
    }
  }, []);

  // 选系统变化时，按需懒加载系统绑定的提示词
  useEffect(() => {
    if (!selectedRepositoryId) return;
    const repo = repositories.find((r) => r.id === selectedRepositoryId);
    if (!repo) return;
    const needModularize = repo.modularizePromptId;
    const needDocument = repo.documentPromptId;
    const fetchOne = async (type: 'MODULARIZE' | 'DOCUMENT_GENERATION', id?: number | null) => {
      if (id == null) return null;
      const all = await listPrompts({ current: 1, size: 200, lifecycle: 'RELEASED', promptType: type, isDefault: 1 });
      return all.records.find((p) => p.id === id) || null;
    };
    if (needModularize != null) {
      fetchOne('MODULARIZE', needModularize).then((p) => {
        if (!p) return;
        setModularizeById((m) => (m[p.id] ? m : { ...m, [p.id]: p }));
      });
    }
    if (needDocument != null) {
      fetchOne('DOCUMENT_GENERATION', needDocument).then((p) => {
        if (!p) return;
        setDocumentById((m) => (m[p.id] ? m : { ...m, [p.id]: p }));
      });
    }
  }, [selectedSystemId, systems]);

  const refreshReadiness = useCallback(async (systemId?: number, repositoryId?: number) => {
    setReadinessLoading(true);
    try {
      return await getRepositoryReadiness({ systemId, repositoryId });
    } catch {
      return null;
    } finally {
      setReadinessLoading(false);
    }
  }, []);

  const ensureReadinessOrBlock = useCallback(
    async (systemId?: number, repositoryId?: number): Promise<boolean> => {
      const data = await refreshReadiness(systemId, repositoryId);
      if (data) setReadiness(data);
      if (data && !data.ready) {
        setReadinessModalOpen(true);
        return false;
      }
      return true;
    },
    [refreshReadiness],
  );

  // 预填：?systemId=&repositoryId=
  useEffect(() => {
    const sysId = Number(searchParams.get('systemId'));
    const repoId = Number(searchParams.get('repositoryId'));
    if (Number.isFinite(sysId) && sysId > 0) {
      form.setFieldsValue({ systemId: sysId });
    }
    if (Number.isFinite(repoId) && repoId > 0) {
      form.setFieldsValue({ repositoryId: repoId });
    }
    if (searchParams.get('systemId') || searchParams.get('repositoryId')) {
      const next = new URLSearchParams(searchParams);
      next.delete('systemId');
      next.delete('repositoryId');
      setSearchParams(next, { replace: true });
    }
  }, []);

  useEffect(() => {
    loadOptions();
  }, [loadOptions]);

  // 模型列表加载后，若未选模型则默认选中 isDefault 项
  useEffect(() => {
    if (models.length === 0) return;
    const current = form.getFieldValue('modelName') as string | undefined;
    if (current) return;
    const defaultModel = models.find((m) => m.isDefault === 'true')?.identifier;
    if (defaultModel) {
      form.setFieldValue('modelName', defaultModel);
    }
  }, [form, models]);

  // 系统变化 → 拉取仓库
  useEffect(() => {
    if (!selectedSystemId) {
      setRepositories([]);
      return;
    }
    listRepositories({ current: 1, size: 50, systemId: selectedSystemId }).then(async (data) => {
      setRepositories(data.records);
      const currentRepoId = form.getFieldValue('repositoryId') as number | undefined;
      if (currentRepoId && !data.records.some((r) => r.id === currentRepoId)) {
        form.setFieldValue('repositoryId', undefined);
        form.setFieldsValue({ entryScanConfig: buildScanConfigWithDefaults(undefined) });
      } else if (currentRepoId) {
        await applyRepositoryScanConfig(currentRepoId);
      }
    });
  }, [applyRepositoryScanConfig, form, selectedSystemId]);

  // 仓库变化 → 拉取仓库详情并初始化 entryScanConfig
  useEffect(() => {
    applyRepositoryScanConfig(selectedRepositoryId);
  }, [applyRepositoryScanConfig, selectedRepositoryId]);

  const handleNext = async () => {
    if (currentStep === 0) {
      const vals = await form.validateFields(['systemId', 'repositoryId']);
      if (!(await ensureReadinessOrBlock(vals.systemId, vals.repositoryId))) return;
      await applyRepositoryScanConfig(vals.repositoryId);
    } else if (currentStep === 1) {
      await form.validateFields(['taskType', 'modelName']);
    }
    setCurrentStep((s) => s + 1);
  };

  const handleSubmit = async () => {
    const vals = form.getFieldsValue(['systemId', 'repositoryId']);
    if (!(await ensureReadinessOrBlock(vals.systemId, vals.repositoryId))) return;

    setSubmitting(true);
    try {
      // 用 getFieldValue 逐个取字段值（避免 display:none 时 getFieldsValue 漏字段）
      const systemId = form.getFieldValue('systemId') as number;
      const repositoryId = form.getFieldValue('repositoryId') as number;
      const modelName = form.getFieldValue('modelName') as string | undefined;
      const taskType = form.getFieldValue('taskType') as string | undefined;
      const entryScanConfig = form.getFieldValue('entryScanConfig') as EntryScanConfig | undefined;
      const requireEntrypointReview = form.getFieldValue('requireEntrypointReview') as boolean | undefined;
      const requireHierarchyReview = form.getFieldValue('requireHierarchyReview') as boolean | undefined;
      const requireKnowledgeReview = form.getFieldValue('requireKnowledgeReview') as boolean | undefined;

      const payload = {
        systemId,
        repositoryId,
        modelName,
        entryScanConfig,
        requireEntrypointReview: requireEntrypointReview === true,
        requireHierarchyReview: requireHierarchyReview === true,
        requireKnowledgeReview: requireKnowledgeReview === true,
      };
      if ((taskType || 'INITIAL') === 'INITIAL') {
        await createInitialTask(payload);
      } else {
        await createIncrementalTask(payload);
      }
      message.success(
        `任务已创建并自动启动（入口复核：${payload.requireEntrypointReview ? '启用' : '跳过'}；模块层级：${payload.requireHierarchyReview ? '启用' : '跳过'}；知识复核：${payload.requireKnowledgeReview ? '启用' : '跳过'}）`,
      );
      navigate('/tasks/query');
    } finally {
      setSubmitting(false);
    }
  };

  const handleSyncRepoScanConfig = async () => {
    const repositoryId =
      selectedRepositoryId ?? (form.getFieldValue('repositoryId') as number | undefined);
    if (!repositoryId) {
      message.warning('请先选择仓库');
      return;
    }
    await applyRepositoryScanConfig(repositoryId);
    message.success('已恢复为仓库默认配置');
  };

  // 摘要：第三步确认
  const summarySystem = systems.find((s) => s.id === form.getFieldValue('systemId'))?.name;
  const summaryRepo = repositories.find((r) => r.id === form.getFieldValue('repositoryId'))?.gitUrl;
  const summaryTaskType = form.getFieldValue('taskType');
  const summaryModel = models.find((m) => m.identifier === form.getFieldValue('modelName'));
  const summaryScan = form.getFieldValue('entryScanConfig') as
    | (EntryScanConfig & Record<string, unknown>)
    | undefined;
  const summaryRequireEntry = form.getFieldValue('requireEntrypointReview');
  const summaryRequireHierarchy = form.getFieldValue('requireHierarchyReview');
  const summaryRequireKnowledge = form.getFieldValue('requireKnowledgeReview');

  const modularizeDisplay = selectedSystem
    ? resolveBoundName(selectedRepository?.modularizePromptId ?? undefined, modularizeById)
    : { name: '请先选择系统', isBound: false };
  const documentDisplay = selectedSystem
    ? resolveBoundName(selectedRepository?.documentPromptId ?? undefined, documentById)
    : { name: '请先选择系统', isBound: false };
  const promptsBlocking = !modularizeDisplay.isBound || !documentDisplay.isBound;

  return (
    <div className="ci-page ci-task-dispatch-page">
      <Card
        title={
          <Space>
            <Button
              type="text"
              icon={<ArrowLeftOutlined />}
              onClick={() => navigate('/tasks/query')}
            >
              返回任务查询
            </Button>
            <span style={{ fontWeight: 600 }}>手动下发：创建知识构建任务</span>
          </Space>
        }
      >
        <Steps
          current={currentStep}
          size="small"
          items={[{ title: '来源' }, { title: '策略' }, { title: '确认' }]}
          style={{ marginBottom: 24 }}
        />

        <Form
          form={form}
          layout="vertical"
          preserve
          initialValues={{
            taskType: 'INITIAL',
            entryScanConfig: buildScanConfigWithDefaults(undefined),
            requireEntrypointReview: false,
            requireHierarchyReview: false,
            requireKnowledgeReview: false,
          }}
        >
          <Row gutter={[16, 16]} style={{ display: currentStep === 0 ? undefined : 'none' }}>
              <Col xs={24} md={12}>
                <Form.Item
                  name="systemId"
                  label="系统"
                  rules={[{ required: true, message: '请选择系统' }]}
                >
                  <Select
                    placeholder="请选择已配置代码库的系统"
                    showSearch
                    filterOption={filterSystemSelectOption}
                    optionRender={renderSystemSelectOption}
                    labelRender={(props) => renderSystemSelectLabel(props, taskSourceSystems)}
                    notFoundContent="暂无已配置代码库的系统"
                    options={toSystemSelectOptions(taskSourceSystems)}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item
                  name="repositoryId"
                  label="代码库"
                  rules={[{ required: true, message: '请选择代码库' }]}
                >
                  <Select
                    placeholder="请选择 Git 代码库"
                    disabled={!selectedSystemId}
                    notFoundContent={selectedSystemId ? '该系统尚未配置代码库' : '请先选择系统'}
                    options={repositories.map((repo) => ({
                      value: repo.id,
                      label: `${repo.gitUrl} (${repo.branch})`,
                    }))}
                  />
                </Form.Item>
              </Col>
          </Row>

          {currentStep === 1 && (
            <>
              {/* 系统绑定的提示词：只读展示 */}
              <Card size="small" style={{ marginBottom: 16, background: '#fafafa' }}>
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    任务仅使用当前系统在「系统与仓库」中绑定的提示词，未绑定将无法创建或启动任务。
                  </Text>
                  {promptsBlocking && (
                    <Alert
                      type="error"
                      showIcon
                      message="当前系统尚未完整绑定提示词"
                      description="请前往「系统与仓库」，在对应系统行点击「提示词」完成模块提取与文档生成的绑定后再下发任务。"
                    />
                  )}
                  <Space wrap>
                    <Tooltip title="AI_ANALYZING / MODULE_HIERARCHY 阶段使用">
                      <Tag color={modularizeDisplay.isBound ? 'geekblue' : 'default'} icon={modularizeDisplay.isBound ? '✓' : undefined}>
                        模块提取：{modularizeDisplay.name}
                      </Tag>
                    </Tooltip>
                    <Tooltip title="GENERATING_DOC 阶段使用">
                      <Tag color={documentDisplay.isBound ? 'geekblue' : 'default'} icon={documentDisplay.isBound ? '✓' : undefined}>
                        文档生成：{documentDisplay.name}
                      </Tag>
                    </Tooltip>
                    {selectedSystemId ? (
                      <Button
                        type="link"
                        size="small"
                        onClick={() => {
                          const params = new URLSearchParams();
                          params.set('systemId', String(selectedSystemId));
                          if (selectedRepositoryId) params.set('repositoryId', String(selectedRepositoryId));
                          params.set('action', 'prompts');
                          navigate(`/systems?${params.toString()}`);
                        }}
                      >
                        前往「系统与仓库」绑定提示词
                      </Button>
                    ) : null}
                  </Space>
                </Space>
              </Card>

              <Row gutter={[16, 16]}>
                <Col xs={24} md={12}>
                  <Form.Item
                    name="taskType"
                    label="任务类型"
                    rules={[{ required: true }]}
                  >
                    <Select
                      options={[
                        { value: 'INITIAL', label: '全量扫描 - 分析整个代码库' },
                        { value: 'INCREMENTAL', label: '增量扫描 - 基于 Git Diff 分析' },
                      ]}
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item
                    name="modelName"
                    label="AI模型"
                    rules={[{ required: true, message: '请选择AI模型' }]}
                  >
                    <Select
                      placeholder="请选择要调用的 AI 模型"
                      options={models.map((m) => ({
                        value: m.identifier,
                        label: `${m.name} (${m.identifier})`,
                      }))}
                    />
                  </Form.Item>
                </Col>
              </Row>

              <div className="ci-scan-config">
                <div
                  className="ci-scan-config-title"
                  style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}
                >
                  <div>
                    <Text type="secondary" style={{ fontSize: 12 }}>扫描规则（任务快照）</Text>
                    <Tooltip title="选中仓库时会自动继承仓库配置；此处修改仅写入本任务快照，不影响仓库。复核阶段追加的排除也写入任务快照。">
                      <ExclamationCircleOutlined style={{ color: '#faad14', marginLeft: 6, fontSize: 12 }} />
                    </Tooltip>
                  </div>
                  <Tooltip title="重新从仓库拉取并覆盖表单（尚未提交前）">
                    <Button
                      size="small"
                      icon={<SyncOutlined />}
                      onClick={handleSyncRepoScanConfig}
                      disabled={!selectedRepositoryId}
                    >
                      重置
                    </Button>
                  </Tooltip>
                </div>
                <EntryScanConfigEditor />
              </div>

              <Alert
                type="info"
                showIcon
                style={{ marginTop: 12 }}
                message="人工复核断点"
                description="启用后流水线会在对应阶段暂停。三个开关默认均为跳过；任务创建后自动启动，知识复核跳过时将自动建版并 NAS 推送。"
              />

              <Form.Item
                name="requireEntrypointReview"
                label="入口复核"
                tooltip="启用后，代码切片完成会停在「入口复核」断点，需在「入口复核」页面确认入口类清单后才继续 AI 分析。"
                valuePropName="checked"
                style={{ marginTop: 12, marginBottom: 0 }}
              >
                <Switch checkedChildren="启用" unCheckedChildren="跳过" />
              </Form.Item>

              <Form.Item
                name="requireHierarchyReview"
                label="模块层级复核"
                tooltip="启用后，AI 提炼模块层级完成会停在「模块层级复核」断点，需在复核页面确认后才继续生成文档。"
                valuePropName="checked"
                style={{ marginTop: 12, marginBottom: 0 }}
              >
                <Switch checkedChildren="启用" unCheckedChildren="跳过" />
              </Form.Item>

              <Form.Item
                name="requireKnowledgeReview"
                label="知识复核"
                tooltip="启用后，文档生成完成会停在「知识复核」；跳过时自动确认、按 v1/v2/v3 建版并 NAS 推送。"
                valuePropName="checked"
                style={{ marginTop: 12, marginBottom: 0 }}
              >
                <Switch checkedChildren="启用" unCheckedChildren="跳过" />
              </Form.Item>
            </>
          )}

          {currentStep === 2 && (
            <div className="ci-confirm-box">
              <p>系统：<b>{summarySystem ?? '未选择'}</b></p>
              <p>代码库：<b>{summaryRepo ?? '未选择'}</b></p>
              <p>
                策略：<b>{summaryTaskType === 'INITIAL' ? '全量扫描' : summaryTaskType === 'INCREMENTAL' ? '增量扫描' : '未选择'}</b>
              </p>
              <p>
                模块提取提示词：<b>{modularizeDisplay.name}</b>
              </p>
              <p>
                文档生成提示词：<b>{documentDisplay.name}</b>
              </p>
              <p>
                AI模型：<b>{summaryModel?.name || form.getFieldValue('modelName') || '未选择'}</b>
              </p>
              <p>
                扫描规则：
                <b>
                  {(() => {
                    const c = (summaryScan ?? buildScanConfigWithDefaults(undefined)) as EntryScanConfig;
                    const typeCounts = Object.entries(c.includesByType ?? {})
                      .map(([k, v]) => {
                        const n =
                          (v.includeAnnotations?.length ?? 0)
                          + (v.includeClasspaths?.length ?? 0)
                          + (v.includeExtends?.length ?? 0);
                        return n > 0 ? `${k} ${n} 条规则` : null;
                      })
                      .filter(Boolean);
                    const ex = (c.excludeTargets?.length ?? 0)
                      + (c.excludeClasspaths?.length ?? 0)
                      + (c.excludePackages?.length ?? 0)
                      + (c.excludeAnnotations?.length ?? 0);
                    return `${typeCounts.join('，') || '默认四类预置'}；排除 ${ex} 条`;
                  })()}
                </b>
              </p>
              <p>
                入口复核：
                <b>
                  {summaryRequireEntry === true
                    ? '启用（切片后暂停，需人工复核后继续）'
                    : '跳过（切片后直接进入 AI 分析）'}
                </b>
              </p>
              <p>
                模块层级复核：
                <b>
                  {summaryRequireHierarchy === true
                    ? '启用（AI 提炼后暂停，需人工复核后继续）'
                    : '跳过（AI 提炼后直接生成文档）'}
                </b>
              </p>
              <p>
                知识复核：
                <b>
                  {summaryRequireKnowledge === true
                    ? '启用（文档生成后暂停，人工确认后再自动建版推送）'
                    : '跳过（自动确认、建版 vN 并 NAS 推送）'}
                </b>
              </p>
              <Text type="secondary">创建后将自动启动任务，无需再点「启动」。</Text>
            </div>
          )}
        </Form>

        <div
          style={{
            marginTop: 24,
            display: 'flex',
            justifyContent: 'flex-end',
            borderTop: '1px solid #f0f0f0',
            paddingTop: 16,
          }}
        >
          <Space>
            {currentStep > 0 && <Button onClick={() => setCurrentStep((s) => s - 1)}>上一步</Button>}
            {currentStep < 2 ? (
              <Button type="primary" loading={readinessLoading} onClick={handleNext}>
                下一步
              </Button>
            ) : (
              <Button type="primary" loading={submitting} onClick={handleSubmit}>
                创建任务
              </Button>
            )}
          </Space>
        </div>
      </Card>

      {/* 就绪度拦截弹窗 */}
      <Modal
        title={
          <Space>
            <CloseCircleOutlined style={{ color: '#ff4d4f' }} />
            <span>
              {readiness?.promptsConfigured === false
                ? '无法新建任务：仓库未绑定提示词'
                : '无法新建任务：尚有未确认的草稿'}
            </span>
          </Space>
        }
        open={readinessModalOpen}
        onCancel={() => setReadinessModalOpen(false)}
        width={720}
        destroyOnClose
        footer={
          <Space>
                <Button onClick={() => setReadinessModalOpen(false)}>关闭</Button>
                {readiness?.promptsConfigured === false && (
                  <Button
                    type="primary"
                    onClick={() => {
                      setReadinessModalOpen(false);
                      const sid = form.getFieldValue('systemId') as number | undefined;
                      const rid = form.getFieldValue('repositoryId') as number | undefined;
                      if (sid) {
                        const params = new URLSearchParams();
                        params.set('systemId', String(sid));
                        if (rid) params.set('repositoryId', String(rid));
                        params.set('action', 'prompts');
                        navigate(`/systems?${params.toString()}`);
                      } else {
                        navigate('/systems');
                      }
                    }}
                  >
                    前往绑定提示词
                  </Button>
                )}
                {(readiness?.unconfirmedCount ?? 0) > 0 && (
                  <Button
                    type={readiness?.promptsConfigured === false ? 'default' : 'primary'}
                    onClick={() => {
                      setReadinessModalOpen(false);
                      navigate('/drafts');
                    }}
                  >
                    前往复核工作区
                  </Button>
                )}
              </Space>
        }
      >
        {readiness?.promptsConfigured === false && (
              <Alert
                type="error"
                showIcon
                message="系统提示词未配置"
                description={
                  readiness.promptsMessage ??
                  '请前往「系统与仓库」，在对应系统行点击「提示词」完成模块提取与文档生成的绑定。'
                }
                style={{ marginBottom: 12 }}
              />
            )}
            {(readiness?.unconfirmedCount ?? 0) > 0 && (
              <>
                <Alert
                  type="error"
                  showIcon
                  message={
                    <Space size={6}>
                      <Text strong>检测到 {readiness?.unconfirmedCount ?? 0} 个待处理草稿</Text>
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        (DRAFT / EDITING)
                      </Text>
                    </Space>
                  }
                  description={
                    <Text>
                      当前系统和代码库下仍有未确认草稿。请先前往复核工作区完成确认，然后再回到这里新建任务。
                    </Text>
                  }
                  style={{ marginBottom: 12 }}
                />
                <Table
                  size="small"
                  pagination={false}
                  rowKey="draftId"
                  dataSource={readiness?.blockingDrafts ?? []}
                  columns={[
                    { title: '模块', dataIndex: 'moduleName', key: 'moduleName', ellipsis: true },
                    {
                      title: '状态',
                      dataIndex: 'status',
                      key: 'status',
                      width: 110,
                      render: (s: string) => {
                        const meta: Record<string, { color: string; label: string }> = {
                          DRAFT: { color: 'magenta', label: '待处理' },
                          EDITING: { color: 'geekblue', label: '已编辑' },
                          REJECTED: { color: 'red', label: '已驳回' },
                        };
                        const m = meta[s] ?? { color: 'default', label: s };
                        return <Tag color={m.color}>{m.label}</Tag>;
                      },
                    },
                    {
                      title: '所属任务',
                      dataIndex: 'taskId',
                      key: 'taskId',
                      width: 100,
                      render: (taskId?: number) => (taskId ? <Text code>#{taskId}</Text> : '-'),
                    },
                    {
                      title: '更新时间',
                      dataIndex: 'updatedDate',
                      key: 'updatedDate',
                      width: 170,
                      render: (t: string) => (t ? new Date(t).toLocaleString() : '-'),
                    },
                  ]}
                />
              </>
            )}
      </Modal>

      <Alert
        type="info"
        showIcon
        style={{ marginTop: 16 }}
        message="小贴士：请先在「系统与仓库」为系统绑定模块提取与文档生成提示词；创建后请到「任务查询」中点击「启动」按钮开始执行。"
      />
    </div>
  );
};

export default TaskDispatchPage;
