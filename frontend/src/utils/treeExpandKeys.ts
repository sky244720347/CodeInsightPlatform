import type { DataNode } from 'antd/es/tree';

/** 收集 Ant Design Tree 中所有可展开（非叶子）节点的 key */
export function collectExpandableKeys(nodes: DataNode[]): string[] {
  const keys: string[] = [];
  const walk = (list: DataNode[]) => {
    for (const n of list) {
      if (n.children?.length) {
        if (n.key != null) keys.push(String(n.key));
        walk(n.children);
      }
    }
  };
  walk(nodes);
  return keys;
}
