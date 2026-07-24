import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Card,
  DatePicker,
  Drawer,
  Empty,
  Input,
  Modal,
  Segmented,
  Select,
  Skeleton,
  Space,
  Table,
  Tag,
  Tooltip,
  Tree,
  Typography,
  message,
} from 'antd';
import type { DataNode } from 'antd/es/tree';
import {
  ApartmentOutlined,
  ClearOutlined,
  CopyOutlined,
  DownloadOutlined,
  EditOutlined,
  EyeOutlined,
  FilterOutlined,
  ReloadOutlined,
  SearchOutlined,
  SyncOutlined,
  ToolOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import { Dayjs } from 'dayjs';
import { useNavigate } from 'react-router-dom';
import { getCurrentOperator } from '../../api/auth';
import {
  approveReleaseDocumentEdit,
  remediateDocuments,
  submitReleaseDocumentEdit,
} from '../../api/knowledge-remediation';
import { listTasks } from '../../api/task';
import PageHelpHint from '../../components/PageHelpHint';
import {
  knowledgeDocumentsHelp,
  knowledgeDocumentsReleaseEditHelp,
  knowledgeDocumentsRemediationHelp,
} from '../../constants/knowledgeQueryPageHelp';
import { useKnowledgeQueryContext } from './useKnowledgeQueryContext';
import {
  getKnowledgeBrowseContent,
  getKnowledgeBrowseTree,
  listKnowledgeBrowse,
} from '../../api/knowledge-browse';
import MarkdownView from '../../components/MarkdownView';
import type {
  KnowledgeBrowseFileType,
  KnowledgeBrowseItem,
  KnowledgeBrowseQuery,
  KnowledgeBrowseTreeNode,
  KnowledgeBrowseTreeResult,
  Task,
} from '../../types';
import {
  filterSystemSelectOption,
  renderComponentCell,
  renderSystemSelectLabel,
  renderSystemSelectOption,
  toSystemSelectOptions,
} from '../../utils/systemSelect';

const { Text, Paragraph } = Typography;
const { RangePicker } = DatePicker;

const VIEW_MODE_KEY = 'ci-knowledge-view-mode';

type ViewMode = 'tree' | 'list';

const TYPE_OPTIONS: { value: KnowledgeBrowseFileType | 'ALL'; label: string }[] = [
  { value: 'ALL', label: '全部' },
  { value: 'DRAFT', label: '知识文档' },
  { value: 'INDEX', label: '索引文档' },
  { value: 'MANIFEST', label: '清单文件' },
];

const TYPE_TAG_META: Record<KnowledgeBrowseFileType, { color: string; label: string }> = {
  DRAFT: { color: 'blue', label: '知识文档' },
  INDEX: { color: 'cyan', label: '索引文档' },
  MANIFEST: { color: 'purple', label: '清单文件' },
  ALL: { color: 'default', label: '全部' },
};

const STATUS_OPTIONS = [
  { value: 'DRAFT', label: 'DRAFT' },
  { value: 'EDITING', label: 'EDITING' },
  { value: 'CONFIRMED', label: 'CONFIRMED' },
  { value: 'PUSHED', label: 'PUSHED' },
  { value: 'ARCHIVED', label: 'ARCHIVED' },
];

const NODE_TYPE_TAG: Record<string, { color: string; label: string }> = {
  MODULE: { color: 'geekblue', label: '模块' },
  SUB_MODULE: { color: 'cyan', label: '子模块' },
  FUNCTION: { color: 'green', label: '功能' },
};

function readStoredViewMode(): ViewMode {
  try {
    const v = localStorage.getItem(VIEW_MODE_KEY);
    return v === 'list' ? 'list' : 'tree';
  } catch {
    return 'tree';
  }
}

function PreviewContent({
  text,
  type,
  filePath,
}: {
  text: string;
  type: KnowledgeBrowseFileType;
  filePath?: string;
}) {
  const pathLower = (filePath || '').toLowerCase();
  const trimmed = (text || '').trimStart();
  const isMarkdownManifest =
    type === 'MANIFEST' && (pathLower.endsWith('.md') || trimmed.startsWith('#'));
  if (type === 'DRAFT' || type === 'INDEX' || isMarkdownManifest) {
    return <MarkdownView content={text} />;
  }
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    return <MarkdownView content={'```json\n' + text + '\n```'} />;
  }
  return <MarkdownView content={'```yaml\n' + text + '\n```'} />;
}

