import React, { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Modal,
  Select,
  Space,
  Tag,
  Typography,
  message,
} from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { listPrompts, getPrompt, deletePrompt } from '../../api/prompt';
import { listRepositories, updateRepository } from '../../api/repository';
import type { Prompt, Repository, System } from '../../types';
import SystemPromptEditorModal from './SystemPromptEditorModal';
import { applyPromptCreated } from './promptSelect';

const { Text } = Typography;

const TYPE_NAME: Record<'MODULARIZE' | 'DOCUMENT_GENERATION', string> = {
  MODULARIZE: '模块提取',
  DOCUMENT_GENERATION: '文档生成',
};

const TYPE_LABEL: Record<'MODULARIZE' | 'DOCUMENT_GENERATION', string> = TYPE_NAME;

interface Props {
  open: boolean;
  repository: Repository | null;
  onClose: () => void;
  /** 父组件的 list 重新拉取回调(创建/绑定后) */
  onSaved?: () => void;
}

/**
 * 「系统 → 提示词」聚焦编辑弹窗
 *
 * 与 wizard Step 4 等价的逻辑,但只有 Step 4 这一段(用于已创建系统的修改)。
 * - 每种类型一个卡片,显示当前绑定的提示词(标签: 默认 / 自定义)
 * - 「自定义」按钮打开编辑器;编辑器内置「从默认提示词加载」+ 试跑
 * - 创建成功后,新 prompt 自动绑定到系统,并刷新本地提示词列表
 */
