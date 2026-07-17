import React, { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Form,
  Input,
  Modal,
  Space,
  Typography,
  message,
} from 'antd';
import {
  PlayCircleOutlined,
  PlusOutlined,
  SnippetsOutlined,
} from '@ant-design/icons';
import { createPrompt, testRunPrompt } from '../../api/prompt';
import type { Prompt } from '../../types';
import PromptTrialModal from '../../components/PromptTrialModal';

const { Text } = Typography;

export type PromptEditorMode = 'custom' | 'clone-default';

interface Props {
  open: boolean;
  mode: PromptEditorMode;
  /** 用于「复制默认后修改」时回填内容（必传） */
  sourcePrompt?: Prompt | null;
  /** 全局默认提示词（用于「从默认提示词加载」按钮） */
  defaultPrompt?: Prompt | null;
  /** 默认系统名称前缀,自动生成 name 建议 */
  systemName?: string;
  /** 提示词类型,用于自动生成 name 标签 */
  promptType: 'MODULARIZE' | 'DOCUMENT_GENERATION';
  /** 类型中文标签,例如「模块提取 / 文档生成」 */
  promptTypeLabel: string;
  /** USER 提示词 scope_id（系统ID）；创建时作为 scopeId 传入 — null=全局 DEFAULT */
  scopeId?: number | null;
  /** 关闭 */
  onClose: () => void;
  /** 创建成功后回调,返回新 prompt 的完整对象 */
  onCreated: (prompt: Prompt) => void;
}

const TYPE_NAME: Record<'MODULARIZE' | 'DOCUMENT_GENERATION', string> = {
  MODULARIZE: '模块提取',
  DOCUMENT_GENERATION: '文档生成',
};

/** 生成自定义提示词默认名称:{类型} - {yyyyMMdd-HHmmss}（不带仓库/系统标识） */
const buildDefaultName = (typeLabel: string) => {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  const ts = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
  return `${typeLabel} - ${ts}`;
};

/**
 * 「新建系统 → 提示词」步骤中,创建自定义提示词的弹窗
 * - mode='custom':空白表单
 * - mode='clone-default':预填默认提示词内容,用户可在其基础上修改
 *
 * 提交后通过 onCreated 把新 prompt 回传给父组件,父组件将 prompt.id 绑定到系统。
 * 集成「试跑」能力,用户可边编辑边试跑校验效果。
 */
