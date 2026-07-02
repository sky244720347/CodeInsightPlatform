import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Input, Space, Typography, message } from 'antd';
import { CheckOutlined, FormatPainterOutlined, ReloadOutlined } from '@ant-design/icons';
import type {
  FunctionNode,
  ModuleHierarchy,
  ModuleNode,
  SubModuleNode,
} from '../types';

const { Text } = Typography;

export interface ModuleHierarchyJsonEditorProps {
  /** 当前模块层级数据（从父组件传入） */
  value: ModuleHierarchy;
  /** 用户提交 "应用" 时把解析结果回传给父组件 */
  onChange: (next: ModuleHierarchy) => void;
}

/**
 * 模块层级 JSON 编辑器：直接以 JSON 文本形式展示与编辑整个模块层级。
 * <ul>
 *   <li>首次加载 / 父组件 value 变化时，把 JSON pretty-printed 填到文本框</li>
 *   <li>「应用」按钮触发本地 JSON 合法性校验 + 结构校验，通过则 onChange</li>
 *   <li>「格式化」按钮把当前文本重新格式化（仅 UI 表现，不调 onChange）</li>
 *   <li>「重置」按钮放弃编辑、回到 value 的最新快照</li>
 * </ul>
 *
 * 注意：JSON 中的 confirmed 字段以 "Y"/"N" 字符串呈现（后端 YnBoolean 序列化器）；
 * 本地校验仅校验结构（节点 ID 前缀 + 长度 + 名称非空 + 父子关系合法），
 * 不校验 confirmed 取值——后端反序列化器会给出清晰错误。
 */
/** 递归把 confirmed 从 boolean → "Y"/"N" 字符串(用于 JSON 展示) */
function yonToDisplay(obj: unknown): unknown {
  if (obj == null) return obj;
  if (Array.isArray(obj)) return (obj as unknown[]).map(yonToDisplay);
  if (typeof obj === 'object') {
    const result: Record<string, unknown> = {};
    for (const key of Object.keys(obj as Record<string, unknown>)) {
      const val = (obj as Record<string, unknown>)[key];
      if (key === 'confirmed') {
        if (typeof val === 'boolean') { result[key] = val ? 'Y' : 'N'; }
        else if (val == null) { result[key] = 'N'; }
        else { result[key] = yonToDisplay(val); }
      } else {
        result[key] = yonToDisplay(val);
      }
    }
    return result;
  }
  return obj;
}

/** 递归把 confirmed 从 "Y"/"N"/boolean 字符串 → boolean(用于树形编辑) */
function yonFromDisplay(obj: unknown): unknown {
  if (obj == null) return obj;
  if (Array.isArray(obj)) return (obj as unknown[]).map(yonFromDisplay);
  if (typeof obj === 'object') {
    const result: Record<string, unknown> = {};
    for (const key of Object.keys(obj as Record<string, unknown>)) {
      const val = (obj as Record<string, unknown>)[key];
      if (key === 'confirmed') {
        if (val === 'Y' || val === 'y' || val === true || val === 'true') { result[key] = true; }
        else if (val === 'N' || val === 'n' || val === false || val === 'false' || val == null) { result[key] = false; }
        else { result[key] = !!val; }
      } else {
        result[key] = yonFromDisplay(val);
      }
    }
    return result;
  }
  return obj;
}

const ModuleHierarchyJsonEditor: React.FC<ModuleHierarchyJsonEditorProps> = ({
  value,
  onChange,
}) => {
  // 当前正在编辑的文本（与 value 不同步，体现用户编辑过程）
  const [text, setText] = useState<string>('');

  // 把 value 序列化成 pretty-printed JSON 字符串作为初始 / 重置来源
  // value 来自 hierarchy state (树形编辑),confirmed 是 boolean → 序列化为 "Y"/"N" 展示
  const snapshotJson = useMemo(() => JSON.stringify(yonToDisplay(value ?? {}), null, 2), [value]);

  // 当父组件 value 变化时（如首次加载、JSON tab 切换、抽屉重开），同步文本
  useEffect(() => {
    setText(snapshotJson);
  }, [snapshotJson]);

  // 本地校验 + 应用
  const handleApply = () => {
    let parsed: unknown;
    try {
      parsed = yonFromDisplay(JSON.parse(text));
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      message.error('JSON 解析失败：' + msg);
      return;
    }
    const validation = validateStructure(parsed);
    if (validation) {
      message.error('结构校验失败：' + validation);
      return;
    }
    onChange(parsed as ModuleHierarchy);
    message.success('已应用 JSON 修改（尚未保存到后端）');
  };

  const handleFormat = () => {
    try {
      const parsed = yonFromDisplay(JSON.parse(text));
      setText(JSON.stringify(yonToDisplay(parsed), null, 2));
      message.success('已格式化');
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      message.error('JSON 解析失败，无法格式化：' + msg);
    }
  };

  const handleReset = () => {
    setText(snapshotJson);
    message.info('已重置为最新已加载的模块层级');
  };

  return (
    <div>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="JSON 编辑说明"
        description={
          <ul style={{ margin: 0, paddingLeft: 18 }}>
            <li>
              直接以 JSON 形式编辑整个模块层级树，<strong>应用</strong>后会在树形编辑 tab 同步显示。
            </li>
            <li>
              <Text code>confirmed</Text> 字段以 <Text code>"Y"</Text> / <Text code>"N"</Text>{' '}
              字符串呈现；填 <Text code>true</Text> / <Text code>false</Text> 也可，后端都能解析。
            </li>
            <li>
              提交时若 JSON 不合法或结构错位（节点 ID 前缀不对、名称为空、父子关系缺失）会就地报错，
              不会写入数据库。
            </li>
          </ul>
        }
      />

      <Space style={{ marginBottom: 8 }}>
        <Button type="primary" icon={<CheckOutlined />} onClick={handleApply}>
          应用
        </Button>
        <Button icon={<FormatPainterOutlined />} onClick={handleFormat}>
          格式化
        </Button>
        <Button icon={<ReloadOutlined />} onClick={handleReset}>
          重置
        </Button>
        <Text type="secondary" style={{ fontSize: 12 }}>
          提示：先应用再保存，否则抽屉底部的「保存并继续」拿到的还是旧数据。
        </Text>
      </Space>

      <Input.TextArea
        value={text}
        onChange={(e) => setText(e.target.value)}
        rows={24}
        spellCheck={false}
        autoSize={false}
        style={{
          fontFamily:
            'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Monaco, Consolas, monospace',
          fontSize: 12,
          lineHeight: 1.5,
        }}
      />
    </div>
  );
};

