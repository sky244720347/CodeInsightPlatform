import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Checkbox,
  Empty,
  Input,
  Popconfirm,
  Popover,
  Select,
  Space,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  CheckCircleOutlined,
  DeleteOutlined,
  HolderOutlined,
  LoadingOutlined,
  NodeCollapseOutlined,
  NodeExpandOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import Tree from 'antd/es/tree';
import type { DataNode, TreeProps } from 'antd/es/tree';
import {
  getModuleHierarchy,
  replaceModuleHierarchy,
  resumeModuleHierarchyReview,
} from '../api/task';
import { collectExpandableKeys } from '../utils/treeExpandKeys';
import type { FunctionNode, ModuleHierarchy, ModuleNode, SubModuleNode } from '../types';
import ModuleHierarchyJsonEditor from './ModuleHierarchyJsonEditor';

const { Text } = Typography;

const EDIT_NODE_TAG = {
  MODULE: { color: 'geekblue', label: '模块' },
  SUB_MODULE: { color: 'cyan', label: '子模块' },
  FUNCTION: { color: 'green', label: '功能' },
} as const;

function stopTreeEvent(e: React.SyntheticEvent) {
  e.stopPropagation();
}

function ConfirmedCheckbox({
  checked,
  onChange,
}: {
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <Tooltip title={checked ? '已确认（点击取消）' : '未确认（点击标记为已确认）'}>
      <Checkbox checked={checked} onChange={(e) => onChange(e.target.checked)} onClick={stopTreeEvent} />
    </Tooltip>
  );
}

function TagsFieldPopover({
  label,
  value,
  onChange,
  placeholder,
  title,
}: {
  label: string;
  value: string[];
  onChange: (v: string[]) => void;
  placeholder: string;
  title?: string;
}) {
  const count = value?.length ?? 0;
  return (
    <Popover
      trigger="click"
      title={title ?? label}
      content={
        <Select
          mode="tags"
          value={value}
          onChange={onChange}
          placeholder={placeholder}
          style={{ width: 280 }}
          tokenSeparators={[',', ' ']}
          open={false}
          suffixIcon={null}
        />
      }
    >
      <Button type="link" size="small" className="ci-tree-meta-btn" onClick={stopTreeEvent}>
        {count > 0 ? `${label}·${count}` : label}
      </Button>
    </Popover>
  );
}

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface ModuleHierarchyEditorProps {
  // ============ 受控模式（新）：父组件持有 hierarchy 状态 ============
  /** 当前模块层级。父组件负责加载（getModuleHierarchy / getPublishedHierarchy 等）。 */
  value?: ModuleHierarchy | null;
  /** 树形/JSON 编辑时回传给父组件的最新值 */
  onChange?: (next: ModuleHierarchy | null) => void;
  /** 父组件提交保存（resolve 后认为提交成功；reject 时由父组件处理错误） */
  onSubmit?: (hierarchy: ModuleHierarchy) => Promise<void> | void;
  /** 父组件标识的「正在加载初始数据」状态 */
  loading?: boolean;
  /** 父组件标识的「正在提交」状态（控制 renderSubmit 的 saving 形参） */
  saving?: boolean;

  // ============ 任务流模式（保留旧行为，向后兼容）============
  /**
   * 任务流模式下的 taskId。非空时启用自加载（getModuleHierarchy）+ 自提交
   * （replaceModuleHierarchy + resumeModuleHierarchyReview），与 onChange/onSubmit 互斥。
   */
  taskId?: number | null;
  /** 任务流模式下，提交成功后通知父组件刷新 */
  onSubmitted?: () => void;

  // ============ 通用定制点 ============
  /** 渲染自定义的「保存」按钮（不传则不渲染） */
  renderSubmit?: (handleSubmit: () => void, saving: boolean) => React.ReactNode;
  /** 渲染顶部的额外说明（不传则渲染默认 Alert） */
  renderAlert?: () => React.ReactNode;
  /** 是否允许拖拽节点（默认 true）。受控只读场景可传 false。 */
  enableDrag?: boolean;
}