function formatBytes(n?: number) {
  if (n == null) return '-';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(2)} MB`;
}

/** 树内关键字过滤：保留命中节点及其祖先 */
function filterTreeNodes(nodes: KnowledgeBrowseTreeNode[], keyword: string): KnowledgeBrowseTreeNode[] {
  const kw = keyword.trim().toLowerCase();
  if (!kw) return nodes;

  const walk = (node: KnowledgeBrowseTreeNode): KnowledgeBrowseTreeNode | null => {
    const titleHit = node.title.toLowerCase().includes(kw);
    const childResults = (node.children ?? [])
      .map(walk)
      .filter((n): n is KnowledgeBrowseTreeNode => n != null);
    if (titleHit || childResults.length > 0) {
      return { ...node, children: childResults.length > 0 ? childResults : node.children };
    }
    return null;
  };

  return nodes.map(walk).filter((n): n is KnowledgeBrowseTreeNode => n != null);
}

/** 收集树中所有非叶节点的 key（用于展开/折叠全部） */
function collectAllKeys(nodes: KnowledgeBrowseTreeNode[]): string[] {
  const keys: string[] = [];
  const walk = (list: KnowledgeBrowseTreeNode[]) => {
    for (const n of list) {
      if (n.children?.length) { keys.push(n.key); walk(n.children); }
    }
  };
  walk(nodes);
  return keys;
}

function countFunctionLeaves(nodes: KnowledgeBrowseTreeNode[]): number {
  let n = 0;
  for (const node of nodes) {
    if (node.nodeType === 'FUNCTION') {
      n += 1;
    } else if (node.children?.length) {
      n += countFunctionLeaves(node.children);
    }
  }
  return n;
}

function collectModuleOptions(nodes: KnowledgeBrowseTreeNode[]): { value: string; label: string }[] {
  const out: { value: string; label: string }[] = [];
  const walk = (list: KnowledgeBrowseTreeNode[]) => {
    for (const n of list) {
      if (n.nodeType === 'MODULE' && n.key.startsWith('module:')) {
        out.push({ value: n.key.slice('module:'.length), label: n.title });
      }
      if (n.children?.length) walk(n.children);
    }
  };
  walk(nodes);
  return out;
}

function moduleIdFromKey(key: string): string | null {
  return key.startsWith('module:') ? key.slice('module:'.length) : null;
}

const KnowledgeBrowse: React.FC = () => {
  const navigate = useNavigate();
  const [viewMode, setViewMode] = useState<ViewMode>(readStoredViewMode);

  const {
    systems,
    repositories,
    systemId,
    setSystemId,
    repositoryId,
    setRepositoryId,
    context,
    contextLoading,
    refreshContext,
  } = useKnowledgeQueryContext();

  const [type, setType] = useState<KnowledgeBrowseFileType | 'ALL'>('ALL');
  const [keyword, setKeyword] = useState('');
  const [treeKeyword, setTreeKeyword] = useState('');
  const [expandedKeys, setExpandedKeys] = useState<string[]>([]);

  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [status, setStatus] = useState<string | undefined>(undefined);
  const [taskId, setTaskId] = useState<number | undefined>(undefined);
  const [dateRange, setDateRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);
  const [tasks, setTasks] = useState<Task[]>([]);

  const [items, setItems] = useState<KnowledgeBrowseItem[]>([]);
  const [listLoading, setListLoading] = useState(false);
  const [listTotal, setListTotal] = useState(0);
  const [listPage, setListPage] = useState(1);
  const [listPageSize, setListPageSize] = useState(20);

  const [treeResult, setTreeResult] = useState<KnowledgeBrowseTreeResult | null>(null);
  const [treeLoading, setTreeLoading] = useState(false);

  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewText, setPreviewText] = useState('');
  const [previewItem, setPreviewItem] = useState<KnowledgeBrowseItem | null>(null);
  const [previewEditing, setPreviewEditing] = useState(false);
  const [editDraftText, setEditDraftText] = useState('');
  const [editSubmitting, setEditSubmitting] = useState(false);
  const [docRerunOpen, setDocRerunOpen] = useState(false);
  const [docRerunModuleIds, setDocRerunModuleIds] = useState<string[]>([]);
  const [docRerunSubmitting, setDocRerunSubmitting] = useState(false);

  const remediationReady =
    repositoryId != null && context?.versionId != null && context.releaseDirExists;

  useEffect(() => {
    if (systemId == null) {
      setTasks([]);
      return;
    }
    listTasks({ current: 1, size: 200, systemId })
      .then((data) => setTasks(data.records ?? []))
      .catch(() => undefined);
  }, [systemId]);

  const handleViewModeChange = (mode: ViewMode) => {
    setViewMode(mode);
    try {
      localStorage.setItem(VIEW_MODE_KEY, mode);
    } catch {
      /* ignore */
    }
  };

  const fetchList = useCallback(async () => {
    const query: KnowledgeBrowseQuery = {
      systemId,
      repositoryId,
      type: type === 'ALL' ? 'ALL' : type,
      keyword: keyword.trim() || undefined,
      taskId,
      status: type === 'DRAFT' ? status : undefined,
      createdDateStart: dateRange?.[0]?.startOf('day').toISOString(),
      createdDateEnd: dateRange?.[1]?.endOf('day').toISOString(),
      current: listPage,
      size: listPageSize,
    };
    setListLoading(true);
    try {
      const data = await listKnowledgeBrowse(query);
      setItems(data.records ?? []);
      setListTotal(data.total ?? 0);
    } catch {
      setItems([]);
      setListTotal(0);
    } finally {
      setListLoading(false);
    }
  }, [systemId, repositoryId, type, keyword, taskId, status, dateRange, listPage, listPageSize]);

  const fetchTree = useCallback(async () => {
    if (systemId == null || repositoryId == null) {
      setTreeResult(null);
      return;
    }
    setTreeLoading(true);
    try {
      const data = await getKnowledgeBrowseTree({
        systemId,
        repositoryId,
      });
      setTreeResult(data);
    } catch {
      setTreeResult(null);
    } finally {
      setTreeLoading(false);
    }
  }, [systemId, repositoryId]);

  useEffect(() => {
    if (viewMode === 'list') {
      fetchList();
    }
  }, [viewMode, fetchList]);

  useEffect(() => {
    if (viewMode === 'tree') {
      fetchTree();
    }
  }, [viewMode, fetchTree]);

  const openPreview = async (it: KnowledgeBrowseItem) => {
    setPreviewItem(it);
    setPreviewText('');
    setPreviewOpen(true);
    setPreviewLoading(true);
    try {
      const params =
        it.contentUri
          ? { contentUri: it.contentUri }
          : it.type === 'DRAFT'
          ? { type: it.type, id: Number(it.id.split(':')[1]) }
          : {
              type: it.type,
              taskId: it.taskId,
              filePath: it.filePath,
            };
      const text = await getKnowledgeBrowseContent(params);
      setPreviewText(text ?? '');
    } catch {
      setPreviewText('');
    } finally {
      setPreviewLoading(false);
    }
  };

  const openPreviewFromTree = useCallback((node: KnowledgeBrowseTreeNode) => {
    if (!node.hasDocument) return;
    setPreviewItem({
      id: node.contentUri ?? `draft:${node.draftId}`,
      name: node.title,
      type: 'DRAFT',
      taskId: treeResult?.taskId,
      filePath: node.documentPath ?? '',
      size: 0,
      status: node.draftStatus ?? 'PUSHED',
      updatedDate: '',
      source: node.contentUri ? 'RELEASE' : 'DB',
      contentUri: node.contentUri,
      systemId: treeResult?.systemId,
      systemName: treeResult?.systemName,
      component: treeResult?.component,
      repositoryId: treeResult?.repositoryId,
      repositoryName: treeResult?.repositoryName,
      versionId: treeResult?.versionId,
      versionNum: treeResult?.versionNum,
    });
    setPreviewText('');
    setPreviewOpen(true);
    setPreviewLoading(true);
    const params = node.contentUri
      ? { contentUri: node.contentUri }
      : { type: 'DRAFT' as const, id: node.draftId! };
    getKnowledgeBrowseContent(params)
      .then((text) => setPreviewText(text ?? ''))
      .catch(() => setPreviewText(''))
      .finally(() => setPreviewLoading(false));
  }, [treeResult]);

  const closePreview = () => {
    setPreviewOpen(false);
    setPreviewItem(null);
    setPreviewText('');
    setPreviewEditing(false);
    setEditDraftText('');
  };

  const canEditReleaseDoc =
    previewItem?.type === 'DRAFT' &&
    previewItem.source === 'RELEASE' &&
    !!previewItem.filePath &&
    repositoryId != null;

  const startPreviewEdit = () => {
    setEditDraftText(previewText);
    setPreviewEditing(true);
  };

  const submitReleaseEdit = async (autoApprove: boolean) => {
    if (!repositoryId || !previewItem?.filePath) return;
    setEditSubmitting(true);
    try {
      const { editId } = await submitReleaseDocumentEdit({
        repositoryId,
        relativePath: previewItem.filePath,
        content: editDraftText,
        operator: getCurrentOperator(),
      });
      if (autoApprove) {
        await approveReleaseDocumentEdit(editId, getCurrentOperator());
        message.success('修订已批准并写入 NAS');
        setPreviewText(editDraftText);
        setPreviewEditing(false);
      } else {
        message.success(`修订已提交，待批准（#${editId}）`);
      }
    } finally {
      setEditSubmitting(false);
    }
  };

  const rerunModuleDocs = (moduleIds: string[]) => {
    if (!repositoryId || moduleIds.length === 0) return;
    Modal.confirm({
      title: '确认重跑文档生成？',
      content: `将对 ${moduleIds.length} 个模块从文档生成阶段重跑 AI。`,
      okText: '确认重跑',
      onOk: async () => {
        setDocRerunSubmitting(true);
        try {
          const resp = await remediateDocuments({
            repositoryId,
            systemId: systemId ?? undefined,
            moduleIds,
            operator: getCurrentOperator(),
          });
          message.success(`纠错任务已创建 #${resp.taskId}`);
          setDocRerunOpen(false);
          setDocRerunModuleIds([]);
          navigate(`/tasks/${resp.taskId}`);
        } finally {
          setDocRerunSubmitting(false);
        }
      },
    });
  };

  const copyPreview = async () => {
    try {
      await navigator.clipboard.writeText(previewText);
      message.success('已复制到剪贴板');
    } catch {
      message.error('复制失败（浏览器可能未授权剪贴板权限）');
    }
  };

  const downloadPreview = () => {
    if (!previewItem) return;
    const blob = new Blob([previewText], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = previewItem.name || 'document';
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  };

  const resetAdvanced = () => {
    setStatus(undefined);
    setTaskId(undefined);
    setDateRange(null);
  };

  const advancedActiveCount = useMemo(() => {
    let n = 0;
    if (status) n += 1;
    if (taskId) n += 1;
    if (dateRange && (dateRange[0] || dateRange[1])) n += 1;
    return n;
  }, [status, taskId, dateRange]);

  const listTypeOptions = useMemo(
    () => (repositoryId != null
      ? TYPE_OPTIONS.filter((o) => o.value !== 'DRAFT')
      : TYPE_OPTIONS),
    [repositoryId],
  );

  useEffect(() => {
    if (repositoryId != null && type === 'DRAFT') {
      setType('ALL');
    }
  }, [repositoryId, type]);

  const filteredTreeNodes = useMemo(
    () => filterTreeNodes(treeResult?.nodes ?? [], treeKeyword),
    [treeResult, treeKeyword],
  );

  const moduleOptions = useMemo(
    () => collectModuleOptions(treeResult?.nodes ?? []),
    [treeResult],
  );

  const treeAntData = useMemo<DataNode[]>(() => {
    const mapNode = (node: KnowledgeBrowseTreeNode): DataNode => {
      const typeMeta = NODE_TYPE_TAG[node.nodeType] ?? { color: 'default', label: node.nodeType };
      const isFunction = node.nodeType === 'FUNCTION';
      const isModule = node.nodeType === 'MODULE';
      const moduleId = moduleIdFromKey(node.key);
      const title = (
        <div className="ci-knowledge-tree-node" style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <Tag color={typeMeta.color} style={{ margin: 0 }}>
            {typeMeta.label}
          </Tag>
          <Text>{node.title}</Text>
          {isFunction && node.documentGranularity === 'module' && (
            <Tag color="gold" style={{ margin: 0 }}>
              模块级文档
            </Tag>
          )}
          {isFunction && node.hasDocument && node.draftStatus && (
            <Tag style={{ margin: 0 }}>{node.draftStatus}</Tag>
          )}
          {isModule && remediationReady && moduleId && (
            <Button
              type="link"
              size="small"
              icon={<SyncOutlined />}
              onClick={(e) => {
                e.stopPropagation();
                rerunModuleDocs([moduleId]);
              }}
            >
              重跑
            </Button>
          )}
          {isFunction && (
            node.hasDocument ? (
              <Button
                type="link"
                size="small"
                icon={<EyeOutlined />}
                onClick={(e) => {
                  e.stopPropagation();
                  openPreviewFromTree(node);
                }}
              >
                查看
              </Button>
            ) : (
              <Text type="secondary" style={{ fontSize: 12 }}>
                暂无文档
              </Text>
            )
          )}
        </div>
      );
      return {
        key: node.key,
        title,
        children: node.children?.length ? node.children.map(mapNode) : undefined,
        selectable: isFunction && !!node.hasDocument,
      };
    };
    return filteredTreeNodes.map(mapNode);
  }, [filteredTreeNodes, openPreviewFromTree, remediationReady]);

  const listColumns = [
    {
      title: '文件名',
      dataIndex: 'name',
      key: 'name',
      width: 220,
      ellipsis: true,
      render: (v: string, r: KnowledgeBrowseItem) => (
        <Tooltip title={r.filePath}>
          <Text strong>{v}</Text>
        </Tooltip>
      ),
    },
    {
      title: '系统',
      dataIndex: 'systemName',
      key: 'systemName',
      width: 140,
      ellipsis: true,
      render: (v: string | undefined) => v ?? '-',
    },
    {
      title: '组件',
      dataIndex: 'component',
      key: 'component',
      width: 120,
      render: (v: string | undefined) => renderComponentCell(v),
    },
    {
      title: '仓库',
      dataIndex: 'repositoryName',
      key: 'repositoryName',
      width: 160,
      ellipsis: true,
      render: (v: string | undefined) => v ?? '-',
    },
    {
      title: '类型',
      dataIndex: 'type',
      key: 'type',
      width: 100,
      render: (t: KnowledgeBrowseFileType) => (
        <Tag color={TYPE_TAG_META[t]?.color}>{TYPE_TAG_META[t]?.label ?? t}</Tag>
      ),
    },
    {
      title: '任务',
      dataIndex: 'taskId',
      key: 'taskId',
      width: 90,
      render: (id?: number) => (id ? <Text code>#{id}</Text> : '-'),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (s: string | undefined, r: KnowledgeBrowseItem) => {
        if (!s) return '-';
        if (r.type === 'DRAFT') {
          const color =
            s === 'CONFIRMED' || s === 'PUSHED'
              ? 'green'
              : s === 'EDITING'
              ? 'gold'
              : s === 'ARCHIVED'
              ? 'default'
              : 'blue';
          return <Tag color={color}>{s}</Tag>;
        }
        return <Tag color="cyan">已生成</Tag>;
      },
    },
    {
      title: '大小',
      dataIndex: 'size',
      key: 'size',
      width: 90,
      render: (n: number) => <Text type="secondary">{formatBytes(n)}</Text>,
    },
    {
      title: '更新时间',
      dataIndex: 'updatedDate',
      key: 'updatedDate',
      width: 160,
      render: (s?: string) => (s ? new Date(s).toLocaleString() : '-'),
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      render: (_: unknown, r: KnowledgeBrowseItem) => (
        <Button type="link" size="small" onClick={() => openPreview(r)}>
          查看
        </Button>
      ),
    },
  ];

  return (
    <div className="ci-page ci-knowledge-browse-page">
      <Card>
        {/* 顶部 toolbar：标题 + 系统/仓库下拉 + 调整并重跑 + 刷新（原 KnowledgeContextBar 内容内联进 Card） */}
        <Space
          wrap
          style={{ width: '100%', justifyContent: 'space-between' }}
          size={12}
        >
          <Space size={8} align="center" wrap>
            <Text strong style={{ fontSize: 16 }}>
              知识文档
            </Text>
            <PageHelpHint title={knowledgeDocumentsHelp.title} content={knowledgeDocumentsHelp.content} />
            {context?.versionId != null && context?.releaseDirExists && (
              <Tag color="processing">
                当前生效：{context.versionNum}
                {context.taskId != null ? ` · 任务 #${context.taskId}` : ''}
              </Tag>
            )}
            {repositoryId != null && !(context?.releaseDirExists) && !contextLoading && (
              <Tag color="warning">尚无生效发布版</Tag>
            )}
          </Space>
          <Space wrap size={12}>
            <Space size={4}>
              <Text type="secondary">系统</Text>
              <Select
                placeholder="请选择系统"
                value={systemId}
                onChange={(v) => {
                  setSystemId(v);
                  setListPage(1);
                }}
                style={{ width: 240 }}
                showSearch
                filterOption={filterSystemSelectOption}
                optionRender={renderSystemSelectOption}
                labelRender={(props) => renderSystemSelectLabel(props, systems)}
                options={toSystemSelectOptions(systems)}
                allowClear={viewMode !== 'tree'}
              />
            </Space>
            <Space size={4}>
              <Text type="secondary">仓库</Text>
              <Select
                placeholder="请选择仓库"
                value={repositoryId}
                onChange={(v) => {
                  setRepositoryId(v);
                  setListPage(1);
                }}
                style={{ width: 240 }}
                showSearch
                optionFilterProp="label"
                options={repositories.map((r) => {
                  const base = r.gitUrl?.split('/').pop()?.replace(/\.git$/, '') ?? `仓库 #${r.id}`;
                  return { value: r.id, label: `${base} (${r.branch})` };
                })}
                disabled={systemId == null}
                allowClear={viewMode !== 'tree'}
              />
            </Space>
            {remediationReady && viewMode === 'tree' && moduleOptions.length > 0 ? (
              <Button
                type="primary"
                icon={<ToolOutlined />}
                onClick={() => {
                  setDocRerunModuleIds([]);
                  setDocRerunOpen(true);
                }}
              >
                调整并重跑
              </Button>
            ) : (
              <Tooltip title="树形视图 + 已选仓库 + 存在生效发布版 时可调整并重跑">
                <Button icon={<ToolOutlined />} disabled>
                  调整并重跑
                </Button>
              </Tooltip>
            )}
            <Tooltip title="刷新">
              <Button
                icon={<ReloadOutlined />}
                loading={contextLoading}
                onClick={() => {
                  refreshContext();
                  if (viewMode === 'list') fetchList();
                  else fetchTree();
                }}
              />
            </Tooltip>
          </Space>
        </Space>

        <div style={{ height: 12 }} />

        {/* 第二行 toolbar：视图切换 + 文件类型 + 搜索框 + 高级筛选（去掉原重复的刷新按钮） */}
        <Space size={12} wrap style={{ width: '100%', justifyContent: 'space-between' }}>
          <Space size={12} wrap>
            <Segmented
              size="large"
              value={viewMode}
              onChange={(v) => handleViewModeChange(v as ViewMode)}
              options={[
                { value: 'tree', label: '树形视图', icon: <ApartmentOutlined /> },
                { value: 'list', label: '列表视图', icon: <UnorderedListOutlined /> },
              ]}
            />
            {viewMode === 'list' && (
              <Segmented
                options={listTypeOptions.map((o) => ({ value: o.value, label: o.label }))}
                value={type}
                onChange={(v) => {
                  setType(v as KnowledgeBrowseFileType | 'ALL');
                  setListPage(1);
                }}
              />
            )}
          </Space>
          <Space size={8} wrap>
            {viewMode === 'list' && repositoryId != null && (
              <Alert
                type="info"
                showIcon
                message="已选仓库：列表展示当前生效发布版（INDEX / MANIFEST），不含任务草稿"
                style={{ marginBottom: 0, padding: '4px 12px' }}
              />
            )}
            <Input
              placeholder={viewMode === 'tree' ? '过滤树节点…' : '搜索文件名 / 系统 / 仓库…'}
              prefix={<SearchOutlined />}
              value={viewMode === 'tree' ? treeKeyword : keyword}
              onChange={(e) => {
                if (viewMode === 'tree') {
                  setTreeKeyword(e.target.value);
                } else {
                  setKeyword(e.target.value);
                }
              }}
              onPressEnter={() => {
                if (viewMode === 'list') {
                  setListPage(1);
                  fetchList();
                }
              }}
              allowClear
              style={{ width: 260 }}
            />
            {viewMode === 'list' && (
              <Tooltip title="高级筛选">
                <Badge count={advancedActiveCount} size="small" offset={[-4, 4]}>
                  <Button icon={<FilterOutlined />} onClick={() => setAdvancedOpen(true)}>
                    高级筛选
                  </Button>
                </Badge>
              </Tooltip>
            )}
          </Space>
        </Space>
      </Card>

      {viewMode === 'tree' ? (
        <Card style={{ marginTop: 16 }}>
          {systemId == null || repositoryId == null ? (
            <Empty description="树形模式请先选择系统与仓库" />
          ) : treeLoading ? (
            <Skeleton active paragraph={{ rows: 10 }} />
          ) : !treeResult ? (
            <Empty description="加载失败或该仓库尚无已发布的生效知识版本" />
          ) : treeResult.nodes.length === 0 ? (
            <Empty description="该仓库尚无已发布的模块层级数据" />
          ) : (
            <>
              <Alert
                type="info"
                showIcon
                style={{ marginBottom: 16 }}
                message={
                  <Space wrap>
                    <span>
                      当前生效版本{' '}
                      <Text code strong>
                        {treeResult.versionNum ?? `#${treeResult.versionId}`}
                      </Text>
                    </span>
                    {treeResult.taskId != null && (
                      <span>
                        源任务 <Text code>#{treeResult.taskId}</Text>
                      </span>
                    )}
                    <Tag>
                      {countFunctionLeaves(treeResult.nodes)} 个功能
                    </Tag>
                    <Tag color="blue">
                      文档粒度：{treeResult.documentGranularity === 'module' ? '模块' : '功能'}
                    </Tag>
                  </Space>
                }
              />
              <Space size={4} style={{ marginBottom: 8 }}>
                <Button size="small" onClick={() => setExpandedKeys(collectAllKeys(filteredTreeNodes))}>
                  展开全部
                </Button>
                <Button size="small" onClick={() => setExpandedKeys([])}>
                  折叠全部
                </Button>
              </Space>
              <div
                className="ci-hierarchy-tree-panel"
                style={{ border: '1px solid #f0f0f0', borderRadius: 6, padding: 12, background: '#fafafa' }}
              >
                <Tree
                  treeData={treeAntData}
                  expandedKeys={expandedKeys}
                  onExpand={(keys) => setExpandedKeys(keys as string[])}
                  showLine={{ showLeafIcon: false }}
                  blockNode
                  style={{ fontSize: 13 }}
                  onSelect={(_, info) => {
                    const key = String(info.node.key);
                    const find = (nodes: KnowledgeBrowseTreeNode[]): KnowledgeBrowseTreeNode | null => {
                      for (const n of nodes) {
                        if (n.key === key) return n;
                        if (n.children?.length) {
                          const hit = find(n.children);
                          if (hit) return hit;
                        }
                      }
                      return null;
                    };
                    const node = find(filteredTreeNodes);
                    if (node?.nodeType === 'FUNCTION' && node.hasDocument) {
                      openPreviewFromTree(node);
                    }
                  }}
                />
              </div>
            </>
          )}
        </Card>
      ) : (
        <Card style={{ marginTop: 16 }} bodyStyle={{ padding: 0 }}>
          <Table<KnowledgeBrowseItem>
            dataSource={items}
            columns={listColumns as any}
            rowKey="id"
            loading={listLoading}
            pagination={{
              current: listPage,
              pageSize: listPageSize,
              total: listTotal,
              showSizeChanger: true,
              showTotal: (t) => `共 ${t} 条`,
              onChange: (page, pageSize) => {
                setListPage(page);
                setListPageSize(pageSize);
              },
            }}
            locale={{ emptyText: <Empty description="该条件下没有可浏览的文件" /> }}
            onRow={(record) => ({
              onClick: () => openPreview(record),
              style: { cursor: 'pointer' },
            })}
          />
        </Card>
      )}

      <Drawer
        title="高级筛选"
        width={360}
        open={advancedOpen}
        onClose={() => setAdvancedOpen(false)}
        extra={
          <Space>
            <Button icon={<ClearOutlined />} onClick={resetAdvanced}>
              清空
            </Button>
            <Button
              type="primary"
              onClick={() => {
                setListPage(1);
                setAdvancedOpen(false);
              }}
            >
              应用筛选
            </Button>
          </Space>
        }
      >
        <Space direction="vertical" size={20} style={{ width: '100%' }}>
          <div>
            <Text type="secondary" style={{ display: 'block', marginBottom: 6 }}>
              状态（仅对知识文档生效）
            </Text>
            <Select
              placeholder="全部状态"
              value={status}
              onChange={(v) => setStatus(v)}
              allowClear
              style={{ width: '100%' }}
              options={STATUS_OPTIONS}
              disabled={type !== 'DRAFT'}
            />
          </div>
          <div>
            <Text type="secondary" style={{ display: 'block', marginBottom: 6 }}>
              所属任务（树形模式亦可用作指定基准任务）
            </Text>
            <Select
              placeholder="全部任务 / 自动选取"
              value={taskId}
              onChange={(v) => setTaskId(v)}
              allowClear
              showSearch
              optionFilterProp="label"
              style={{ width: '100%' }}
              options={tasks.map((t) => ({
                value: t.id,
                label: `#${t.id} ${t.type === 'INITIAL' ? '全量' : '增量'}`,
              }))}
              disabled={systemId == null}
            />
          </div>
          <div>
            <Text type="secondary" style={{ display: 'block', marginBottom: 6 }}>
              更新时间区间
            </Text>
            <RangePicker
              value={dateRange as any}
              onChange={(v) => setDateRange(v as any)}
              style={{ width: '100%' }}
              allowClear
            />
          </div>
        </Space>
      </Drawer>

      <Drawer
        title={
          previewItem ? (
            <Space size={8} align="center" wrap>
              <Text strong>{previewItem.name}</Text>
              <Tag color={TYPE_TAG_META[previewItem.type]?.color}>
                {TYPE_TAG_META[previewItem.type]?.label}
              </Tag>
              {previewItem.taskId && <Text type="secondary">#{previewItem.taskId}</Text>}
              {previewEditing && (
                <PageHelpHint
                  title={knowledgeDocumentsReleaseEditHelp.title}
                  content={knowledgeDocumentsReleaseEditHelp.content}
                />
              )}
            </Space>
          ) : (
            '文件内容'
          )
        }
        width="62%"
        open={previewOpen}
        onClose={closePreview}
        destroyOnClose
        extra={
          <Space>
            {canEditReleaseDoc && !previewEditing && (
              <Button icon={<EditOutlined />} onClick={startPreviewEdit}>
                编辑
              </Button>
            )}
            {previewEditing && (
              <>
                <Button onClick={() => setPreviewEditing(false)}>取消编辑</Button>
                <Button
                  loading={editSubmitting}
                  onClick={() => submitReleaseEdit(false)}
                >
                  提交待审
                </Button>
                <Button
                  type="primary"
                  loading={editSubmitting}
                  onClick={() => submitReleaseEdit(true)}
                >
                  批准并写入
                </Button>
              </>
            )}
            <Button icon={<CopyOutlined />} onClick={copyPreview} disabled={!previewText}>
              复制
            </Button>
            <Button icon={<DownloadOutlined />} onClick={downloadPreview} disabled={!previewText}>
              下载
            </Button>
          </Space>
        }
      >
        {previewLoading ? (
          <Skeleton active paragraph={{ rows: 12 }} />
        ) : !previewItem ? null : !previewText && !previewEditing ? (
          <Empty description="无可显示内容（文件可能为空或读取失败）" />
        ) : previewEditing ? (
          <>
            <Input.TextArea
              value={editDraftText}
              onChange={(e) => setEditDraftText(e.target.value)}
              autoSize={{ minRows: 16, maxRows: 40 }}
              style={{ fontFamily: 'monospace', fontSize: 13 }}
            />
          </>
        ) : (
          <>
            {previewItem.filePath && (
              <Paragraph
                type="secondary"
                style={{ fontSize: 12, marginBottom: 12 }}
                copyable={{ text: previewItem.filePath }}
              >
                路径：{previewItem.filePath} · {formatBytes(previewItem.size)}
                {previewItem.source === 'RELEASE' && (
                  <Tag color="green" style={{ marginLeft: 8 }}>
                    发布版
                  </Tag>
                )}
              </Paragraph>
            )}
            <PreviewContent text={previewText} type={previewItem.type} filePath={previewItem.filePath} />
          </>
        )}
      </Drawer>

      <Modal
        title={
          <Space size={8} align="center">
            <span>选择模块并重跑文档</span>
            <PageHelpHint
              title={knowledgeDocumentsRemediationHelp.title}
              content={knowledgeDocumentsRemediationHelp.content}
            />
          </Space>
        }
        open={docRerunOpen}
        onCancel={() => setDocRerunOpen(false)}
        onOk={() => rerunModuleDocs(docRerunModuleIds)}
        okText="确认重跑"
        confirmLoading={docRerunSubmitting}
        okButtonProps={{ disabled: docRerunModuleIds.length === 0 }}
      >
        <Select
          mode="multiple"
          style={{ width: '100%' }}
          placeholder="选择 moduleId"
          value={docRerunModuleIds}
          onChange={setDocRerunModuleIds}
          options={moduleOptions}
        />
      </Modal>
    </div>
  );
};

export default KnowledgeBrowse;