const SystemPromptEditorModal: React.FC<Props> = ({
  open,
  mode,
  sourcePrompt,
  defaultPrompt,
  systemName,
  scopeId,
  promptType,
  promptTypeLabel,
  onClose,
  onCreated,
}) => {
  const [form] = Form.useForm<{ name: string; content: string }>();
  const [submitting, setSubmitting] = useState(false);
  const [trialOpen, setTrialOpen] = useState(false);
  const [trialPreview, setTrialPreview] = useState<Prompt | null>(null);

  // 弹窗打开时,根据 mode 初始化表单
  useEffect(() => {
    if (!open) return;
    const defaultName = buildDefaultName(TYPE_NAME[promptType]);
    const initialContent =
      mode === 'clone-default' && sourcePrompt ? sourcePrompt.content ?? '' : '';
    form.setFieldsValue({ name: defaultName, content: initialContent });
  }, [open, mode, sourcePrompt, systemName, promptType, form]);

  const currentContent: string = Form.useWatch('content', form) ?? '';
  const currentName: string = Form.useWatch('name', form) ?? '';

  /** 打开试跑:用当前表单内容(可能未保存)作为 preview prompt */
  const handleOpenTrial = () => {
    const preview: Prompt = {
      id: 0,
      name: currentName,
      content: currentContent,
      version: 1,
      isDefault: 0,
      promptType,
      lifecycle: 'DRAFT',
      createdDate: new Date().toISOString(),
      updatedDate: new Date().toISOString(),
    };
    setTrialPreview(preview);
    setTrialOpen(true);
  };

  /** 「从默认提示词加载」:把默认提示词的内容填到当前 content 字段 */
  const handleLoadFromDefault = () => {
    if (!defaultPrompt?.content) {
      message.warning('当前类型暂无默认提示词');
      return;
    }
    form.setFieldValue('content', defaultPrompt.content);
    message.success('已加载默认提示词内容到编辑区');
  };

  /** 提交:创建新提示词（Wizard 场景下直接 RELEASED,无需手动发布） */
  const handleSubmit = async () => {
    const values = await form.validateFields();
    if (!values.content?.trim()) {
      message.error('请输入提示词内容');
      return;
    }
    setSubmitting(true);
    try {
      const created = await createPrompt({
        name: values.name,
        content: values.content,
        promptType,
        // 自定义提示词在 Wizard 里需要「创建即可用」,不强制写死 DRAFT,走后端缺省(lifecycle 留空 → RELEASED)
        isDefault: 0,
        category: scopeId != null ? 'USER' : 'DEFAULT',
        scopeId: scopeId ?? null,
      });
      message.success(`已创建${promptTypeLabel}提示词:${created.name}（已发布,可直接绑定）`);
      onCreated(created);
      onClose();
    } finally {
      setSubmitting(false);
    }
  };

  const title = mode === 'custom' ? '自定义提示词' : '复制默认后修改';
  const desc =
    mode === 'custom'
      ? '从空白开始编写。提交后直接以「已发布」状态创建，可在当前页面的下拉框中选择并绑定到仓库。'
      : '已预填默认提示词内容,可在其基础上修改。提交后直接以「已发布」状态创建新副本。';

  return (
    <>
      <Modal
        title={
          <Space>
            {mode === 'custom' ? <PlusOutlined /> : <SnippetsOutlined />}
            {title} · {promptTypeLabel}
          </Space>
        }
        open={open}
        onCancel={onClose}
        width={860}
        destroyOnClose
        footer={[
          <Button key="cancel" onClick={onClose}>
            取消
          </Button>,
          <Button
            key="load-default"
            icon={<SnippetsOutlined />}
            disabled={!defaultPrompt?.content}
            onClick={handleLoadFromDefault}
          >
            从默认提示词加载
          </Button>,
          <Button
            key="trial"
            icon={<PlayCircleOutlined />}
            loading={false}
            disabled={!currentContent.trim()}
            onClick={handleOpenTrial}
          >
            试跑
          </Button>,
          <Button key="submit" type="primary" loading={submitting} onClick={handleSubmit}>
            创建
          </Button>,
        ]}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message={
            <Space direction="vertical" size={4}>
              <Text>{desc}</Text>
              <Text type="secondary">
                Wizard 自定义提示词将直接以「已发布」状态创建，绑定到仓库后即可在创建任务时使用，无需再到「基础配置 → 提示词」手动发布。
              </Text>
            </Space>
          }
        />
        <Form
          form={form}
          layout="vertical"
          // 用 Form 的 initialValues 兜底
          initialValues={{
            name: buildDefaultName(TYPE_NAME[promptType]),
            content: mode === 'clone-default' ? sourcePrompt?.content ?? '' : '',
          }}
        >
          <Form.Item
            name="name"
            label="提示词名称"
            rules={[{ required: true, message: '请输入提示词名称' }]}
          >
            <Input placeholder={buildDefaultName(TYPE_NAME[promptType])} />
          </Form.Item>
          <Form.Item
            name="content"
            label="提示词内容"
            rules={[{ required: true, message: '请输入提示词内容' }]}
            extra={
              <Text type="secondary" style={{ fontSize: 12 }}>
                命名规则:{TYPE_NAME[promptType]} - 时间戳
              </Text>
            }
          >
            <Input.TextArea
              autoSize={{ minRows: 14, maxRows: 24 }}
              className="ci-code-input"
              placeholder={
                mode === 'custom'
                  ? '请输入完整的提示词模板,支持 ${code}、${class_name} 等占位符'
                  : '已预填默认提示词内容,可在其上继续修改'
              }
            />
          </Form.Item>
        </Form>
      </Modal>

      {/* 试跑子弹窗:用当前表单内容(可能未保存)作为 preview prompt */}
      {trialPreview && (
        <PromptTrialModal
          open={trialOpen}
          prompt={trialPreview}
          promptTypeLabel={promptTypeLabel}
          onClose={() => setTrialOpen(false)}
          onRun={async (params) => {
            try {
              // id=0 未落库：后端在 resolvedContent 非空时跳过按 id 查库，直接试跑
              return await testRunPrompt(
                trialPreview.id,
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
          }}
        />
      )}
    </>
  );
};

export default SystemPromptEditorModal;
