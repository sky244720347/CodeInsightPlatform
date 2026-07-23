import React from 'react';
import { Space, Tag, Typography } from 'antd';
import type { System } from '../types';

const { Text } = Typography;

/** 系统 Select 选项：label 仅系统名（搜索可另匹配 component） */
export interface SystemSelectOption {
  value: number;
  label: string;
  nameCn?: string;
  component?: string;
}

/** 将系统列表转为 Select options（value=systemId，label=纯 name） */
export function toSystemSelectOptions(
  systems: System[],
  opts?: { withNameCn?: boolean },
): SystemSelectOption[] {
  return systems.map((s) => ({
    value: s.id,
    label: opts?.withNameCn && s.nameCn ? `${s.name}（${s.nameCn}）` : s.name,
    nameCn: s.nameCn,
    component: s.component?.trim() || undefined,
  }));
}

/** 下拉选项渲染：系统名 + 独立组件 Tag */
export function renderSystemSelectOption(option: {
  data?: SystemSelectOption;
  label?: React.ReactNode;
}): React.ReactNode {
  const data = option.data;
  const label = data?.label ?? option.label;
  const component = data?.component;
  return (
    <Space size={6} wrap>
      <span>{label}</span>
      {component ? <Tag>{component}</Tag> : null}
    </Space>
  );
}

/** 已选值渲染：系统名 + 独立组件 Tag */
export function renderSystemSelectLabel(
  props: { label?: React.ReactNode; value?: string | number | null },
  systems: System[],
): React.ReactNode {
  const id = props.value == null || props.value === '' ? undefined : Number(props.value);
  const s = id != null && !Number.isNaN(id) ? systems.find((x) => x.id === id) : undefined;
  if (!s) return props.label;
  return (
    <Space size={6} wrap>
      <span>{s.name}</span>
      {s.component?.trim() ? <Tag>{s.component.trim()}</Tag> : null}
    </Space>
  );
}

/** 按系统名 / 中文名 / 组件过滤 */
export function filterSystemSelectOption(
  input: string,
  option?: SystemSelectOption,
): boolean {
  const q = input.trim().toLowerCase();
  if (!q || !option) return true;
  const hay = [option.label, option.nameCn, option.component]
    .filter(Boolean)
    .join(' ')
    .toLowerCase();
  return hay.includes(q);
}

/** 表格「组件」列单元格 */
export function renderComponentCell(component?: string | null): React.ReactNode {
  const v = component?.trim();
  return v ? <Tag>{v}</Tag> : <Text type="secondary">—</Text>;
}
