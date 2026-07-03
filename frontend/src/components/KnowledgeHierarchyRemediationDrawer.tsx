import React, { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Drawer,
  Modal,
  Select,
  Space,
  Typography,
  message,
} from 'antd';
import type { ModuleHierarchy } from '../types';
import ModuleHierarchyEditor from './ModuleHierarchyEditor';

const { Text } = Typography;

export interface KnowledgeHierarchyRemediationDrawerProps {
  open: boolean;
  /** 已加载好的发布版模块层级快照，作为编辑器初始值 */
  initialHierarchy: ModuleHierarchy | null;
  /** 模块下拉选项 [{value: moduleId, label: '模块名 (id)'}] */
  moduleOptions: { value: string; label: string }[];
  /**
   * 父组件负责实际调用 `remediateHierarchy` API + 跳转。
   * 抽屉在 onSubmit resolve 后会自己关掉；reject 时不关，由父组件决定。
   */
  onSubmit: (params: { hierarchy: ModuleHierarchy; moduleIds: string[] }) => Promise<void>;
  onClose: () => void;
}

/**
 * 知识查看 → 模块层级 → 「调整并重跑」抽屉。
 * - 顶部：说明 Alert + 「重跑范围」Select（必选，至少 1 个）
 * - 中部：受控的 `ModuleHierarchyEditor`（树形/JSON 双 Tab）
 * - 抽屉底部 extra：「确认并重跑」按钮（带 Modal.confirm 二次确认）
 */
const KnowledgeHierarchyRemediationDrawer: React.FC<KnowledgeHierarchyRemediationDrawerProps> = ({
  open,
  initialHierarchy,
  moduleOptions,
  onSubmit,
  onClose,
}) => {
  const [draft, setDraft] = useState<ModuleHierarchy | null>(initialHierarchy);
  const [scopeModuleIds, setScopeModuleIds] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);

  // 抽屉打开时重置 draft + scope；新仓库进来时也重置
  useEffect(() => {
    if (open) {
      setDraft(initialHierarchy);
      setScopeModuleIds([]);
    }
  }, [open, initialHierarchy]);

  const handleConfirm = () => {
    if (!draft) {
      message.warning('层级尚未加载');
      return;
    }
    if (scopeModuleIds.length === 0) {
      message.warning('请选择需要重生成文档的模块');
      return;
    }
    Modal.confirm({
      title: '确认从文档生成阶段重跑？',
      content: `将仅对选中的 ${scopeModuleIds.length} 个模块重跑 AI 文档生成。`,
      okText: '确认重跑',
      cancelText: '取消',
      onOk: async () => {
        setSaving(true);
        try {
          await onSubmit({ hierarchy: draft, moduleIds: scopeModuleIds });
          onClose();
        } catch {
          // 父组件 / request.ts 拦截器已处理错误，保留抽屉
        } finally {
          setSaving(false);
        }
      },
    });
  };

  return (
    <Drawer
      title="调整模块层级并重跑"
      width={1080}
      open={open}
      onClose={onClose}
      destroyOnHidden
      extra={
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={saving} onClick={handleConfirm}>
            确认并重跑
          </Button>
        </Space>
      }
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="在树形/JSON 双视图调整层级结构，并选择需要重生成文档的模块（默认仅 scope 内模块调 AI）。"
        description={
          <ul style={{ margin: 0, paddingLeft: 18 }}>
            <li>
              「树形编辑」支持节点改名、新增/删除、拖拽排序/跨级移动，逐项编辑类路径与方法签名。
            </li>
            <li>
              「JSON 编辑」适合大批量文本替换。两侧通过「应用」按钮双向同步。
            </li>
            <li>
              「重跑文档的模块范围」决定哪些模块走 AI 重跑；其他模块保留原草稿。
            </li>
          </ul>
        }
      />

      <div
        style={{
          marginBottom: 16,
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          flexWrap: 'wrap',
        }}
      >
        <Text type="secondary" strong>
          重跑文档的模块范围：
        </Text>
        <Select
          mode="multiple"
          style={{ minWidth: 420, flex: 1 }}
          placeholder="选择 moduleId（至少 1 个）"
          value={scopeModuleIds}
          onChange={setScopeModuleIds}
          options={moduleOptions}
          maxTagCount="responsive"
        />
      </div>

      {draft && (
        <ModuleHierarchyEditor
          // 重开抽屉时强制重挂，确保 expandedKeys 等内部 state 重置
          key={`hie-editor-${open ? 'open' : 'closed'}-${draft.modules ? Object.keys(draft.modules).length : 0}`}
          value={draft}
          loading={false}
          saving={saving}
          onChange={(next) => setDraft(next ?? null)}
          onSubmit={() => {
            /* 不走这里：提交由抽屉底部「确认并重跑」按钮触发 */
          }}
        />
      )}
    </Drawer>
  );
};

export default KnowledgeHierarchyRemediationDrawer;