const SystemPromptBindModal: React.FC<Props> = ({ open, repository, onClose, onSaved }) => {
  const [prompts, setPrompts] = useState<Prompt[]>([]);
  const [promptsLoading, setPromptsLoading] = useState(false);

  const [editorState, setEditorState] = useState<{
    open: boolean;
    promptType: 'MODULARIZE' | 'DOCUMENT_GENERATION';
  } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  /**
   * 待保存的提示词绑定(下拉切换、自定义创建均只更新 pending,需点底部「保存」才落库)
   * - undefined: 用户尚未修改该字段,沿用 repository 的当前绑定
   * - number | null: 用户显式选择(含清空)
   */
  const [pendingModularizeId, setPendingModularizeId] = useState<number | null | undefined>(undefined);
  const [pendingDocumentId, setPendingDocumentId] = useState<number | null | undefined>(undefined);

  // 拉取可用提示词(DEFAULT 全局默认 + 当前系统下所有仓库的 USER 自定义)
  // 提示词 scope_id = repository.id,但下拉框需要看到系统下所有仓库的 USER 提示词（共享）
  // 实现方式:先 listRepositories 拿到系统下所有仓库 id,再对每个 id 调 listPrompts
  const fetchAll = async () => {
    setPromptsLoading(true);
    try {
      const all: Prompt[] = [];
      const systemId = repository?.systemId;

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
      // 当前仓库一定包含在内（即使 listRepositories 失败,也至少能查到当前仓库）
      if (repository && !repoIds.includes(repository.id)) {
        repoIds = [repository.id, ...repoIds];
      }

      for (const t of Object.keys(TYPE_NAME) as ('MODULARIZE' | 'DOCUMENT_GENERATION')[]) {
        // 2) 拉 DEFAULT 类别的全局默认
        const defRes = await listPrompts({
          current: 1,
          size: 200,
          lifecycle: 'RELEASED',
          promptType: t,
          category: 'DEFAULT',
          isDefault: 1,
        });
        all.push(...defRes.records);
        // 3) 对系统下每个仓库,拉 USER 自定义并合并
        for (const rid of repoIds) {
          const userRes = await listPrompts({
            current: 1,
            size: 200,
            promptType: t,
            category: 'USER',
            scopeId: rid,
          });
          all.push(...userRes.records);
        }
      }
      // 已绑定但未在前几次拉取中出现的,补拉一次(防御性)
      if (repository) {
        const boundIds = [repository.modularizePromptId, repository.documentPromptId].filter(
          Boolean,
        ) as number[];
        for (const id of boundIds) {
          if (!all.some((p) => p.id === id)) {
            try {
              const p = await getPrompt(id);
              if (p) all.push(p);
            } catch {
              // 忽略
            }
          }
        }
      }
      // 按 id 去重(防御性,正常情况下不会有重复)
      const dedup = Array.from(new Map(all.map((p) => [p.id, p])).values());
      setPrompts(dedup);
    } finally {
      setPromptsLoading(false);
    }
  };

  useEffect(() => {
    if (open) fetchAll();
  }, [open, repository?.id]);

  // 弹窗打开/切换仓库时,把 pending 重置为当前已绑定值
  useEffect(() => {
    if (open) {
      setPendingModularizeId(repository?.modularizePromptId ?? null);
      setPendingDocumentId(repository?.documentPromptId ?? null);
    }
  }, [open, repository?.id]);

  // 找到当前已绑定的 prompt(可能为 null)
  const selectedModularize = useMemo(
    () => prompts.find((p) => p.id === repository?.modularizePromptId) ?? null,
    [prompts, repository?.modularizePromptId],
  );
  const selectedDocument = useMemo(
    () => prompts.find((p) => p.id === repository?.documentPromptId) ?? null,
    [prompts, repository?.documentPromptId],
  );

  // 全局默认(同类型 is_default=1)
  const defaultModularize = useMemo(
    () => prompts.find((p) => p.promptType === 'MODULARIZE' && p.isDefault === 1) ?? null,
    [prompts],
  );
  const defaultDocument = useMemo(
    () => prompts.find((p) => p.promptType === 'DOCUMENT_GENERATION' && p.isDefault === 1) ?? null,
    [prompts],
  );

  // 下拉框选项:全局 DEFAULT + 当前仓库的 USER 自定义(无 lifecycle 区分)
  const modularizeOptions = useMemo(
    () =>
      prompts
        .filter((p) => p.promptType === 'MODULARIZE')
        .map((p) => ({
          value: p.id,
          label: `${p.name} (v${p.version})${p.isDefault === 1 ? ' · 默认' : ''}`,
        })),
    [prompts],
  );
  const documentOptions = useMemo(
    () =>
      prompts
        .filter((p) => p.promptType === 'DOCUMENT_GENERATION')
        .map((p) => ({
          value: p.id,
          label: `${p.name} (v${p.version})${p.isDefault === 1 ? ' · 默认' : ''}`,
        })),
    [prompts],
  );

  /** 清理孤立的 USER 提示词(旧绑定是 USER + 同 scopeId + 不再被任何系统引用) */
  const cleanupOrphanedUserPrompt = async (_promptType: 'MODULARIZE' | 'DOCUMENT_GENERATION', oldPrompt: Prompt | null) => {
    if (!oldPrompt || oldPrompt.category !== 'USER') return;
    if (!repository) return;
    if (oldPrompt.scopeId !== repository.id) return;
    try {
      await deletePrompt(oldPrompt.id);
    } catch {
      // 后端拦截(如仍被引用)自动阻止
    }
  };

  /** 从下拉框切换提示词 → 仅 stage 到 pending,不调后端 */
  const handleSelectExisting = (
    promptType: 'MODULARIZE' | 'DOCUMENT_GENERATION',
    id: number | undefined,
  ) => {
    if (promptType === 'MODULARIZE') {
      setPendingModularizeId(id ?? null);
    } else {
      setPendingDocumentId(id ?? null);
    }
  };

  /** 打开自定义编辑器 */
  const openCustom = (promptType: 'MODULARIZE' | 'DOCUMENT_GENERATION') => {
    setEditorState({ open: true, promptType });
  };

  /** 自定义创建成功 → 仅把新 prompt 推到本地列表 + stage 到 pending,不调后端绑定 */
  const handlePromptCreated = (p: Prompt) => {
    applyPromptCreated(p, {
      setPrompts,
      setPendingModularizeId,
      setPendingDocumentId,
      promptType: p.promptType as 'MODULARIZE' | 'DOCUMENT_GENERATION',
    });
    message.success(`已创建自定义提示词:${p.name}（点击底部「保存」生效）`);
  };

  /** 是否有未保存的暂存变更 */
  const isDirty = useMemo(() => {
    if (!repository) return false;
    const m = pendingModularizeId === undefined ? repository.modularizePromptId ?? null : pendingModularizeId;
    const d = pendingDocumentId === undefined ? repository.documentPromptId ?? null : pendingDocumentId;
    return m !== (repository.modularizePromptId ?? null) || d !== (repository.documentPromptId ?? null);
  }, [pendingModularizeId, pendingDocumentId, repository]);

  /** 底部「保存」:把 modularize + document 一次性提交,并清理旧孤立 USER prompt */
  const handleSaveAll = async () => {
    if (!repository) return;
    const modularizeId = pendingModularizeId === undefined ? repository.modularizePromptId ?? null : pendingModularizeId;
    const documentId = pendingDocumentId === undefined ? repository.documentPromptId ?? null : pendingDocumentId;
    const oldModularize = selectedModularize;
    const oldDocument = selectedDocument;
    setSubmitting(true);
    try {
      await updateRepository(repository.id, {
        modularizePromptId: modularizeId,
        documentPromptId: documentId,
      } as Partial<System>);
      message.success('提示词绑定已保存');
      // 异步清理被替换下来的孤立 USER 提示词
      if (modularizeId !== (repository.modularizePromptId ?? null)) {
        cleanupOrphanedUserPrompt('MODULARIZE', oldModularize);
      }
      if (documentId !== (repository.documentPromptId ?? null)) {
        cleanupOrphanedUserPrompt('DOCUMENT_GENERATION', oldDocument);
      }
      onSaved?.();
      onClose();
    } catch {
      // 拦截器已提示
    } finally {
      setSubmitting(false);
    }
  };

  const renderCard = (promptType: 'MODULARIZE' | 'DOCUMENT_GENERATION') => {
    // 选中值优先取 pending(用户暂存),没有 pending 则取 repository 实际绑定
    const pendingId = promptType === 'MODULARIZE' ? pendingModularizeId : pendingDocumentId;
    const effectiveId =
      pendingId === undefined
        ? promptType === 'MODULARIZE'
          ? repository?.modularizePromptId
          : repository?.documentPromptId
        : pendingId;
    const selected = effectiveId != null ? prompts.find((p) => p.id === effectiveId) ?? null : null;
    const isPending =
      pendingId !== undefined &&
      pendingId !== (promptType === 'MODULARIZE'
        ? repository?.modularizePromptId
        : repository?.documentPromptId);
    const defaultP = promptType === 'MODULARIZE' ? defaultModularize : defaultDocument;
    return (
      <Card
        size="small"
        style={{ marginBottom: 12 }}
        title={
          <Space>
            <span>{TYPE_LABEL[promptType]}提示词</span>
            {selected && (
              <Tag color="green">
                {selected.name} (v{selected.version})
                {selected.isDefault === 1 ? ' · 默认' : ' · 自定义'}
              </Tag>
            )}
            {isPending && <Tag color="orange">待保存</Tag>}
          </Space>
        }
        extra={
          <Space size={4} wrap>
            {/* key 跟随当前 promptType + pending 选择变化,新建自定义提示词后强制 Select 重渲染以显示高亮 */}
            <Select
              key={`${promptType}:${pendingModularizeId ?? 'x'}:${pendingDocumentId ?? 'x'}`}
              showSearch
              optionFilterProp="label"
              placeholder="选择已有提示词"
              style={{ width: 320 }}
              value={effectiveId ?? undefined}
              onChange={(id) => handleSelectExisting(promptType, id)}
              options={promptType === 'MODULARIZE' ? modularizeOptions : documentOptions}
              loading={promptsLoading}
              allowClear
            />
            <Button
              size="small"
              icon={<PlusOutlined />}
              onClick={() => openCustom(promptType)}
            >
              自定义
            </Button>
          </Space>
        }
      >
        {selected ? (
          <Text type="secondary">
            当前{isPending ? '暂存' : '已绑定'}：{selected.name}（v{selected.version}）。点「自定义」可基于默认提示词({defaultP?.name ?? '无'})复制修改并保存为该仓库专属的提示词。
          </Text>
        ) : (
          <Text type="secondary">
            当前未绑定。点「自定义」创建一个(将基于默认提示词 {defaultP?.name ?? '无'} 复制修改)。
          </Text>
        )}
      </Card>
    );
  };

  return (
    <>
      <Modal
        title={
          <Space>
            提示词 · {repository?.gitUrl ?? ''}
            {repository?.gitUrl && <Text type="secondary">({repository?.gitUrl})</Text>}
          </Space>
        }
        open={open}
        onCancel={onClose}
        width={760}
        destroyOnClose
        footer={[
          <Button key="cancel" onClick={onClose}>
            取消
          </Button>,
          <Button
            key="save"
            type="primary"
            loading={submitting}
            disabled={!isDirty || submitting}
            onClick={handleSaveAll}
          >
            保存
          </Button>,
        ]}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message={
            <Space direction="vertical" size={4}>
              <Text>仓库绑定 1 个模块提取提示词 + 1 个文档生成提示词,初始默认绑定到「全局默认提示词」。</Text>
              <Text type="secondary">
                下拉切换或自定义创建仅暂存,点底部「保存」才统一提交;点「取消」或关闭弹窗丢弃本次暂存。
              </Text>
            </Space>
          }
        />
        {renderCard('MODULARIZE')}
        {renderCard('DOCUMENT_GENERATION')}

        {(promptsLoading || submitting) && (
          <Text type="secondary" style={{ display: 'block', textAlign: 'right' }}>
            {submitting ? '保存中...' : '加载提示词中...'}
          </Text>
        )}
      </Modal>

      {/* 自定义编辑器弹窗 */}
      {editorState && (
        <SystemPromptEditorModal
          open={editorState.open}
          mode="custom"
          defaultPrompt={
            editorState.promptType === 'MODULARIZE' ? defaultModularize : defaultDocument
          }
          systemName={repository?.gitUrl}
          scopeId={repository?.id}
          promptType={editorState.promptType}
          promptTypeLabel={TYPE_LABEL[editorState.promptType]}
          onClose={() => setEditorState(null)}
          onCreated={handlePromptCreated}
        />
      )}
    </>
  );
};

export default SystemPromptBindModal;
