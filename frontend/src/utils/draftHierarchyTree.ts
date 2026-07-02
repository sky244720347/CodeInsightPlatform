import type { DraftTreeNode } from '../api/draft';
import type { FunctionNode, ModuleHierarchy, ModuleNode, SubModuleNode } from '../types';

export interface DraftHierarchyTreeNode {
  key: string;
  nodeType: 'MODULE' | 'SUB_MODULE' | 'FUNCTION';
  title: string;
  draftId?: number;
  hasDocument: boolean;
  draftStatus?: string;
  classPaths?: string[];
  methodSignatures?: string[];
  children?: DraftHierarchyTreeNode[];
}

const NODE_TYPE_TAG: Record<DraftHierarchyTreeNode['nodeType'], { color: string; label: string }> = {
  MODULE: { color: 'geekblue', label: '模块' },
  SUB_MODULE: { color: 'cyan', label: '子模块' },
  FUNCTION: { color: 'green', label: '功能' },
};

export { NODE_TYPE_TAG };

function normalizeLabel(s: string): string {
  return s.trim().toLowerCase();
}

/** 从 DB 树拍平所有草稿叶子 */
export function flattenDraftLeaves(nodes: DraftTreeNode[]): DraftTreeNode[] {
  const result: DraftTreeNode[] = [];
  for (const n of nodes) {
    if (n.isFolder) {
      result.push(...flattenDraftLeaves(n.children || []));
    } else {
      result.push(n);
    }
  }
  return result;
}

function indexDrafts(leaves: DraftTreeNode[]) {
  const byModuleName = new Map<string, DraftTreeNode>();
  const byPath = new Map<string, DraftTreeNode>();
  for (const d of leaves) {
    if (d.moduleName) {
      byModuleName.set(d.moduleName, d);
      byModuleName.set(normalizeLabel(d.moduleName), d);
    }
    if (d.filePath) {
      byPath.set(d.filePath, d);
    }
  }
  return { byModuleName, byPath };
}

function resolveFunctionDraft(
  moduleName: string,
  subModuleName: string,
  functionName: string,
  indexes: ReturnType<typeof indexDrafts>,
): DraftTreeNode | undefined {
  const label = `${moduleName} / ${subModuleName} / ${functionName}`;
  return (
    indexes.byModuleName.get(label)
    ?? indexes.byModuleName.get(normalizeLabel(label))
  );
}

function resolveModuleDraft(moduleName: string, indexes: ReturnType<typeof indexDrafts>): DraftTreeNode | undefined {
  return (
    indexes.byModuleName.get(moduleName)
    ?? indexes.byModuleName.get(normalizeLabel(moduleName))
  );
}

function inferFunctionGranularity(leaves: DraftTreeNode[]): boolean {
  if (leaves.length === 0) return true;
  const withTriplePath = leaves.filter((d) => (d.moduleName?.split(' / ').length ?? 0) >= 3).length;
  return withTriplePath >= leaves.length * 0.5;
}

function fromDraftNode(d: DraftTreeNode): Pick<DraftHierarchyTreeNode, 'draftId' | 'hasDocument' | 'draftStatus'> {
  return {
    draftId: d.id,
    hasDocument: true,
    draftStatus: d.status,
  };
}

function buildFromHierarchy(
  hierarchy: ModuleHierarchy,
  indexes: ReturnType<typeof indexDrafts>,
  functionGranularity: boolean,
): DraftHierarchyTreeNode[] {
  const modules = Object.values(hierarchy.modules ?? {});
  return modules.map((mod) => buildModuleNode(mod, indexes, functionGranularity));
}

function buildModuleNode(
  mod: ModuleNode,
  indexes: ReturnType<typeof indexDrafts>,
  functionGranularity: boolean,
): DraftHierarchyTreeNode {
  const moduleDraft = functionGranularity ? undefined : resolveModuleDraft(mod.moduleName, indexes);
  const subNodes = Object.values(mod.subModules ?? {}).map((sub) =>
    buildSubModuleNode(mod, sub, indexes, functionGranularity, moduleDraft),
  );

  return {
    key: `module:${mod.id}`,
    nodeType: 'MODULE',
    title: mod.moduleName,
    hasDocument: false,
    children: subNodes,
  };
}

function buildSubModuleNode(
  mod: ModuleNode,
  sub: SubModuleNode,
  indexes: ReturnType<typeof indexDrafts>,
  functionGranularity: boolean,
  moduleDraft?: DraftTreeNode,
): DraftHierarchyTreeNode {
  const fnNodes = Object.values(sub.functions ?? {}).map((fn) =>
    buildFunctionNode(mod, sub, fn, indexes, functionGranularity, moduleDraft),
  );

  return {
    key: `sub:${sub.id}`,
    nodeType: 'SUB_MODULE',
    title: sub.subModuleName,
    hasDocument: false,
    children: fnNodes,
  };
}