export default ModuleHierarchyJsonEditor;

/**
 * 校验解析后的 JSON 是否符合 ModuleHierarchy 的最小结构。
 * 与后端 ModuleHierarchyServiceImpl.validateReplacement 保持一致（仅结构层）：
 * - modules 必须为对象
 * - 每个 module 必须以 m 开头且长度为 5，moduleName 非空
 * - subModules / functions 的 key 分别必须以 s / f 开头且长度为 5
 * - function 的 functionName 非空，classPaths 元素为字符串（若存在）
 *
 * confirmed 字段不校验取值（Y/N/true/false/null 都接受，后端反序列化器给出精确报错）。
 */
function validateStructure(value: unknown): string | null {
  if (!value || typeof value !== 'object') {
    return '顶层必须是 JSON 对象';
  }
  const root = value as { modules?: unknown };
  if (!root.modules || typeof root.modules !== 'object' || Array.isArray(root.modules)) {
    return '顶层必须包含 modules 对象（key 为模块 ID，value 为模块）';
  }
  const modulesObj = root.modules as Record<string, unknown>;
  for (const [modKey, modVal] of Object.entries(modulesObj)) {
    if (!isValidNodeId(modKey, 'm')) {
      return `模块 ID "${modKey}" 必须以 m 开头且长度为 5`;
    }
    const mErr = validateModule(modVal);
    if (mErr) return `模块 ${modKey}：${mErr}`;
  }
  return null;
}

function validateModule(modVal: unknown): string | null {
  if (!modVal || typeof modVal !== 'object') return '必须为对象';
  const m = modVal as ModuleNode;
  if (!m.moduleName || typeof m.moduleName !== 'string' || !m.moduleName.trim()) {
    return 'moduleName 不能为空';
  }
  if (m.subModules && typeof m.subModules === 'object' && !Array.isArray(m.subModules)) {
    for (const [subKey, subVal] of Object.entries(m.subModules)) {
      if (!isValidNodeId(subKey, 's')) {
        return `子模块 ID "${subKey}" 必须以 s 开头且长度为 5`;
      }
      const smErr = validateSubModule(subVal);
      if (smErr) return `子模块 ${subKey}：${smErr}`;
    }
  }
  return null;
}

function validateSubModule(subVal: unknown): string | null {
  if (!subVal || typeof subVal !== 'object') return '必须为对象';
  const sm = subVal as SubModuleNode;
  if (!sm.subModuleName || typeof sm.subModuleName !== 'string' || !sm.subModuleName.trim()) {
    return 'subModuleName 不能为空';
  }
  if (sm.functions && typeof sm.functions === 'object' && !Array.isArray(sm.functions)) {
    for (const [fnKey, fnVal] of Object.entries(sm.functions)) {
      if (!isValidNodeId(fnKey, 'f')) {
        return `功能 ID "${fnKey}" 必须以 f 开头且长度为 5`;
      }
      const fnErr = validateFunction(fnVal);
      if (fnErr) return `功能 ${fnKey}：${fnErr}`;
    }
  }
  return null;
}

function validateFunction(fnVal: unknown): string | null {
  if (!fnVal || typeof fnVal !== 'object') return '必须为对象';
  const fn = fnVal as FunctionNode;
  if (!fn.functionName || typeof fn.functionName !== 'string' || !fn.functionName.trim()) {
    return 'functionName 不能为空';
  }
  if (fn.classPaths != null) {
    if (!Array.isArray(fn.classPaths)) return 'classPaths 必须为字符串数组';
    for (const cp of fn.classPaths) {
      if (typeof cp !== 'string') return 'classPaths 中存在非字符串元素';
    }
  }
  if (fn.methodSignatures != null) {
    if (!Array.isArray(fn.methodSignatures)) return 'methodSignatures 必须为字符串数组';
    for (const sig of fn.methodSignatures) {
      if (typeof sig !== 'string') return 'methodSignatures 中存在非字符串元素';
    }
  }
  return null;
}

function isValidNodeId(id: string, prefix: 'm' | 's' | 'f'): boolean {
  return typeof id === 'string' && id.length === 5 && id.startsWith(prefix);
}