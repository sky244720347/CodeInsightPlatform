import { Tag, Typography } from 'antd';
import type { DataNode } from 'antd/es/tree';
import {
  type DraftHierarchyTreeNode,
  NODE_TYPE_TAG,
} from './draftHierarchyTree';

const { Text } = Typography;

const statusColor: Record<string, string> = {
  DRAFT: 'magenta',
  EDITING: 'geekblue',
  CONFIRMED: 'green',
  PUSHED: 'green',
  ARCHIVED: 'default',
};

const statusLabel: Record<string, string> = {
  DRAFT: '待处理',
  EDITING: '已编辑',
  CONFIRMED: '已确认',
  PUSHED: '已推送',
  ARCHIVED: '已归档',
};

export type HierarchyTreeDataNode = DataNode & { draftId?: number };

/** 变更类型 → Tag 颜色 + 文案 */
const diffTypeMeta: Record<string, { color: string; label: string }> = {
  new: { color: 'green', label: '新增' },
  modified: { color: 'orange', label: '修改' },
  inherited: { color: 'default', label: '继承' },
  deleted: { color: 'red', label: '删除' },
};

/** 层级目录 → Ant Design Tree（样式对齐知识查看） */
export function buildHierarchyAntTreeNodes(nodes: DraftHierarchyTreeNode[]): HierarchyTreeDataNode[] {
  return nodes.map((n) => {
    const isFunction = n.nodeType === 'FUNCTION';
    const typeMeta = NODE_TYPE_TAG[n.nodeType];
    const diffMeta = n.diffType ? diffTypeMeta[n.diffType] : null;
    const title = (
      <div
        className="ci-knowledge-tree-node"
        style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap', minWidth: 0 }}
      >
        <Tag color={typeMeta.color} style={{ margin: 0 }}>
          {typeMeta.label}
        </Tag>
        <Text style={{ fontSize: 13, textDecoration: n.diffType === 'deleted' ? 'line-through' : 'none', color: n.diffType === 'deleted' ? '#999' : undefined }}>{n.title}</Text>
        {diffMeta && (
          <Tag color={diffMeta.color} style={{ margin: 0, fontSize: 11 }}>
            {diffMeta.label}
          </Tag>
        )}
        {isFunction && n.classPaths && n.classPaths.length > 0 && (
          <Text type="secondary" style={{ fontSize: 11 }} title={n.classPaths.join('\n')}>
            {n.classPaths.length === 1
              ? n.classPaths[0].split('.').pop()
              : `${n.classPaths.length} 类`}
          </Text>
        )}
        {isFunction && n.methodSignatures && n.methodSignatures.length > 0 && (
          <Tag style={{ margin: 0, fontSize: 11 }}>
            {n.methodSignatures.length === 1
              ? n.methodSignatures[0]
              : `${n.methodSignatures.length} 个方法`}
          </Tag>
        )}
        {isFunction && (
          n.hasDocument ? (
            n.draftStatus ? (
              <Tag color={statusColor[n.draftStatus] ?? 'default'} style={{ margin: 0 }}>
                {statusLabel[n.draftStatus] ?? n.draftStatus}
              </Tag>
            ) : null
          ) : (
            <Text type="secondary" style={{ fontSize: 12 }}>
              无文档
            </Text>
          )
        )}
      </div>
    );

    return {
      key: n.key,
      title,
      draftId: n.draftId,
      selectable: isFunction && n.hasDocument && n.diffType !== 'deleted',
      children: n.children?.length ? buildHierarchyAntTreeNodes(n.children) : undefined,
    };
  });
}
