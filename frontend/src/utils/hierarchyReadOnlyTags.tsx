import { Tag } from 'antd';
import React from 'react';
import type { FunctionNode } from '../types';

/**
 * function 节点在「只读树」中的「类路径 / 方法签名」元数据 Tag。
 * 复用于：
 * - 知识查看模块层级页的只读树
 * - 草稿模块目录树
 *
 * 行为：
 * - 类路径为 1 个时显示该类短名（取最后一个 `.` 之后），否则显示「类路径·N」；
 * - 方法签名为 1 个时显示该签名本身，否则显示「方法签名·N」；
 * - Tag 自带 `title` 属性（hover tooltip）展示完整内容（多值时换行分隔）；
 * - 两者都为空时返回 null，由调用方决定是否仍然保留前后 Space。
 */
export function renderFunctionMetaTags(
  fn: Pick<FunctionNode, 'classPaths' | 'methodSignatures'>,
): React.ReactNode {
  const cps = fn.classPaths ?? [];
  const sigs = fn.methodSignatures ?? [];
  if (cps.length === 0 && sigs.length === 0) return null;
  return (
    <>
      {cps.length > 0 && (
        <Tag style={{ margin: 0, fontSize: 11 }} title={cps.join('\n')}>
          {cps.length === 1 ? cps[0].split('.').pop() : `类路径·${cps.length}`}
        </Tag>
      )}
      {sigs.length > 0 && (
        <Tag style={{ margin: 0, fontSize: 11 }} title={sigs.join('\n')}>
          {sigs.length === 1 ? sigs[0] : `方法签名·${sigs.length}`}
        </Tag>
      )}
    </>
  );
}