function buildFunctionNode(
  mod: ModuleNode,
  sub: SubModuleNode,
  fn: FunctionNode,
  indexes: ReturnType<typeof indexDrafts>,
  functionGranularity: boolean,
  moduleDraft?: DraftTreeNode,
): DraftHierarchyTreeNode {
  const draft = functionGranularity
    ? resolveFunctionDraft(mod.moduleName, sub.subModuleName, fn.functionName, indexes)
    : moduleDraft;

  return {
    key: `fn:${fn.id}`,
    nodeType: 'FUNCTION',
    title: fn.functionName,
    classPaths: fn.classPaths,
    methodSignatures: fn.methodSignatures,
    ...(draft ? fromDraftNode(draft) : { hasDocument: false }),
  };
}

/** moduleName 形如 "A / B / C" 时按路径合成树（无 hierarchy 时的兜底） */
function buildFromFlatModuleNames(leaves: DraftTreeNode[]): DraftHierarchyTreeNode[] {
  type MutableNode = DraftHierarchyTreeNode & { childMap?: Map<string, MutableNode> };
  const roots: MutableNode[] = [];
  const rootMap = new Map<string, MutableNode>();

  const ensureChild = (
    parentList: MutableNode[],
    parentMap: Map<string, MutableNode>,
    key: string,
    nodeType: DraftHierarchyTreeNode['nodeType'],
    title: string,
  ): MutableNode => {
    const existing = parentMap.get(key);
    if (existing) return existing;
    const node: MutableNode = {
      key,
      nodeType,
      title,
      hasDocument: false,
      children: [],
      childMap: new Map(),
    };
    parentList.push(node);
    parentMap.set(key, node);
    return node;
  };

  for (const leaf of leaves) {
    const parts = (leaf.moduleName || '').split(' / ').map((p) => p.trim()).filter(Boolean);
    if (parts.length === 0) {
      roots.push({
        key: `draft:${leaf.id}`,
        nodeType: 'FUNCTION',
        title: leaf.moduleName || `草稿 #${leaf.id}`,
        ...fromDraftNode(leaf),
      });
      continue;
    }

    let parentList = roots;
    let parentMap = rootMap;
    let pathKey = '';

    parts.forEach((part, index) => {
      const isLeaf = index === parts.length - 1;
      pathKey = pathKey ? `${pathKey}/${part}` : part;
      if (isLeaf) {
        const fnKey = `leaf:${leaf.id}`;
        parentList.push({
          key: fnKey,
          nodeType: 'FUNCTION',
          title: part,
          ...fromDraftNode(leaf),
        });
        return;
      }
      const nodeType: DraftHierarchyTreeNode['nodeType'] =
        index === 0 ? 'MODULE' : 'SUB_MODULE';
      const node = ensureChild(parentList, parentMap, `${nodeType}:${pathKey}`, nodeType, part);
      if (!node.children) node.children = [];
      if (!node.childMap) node.childMap = new Map();
      parentList = node.children as MutableNode[];
      parentMap = node.childMap;
    });
  }

  const strip = (nodes: MutableNode[]): DraftHierarchyTreeNode[] =>
    nodes.map(({ childMap: _c, ...rest }) => ({
      ...rest,
      children: rest.children?.length ? strip(rest.children as MutableNode[]) : undefined,
    }));

  return strip(roots);
}

/** 结合模块层级 + 工作区草稿，生成与「知识查看」一致的树形目录 */
export function buildDraftHierarchyTree(
  hierarchy: ModuleHierarchy | null | undefined,
  draftTree: DraftTreeNode[],
): DraftHierarchyTreeNode[] {
  const leaves = flattenDraftLeaves(draftTree);
  const indexes = indexDrafts(leaves);

  if (hierarchy?.modules && Object.keys(hierarchy.modules).length > 0) {
    return buildFromHierarchy(hierarchy, indexes, inferFunctionGranularity(leaves));
  }
  return buildFromFlatModuleNames(leaves);
}

export function countHierarchyFunctions(nodes: DraftHierarchyTreeNode[]): number {
  let count = 0;
  for (const n of nodes) {
    if (n.nodeType === 'FUNCTION') count += 1;
    else if (n.children?.length) count += countHierarchyFunctions(n.children);
  }
  return count;
}

export function findFirstDraftId(nodes: DraftHierarchyTreeNode[]): number | null {
  for (const n of nodes) {
    if (n.nodeType === 'FUNCTION' && n.hasDocument && n.draftId != null) {
      return n.draftId;
    }
    if (n.children?.length) {
      const id = findFirstDraftId(n.children);
      if (id != null) return id;
    }
  }
  return null;
}

export function collectDocumentLeaves(
  nodes: DraftHierarchyTreeNode[],
): Array<{ draftId: number; title: string; modulePath: string }> {
  const result: Array<{ draftId: number; title: string; modulePath: string }> = [];
  const walk = (list: DraftHierarchyTreeNode[], ancestors: string[]) => {
    for (const n of list) {
      const pathParts = [...ancestors, n.title];
      if (n.nodeType === 'FUNCTION' && n.hasDocument && n.draftId != null) {
        result.push({
          draftId: n.draftId,
          title: n.title,
          modulePath: pathParts.join(' / '),
        });
      }
      if (n.children?.length) walk(n.children, pathParts);
    }
  };
  walk(nodes, []);
  return result;
}
