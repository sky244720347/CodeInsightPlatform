import type { FormInstance } from 'antd';
import type { Prompt } from '../../types';

/**
 * 公共参数：调用方传 setter + 可选 form 实例。
 * - 不传递 setter 也能工作（用于只需"加进列表"的场景）
 * - 不传 form 也行（SystemPromptBindModal 没有外层 Form）
 */
export interface ApplyPromptCreatedCtx {
  /** 把新 prompt 推入到当前 Select 的 options 列表 */
  setPrompts: (updater: (prev: Prompt[]) => Prompt[]) => void;
  /** 可选：MODULARIZE 的 pending 选择 ID（Form.Item 独占时不需要） */
  setPendingModularizeId?: (id: number | null) => void;
  /** 可选：DOCUMENT_GENERATION 的 pending 选择 ID（Form.Item 独占时不需要） */
  setPendingDocumentId?: (id: number | null) => void;
  /** 可选：Ant Design Form 实例。需要 setFieldsValue 把 Form.Item name 的字段也同步上—— */
  /** 否则 <Select> 用 value 受控，但 <Form.Item> 的内部 form 状态可能对不上 */
  form?: FormInstance;
  /** 提示词类型，决定走哪条 pending 选择通道以及 Form.Item 的字段名 */
  promptType: 'MODULARIZE' | 'DOCUMENT_GENERATION';
  /** 字段名前缀，与外层 Form.Item `name=...` 保持一致 */
  fieldPrefix?: 'modularizePromptId' | 'documentPromptId';
}

/**
 * 一站式「新建自定义提示词 → 自动选中到下拉框」逻辑。
 *
 * <p>覆盖两条状态同步路径：</p>
 * <ul>
 *   <li>Select 控件的 `value`（由 `pendingXxxId` 控制）—— 用 `setPendingXxxId`</li>
 *   <li>&lt;Form.Item name=...&gt; 内部 form 状态（提交时 validateFields 要拿到的字段）—— 用 `form.setFieldsValue`</li>
 * </ul>
 *
 * 如果只同步一个，Ant Design Select 看上去"高亮"了新选项，但实际上表单提交时不会带过去，
 * 导致用户以为选上了、最终却没生效。
 */
export function applyPromptCreated(
  newPrompt: Prompt,
  ctx: ApplyPromptCreatedCtx,
): void {
  const fieldName =
    ctx.fieldPrefix ??
    (ctx.promptType === 'MODULARIZE' ? 'modularizePromptId' : 'documentPromptId');

  // 1) options 列表立刻包含新项（避免渲染时 value=新 ID 但 options 还没它）
  ctx.setPrompts((prev) =>
    prev.some((x) => x.id === newPrompt.id) ? prev : [...prev, newPrompt],
  );

  // 2) pending 选择状态（可选，Form.Item 独占时不需要）
  if (ctx.promptType === 'MODULARIZE') {
    ctx.setPendingModularizeId?.(newPrompt.id);
  } else {
    ctx.setPendingDocumentId?.(newPrompt.id);
  }

  // 3) 把 Form.Item 字段也同步——这一步修复了用户报告的"创建后再去人工点一下"
  //    的根因：value prop 改了但 form 字段没改，提交时不会带新 ID
  ctx.form?.setFieldsValue({ [fieldName]: newPrompt.id });
}