interface EditorDataNode extends DataNode {
  nodeType: 'MODULE' | 'SUB_MODULE' | 'FUNCTION';
  /** 方便 allowDrop / onDrop 判断兄弟关系 */
  parentModuleId?: string;
  parentSubModuleId?: string;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** 递归把 hierarchy 中的 confirmed 从 "Y"/"N" 字符串 → boolean */
function yonHierarchyFromDisplay(obj: unknown): any {
  if (obj == null) return obj;
  if (Array.isArray(obj)) return (obj as unknown[]).map(yonHierarchyFromDisplay);
  if (typeof obj === 'object') {
    const result: Record<string, unknown> = {};
    for (const key of Object.keys(obj as Record<string, unknown>)) {
      const val = (obj as Record<string, unknown>)[key];
      if (key === 'confirmed') {
        if (val === 'Y' || val === 'y' || val === true || val === 'true') { result[key] = true; }
        else { result[key] = false; }
      } else {
        result[key] = yonHierarchyFromDisplay(val);
      }
    }
    return result;
  }
  return obj;
}

/** 根据 nodeKey 在 hierarchy 中定位节点，返回其所在容器引用信息 */
function findNodeLocation(
  h: ModuleHierarchy,
  nodeKey: string,
): {
  type: 'MODULE' | 'SUB_MODULE' | 'FUNCTION';
  moduleId?: string;
  subModuleId?: string;
} | null {
  if (h.modules?.[nodeKey]) return { type: 'MODULE' };
  for (const [modId, mod] of Object.entries(h.modules ?? {})) {
    if (mod.subModules?.[nodeKey]) return { type: 'SUB_MODULE', moduleId: modId };
    for (const [subId, sub] of Object.entries(mod.subModules ?? {})) {
      if (sub.functions?.[nodeKey]) return { type: 'FUNCTION', moduleId: modId, subModuleId: subId };
    }
  }
  return null;
}

/** 生成候选 ID：前缀 + 4 位 Base36（总长 5，与后端 Base62Generator.generateWithPrefix(4) 一致） */
function generateCandidateId(prefix: 'm' | 's' | 'f', existing: string[]): string {
  // 后端校验长度为 5（prefix + 4 位随机），这里同步匹配；
  // 同时包容可能存在的旧版 6 位 ID（prefix + 5 位），只按前缀过滤、不限制长度
  const used = new Set(
    existing
      .filter((id) => id?.startsWith(prefix))
      .map((id) => id.substring(1)),
  );
  for (let counter = 0; counter < 36 ** 4; counter++) {
    const body = counter.toString(36).padStart(4, '0');
    if (!used.has(body)) return `${prefix}${body}`;
  }
  return `${prefix}0000`;
}

// ---------------------------------------------------------------------------
// Main component
// ---------------------------------------------------------------------------

const ModuleHierarchyEditor: React.FC<ModuleHierarchyEditorProps> = ({
  taskId,
  value,
  onChange,
  onSubmit,
  loading: loadingProp,
  saving: savingProp,
  onSubmitted,
  renderSubmit,
  renderAlert,
  enableDrag = true,
}) => {
  /** 受控模式：未传 taskId 时由父组件通过 value/onChange 持有 hierarchy */
  const isControlled = taskId == null;

  // 内部 state（受控模式下也保留镜像，用于渲染 + ref 同步；外部 value 变化时由 effect 同步）
  const [internalLoading, setInternalLoading] = useState(false);
  const [internalSaving, setInternalSaving] = useState(false);
  const [hierarchy, setHierarchy] = useState<ModuleHierarchy | null>(value ?? null);
  const [expandedKeys, setExpandedKeys] = useState<React.Key[]>([]);
  const hierarchyRef = useRef<ModuleHierarchy | null>(hierarchy);

  // 同步 ref，确保 onDrop / applyHierarchy 等回调读到最新 hierarchy
  useEffect(() => {
    hierarchyRef.current = hierarchy;
  }, [hierarchy]);

  // 受控模式：value 变化 → 同步到内部 state + ref；展开状态只在 null→non-null 时重置
  const wasNullRef = useRef<boolean>(true);
  useEffect(() => {
    if (!isControlled) return;
    const next = value ?? null;
    setHierarchy(next);
    hierarchyRef.current = next;
    if (wasNullRef.current && next !== null) {
      setExpandedKeys([]);
    }
    wasNullRef.current = next === null;
  }, [isControlled, value]);

  // 任务流模式：自加载（getModuleHierarchy）
  useEffect(() => {
    if (isControlled || taskId == null) return;
    let cancelled = false;
    setInternalLoading(true);
    setHierarchy(null);
    setExpandedKeys([]);
    wasNullRef.current = true;
    getModuleHierarchy(taskId)
      .then((data) => {
        if (cancelled) return;
        // 把后端返回的 "Y"/"N" 字符串统一转成 boolean，使树形 Checkbox 正确显示
        const normalized = yonHierarchyFromDisplay(data ?? { taskId, modules: {} });
        setHierarchy(normalized);
        hierarchyRef.current = normalized;
        wasNullRef.current = false;
      })
      .catch(() => {
        // request.ts 拦截器已统一弹错
      })
      .finally(() => {
        if (!cancelled) setInternalLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [isControlled, taskId]);

  // 统一的「应用变更」入口
  // - 受控模式：基于 hierarchyRef.current 计算新值，eager 写回 ref，调用 onChange 让父组件回填
  // - 非受控模式：直接 setHierarchy
  const applyHierarchy = useCallback(
    (
      next:
        | ModuleHierarchy
        | null
        | ((prev: ModuleHierarchy | null) => ModuleHierarchy | null),
    ) => {
      if (isControlled) {
        const base = hierarchyRef.current;
        const computed = typeof next === 'function' ? next(base) : next;
        if (computed === base) return;
        hierarchyRef.current = computed;
        onChange?.(computed);
      } else {
        setHierarchy((prev) => (typeof next === 'function' ? next(prev) : next));
      }
    },
    [isControlled, onChange],
  );

  // --------------- Mutation helpers ---------------

  const updateModule = useCallback((moduleId: string, next: ModuleNode | null) => {
    applyHierarchy((prev) => {
      if (!prev) return prev;
      const modules = { ...(prev.modules ?? {}) };
      if (next === null) {
        delete modules[moduleId];
      } else {
        modules[moduleId] = next;
      }
      return { ...prev, modules };
    });
  }, [applyHierarchy]);

  const updateSubModule = useCallback(
    (moduleId: string, subId: string, next: SubModuleNode | null) => {
      applyHierarchy((prev) => {
        if (!prev) return prev;
        const m = prev.modules?.[moduleId];
        if (!m) return prev;
        const subs = { ...(m.subModules ?? {}) };
        if (next === null) {
          delete subs[subId];
        } else {
          subs[subId] = next;
        }
        return {
          ...prev,
          modules: { ...prev.modules, [moduleId]: { ...m, subModules: subs } },
        };
      });
    },
    [applyHierarchy],
  );

  const updateFunction = useCallback(
    (moduleId: string, subId: string, fnId: string, next: FunctionNode | null) => {
      applyHierarchy((prev) => {
        if (!prev) return prev;
        const m = prev.modules?.[moduleId];
        const sm = m?.subModules?.[subId];
        if (!m || !sm) return prev;
        const fns = { ...(sm.functions ?? {}) };
        if (next === null) {
          delete fns[fnId];
        } else {
          fns[fnId] = next;
        }
        return {
          ...prev,
          modules: {
            ...prev.modules,
            [moduleId]: {
              ...m,
              subModules: {
                ...m.subModules,
                [subId]: { ...sm, functions: fns },
              },
            },
          },
        };
      });
    },
    [applyHierarchy],
  );

  const addModule = useCallback(() => {
    applyHierarchy((prev) => {
      if (!prev) return prev;
      const existingIds = Object.keys(prev.modules ?? {});
      const newId = generateCandidateId('m', existingIds);
      const newMod: ModuleNode = {
        id: newId,
        moduleName: '新模块',
        keywords: [],
        subModules: {},
      };
      // 自动展开新模块
      setExpandedKeys((keys) => [...keys, newId]);
      return { ...prev, modules: { ...(prev.modules ?? {}), [newId]: newMod } };
    });
  }, [applyHierarchy]);

  const addSubModule = useCallback((moduleId: string) => {
    applyHierarchy((prev) => {
      if (!prev) return prev;
      const m = prev.modules?.[moduleId];
      if (!m) return prev;
      const existingIds = Object.keys(m.subModules ?? {});
      const newId = generateCandidateId('s', existingIds);
      const newSm: SubModuleNode = {
        id: newId,
        subModuleName: '新子模块',
        keywords: [],
        functions: {},
      };
      // 展开父模块以显示新增子模块
      setExpandedKeys((keys) => {
        if (!keys.includes(moduleId)) return [...keys, moduleId];
        return keys;
      });
      return {
        ...prev,
        modules: {
          ...prev.modules,
          [moduleId]: {
            ...m,
            subModules: { ...(m.subModules ?? {}), [newId]: newSm },
          },
        },
      };
    });
  }, [applyHierarchy]);

  const addFunction = useCallback((moduleId: string, subId: string) => {
    applyHierarchy((prev) => {
      if (!prev) return prev;
      const m = prev.modules?.[moduleId];
      const sm = m?.subModules?.[subId];
      if (!m || !sm) return prev;
      const existingIds = Object.keys(sm.functions ?? {});
      const newId = generateCandidateId('f', existingIds);
      const newFn: FunctionNode = {
        id: newId,
        functionName: '新功能',
        classPaths: [],
      };
      // 展开父模块与子模块以显示新增功能
      setExpandedKeys((keys) => {
        const next = new Set(keys);
        next.add(moduleId);
        next.add(subId);
        return Array.from(next);
      });
      return {
        ...prev,
        modules: {
          ...prev.modules,
          [moduleId]: {
            ...m,
            subModules: {
              ...m.subModules,
              [subId]: {
                ...sm,
                functions: { ...(sm.functions ?? {}), [newId]: newFn },
              },
            },
          },
        },
      };
    });
  }, [applyHierarchy]);

  // --------------- Drag & Drop ---------------

  const allowDrop: TreeProps['allowDrop'] = useCallback(({ dragNode, dropNode, dropPosition }) => {
    const drag = dragNode as unknown as EditorDataNode;
    const drop = dropNode as unknown as EditorDataNode;
    if (!drag || !drop) return false;

    if (dropPosition === 0) {
      // 放入节点内部：s → m（移动子模块到模块）, f → s（移动功能到子模块）
      return (
        (drag.nodeType === 'SUB_MODULE' && drop.nodeType === 'MODULE') ||
        (drag.nodeType === 'FUNCTION' && drop.nodeType === 'SUB_MODULE')
      );
    }
    // 间隙放置：只能同类型 + 同父节点（排序）
    if (drag.nodeType !== drop.nodeType) return false;
    if (drag.nodeType === 'SUB_MODULE') {
      return drag.parentModuleId === drop.parentModuleId;
    }
    if (drag.nodeType === 'FUNCTION') {
      return drag.parentSubModuleId === drop.parentSubModuleId;
    }
    return false;
  }, []);

  const onDrop: TreeProps['onDrop'] = useCallback((info) => {
    applyHierarchy((prev) => {
      if (!prev) return prev;

      const dragNode = info.dragNode as unknown as EditorDataNode;
      const dropNode = info.node as unknown as EditorDataNode;
      const dragKey = String(dragNode.key);
      const dropKey = String(dropNode.key);

      if (dragKey === dropKey) return prev;

      if (!info.dropToGap) {
        // ---- 放入节点内部：改变父子关系 ----
        if (dragNode.nodeType === 'SUB_MODULE' && dropNode.nodeType === 'MODULE') {
          // 移动子模块到另一个模块
          const loc = findNodeLocation(prev, dragKey);
          if (!loc || loc.type !== 'SUB_MODULE' || loc.moduleId === dropKey) return prev;
          const oldModId = loc.moduleId!;
          const modules = { ...(prev.modules ?? {}) };
          const oldMod = { ...modules[oldModId] };
          const subs = { ...(oldMod.subModules ?? {}) };
          const moved = subs[dragKey];
          if (!moved) return prev;
          delete subs[dragKey];
          oldMod.subModules = subs;
          modules[oldModId] = oldMod;
          const newMod = { ...modules[dropKey] };
          newMod.subModules = { ...(newMod.subModules ?? {}), [dragKey]: moved };
          modules[dropKey] = newMod;
          return { ...prev, modules };
        }
        if (dragNode.nodeType === 'FUNCTION' && dropNode.nodeType === 'SUB_MODULE') {
          // 移动功能到另一个子模块
          const loc = findNodeLocation(prev, dragKey);
          if (!loc || loc.type !== 'FUNCTION' || loc.subModuleId === dropKey) return prev;
          const oldModId = loc.moduleId!;
          const oldSubId = loc.subModuleId!;
          const modules = { ...(prev.modules ?? {}) };
          const oldMod = { ...modules[oldModId] };
          const oldSubs = { ...(oldMod.subModules ?? {}) };
          const oldSub = { ...oldSubs[oldSubId] };
          const fns = { ...(oldSub.functions ?? {}) };
          const moved = fns[dragKey];
          if (!moved) return prev;
          delete fns[dragKey];
          oldSub.functions = fns;
          oldSubs[oldSubId] = oldSub;
          oldMod.subModules = oldSubs;
          modules[oldModId] = oldMod;

          // 找到目标子模块所在模块
          const targetModId = dropNode.parentModuleId!;
          const targetMod = { ...modules[targetModId] };
          const targetSubs = { ...(targetMod.subModules ?? {}) };
          const targetSub = { ...targetSubs[dropKey] };
          targetSub.functions = { ...(targetSub.functions ?? {}), [dragKey]: moved };
          targetSubs[dropKey] = targetSub;
          targetMod.subModules = targetSubs;
          modules[targetModId] = targetMod;

          return { ...prev, modules };
        }
        return prev;
      }

      // ---- 间隙放置：同级排序 ----
      if (dragNode.nodeType === 'SUB_MODULE' && dropNode.nodeType === 'SUB_MODULE') {
        const modId = dragNode.parentModuleId!;
        const modules = { ...(prev.modules ?? {}) };
        const mod = { ...modules[modId] };
        const subs = { ...(mod.subModules ?? {}) };
        const entries = Object.entries(subs);
        const dragIdx = entries.findIndex(([k]) => k === dragKey);
        if (dragIdx === -1) return prev;
        const [moved] = entries.splice(dragIdx, 1);
        let dropIdx = entries.findIndex(([k]) => k === dropKey);
        if (dropIdx === -1) return prev;
        if (info.dropPosition === 1) dropIdx += 1;
        entries.splice(dropIdx, 0, moved);
        mod.subModules = Object.fromEntries(entries);
        modules[modId] = mod;
        return { ...prev, modules };
      }

      if (dragNode.nodeType === 'FUNCTION' && dropNode.nodeType === 'FUNCTION') {
        const modId = dragNode.parentModuleId!;
        const subId = dragNode.parentSubModuleId!;
        const modules = { ...(prev.modules ?? {}) };
        const mod = { ...modules[modId] };
        const subs = { ...(mod.subModules ?? {}) };
        const sub = { ...subs[subId] };
        const fns = { ...(sub.functions ?? {}) };
        const entries = Object.entries(fns);
        const dragIdx = entries.findIndex(([k]) => k === dragKey);
        if (dragIdx === -1) return prev;
        const [moved] = entries.splice(dragIdx, 1);
        let dropIdx = entries.findIndex(([k]) => k === dropKey);
        if (dropIdx === -1) return prev;
        if (info.dropPosition === 1) dropIdx += 1;
        entries.splice(dropIdx, 0, moved);
        sub.functions = Object.fromEntries(entries);
        subs[subId] = sub;
        mod.subModules = subs;
        modules[modId] = mod;
        return { ...prev, modules };
      }

      return prev;
    });
  }, [applyHierarchy]);

  // --------------- Tree data ---------------

  const treeData: EditorDataNode[] = useMemo(() => {
    if (!hierarchy) return [];
    return Object.values(hierarchy.modules ?? {}).map((mod) => {
      const subChildren: EditorDataNode[] = Object.values(mod.subModules ?? {}).map((sub) => {
        const fnChildren: EditorDataNode[] = Object.values(sub.functions ?? {}).map((fn) => ({
          key: fn.id,
          title: fn.functionName,
          nodeType: 'FUNCTION' as const,
          parentModuleId: mod.id,
          parentSubModuleId: sub.id,
          isLeaf: true,
        }));
        return {
          key: sub.id,
          title: sub.subModuleName,
          nodeType: 'SUB_MODULE' as const,
          parentModuleId: mod.id,
          children: fnChildren,
        };
      });
      return {
        key: mod.id,
        title: mod.moduleName,
        nodeType: 'MODULE' as const,
        children: subChildren,
      };
    });
  }, [hierarchy]);

  const allExpandableKeys = useMemo(() => collectExpandableKeys(treeData), [treeData]);

  // --------------- titleRender ---------------
  // 直接使用 hierarchy state（非 ref），确保渲染期间读到最新值

  const titleRender = (nodeData: DataNode) => {
    const nd = nodeData as EditorDataNode;
    if (!hierarchy) return <Text type="secondary">—</Text>;

      if (nd.nodeType === 'MODULE') {
        const mod = hierarchy.modules?.[nd.key as string];
        if (!mod) return <Text type="secondary">—</Text>;
        return (
          <div className="ci-knowledge-tree-node ci-tree-node-edit" onMouseDown={stopTreeEvent}>
            <HolderOutlined className="ci-tree-drag-handle" />
            <Tag color={EDIT_NODE_TAG.MODULE.color} className="ci-tree-node-tag">
              {EDIT_NODE_TAG.MODULE.label}
            </Tag>
            <Input
              size="small"
              variant="borderless"
              className="ci-tree-edit-name"
              value={mod.moduleName}
              onChange={(e) => updateModule(mod.id, { ...mod, moduleName: e.target.value })}
              placeholder="模块名"
              onClick={stopTreeEvent}
            />
            <ConfirmedCheckbox
              checked={!!mod.confirmed}
              onChange={(confirmed) => updateModule(mod.id, { ...mod, confirmed })}
            />
            <TagsFieldPopover
              label="关键词"
              value={mod.keywords ?? []}
              onChange={(keywords) => updateModule(mod.id, { ...mod, keywords })}
              placeholder="输入关键词后回车"
            />
            <span className="ci-tree-node-actions">
              <Tooltip title="新增子模块">
                <Button
                  size="small"
                  type="link"
                  icon={<PlusOutlined />}
                  onClick={(e) => {
                    e.stopPropagation();
                    addSubModule(mod.id);
                  }}
                />
              </Tooltip>
              <Popconfirm
                title="确认删除该模块及其所有子节点？"
                onConfirm={(e) => {
                  e?.stopPropagation();
                  updateModule(mod.id, null);
                }}
                onCancel={(e) => e?.stopPropagation()}
              >
                <Button
                  size="small"
                  type="link"
                  danger
                  icon={<DeleteOutlined />}
                  onClick={stopTreeEvent}
                />
              </Popconfirm>
            </span>
          </div>
        );
      }

      if (nd.nodeType === 'SUB_MODULE') {
        const modId = nd.parentModuleId!;
        const sub = hierarchy.modules?.[modId]?.subModules?.[nd.key as string];
        if (!sub) return <Text type="secondary">—</Text>;
        return (
          <div className="ci-knowledge-tree-node ci-tree-node-edit" onMouseDown={stopTreeEvent}>
            <HolderOutlined className="ci-tree-drag-handle" />
            <Tag color={EDIT_NODE_TAG.SUB_MODULE.color} className="ci-tree-node-tag">
              {EDIT_NODE_TAG.SUB_MODULE.label}
            </Tag>
            <Input
              size="small"
              variant="borderless"
              className="ci-tree-edit-name"
              value={sub.subModuleName}
              onChange={(e) =>
                updateSubModule(modId, sub.id, { ...sub, subModuleName: e.target.value })
              }
              placeholder="子模块名"
              onClick={stopTreeEvent}
            />
            <ConfirmedCheckbox
              checked={!!sub.confirmed}
              onChange={(confirmed) => updateSubModule(modId, sub.id, { ...sub, confirmed })}
            />
            <TagsFieldPopover
              label="关键词"
              value={sub.keywords ?? []}
              onChange={(keywords) => updateSubModule(modId, sub.id, { ...sub, keywords })}
              placeholder="输入关键词后回车"
            />
            <span className="ci-tree-node-actions">
              <Tooltip title="新增功能">
                <Button
                  size="small"
                  type="link"
                  icon={<PlusOutlined />}
                  onClick={(e) => {
                    e.stopPropagation();
                    addFunction(modId, sub.id);
                  }}
                />
              </Tooltip>
              <Popconfirm
                title="确认删除该子模块及其所有功能？"
                onConfirm={(e) => {
                  e?.stopPropagation();
                  updateSubModule(modId, sub.id, null);
                }}
                onCancel={(e) => e?.stopPropagation()}
              >
                <Button
                  size="small"
                  type="link"
                  danger
                  icon={<DeleteOutlined />}
                  onClick={stopTreeEvent}
                />
              </Popconfirm>
            </span>
          </div>
        );
      }

      // FUNCTION
      const modId = nd.parentModuleId!;
      const subId = nd.parentSubModuleId!;
      const fn = hierarchy.modules?.[modId]?.subModules?.[subId]?.functions?.[nd.key as string];
      if (!fn) return <Text type="secondary">—</Text>;
      return (
        <div className="ci-knowledge-tree-node ci-tree-node-edit" onMouseDown={stopTreeEvent}>
          <HolderOutlined className="ci-tree-drag-handle" />
          <Tag color={EDIT_NODE_TAG.FUNCTION.color} className="ci-tree-node-tag">
            {EDIT_NODE_TAG.FUNCTION.label}
          </Tag>
          <Input
            size="small"
            variant="borderless"
            className="ci-tree-edit-name"
            value={fn.functionName}
            onChange={(e) =>
              updateFunction(modId, subId, fn.id, { ...fn, functionName: e.target.value })
            }
            placeholder="功能名"
            onClick={stopTreeEvent}
          />
          <ConfirmedCheckbox
            checked={!!fn.confirmed}
            onChange={(confirmed) =>
              updateFunction(modId, subId, fn.id, { ...fn, confirmed })
            }
          />
          <TagsFieldPopover
            label="类路径"
            title="入口类全限定名（落表保存，提示词中剥离）"
            value={fn.classPaths ?? []}
            onChange={(classPaths) => updateFunction(modId, subId, fn.id, { ...fn, classPaths })}
            placeholder="如 com.example.Controller"
          />
          <TagsFieldPopover
            label="方法签名"
            title="methodName(ParamTypes)，不含返回类型；用于文档生成与代码来源"
            value={fn.methodSignatures ?? []}
            onChange={(methodSignatures) =>
              updateFunction(modId, subId, fn.id, { ...fn, methodSignatures })
            }
            placeholder="如 listUsers(Integer, Integer)"
          />
          <span className="ci-tree-node-actions">
            <Popconfirm
              title="确认删除该功能节点？"
              onConfirm={(e) => {
                e?.stopPropagation();
                updateFunction(modId, subId, fn.id, null);
              }}
              onCancel={(e) => e?.stopPropagation()}
            >
              <Button
                size="small"
                type="link"
                danger
                icon={<DeleteOutlined />}
                onClick={stopTreeEvent}
              />
            </Popconfirm>
          </span>
        </div>
      );
    };

  // --------------- Submit ---------------

  const handleSubmit = async () => {
    // 注意：handleSubmit 只在 click 事件里触发，那时 hierarchy state 一定是最新的；
    // 不读 hierarchyRef（避免 react-hooks/refs 误判 + 防止 ref 渲染期访问）。
    if (!hierarchy) return;
    if (isControlled) {
      // 受控模式：完全交给父组件处理（含错误捕获与状态管理）
      await onSubmit?.(hierarchy);
      return;
    }
    // 任务流模式：内部直接调后端 API
    setInternalSaving(true);
    try {
      await replaceModuleHierarchy(taskId!, hierarchy);
      await resumeModuleHierarchyReview(taskId!);
      message.success('已保存并提交继续生成文档');
      onSubmitted?.();
    } finally {
      setInternalSaving(false);
    }
  };

  // --------------- Render ---------------

  const moduleCount = Object.keys(hierarchy?.modules ?? {}).length;
  const isLoading = isControlled ? !!loadingProp : internalLoading;
  const isSaving = isControlled ? !!savingProp : internalSaving;

  // 当前激活的 tab：tree（默认）/ json
  const [activeTab, setActiveTab] = useState<'tree' | 'json'>('tree');

  // 批量确认操作
  const handleConfirmAll = (value: boolean) => {
    applyHierarchy((prev) => {
      if (!prev) return prev;
      const modules = { ...(prev.modules ?? {}) };
      for (const modKey of Object.keys(modules)) {
        const mod = modules[modKey];
        if (!mod) continue;
        const newSubModules: Record<string, SubModuleNode> = { ...(mod.subModules ?? {}) };
        for (const subKey of Object.keys(newSubModules)) {
          const sub = newSubModules[subKey];
          if (!sub) continue;
          const newFns: Record<string, FunctionNode> = { ...(sub.functions ?? {}) };
          for (const fnKey of Object.keys(newFns)) {
            const fn = newFns[fnKey];
            if (!fn) continue;
            newFns[fnKey] = { ...fn, confirmed: value };
          }
          newSubModules[subKey] = { ...sub, confirmed: value, functions: newFns };
        }
        modules[modKey] = { ...mod, confirmed: value, subModules: newSubModules };
      }
      return { ...prev, modules };
    });
  };

  if (isLoading) {
    return (
      <div style={{ padding: 48, textAlign: 'center' }}>
        <LoadingOutlined /> 正在加载模块层级...
      </div>
    );
  }

  if (!hierarchy) {
    return <Empty description="未加载到模块层级" />;
  }

  return (
    <div>
      {/* 顶部说明 */}
      {renderAlert ? (
        renderAlert()
      ) : (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="模块层级调试说明"
          description={
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              <li>
                树状展示模块 → 子模块 → 功能；默认仅显示模块，点击箭头展开查看子级。
              </li>
              <li>
                拖拽 <HolderOutlined /> 手柄可移动子模块（放入另一模块）或功能（放入另一子模块），同层间隙放置可排序。
              </li>
              <li>
                每个节点右侧带「已确认/未确认」复选框，用于逐项标记人工复核进度；JSON 中以 <Text code>"Y"</Text> / <Text code>"N"</Text>{' '}
                呈现。也可以切换到 <b>JSON 编辑</b> tab 直接基于 JSON 文本快速批量修改。
              </li>
              <li>
                功能节点的「类路径」与其它字段一起整体落表 <Text code>ci_module_hierarchy</Text>
                （FUNCTION 行 class_paths 列），服务重启不会丢失。
              </li>
              <li>
                「类路径」与「已确认」仅在调用 AI 时被剥离，不会出现在 analyze / module_doc 提示词中。
              </li>
              <li>
                点击「保存并继续」将落表 ModuleHierarchy 并推进流水线至 GENERATING_DOC。
              </li>
            </ul>
          }
        />
      )}

      {/* 操作栏 */}
      <div
        style={{
          marginBottom: 12,
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: 8,
        }}
      >
        <Space>
          <Button type="dashed" icon={<PlusOutlined />} onClick={addModule}>
            新增模块
          </Button>
          {moduleCount > 0 && (
            <>
              <Tooltip title="全部展开">
                <Button
                  size="small"
                  type="text"
                  icon={<NodeExpandOutlined />}
                  disabled={treeData.length === 0}
                  onClick={() => setExpandedKeys(allExpandableKeys)}
                />
              </Tooltip>
              <Tooltip title="全部折叠">
                <Button
                  size="small"
                  type="text"
                  icon={<NodeCollapseOutlined />}
                  disabled={treeData.length === 0}
                  onClick={() => setExpandedKeys([])}
                />
              </Tooltip>
            </>
          )}
          {moduleCount > 0 && (
            <>
              <Button
                size="small"
                icon={<CheckCircleOutlined />}
                onClick={() => handleConfirmAll(true)}
              >
                全部标记为已确认
              </Button>
              <Button size="small" onClick={() => handleConfirmAll(false)}>
                取消全部确认
              </Button>
            </>
          )}
        </Space>
        {renderSubmit && renderSubmit(handleSubmit, isSaving)}
      </div>

      <Tabs
        activeKey={activeTab}
        onChange={(k) => setActiveTab(k as 'tree' | 'json')}
        items={[
          {
            key: 'tree',
            label: '树形编辑',
            children: (
              <>
                {moduleCount === 0 ? (
                  <Empty description="尚无模块，点击上方「新增模块」按钮添加" />
                ) : (
                  <div className="ci-hierarchy-tree-panel ci-hierarchy-tree-edit-panel">
                    <Tree
                      className="ci-hierarchy-tree ci-hierarchy-tree--compact"
                      treeData={treeData}
                      titleRender={titleRender}
                      draggable={
                        enableDrag
                          ? { icon: false, nodeDraggable: () => true }
                          : false
                      }
                      allowDrop={allowDrop}
                      onDrop={onDrop}
                      expandedKeys={expandedKeys}
                      onExpand={(keys) => setExpandedKeys(keys)}
                      blockNode
                      showLine={{ showLeafIcon: false }}
                      style={{ fontSize: 13 }}
                      motion={{
                        motionName: '',
                        motionAppear: false,
                        onAppearStart: () => ({ height: 0, opacity: 0 }),
                        onAppearActive: () => ({ height: 'auto', opacity: 1 }),
                        onLeaveStart: () => ({ height: 'auto', opacity: 1 }),
                        onLeaveActive: () => ({ height: 0, opacity: 0 }),
                      }}
                      virtual={false}
                    />
                  </div>
                )}
              </>
            ),
          },
          {
            key: 'json',
            label: 'JSON 编辑',
            children: (
              <ModuleHierarchyJsonEditor
                value={hierarchy}
                onChange={(next) => applyHierarchy(next)}
              />
            ),
          },
        ]}
      />
    </div>
  );
};

export default ModuleHierarchyEditor;
