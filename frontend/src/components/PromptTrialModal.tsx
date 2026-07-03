import {
  Alert,
  Button,
  Card,
  Col,
  Collapse,
  Divider,
  Empty,
  Input,
  Modal,
  Row,
  Select,
  Skeleton,
  Space,
  Spin,
  Statistic,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import { CheckCircleOutlined, CopyOutlined, PlayCircleOutlined, SwapOutlined } from '@ant-design/icons';
import React, { useEffect, useMemo, useState } from 'react';
import { listModels } from '../api/model';
import { type PromptTestResult } from '../api/prompt';
import type { AiModel, Prompt } from '../types';
import {
  extractPlaceholders,
  formatPlaceholderToken,
  formatVariableLabel,
  isFilledBySampleCode,
  JAVA_CODE_VAR_NAMES,
  substitutePlaceholders,
} from '../utils/promptPlaceholders';
import { getModelOptionDisabled, getPreferredModel } from '../pages/basic/prompts/utils';

const { Text, Paragraph } = Typography;

export interface PromptTrialModalProps {
  open: boolean;
  /** 要试跑的 prompt；支持 id=0 的「未保存预览」prompt（content 即表单内容） */
  prompt: Prompt | null;
  /** 提示词类型的中文标签，例如「模块提取 / 文档生成」 */
  promptTypeLabel?: string;
  /** 关闭弹窗 */
  onClose: () => void;
  /**
   * 触发试跑。
   * - sampleCode: 用户填的 Java 代码
   * - variables: 用户为占位符填的值（key 为占位符名，不含花括号）
   * - resolvedContent: 已把 variables + sampleCode 替换进 prompt.content 的最终字符串
   * 返回 PromptTestResult（由父组件决定如何调后端 API）。
   * 对于 id=0 的预览 prompt，父组件可拒绝调用并返回 errorReason 即可。
   */
  onRun: (params: {
    sampleCode: string;
    variables: Record<string, string>;
    resolvedContent: string;
  }) => Promise<PromptTestResult>;
  /** 默认填入的 Java 示例代码。默认: OrderService 经典样例。 */
  defaultSampleCode?: string;
  /** 「复制提示词正文」按钮 — 默认 true */
  showCopyTemplate?: boolean;
  /** 「复制试跑结果」按钮 — 默认 true */
  showCopyResult?: boolean;
}

const DEFAULT_SAMPLE = `public class OrderService {
  private final OrderMapper orderMapper;
  public void createOrder(Order order) {
    checkInventory(order.getItemId());
    orderMapper.insert(order);
  }
}`;

/**
 * 通用提示词试跑弹窗。
 *
 * 设计要点：
 * - 「自管 state」：sampleCode / variables / models / selectedModelId / running / result 全部在内部 useState；
 *   父组件只需提供 prompt + onRun + onClose，不再管理 6 个受控 prop。
 * - 占位符识别：自动从 prompt.content 抽取 {var} / ${var}，动态生成输入框；
 *   java_code / source_code / java.code 可由下方示例代码自动替换。
 * - 「最终 prompt 预览」：实时合成 resolvedContent 并展示。
 * - 模型选择：自动选 isDefault='true'，无 key 的模型灰显。
 * - 支持 id=0 的预览草稿（编辑器内试跑）：父组件 onRun 决定是否调后端。
 */
const PromptTrialModal: React.FC<PromptTrialModalProps> = ({
  open,
  prompt,
  promptTypeLabel,
  onClose,
  onRun,
  defaultSampleCode = DEFAULT_SAMPLE,
  showCopyTemplate = true,
  showCopyResult = true,
}) => {
  const [sampleCode, setSampleCode] = useState(defaultSampleCode);
  const [models, setModels] = useState<AiModel[]>([]);
  const [modelId, setModelId] = useState<number | undefined>(undefined);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<PromptTestResult | null>(null);
  /** 占位符变量（key 不含花括号） */
  const [variables, setVariables] = useState<Record<string, string>>({});

  // 打开弹窗：拉取模型 + 自动选默认 + 重置结果与变量
  useEffect(() => {
    if (!open) return;
    setResult(null);
    setRunning(false);
    setVariables({});
    setSampleCode(defaultSampleCode);
    listModels()
      .then((list) => {
        setModels(list);
        const def = getPreferredModel(list);
        if (def) setModelId(def.id);
      })
      .catch(() => {
        // request.ts 拦截器已统一弹错
      });
  }, [open, defaultSampleCode]);

  // 切换 prompt 时清空变量缓存
  useEffect(() => {
    if (open) setVariables({});
  }, [open, prompt?.id, prompt?.version]);

  /** 当前 prompt 的占位符列表（按出现顺序） */
  const placeholders = useMemo(
    () => extractPlaceholders(prompt?.content),
    [prompt?.content],
  );

  /** 把用户填的变量 + sampleCode 合成最终发送给 AI 的字符串 */
  const resolvedContent = useMemo(() => {
    if (!prompt) return '';
    const merged: Record<string, string> = { ...variables };
    if (sampleCode) {
      for (const name of JAVA_CODE_VAR_NAMES) {
        if (!merged[name]?.trim()) merged[name] = sampleCode;
      }
    }
    return substitutePlaceholders(prompt.content, merged);
  }, [prompt, variables, sampleCode]);

  const updateVariable = (name: string, value: string) => {
    setVariables((prev) => ({ ...prev, [name]: value }));
  };

  const handleRun = async () => {
    if (!prompt) {
      message.error('未选择提示词');
      return;
    }
    if (!sampleCode.trim()) {
      message.warning('请输入一段示例 Java 代码');
      return;
    }
    setRunning(true);
    setResult(null);
    try {
      const r = await onRun({ sampleCode, variables, resolvedContent });
      setResult(r);
      if (r.errorReason) message.error('试跑失败');
      else message.success('试跑完成');
    } catch {
      // request.ts 拦截器已统一弹错；保留弹窗供用户继续操作
    } finally {
      setRunning(false);
    }
  };

  const titleSuffix = promptTypeLabel ?? '提示词';
  const promptTitle = prompt?.name ? `${prompt.name} v${prompt.version ?? ''}` : '';
  const isPreview = !!prompt && prompt.id === 0;

  return (
    <Modal
      title={
        <Space>
          <PlayCircleOutlined />
          试跑 · {titleSuffix} · {promptTitle}
          {isPreview && <Tag color="orange">未保存预览</Tag>}
        </Space>
      }
      open={open}
      onCancel={onClose}
      width={880}
      destroyOnClose
      footer={null}
    >
      {!prompt ? (
        <Empty description="未选择提示词" />
      ) : (
        <Space direction="vertical" size={16} className="ci-prompt-trial-body">
          {/* 提示词模板正文（只读，可折叠） */}
          <Collapse
            ghost
            defaultActiveKey={['template']}
            items={[
              {
                key: 'template',
                label: (
                  <div className="ci-prompt-collapse-label">
                    <Text strong>提示词模板正文</Text>
                    {showCopyTemplate && (
                      <Button
                        size="small"
                        type="text"
                        icon={<CopyOutlined />}
                        onClick={(event) => {
                          event.stopPropagation();
                          navigator.clipboard.writeText(prompt.content ?? '');
                          message.success('模板正文已复制');
                        }}
                      >
                        复制
                      </Button>
                    )}
                  </div>
                ),
                children: (
                  <Input.TextArea
                    autoSize={{ minRows: 4, maxRows: 10 }}
                    className="ci-code-input"
                    value={prompt.content ?? ''}
                    readOnly
                  />
                ),
              },
            ]}
          />

          {/* 占位符变量填入区 */}
          <Card
            size="small"
            title={
              <Space>
                <SwapOutlined />
                <span>占位符变量</span>
                {placeholders.length > 0 ? (
                  <Tag color="blue">{placeholders.length} 个</Tag>
                ) : (
                  <Tag>无占位符</Tag>
                )}
              </Space>
            }
            extra={
              placeholders.length > 0 && (
                <Tooltip title="识别模板中的 {var} 或 ${var} 占位符，均可选填；未填写的保留原文。java_code / java.code 若有下方示例代码会自动替换。">
                  <Text type="secondary" style={{ fontSize: 12 }}>使用说明</Text>
                </Tooltip>
              )
            }
          >
            {placeholders.length === 0 ? (
              <Paragraph type="secondary" style={{ marginBottom: 0 }}>
                当前模板中没有占位符变量，直接点击下方「试跑」即可。
              </Paragraph>
            ) : (
              <Row gutter={[12, 8]}>
                {placeholders.map((name) => {
                  const canUseSampleCode = isFilledBySampleCode(name, sampleCode);
                  const value = variables[name] ?? '';
                  return (
                    <Col xs={24} md={12} key={name}>
                      <div className="ci-prompt-var-row">
                        <Text strong className="ci-prompt-var-label">
                          <code>{formatPlaceholderToken(name, prompt?.content)}</code>
                          <Text type="secondary" style={{ fontSize: 12, marginLeft: 4 }}>
                            {formatVariableLabel(name)}
                          </Text>
                        </Text>
                        <Input
                          placeholder={
                            canUseSampleCode
                              ? '可选；留空时若有下方示例代码会自动替换'
                              : '可选；留空时保留占位符原文'
                          }
                          value={value}
                          onChange={(e) => updateVariable(name, e.target.value)}
                          allowClear
                        />
                        {canUseSampleCode && (
                          <Text type="secondary" style={{ fontSize: 12 }}>
                            未填时，若有下方「Java 示例代码」会自动用于替换。
                          </Text>
                        )}
                      </div>
                    </Col>
                  );
                })}
              </Row>
            )}
          </Card>

          {/* 模型选择 + 标识 */}
          <div className="ci-prompt-model-row">
            <Text strong className="ci-prompt-model-label">
              选择 AI 模型
            </Text>
            <Space size={8} wrap style={{ flex: 1 }}>
              <Select
                className="ci-prompt-model-select"
                placeholder="选择 AI 模型（默认=系统默认）"
                style={{ minWidth: 280 }}
                options={models.map((m) => ({
                  label: `${m.name} (${m.identifier})${m.isDefault === 'true' ? ' [系统默认]' : ''}`,
                  value: m.id,
                  disabled: getModelOptionDisabled(m),
                }))}
                value={modelId}
                onChange={setModelId}
                allowClear
                loading={models.length === 0}
              />
              {prompt.version != null && <Tag color="blue">v{prompt.version}</Tag>}
              {prompt.isDefault === 1 && <Tag color="gold">默认</Tag>}
              {isPreview && <Tag color="orange">预览（不会真正调用 AI）</Tag>}
            </Space>
          </div>

          {/* 示例代码 */}
          <div>
            <Text strong className="ci-prompt-field-label">
              Java 示例代码
            </Text>
            <Input.TextArea
              autoSize={{ minRows: 6, maxRows: 16 }}
              className="ci-code-input"
              value={sampleCode}
              onChange={(e) => setSampleCode(e.target.value)}
              placeholder="在此输入 Java 示例代码，作为 java_code / java.code 等占位符的默认值"
            />
          </div>

          {/* 替换后的最终 prompt 预览 */}
          {placeholders.length > 0 && (
            <Collapse
              ghost
              items={[
                {
                  key: 'preview',
                  label: (
                    <Space>
                      <CheckCircleOutlined style={{ color: '#52c41a' }} />
                      <Text strong>替换后的最终 prompt（发送给 AI 的内容）</Text>
                    </Space>
                  ),
                  children: (
                    <Input.TextArea
                      autoSize={{ minRows: 4, maxRows: 14 }}
                      className="ci-code-input"
                      value={resolvedContent}
                      readOnly
                    />
                  ),
                },
              ]}
            />
          )}

          <Button
            type="primary"
            block
            icon={<PlayCircleOutlined />}
            loading={running}
            onClick={handleRun}
          >
            {running ? '正在流式输出...' : '开始试跑'}
          </Button>

          {running && !result?.result && !result?.errorReason && (
            <div className="ci-empty-panel">
              <Skeleton active paragraph={{ rows: 4 }} />
              <Spin tip="AI 正在分析..." style={{ display: 'none' }} />
            </div>
          )}

          {result && (
            <Space direction="vertical" size={12} className="ci-prompt-trial-result">
              <Divider className="ci-prompt-divider" />
              <Row gutter={12}>
                <Col span={8}>
                  <Card size="small">
                    <Statistic title="输入 Token" value={result.inputTokens} />
                  </Card>
                </Col>
                <Col span={8}>
                  <Card size="small">
                    <Statistic title="输出 Token" value={result.outputTokens} />
                  </Card>
                </Col>
                <Col span={8}>
                  <Card size="small">
                    <Statistic title="耗时" value={result.durationMs} suffix="ms" />
                  </Card>
                </Col>
              </Row>
              {result.errorReason ? (
                <Alert
                  type="error"
                  showIcon
                  message="分析失败"
                  description={result.errorReason}
                />
              ) : (
                <div className="ci-editor-shell ci-prompt-result-shell">
                  <div className="ci-prompt-result-header">
                    <Text strong className="ci-prompt-result-title">
                      AI 归纳结果输出
                    </Text>
                    {showCopyResult && (
                      <Button
                        size="small"
                        icon={<CopyOutlined />}
                        onClick={() => {
                          navigator.clipboard.writeText(result.result);
                          message.success('试跑结果已复制到剪贴板');
                        }}
                      >
                        复制结果
                      </Button>
                    )}
                  </div>
                  <pre
                    className={`ci-result-preview ci-prompt-result-preview${
                      running ? ' ci-prompt-result-preview-streaming' : ''
                    }`}
                  >
                    {result.result}
                  </pre>
                </div>
              )}
            </Space>
          )}
        </Space>
      )}
    </Modal>
  );
};

export default PromptTrialModal;
