import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button,
  Card,
  Col,
  Input,
  Modal,
  Row,
  Segmented,
  Space,
  Spin,
  Tag,
  Tooltip,
  Tree,
  Typography,
  message,
} from 'antd';
import {
  AppstoreOutlined,
  ArrowLeftOutlined,
  ArrowRightOutlined,
  CheckCircleOutlined,
  CheckOutlined,
  ClockCircleOutlined,
  CloseOutlined,
  CodeOutlined,
  EditOutlined,
  EyeOutlined,
  ExperimentOutlined,
  FileSearchOutlined,
  FileTextOutlined,
  FullscreenExitOutlined,
  FullscreenOutlined,
  HistoryOutlined,
  MessageOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SaveOutlined,
  CloudUploadOutlined,
  BarsOutlined,
  ColumnHeightOutlined,
} from '@ant-design/icons';
// 模块目录树节点改用 Tag 展示每个文档的状态文案，不再依赖 Badge 小圆点
import Editor from '@monaco-editor/react';
import DraftModuleDirectory from '../../components/DraftModuleDirectory';
import MarkdownView from '../../components/MarkdownView';
import { getModuleHierarchy } from '../../api/task';
import {
  acquireDraftEditLock,
  autoSaveDraft,
  getComments,
  getDemoMode,
  getDraftContent,
  getReferences,
  getRevisions,
  getWorkspaceByTask,
  getWorkspaceTree,
  listTaskComments,
  releaseDraftEditLock,
  renewDraftEditLock,
  saveDraft,
  toggleDemoMode,
  type DraftReviewComment,
  type DraftRevision,
  type DraftSourceReference,
  approveDraft,
  regenerateDraft,
  type DraftTreeNode,
  type DraftWorkspace,
  type KnowledgeDraft,
  type TaskCommentDto,
} from '../../api/draft';
import type { Task } from '../../types';
import type { DraftHierarchyTreeNode } from '../../utils/draftHierarchyTree';
import {
  buildDraftHierarchyTree,
  collectDocumentLeaves,
  countHierarchyFunctions,
  findFirstDraftId,
  flattenDraftLeaves,
} from '../../utils/draftHierarchyTree';
import { buildHierarchyAntTreeNodes } from '../../utils/draftHierarchyTreeUi';
import { confirmTask, getTask, retryTask } from '../../api/task';
import { getCurrentOperator } from '../../api/auth';

const { Text } = Typography;
const { TextArea } = Input;

// 草稿状态颜色映射（与后端 DraftStatus 枚举一一对应；与任务 TaskStatus 词汇已解耦）
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

/**
 * 「无需复核」= 只能浏览不能编辑的文档状态。
 * - PUSHED / PUSHING / ARCHIVED  属于已固化或推送中知识
 *
 * 注意：本集合描述的是 KnowledgeDraft.status（文档级），
 * 顶部的「待复核 / 复核中 / 已确认」chips 描述的是 DecompileTask.status（任务级），
 * 两者自 v0.2 起字面值已解耦，不再共享。
 *
 * 自 v0.3 起 CONFIRMED 已从只读集合中移除：复核人确认后仍可继续编辑修改
 * （保存后状态回流到 EDITING）；推送锁定由任务级 PUSHING / PUSHED 统一控制。
 */
const READ_ONLY_STATUSES = ['PUSHED', 'PUSHING', 'ARCHIVED'];

/**
 * 文档视图模式：
 * - edit   只显示 Monaco 编辑器（适合写入）
 * - preview 只显示 Markdown 渲染（含 Mermaid 图）
 * - split  左右分屏：左编辑右预览
 */
type ViewMode = 'edit' | 'preview' | 'split';

/** 草稿编辑锁状态（对接后端 Redis 分布式锁） */
type EditLockStatus = 'off' | 'acquiring' | 'held' | 'blocked';

/** 续租间隔（秒），应小于后端 draft-edit-lock-ttl-seconds（默认 120） */
const EDIT_LOCK_RENEW_MS = 60_000;

const VIEW_MODE_OPTIONS: { value: ViewMode; label: string; icon: React.ReactNode }[] = [
  { value: 'edit', label: '编辑', icon: <EditOutlined /> },
  { value: 'preview', label: '预览', icon: <EyeOutlined /> },
  { value: 'split', label: '分屏', icon: <ColumnHeightOutlined /> },
];

/**
 * 「保存」弹窗中的快捷修订标签。
 * 点一下即填入输入框（已存在内容时在末尾追加），便于复核人快速标注本次修改意图。
 */
const SAVE_REMARK_PRESETS = [
  '修正错别字',
  '补充业务说明',
  '调整文档结构',
  '补充代码示例',
  '完善异常分支',
  '修正引用链接',
];

/**
 * 编辑器标题栏右侧「信息查询按钮」弹窗的类型。
 * - source    代码来源（DraftSourceReference[]）
 * - revision  修订记录（DraftRevision[]）
 * - comment   复核意见（DraftReviewComment[]）
 * 用单一 Modal + 动态内容渲染，比维护三个独立弹窗更易管理。
 */
type InfoModalType = 'source' | 'revision' | 'comment';

/**
 * 知识复核工作区（单任务）：Monaco 编辑、模块目录、任务级确认/重跑/推送。
 */
export interface DraftReviewWorkspaceProps {
  taskId: number;
}

/** 把 DraftSourceReference[] 按文件路径分组为 antd Tree 的 DataNode[] */
function formatRefLineRange(startLine: number, endLine: number): string {
  if (endLine === 0 || endLine < startLine) return '整文件';
  return `L${startLine} — L${endLine}`;
}

function buildRefTree(refs: DraftSourceReference[]): import('antd/es/tree').DataNode[] {
  const groups: Record<string, DraftSourceReference[]> = {};
  refs.forEach((r) => {
    const path = r.filePath || '(unknown)';
    if (!groups[path]) groups[path] = [];
    groups[path].push(r);
  });
  return Object.entries(groups).map(([path, items]) => ({
    key: path,
    title: (
      <span style={{ fontSize: 13 }}>
        <FileTextOutlined style={{ marginRight: 6 }} />
        {path} · {items.length} 条
      </span>
    ),
    children: items.map((r, i) => ({
      key: `${path}-${i}`,
      isLeaf: true,
      title: (
        <span style={{ fontSize: 12, color: '#667085' }}>
          {r.className && (
            <Text code style={{ fontSize: 11, marginRight: 6 }}>
              {r.className.split('.').pop()}
            </Text>
          )}
          {r.methodSignature && (
            <Text code style={{ fontSize: 11, marginRight: 6 }}>
              {r.methodSignature}
            </Text>
          )}
          {formatRefLineRange(r.startLine, r.endLine)}
        </span>
      ),
    })),
  }));
}

const DraftReviewWorkspace: React.FC<DraftReviewWorkspaceProps> = ({ taskId }) => {
  const navigate = useNavigate();
  const selectedTaskId = taskId;

  // ============ 当前任务 ============
  const [selectedTask, setSelectedTask] = useState<Task | null>(null);
  const [taskLoading, setTaskLoading] = useState(false);

  // ============ 演示模式开关 ============
  // 开启后所有 API 调用都走本地 mock store，可以离线体验完整复核流程。
  const [demoMode, setDemoMode] = useState<boolean>(() => getDemoMode());

  // ============ 工作区 & 草稿树（来自 DB） ============
  const [workspace, setWorkspace] = useState<DraftWorkspace | null>(null);
  const [treeData, setTreeData] = useState<DraftTreeNode[]>([]);
  const [hierarchyTree, setHierarchyTree] = useState<DraftHierarchyTreeNode[]>([]);
  const [draftsLoading, setDraftsLoading] = useState(false);
  const [selectedDraftId, setSelectedDraftId] = useState<number | null>(null);
  const [selectedDraft, setSelectedDraft] = useState<KnowledgeDraft | null>(null);

  // ============ 编辑器状态 ============
  const [editorContent, setEditorContent] = useState('');
  const [originalContent, setOriginalContent] = useState('');
  const [autoSaveStatus, setAutoSaveStatus] = useState('已同步');
  const autoSaveTimer = useRef<number | null>(null);
  const [editLockStatus, setEditLockStatus] = useState<EditLockStatus>('off');
  const editLockDraftIdRef = useRef<number | null>(null);
  const editLockRenewTimer = useRef<number | null>(null);

  // ============ 信息查询弹窗 ============
  // 由「代码来源 / 修订记录」两个按钮触发（复核意见已抽到任务级），null 表示无弹窗。
  const [infoModalType, setInfoModalType] = useState<InfoModalType | null>(null);
  const [revisions, setRevisions] = useState<DraftRevision[]>([]);
  // 当前选中草稿的复核意见（保留：单文件级查阅场景）
  const [comments, setComments] = useState<DraftReviewComment[]>([]);
  const [references, setReferences] = useState<DraftSourceReference[]>([]);
  const [sourceViewMode, setSourceViewMode] = useState<'flat' | 'tree'>('flat');
  const [infoLoading, setInfoLoading] = useState(false);

  // ============ 任务级复核意见（task-level comments） ============
  // 复核工作区「复核意见」按钮的真实语义入口：按任务粒度聚合整组草稿的意见。
  // 与单文件 comments 区分：前者面向整组（含任务级 [任务级通过] 记录），后者面向单篇。
  const [taskComments, setTaskComments] = useState<TaskCommentDto[]>([]);
  const [taskCommentsModalOpen, setTaskCommentsModalOpen] = useState(false);
  const [taskCommentsLoading, setTaskCommentsLoading] = useState(false);

  // ============ 左侧模块目录（hover/pin 抽屉） ============
  const [moduleDirOpen, setModuleDirOpen] = useState(false);
  const [moduleDirPinned, setModuleDirPinned] = useState(false);
  const moduleDirPinnedRef = useRef(false);
  const closeTimer = useRef<number | null>(null);

  // ============ 弹层 & 模态 ============
  const [saveModalOpen, setSaveModalOpen] = useState(false);
  const [saveRemark, setSaveRemark] = useState('');
  const [saveLoading, setSaveLoading] = useState(false);
  const [confirmModalOpen, setConfirmModalOpen] = useState(false);
  const [confirmComment, setConfirmComment] = useState('');
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [rerunLoading, setRerunLoading] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [autoSaveRetrying, setAutoSaveRetrying] = useState(false);
  const editorRef = useRef<any>(null);
  // 首次进入工作区时自动展开模块目录，让用户感知抽屉存在
  const hasAutoExpanded = useRef(false);

  // ============ 文档视图模式 ============
  // edit = 只编辑器；preview = 只预览；split = 左右分屏（编辑 + 渲染）
  const [viewMode, setViewMode] = useState<ViewMode>('preview');

  // 已推送 / 推送中 / 已归档 = 只读浏览
  // 自 v0.3 起 CONFIRMED 已从只读集合中移除：复核人确认后仍可继续编辑修改草稿。
  const isReadOnly = useMemo(
    () => (selectedDraft ? READ_ONLY_STATUSES.includes(selectedDraft.status) : false),
    [selectedDraft],
  );

  const clearEditLockRenewTimer = () => {
    if (editLockRenewTimer.current) {
      window.clearInterval(editLockRenewTimer.current);
      editLockRenewTimer.current = null;
    }
  };

  const releaseEditLockQuiet = async (draftId: number | null) => {
    if (!draftId || demoMode) return;
    try {
      await releaseDraftEditLock(draftId, getCurrentOperator());
    } catch {
      /* 离开页面时静默释放 */
    }
  };

  const handleToggleDemo = (next: boolean) => {
    toggleDemoMode(next);
    setDemoMode(next);
    setWorkspace(null);
    setTreeData([]);
    setHierarchyTree([]);
    setSelectedDraftId(null);
    setSelectedDraft(null);
    setEditorContent('');
    setOriginalContent('');
    setRevisions([]);
    setComments([]);
    setReferences([]);
    setEditLockStatus('off');
    editLockDraftIdRef.current = null;
    clearEditLockRenewTimer();
    message.success(
      next
        ? '已启用演示数据：所有读写走本地 mock store，可自由体验复核流程'
        : '已关闭演示数据，恢复调用真实后端接口',
    );
  };

  useEffect(() => {
    setTaskLoading(true);
    getTask(taskId)
      .then(setSelectedTask)
      .catch(() => {
        message.error('加载任务信息失败');
        setSelectedTask(null);
      })
      .finally(() => setTaskLoading(false));
  }, [taskId, demoMode]);

  /**
   * 推送锁定判定：任务处于 PUSHING / PUSHED 时所有写入操作（编辑 / 重跑 / 确认）禁用。
   */
  const LOCKED_TASK_STATUSES = ['PUSHING', 'PUSHED'];
  const isTaskLocked = useMemo(
    () => (selectedTask ? LOCKED_TASK_STATUSES.includes(selectedTask.status) : false),
    [selectedTask],
  );

  /** 编辑锁未就绪或已被他人占用时，Monaco 只读 */
  const isEditorReadOnly = useMemo(
    () => isReadOnly || isTaskLocked || editLockStatus === 'blocked' || editLockStatus === 'acquiring',
    [isReadOnly, isTaskLocked, editLockStatus],
  );

  // 平铺有文档的功能叶子，用于上一篇/下一篇导航
  const documentLeaves = useMemo(() => collectDocumentLeaves(hierarchyTree), [hierarchyTree]);
  const flatLeaves = useMemo(
    () => flattenDraftLeaves(treeData),
    [treeData],
  );

  // 当前选中草稿在平铺列表中的索引
  const currentLeafIndex = useMemo(
    () => documentLeaves.findIndex((leaf) => leaf.draftId === selectedDraftId),
    [documentLeaves, selectedDraftId],
  );

  // 按状态统计当前任务的模块数量（用于任务整体通过弹窗）
  const moduleStatusCounts = useMemo(() => countModulesByStatus(treeData), [treeData]);

  const allDraftsConfirmed = useMemo(() => {
    if (flatLeaves.length === 0) return false;
    return flatLeaves.every((leaf) => leaf.status === 'CONFIRMED' || leaf.status === 'PUSHED');
  }, [flatLeaves]);

  const unconfirmedDraftCount = useMemo(
    () => flatLeaves.filter((leaf) => leaf.status !== 'CONFIRMED' && leaf.status !== 'PUSHED').length,
    [flatLeaves],
  );

  const taskOverallConfirmed = useMemo(
    () =>
      selectedTask?.status === 'CONFIRMED'
      || selectedTask?.status === 'PUSHING'
      || selectedTask?.status === 'PUSHED',
    [selectedTask],
  );

  const canTaskOverallPass =
    allDraftsConfirmed && !taskOverallConfirmed && !isTaskLocked && !demoMode && flatLeaves.length > 0;

  /* ===================================================================
   *  数据拉取
   * =================================================================*/

  // 任务变更：拉取工作区 + DB 草稿树
  useEffect(() => {
    setDraftsLoading(true);
    Promise.all([
      getWorkspaceByTask(taskId),
      getWorkspaceTreeForTask(taskId),
      getModuleHierarchy(taskId).catch(() => null),
    ])
      .then(([wsRes, tree, hierarchy]) => {
        setWorkspace(wsRes.workspace);
        setTreeData(tree);
        const built = buildDraftHierarchyTree(hierarchy, tree);
        setHierarchyTree(built);
        const firstDraftId = findFirstDraftId(built);
        setSelectedDraftId(firstDraftId);
      })
      .catch(() => {
    setTreeData([]);
    setHierarchyTree([]);
    setSelectedDraftId(null);
      })
      .finally(() => setDraftsLoading(false));
  }, [taskId, demoMode]);

  // 选中草稿变更：拉取内容 + 修订记录 + 复核意见 + 代码来源引用
  useEffect(() => {
    if (!selectedDraftId) {
      setSelectedDraft(null);
      setEditorContent('');
      setOriginalContent('');
      setRevisions([]);
      setComments([]);
      setReferences([]);
      return;
    }

    // 找到当前选中的草稿实体（用于顶部标题展示状态）
    const found = findDraftInTree(treeData, selectedDraftId);
    setSelectedDraft(found);

    setInfoLoading(true);
    Promise.all([
      getDraftContent(selectedDraftId),
      getRevisions(selectedDraftId),
      getComments(selectedDraftId),
      getReferences(selectedDraftId),
    ])
      .then(([content, revisionData, commentData, referenceData]) => {
        setEditorContent(content);
        setOriginalContent(content);
        setAutoSaveStatus('已同步');
        setRevisions(revisionData);
        setComments(commentData);
        setReferences(referenceData);
      })
      .catch(() => {
        message.error('加载草稿详情失败');
      })
      .finally(() => setInfoLoading(false));
  }, [treeData, selectedDraftId, demoMode]);

  // 草稿编辑锁：选中可编辑草稿时 acquire，周期 renew，切换/离开时 release
  useEffect(() => {
    let cancelled = false;
    const author = getCurrentOperator();

    const setupLock = async () => {
      clearEditLockRenewTimer();
      const prevId = editLockDraftIdRef.current;
      if (prevId && prevId !== selectedDraftId) {
        await releaseEditLockQuiet(prevId);
        editLockDraftIdRef.current = null;
      }

      const draftMeta = selectedDraftId ? findDraftInTree(treeData, selectedDraftId) : null;
      const draftReadOnly = draftMeta ? READ_ONLY_STATUSES.includes(draftMeta.status) : true;
      const needLock = selectedDraftId && !draftReadOnly && !isTaskLocked && !demoMode;

      if (!needLock) {
        setEditLockStatus('off');
        return;
      }

      setEditLockStatus('acquiring');
      try {
        await acquireDraftEditLock(selectedDraftId!, author);
        if (cancelled) {
          await releaseDraftEditLock(selectedDraftId!, author);
          return;
        }
        editLockDraftIdRef.current = selectedDraftId;
        setEditLockStatus('held');
        editLockRenewTimer.current = window.setInterval(() => {
          if (editLockDraftIdRef.current !== selectedDraftId) return;
          renewDraftEditLock(selectedDraftId!, author).catch(() => {
            setEditLockStatus('blocked');
            message.warning('编辑锁已失效，请刷新页面后重试');
            clearEditLockRenewTimer();
          });
        }, EDIT_LOCK_RENEW_MS);
      } catch {
        if (!cancelled) {
          setEditLockStatus('blocked');
          message.error('该草稿正由其他用户编辑，当前为只读浏览');
        }
      }
    };

    setupLock();

    return () => {
      cancelled = true;
      clearEditLockRenewTimer();
    };
  }, [selectedDraftId, treeData, isTaskLocked, demoMode]);

  useEffect(() => () => {
    clearEditLockRenewTimer();
    const id = editLockDraftIdRef.current;
    if (id) {
      releaseEditLockQuiet(id);
      editLockDraftIdRef.current = null;
    }
  }, []);

  // 首次进入工作区时自动展开模块目录（3 秒后自动收起），让用户感知抽屉存在
  useEffect(() => {
    if (workspace && treeData.length > 0 && !hasAutoExpanded.current) {
      hasAutoExpanded.current = true;
      setModuleDirOpen(true);
      const timer = window.setTimeout(() => {
        if (!moduleDirPinnedRef.current) {
          setModuleDirOpen(false);
        }
      }, 3000);
      return () => window.clearTimeout(timer);
    }
  }, [workspace, treeData]);

  // 键盘快捷键：Ctrl+S 保存，Alt+←/→ 切换模块
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      // Ctrl+S / Cmd+S：保存当前草稿
      if ((e.ctrlKey || e.metaKey) && e.key === 's') {
        e.preventDefault();
        if (!selectedDraftId || isEditorReadOnly) return;
        if (editorContent === originalContent) return;
        // 小改动（<50 字符差异）：直接快速保存，不弹窗
        const diff = editorContent.length - originalContent.length;
        const absDiff = Math.abs(diff);
        if (absDiff < 50) {
          setSaveRemark('小修小补');
          setSaveModalOpen(true);
        } else {
          // 大改动：弹窗填写备注
          setSaveRemark('');
          setSaveModalOpen(true);
        }
      }
      // Alt+← / Alt+→：切换上一篇/下一篇模块
      if (e.altKey && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) {
        e.preventDefault();
        if (documentLeaves.length === 0 || currentLeafIndex < 0) return;
        if (e.key === 'ArrowLeft' && currentLeafIndex > 0) {
          setSelectedDraftId(documentLeaves[currentLeafIndex - 1].draftId);
        } else if (e.key === 'ArrowRight' && currentLeafIndex < documentLeaves.length - 1) {
          setSelectedDraftId(documentLeaves[currentLeafIndex + 1].draftId);
        }
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [selectedDraftId, isEditorReadOnly, editorContent, originalContent, documentLeaves, currentLeafIndex]);

  /* ===================================================================
   *  编辑器交互
   * =================================================================*/

  const handleEditorDidMount = (editor: any) => {
    editorRef.current = editor;
  };

  useEffect(() => {
    editorRef.current?.updateOptions({ readOnly: isEditorReadOnly });
  }, [isEditorReadOnly]);

  const handleEditorChange = (value: string | undefined) => {
    const nextValue = value || '';
    setEditorContent(nextValue);
    if (nextValue === originalContent) {
      return;
    }
    if (editLockStatus !== 'held') {
      return;
    }
    setAutoSaveStatus('正在保存草稿...');
    if (autoSaveTimer.current) {
      window.clearTimeout(autoSaveTimer.current);
    }
    // 1.8 秒防抖自动暂存
    autoSaveTimer.current = window.setTimeout(async () => {
      if (!selectedDraftId) return;
      try {
        await autoSaveDraft(selectedDraftId, nextValue, getCurrentOperator());
        setAutoSaveStatus('已自动保存');
      } catch {
        setAutoSaveStatus('自动保存失败（点击重试）');
      }
    }, 1800);
  };

  /** 自动保存失败后手动重试 */
  const handleRetryAutoSave = async () => {
    if (!selectedDraftId || autoSaveRetrying || editLockStatus !== 'held') return;
    setAutoSaveRetrying(true);
    setAutoSaveStatus('正在重试保存...');
    try {
      await autoSaveDraft(selectedDraftId, editorContent, getCurrentOperator());
      setAutoSaveStatus('已自动保存');
    } catch {
      setAutoSaveStatus('自动保存失败（点击重试）');
    } finally {
      setAutoSaveRetrying(false);
    }
  };

  const handleApprove = async () => {
    if (!selectedDraftId) return;
    try {
      await approveDraft(selectedDraftId);
      message.success("已审核通过，文档已锁定");
      // 更新本地选中草稿状态
      if (selectedDraft) setSelectedDraft({ ...selectedDraft, status: "CONFIRMED" });
      // 更新左侧树节点状态
      setTreeData((prev) => updateDraftStatusInTree(prev, selectedDraftId, "CONFIRMED"));
      // 通过后自动跳转到下一个文档
      if (currentLeafIndex >= 0 && currentLeafIndex < documentLeaves.length - 1) {
        setSelectedDraftId(documentLeaves[currentLeafIndex + 1].draftId);
      } else {
        message.success('已是最后一篇，全部文档已逐篇通过时可点击「任务整体通过」');
      }
    } catch (e) { message.error("审核通过失败"); }
  };

  const handleRegenerate = async () => {
    if (!selectedDraftId) return;
    try {
      await regenerateDraft(selectedDraftId);
      message.success("已重跑，文档已重置");
      if (selectedDraft) setSelectedDraft({ ...selectedDraft, status: "EDITING" });
    } catch (e) { message.error("重跑失败"); }
  };

  const handleSave = () => {
    if (!selectedDraftId || editLockStatus !== 'held') return;
    // 内容未改动时直接同步状态，避免无意义的修订记录
    if (editorContent === originalContent) {
      setAutoSaveStatus('已同步');
      return;
    }
    setSaveRemark('');
    setSaveModalOpen(true);
  };

  /**
   * 应用预设标签：已存在内容时用「；」分隔追加，保证可读性
   */
  const handleApplyPreset = (preset: string) => {
    setSaveRemark((prev) => (prev.trim() ? `${prev.trim()}；${preset}` : preset));
  };

  /**
   * 真正落盘：弹窗内点「确认保存」时触发，校验修订说明非空后调用 saveDraft。
   */
  const handleConfirmSave = async () => {
    if (!selectedDraftId) return;
    const remark = saveRemark.trim();
    if (!remark) {
      message.warning('请填写修订说明，便于后续追溯本次修改');
      return;
    }
    setSaveLoading(true);
    try {
      await saveDraft(selectedDraftId, editorContent, getCurrentOperator(), remark);
      message.success('草稿已保存');
      setOriginalContent(editorContent);
      setAutoSaveStatus('已同步');
      setRevisions(await getRevisions(selectedDraftId));
      setSaveModalOpen(false);
      setSaveRemark('');
    } catch {
      message.error('保存失败，请重试');
    } finally {
      setSaveLoading(false);
    }
  };

  const handleConfirm = () => {
    if (!selectedTaskId) return;
    setConfirmComment('');
    setConfirmModalOpen(true);
  };

  /**
   * 弹窗内点「确认通过」时触发 — 任务级入口：
   * 把当前 task 下整组草稿一次性置为 CONFIRMED，工作区升 COMPLETED，任务升 CONFIRMED。
   * 意见可选填：填写则作为任务级通过意见留痕（前缀 `[任务级通过]`）；不填也直接通过。
   *
   * <p>操作粒度是任务，不是单文件 — 即使当前只展示了某一篇 draft，点确认后整组都会通过。</p>
   */
  const handleConfirmSubmit = async () => {
    if (!selectedTaskId) return;
    setConfirmLoading(true);
    try {
      await confirmTask(
        selectedTaskId,
        getCurrentOperator(),
        confirmComment.trim() || undefined,
      );
      message.success('任务已整体确认通过，可前往推送页创建版本');
      setConfirmModalOpen(false);
      setConfirmComment('');
      // 重新拉取 treeData 让目录树每个节点状态从最新数据派生
      const tree = await getWorkspaceTreeForTask(selectedTaskId);
      setTreeData(tree);
      const hierarchy = await getModuleHierarchy(selectedTaskId).catch(() => null);
      setHierarchyTree(buildDraftHierarchyTree(hierarchy, tree));
      // 当前选中 draft 可能已被推到 CONFIRMED，重新拉评论列表保持一致
      if (selectedDraftId) {
        setComments(await getComments(selectedDraftId));
      }
      // 任务本身状态也变了，刷新一下工作区元数据
      try {
        const ws = await getWorkspaceByTask(selectedTaskId);
        setWorkspace(ws.workspace);
      } catch {
        /* 忽略，工作区状态在 treeData 中已经反映 */
      }
      // 重新拉取任务列表（确认后任务状态已变为 CONFIRMED，tasks 数组需要同步刷新）
      try {
        const updated = await getTask(selectedTaskId);
        setSelectedTask(updated);
      } catch {
        /* 忽略 */
      }
    } catch {
      message.error('确认失败，请重试');
    } finally {
      setConfirmLoading(false);
    }
  };

  const handleRerun = async () => {
    if (!selectedTaskId) return;
    setRerunLoading(true);
    message.loading({ content: '正在为当前任务发送重跑指令...', key: 'rerun' });
    try {
      // 走通用任务 API 重跑
      await retryTask(selectedTaskId);
      message.success({
        content: '任务已重新启动，系统正在后台解析并重新生成草稿',
        key: 'rerun',
      });
      // 简单延迟后刷新工作区
      setTimeout(() => {
        if (selectedTaskId) {
          getWorkspaceByTask(selectedTaskId)
            .then((res) => {
              setWorkspace(res.workspace);
              getWorkspaceTreeForTask(selectedTaskId).then(setTreeData);
            })
            .catch(() => {});
        }
      }, 1500);
    } catch {
      message.error({ content: '重跑指令发送失败', key: 'rerun' });
    } finally {
      setRerunLoading(false);
    }
  };

  const handlePush = () => {
    if (selectedTask?.status !== 'CONFIRMED') {
      message.warning('请先在复核页完成「任务整体通过」，任务状态为已确认后才可推送');
      return;
    }
    // 推送前最后一道人工防线：让复核人确认"无变更"再进推送页面。
    // 后端 pushVersion / pushToGit 已有 CONFIRMED 校验兜底，前端这里只做 UX 确认。
    const sysName = selectedTask?.systemId != null ? `系统 #${selectedTask.systemId}` : '当前系统';
    Modal.confirm({
      title: '确认开始推送？',
      content: (
        <Space direction="vertical" size={6} style={{ width: '100%' }}>
          <Text>请确认所有草稿内容已确认无变更。点击确认后前往推送页面。</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>
            当前任务：#{selectedTaskId} · {sysName}
          </Text>
        </Space>
      ),
      okText: '确认无变更，去推送',
      cancelText: '取消',
      onOk: () => navigate('/push', { state: { systemId: selectedTask?.systemId } }),
    });
  };

  /**
   * 任务级「复核意见」按钮处理：拉取 task 下整组草稿的复核意见并打开弹窗。
   * 粒度是任务，不是单文件 — 即使当前只看了某一篇 draft，弹窗里也是整组任务的全部意见
   * （含 confirmTask 写入的 [任务级通过] 任务级记录 + 各篇草稿的普通意见）。
   */
  const handleOpenTaskComments = async () => {
    if (!selectedTaskId) return;
    setTaskCommentsModalOpen(true);
    setTaskCommentsLoading(true);
    try {
      const list = await listTaskComments(selectedTaskId);
      setTaskComments(list);
    } catch {
      message.error('加载任务级复核意见失败');
    } finally {
      setTaskCommentsLoading(false);
    }
  };

  /* ===================================================================
   *  模块目录抽屉的 hover / pin 行为
   * =================================================================*/

  const openDrawer = () => {
    if (closeTimer.current) {
      window.clearTimeout(closeTimer.current);
      closeTimer.current = null;
    }
    setModuleDirOpen(true);
  };

  const scheduleCloseDrawer = () => {
    if (moduleDirPinned) return;
    if (closeTimer.current) window.clearTimeout(closeTimer.current);
    closeTimer.current = window.setTimeout(() => {
      setModuleDirOpen(false);
    }, 220);
  };

  const handlePinnedChange = (next: boolean) => {
    moduleDirPinnedRef.current = next;
    setModuleDirPinned(next);
    if (next) {
      setModuleDirOpen(true);
    }
  };

  const handleResetModuleDir = () => {
    moduleDirPinnedRef.current = false;
    setModuleDirPinned(false);
    setModuleDirOpen(false);
  };

  const drawerVisible = moduleDirOpen || moduleDirPinned;
  const hierarchyFunctionCount = useMemo(() => countHierarchyFunctions(hierarchyTree), [hierarchyTree]);

  // 模块层级树 → AntD Tree
  const treeNodes = useMemo(
    () => buildHierarchyAntTreeNodes(hierarchyTree),
    [hierarchyTree],
  );

  // 状态栏渲染函数：提取为独立函数，页面和全屏 Modal 复用
  const renderStatusBar = () => (
    <div className="ci-editor-statusbar">
      <div className="ci-statusbar-cluster">
        {(() => {
          if (demoMode || editLockStatus === 'off') {
            return null;
          }
          if (editLockStatus === 'held') {
            return (
              <Tooltip title="已持有编辑锁，其他用户无法同时编辑此草稿">
                <span className="ci-status-pill is-ok">
                  <span className="ci-status-pill-dot" />
                  <SafetyCertificateOutlined />
                  编辑锁已持有
                </span>
              </Tooltip>
            );
          }
          if (editLockStatus === 'acquiring') {
            return (
              <Tooltip title="正在向服务器申请编辑锁…">
                <span className="ci-status-pill is-saving">
                  <span className="ci-status-pill-dot" />
                  <ReloadOutlined spin />
                  获取编辑锁…
                </span>
              </Tooltip>
            );
          }
          return (
            <Tooltip title="该草稿正由其他用户编辑，当前为只读浏览">
              <span className="ci-status-pill is-error">
                <span className="ci-status-pill-dot" />
                <SafetyCertificateOutlined />
                编辑锁被占用
              </span>
            </Tooltip>
          );
        })()}
        {isReadOnly || isEditorReadOnly ? (
          <span className="ci-status-pill is-warn">
            <span className="ci-status-pill-dot" />
            只读浏览
          </span>
        ) : (
          <span className="ci-status-pill">
            <span className="ci-status-pill-dot" />
            可编辑
          </span>
        )}
      </div>
      <div className="ci-statusbar-cluster">
        {(() => {
          if (autoSaveStatus === '已自动保存' || autoSaveStatus === '已同步') {
            return (
              <span className="ci-status-pill is-ok">
                <span className="ci-status-pill-dot" />
                <CheckOutlined />
                {autoSaveStatus}
              </span>
            );
          }
          if (autoSaveStatus.includes('正在保存') || autoSaveStatus.includes('正在重试')) {
            return (
              <span className="ci-status-pill is-saving">
                <span className="ci-status-pill-dot" />
                <ReloadOutlined spin />
                {autoSaveStatus}
              </span>
            );
          }
          if (autoSaveStatus.includes('失败')) {
            return (
              <Tooltip title="点击重试自动保存">
                <span
                  className="ci-status-pill is-error ci-status-pill-clickable"
                  onClick={handleRetryAutoSave}
                  style={{ cursor: 'pointer' }}
                >
                  <span className="ci-status-pill-dot" />
                  <ReloadOutlined spin={autoSaveRetrying} />
                  {autoSaveRetrying ? '正在重试...' : '保存失败，点击重试'}
                </span>
              </Tooltip>
            );
          }
          return (
            <span className="ci-status-pill">
              <span className="ci-status-pill-dot" />
              {autoSaveStatus}
            </span>
          );
        })()}
      </div>
    </div>
  );

  // 任务级操作按钮组 — 粒度是整组任务，不是单篇草稿。
  // 设计：放在顶部「选择上下文」卡片里（系统/任务选择器行的下方），而不是编辑器工具栏。
  // 信息架构对齐「先选上下文 → 再对当前任务做任务级操作」。
  // 包含：
  //   - 确认通过：把整组草稿置 CONFIRMED，任务升 CONFIRMED
  //   - 复核意见：浏览 task 下整组草稿的全部意见（含任务级 [任务级通过] 记录）
  //   - 重跑：触发任务流水线重新分析
  //   - 去推送：跳转 /push 页触发整组知识版本推送
  // 任务处于 PUSHING / PUSHED 时确认/重跑被锁定，与后端 assertNotPushed 同步。
  const renderTaskActions = () => null;


  // 编辑器标题栏 actions - 按"导航 / 视图 / 复核 / 信息 / 工具"五组分组
  const renderEditorActions = () => {
    // 模块导航组：上一篇 / 下一篇，包含复核进度指示
    const totalLeaves = documentLeaves.length;
    const hasPrev = currentLeafIndex > 0;
    const hasNext = currentLeafIndex >= 0 && currentLeafIndex < totalLeaves - 1;
    const navGroup = selectedDraftId && totalLeaves > 1 ? (
      <div className="ci-action-group">
        <Tooltip title={hasPrev ? `上一篇：${documentLeaves[currentLeafIndex - 1]?.modulePath}` : '已是第一篇'}>
          <Button
            icon={<ArrowLeftOutlined />}
            onClick={() => hasPrev && setSelectedDraftId(documentLeaves[currentLeafIndex - 1].draftId)}
            disabled={!hasPrev}
          />
        </Tooltip>
        <span className="ci-nav-progress">
          {currentLeafIndex + 1}<span className="ci-nav-progress-sep">/</span>{totalLeaves}
        </span>
        <Tooltip title={hasNext ? `下一篇：${documentLeaves[currentLeafIndex + 1]?.modulePath}` : '已是最后一篇'}>
          <Button
            icon={<ArrowRightOutlined />}
            onClick={() => hasNext && setSelectedDraftId(documentLeaves[currentLeafIndex + 1].draftId)}
            disabled={!hasNext}
          />
        </Tooltip>
      </div>
    ) : null;

    // 视图切换组：edit / preview / split；位于工具栏最左侧，作为第一组
    // 容器复用 .ci-action-group（与"代码来源/重跑"等按钮组同尺寸），
    // Segmented 内部样式清零，让 label 与 .ci-action-group 内的按钮完全同尺寸
    const viewGroup = (
      <Tooltip title="切换文档视图：编辑 / 预览 / 分屏">
        <div className="ci-action-group">
          <Segmented
            className="ci-view-group"
            value={viewMode}
            onChange={(v) => setViewMode(v as ViewMode)}
            options={VIEW_MODE_OPTIONS.map((o) => ({
              value: o.value,
              label: (
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                  {o.icon}
                  <span>{o.label}</span>
                </span>
              ),
            }))}
          />
        </div>
      </Tooltip>
    );

    // 当前草稿编辑组：仅保存这一篇选中草稿；任务级别操作已抽到顶层 renderTaskActions
    // 自 v0.3 起移除"驳回"按钮：复核人通过直接编辑修改草稿，状态自动回流到 EDITING。
    // 任务处于 PUSHING / PUSHED 时即使草稿可编辑也禁用保存，与后端 assertNotPushed 同步。
    const draftEditGroup = !isEditorReadOnly ? (
      <div className="ci-action-group">
        <Tooltip title={isTaskLocked ? '任务已推送，文档已锁定' : '保存当前选中的草稿（物理落盘并写入修订记录）'}>
          <Button type="primary" icon={<SaveOutlined />} onClick={handleSave} disabled={isTaskLocked}>
            保存
          </Button>
        </Tooltip>
        <Tooltip title="审核通过此文档（锁定，不可再编辑）">
          <Button icon={<CheckCircleOutlined />} onClick={handleApprove} disabled={isTaskLocked}>
            通过
          </Button>
        </Tooltip>
        <Tooltip title="重跑此文档（重置为可编辑）">
          <Button icon={<ReloadOutlined />} onClick={handleRegenerate} disabled={isTaskLocked}>
            重跑此篇
          </Button>
        </Tooltip>
      </div>
    ) : null;

    // 信息查询组：代码来源 / 修订记录（每篇草稿级别）
    // 复核意见已抽到任务级 renderTaskActions —— 粒度从 per-draft 改为 per-task。
    const infoGroup = (
      <div className="ci-action-group">
        <Tooltip title="查看当前模块引用的源码文件与行号">
          <Button
            icon={<CodeOutlined />}
            onClick={() => setInfoModalType('source')}
            disabled={!selectedDraftId}
          >
            代码来源
            {references.length > 0 && <span className="ci-action-group-badge">{references.length}</span>}
          </Button>
        </Tooltip>
        <Tooltip title="查看当前模块的保存/修订历史">
          <Button
            icon={<HistoryOutlined />}
            onClick={() => setInfoModalType('revision')}
            disabled={!selectedDraftId}
          >
            修订记录
            {revisions.length > 0 && <span className="ci-action-group-badge">{revisions.length}</span>}
          </Button>
        </Tooltip>
      </div>
    );

    // 工具组：任务整体通过 / 全屏
    const toolGroup = (
      <div className="ci-action-group">
        <Tooltip
          title={
            taskOverallConfirmed
              ? '任务已完成整体确认'
              : unconfirmedDraftCount > 0
                ? `尚有 ${unconfirmedDraftCount} 篇文档未逐篇「通过」`
                : flatLeaves.length === 0
                  ? '暂无草稿文档'
                  : isTaskLocked
                    ? '任务已推送，不可再次确认'
                    : '全部文档已逐篇通过，点击完成任务整体确认后可创建版本并推送'
          }
        >
          <Button
            icon={<SafetyCertificateOutlined />}
            type="primary"
            onClick={handleConfirm}
            disabled={!canTaskOverallPass}
          >
            任务整体通过
          </Button>
        </Tooltip>
        <Tooltip title={isFullscreen ? '退出全屏' : '全屏浏览：文档占满整个视口'}>
          <Button
            icon={isFullscreen ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
            onClick={() => setIsFullscreen((v) => !v)}
          >
            {isFullscreen ? '退出全屏' : '全屏'}
          </Button>
        </Tooltip>
      </div>
    );

    return (
      <div className="ci-action-groups">
        {navGroup}
        {navGroup && <span className="ci-action-divider" />}
        {viewGroup}
        {draftEditGroup && <span className="ci-action-divider" />}
        {draftEditGroup}
        <span className="ci-action-divider" />
        {infoGroup}
        <span className="ci-action-divider" />
        {toolGroup}
      </div>
    );
  };

  // 信息查询弹窗 - 根据 infoModalType 渲染对应内容（时间线样式）
  const renderInfoModalContent = () => {
    if (!infoModalType) return null;
    if (infoLoading) {
      return (
        <div style={{ textAlign: 'center', padding: '48px 0' }}>
          <Spin />
        </div>
      );
    }

    // 空态视图（与时间线一致的视觉语言）
    const renderEmpty = (text: string, subtext: string) => (
      <div className="ci-info-empty">
        <div className="ci-info-empty-illus">
          {infoModalType === 'source' && <CodeOutlined />}
          {infoModalType === 'revision' && <HistoryOutlined />}
          {infoModalType === 'comment' && <MessageOutlined />}
        </div>
        <strong>{text}</strong>
        <span>{subtext}</span>
      </div>
    );

    switch (infoModalType) {
      case 'source': {
        if (references.length === 0) return renderEmpty('暂无代码来源', '当前模块未关联到具体的源码位置。');
        const refTreeData = buildRefTree(references);
        return (
          <div className="ci-info-modal-body">
            <Segmented
              size="small"
              options={[{ value: 'flat', label: '列表' }, { value: 'tree', label: '树形' }]}
              value={sourceViewMode}
              onChange={(v) => setSourceViewMode(v as 'flat' | 'tree')}
              style={{ marginBottom: 12 }}
            />
            {sourceViewMode === 'tree' ? (
              <Tree
                treeData={refTreeData}
                defaultExpandAll
                showLine={{ showLeafIcon: false }}
                blockNode
                style={{ fontSize: 13 }}
              />
            ) : (
              <div className="ci-info-timeline">
                {references.map((ref) => (
                  <div key={`${ref.filePath}-${ref.startLine}`} className="ci-info-timeline-item">
                    <div className="ci-info-timeline-rail">
                      <div className="ci-info-timeline-dot is-source">
                        <FileTextOutlined />
                      </div>
                    </div>
                    <div className="ci-info-timeline-body">
                      <div className="ci-info-timeline-title" title={ref.filePath}>
                        {ref.filePath}
                      </div>
                      <div className="ci-info-timeline-meta">
                        <FileTextOutlined />
                        <span>源码引用</span>
                        {ref.className && (
                          <>
                            <span className="ci-info-timeline-meta-sep">·</span>
                            <Text code style={{ fontSize: 11 }}>{ref.className}</Text>
                          </>
                        )}
                        {ref.methodSignature && (
                          <>
                            <span className="ci-info-timeline-meta-sep">·</span>
                            <Text code style={{ fontSize: 11 }}>{ref.methodSignature}</Text>
                          </>
                        )}
                      </div>
                      <div className="ci-info-timeline-line">
                        {formatRefLineRange(ref.startLine, ref.endLine)}
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        );
      }
      case 'revision':
        if (revisions.length === 0) return renderEmpty('暂无保存记录', '当前模块还未产生任何保存/修订历史。');
        return (
          <div className="ci-info-modal-body">
            <div className="ci-info-timeline">
              {revisions.map((rev) => (
                <div key={String(rev.id)} className="ci-info-timeline-item">
                  <div className="ci-info-timeline-rail">
                    <div className="ci-info-timeline-dot is-revision">
                      <ClockCircleOutlined />
                    </div>
                  </div>
                  <div className="ci-info-timeline-body">
                    <div className="ci-info-timeline-title">{rev.remark || '未填写修订说明'}</div>
                    <div className="ci-info-timeline-meta">
                      <span>操作人 {rev.author}</span>
                      <span className="ci-info-timeline-meta-sep">·</span>
                      <span>{new Date(rev.createdAt).toLocaleString()}</span>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        );
      case 'comment':
        if (comments.length === 0) return renderEmpty('暂无复核意见', '当前模块还未收到任何复核意见。');
        return (
          <div className="ci-info-modal-body">
            <div className="ci-info-timeline">
              {comments.map((c) => {
                const cType = (c.type || 'NORMAL').toUpperCase();
                const isPass = cType === 'PASS';
                const isReject = cType === 'REJECT';
                const dotClass = isPass
                  ? 'ci-info-timeline-dot is-comment is-pass'
                  : isReject
                  ? 'ci-info-timeline-dot is-comment is-reject'
                  : 'ci-info-timeline-dot is-comment';
                const typeLabel = isPass
                  ? { text: '通过意见', color: 'green' as const }
                  : isReject
                  ? { text: '驳回意见', color: 'red' as const }
                  : { text: '通用意见', color: 'cyan' as const };
                return (
                  <div key={String(c.id)} className="ci-info-timeline-item">
                    <div className="ci-info-timeline-rail">
                      <div className={dotClass}>
                        {isPass ? <CheckOutlined /> : isReject ? <CloseOutlined /> : <MessageOutlined />}
                      </div>
                    </div>
                    <div className="ci-info-timeline-body">
                      <div className="ci-info-timeline-title">
                        {c.author}
                        <Tag color={typeLabel.color} style={{ marginLeft: 8, fontSize: 11 }}>
                          {typeLabel.text}
                        </Tag>
                      </div>
                      <div className="ci-info-timeline-meta">
                        <ClockCircleOutlined />
                        <span>{new Date(c.createdAt).toLocaleString()}</span>
                      </div>
                      <div className="ci-info-timeline-content">{c.comment}</div>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        );
    }
  };

  // 信息查询弹窗的标题
  const infoModalTitle = (() => {
    switch (infoModalType) {
      case 'source':
        return '代码来源';
      case 'revision':
        return '修订记录';
      case 'comment':
        return '复核意见';
      default:
        return '';
    }
  })();

  /* ===================================================================
   *  渲染主结构
   * =================================================================*/

  return (
    <div className="ci-page ci-review-page ci-drafts-page">
      {/* 演示模式横幅：开启后所有数据来自本地 mock store */}
      {demoMode && (
        <div className="ci-demo-banner" role="status">
          <span className="ci-demo-banner-icon">
            <ExperimentOutlined />
          </span>
          <div className="ci-demo-banner-text">
            <strong>演示模式已开启</strong>
            <span>所有读写走本地 mock store，可自由编辑 / 保存 / 确认，不会写入真实数据库</span>
          </div>
          <Button size="small" type="default" onClick={() => handleToggleDemo(false)}>
            退出演示
          </Button>
        </div>
      )}

      {/* 任务上下文与任务级操作 */}
      <Card className="ci-filter-card" style={{ flexShrink: 0 }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 12,
            flexWrap: 'wrap',
          }}
        >
          <Space wrap>
            <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/drafts')}>
              返回任务列表
            </Button>
            <Text strong>知识复核 · 任务 #{taskId}</Text>
            {selectedTask && (
              <>
                <Tag color={selectedTask.type === 'INITIAL' ? 'geekblue' : 'green'}>
                  {selectedTask.type === 'INITIAL' ? '全量' : '增量'}
                </Tag>
                <Tag color={statusColor[selectedTask.status] ?? 'default'}>
                  {statusLabel[selectedTask.status] ?? selectedTask.status}
                </Tag>
              </>
            )}
            {taskLoading && <Text type="secondary">加载中…</Text>}
          </Space>
          {renderTaskActions()}
        </div>
      </Card>

      {/* 工作区主体：左侧模块目录（hover/pin 抽屉）+ 编辑器（代码来源/修订记录/复核意见已搬到标题栏弹窗） */}
      {workspace ? (
        <Row gutter={[16, 16]} className="ci-review-grid">
          <Col xs={24} lg={24}>
            <Card
              className="ci-review-panel"
              title={
                <div className="ci-review-title-block">
                  <span className="ci-review-title-icon">
                    <AppstoreOutlined />
                  </span>
                  <div className="ci-review-title-text">
                    <div className="ci-review-title-name">
                      <strong>{selectedDraft?.moduleName || '未选择模块'}</strong>
                      {selectedDraft && (
                        <Tag color={statusColor[selectedDraft.status] ?? 'default'}>
                          {statusLabel[selectedDraft.status] ?? selectedDraft.status}
                        </Tag>
                      )}
                      {isTaskLocked && <Tag color="red">任务已锁定</Tag>}
                      {isReadOnly && <Tag color="default">只读浏览</Tag>}
                    </div>
                    {selectedDraft?.filePath ? (
                      <div className="ci-review-title-path" title={selectedDraft.filePath}>
                        <FileTextOutlined />
                        <span>{selectedDraft.filePath}</span>
                      </div>
                    ) : (
                      <div className="ci-review-title-path" style={{ opacity: 0.5 }}>
                        <FileTextOutlined />
                        <span>从左侧模块目录选择一个 Markdown 草稿开始</span>
                      </div>
                    )}
                  </div>
                </div>
              }
              extra={renderEditorActions()}
            >
              {selectedDraftId ? (
                <div style={{ display: 'flex', flexDirection: 'column', height: '100%', gap: '10px' }}>
                  <div
                    className={
                      viewMode === 'split'
                        ? 'ci-editor-split'
                        : 'ci-editor-shell ci-editor-shell-premium'
                    }
                    style={{ flex: 1, minHeight: 0 }}
                  >
                    {(viewMode === 'edit' || viewMode === 'split') && (
                      <div className="ci-editor-shell ci-editor-shell-premium" style={{ minHeight: 0 }}>
                        <Editor
                          height="100%"
                          defaultLanguage="markdown"
                          theme="vs-light"
                          value={editorContent}
                          onChange={handleEditorChange}
                          onMount={handleEditorDidMount}
                          options={{
                            readOnly: isEditorReadOnly,
                            minimap: { enabled: false },
                            wordWrap: 'on',
                            lineNumbers: 'on',
                            fontSize: 14,
                            fontFamily: 'Consolas, "Courier New", monospace',
                            lineHeight: 22,
                            scrollbar: { vertical: 'visible', horizontal: 'visible' },
                          }}
                        />
                      </div>
                    )}
                    {(viewMode === 'preview' || viewMode === 'split') && (
                      <div className="ci-md-scroll">
                        <MarkdownView content={editorContent} />
                      </div>
                    )}
                  </div>
                  {renderStatusBar()}
                </div>
              ) : (
                <div className="ci-empty-panel" style={{ height: '100%' }}>
                  <div className="ci-empty-illus">
                    <FileSearchOutlined />
                  </div>
                  <strong>请选择一个模块草稿</strong>
                  <span>从左侧模块目录（点击页面左边缘可展开）选择一份 Markdown 草稿开始浏览或复核。</span>
                </div>
              )}
            </Card>
          </Col>
        </Row>
      ) : (
        <Card>
          <div className="ci-empty-panel">
            <div className="ci-empty-illus">
              <FileSearchOutlined />
            </div>
            <strong>该任务暂未生成草稿工作区</strong>
            <span>请先执行任务直到文档生成完成，之后此处会自动出现可复核的草稿列表。</span>
          </div>
        </Card>
      )}

      {/* 左侧模块目录 - 悬浮触发器（沿页面左边缘） */}
      {workspace && (
        <Tooltip placement="right" title={drawerVisible ? '收起模块目录' : '展开模块目录（可固定）'}>
          <div
            className={`ci-module-drawer-handle ${drawerVisible ? 'is-open' : ''}`}
            onMouseEnter={openDrawer}
            onClick={() => (drawerVisible ? scheduleCloseDrawer() : openDrawer())}
          >
            <BarsOutlined />
            <span className="ci-module-drawer-handle-label">模块</span>
            <span className="ci-module-drawer-handle-count">{hierarchyFunctionCount}</span>
          </div>
        </Tooltip>
      )}

      {/* 左侧模块目录 - 层级树 + 可调大小 / 固定拖拽 */}
      {workspace && (
        <DraftModuleDirectory
          visible={drawerVisible}
          pinned={moduleDirPinned}
          loading={draftsLoading}
          leafCount={hierarchyFunctionCount}
          treeData={treeNodes}
          selectedDraftId={selectedDraftId}
          onSelectDraft={setSelectedDraftId}
          onPinnedChange={handlePinnedChange}
          onResetDock={handleResetModuleDir}
          onMouseEnter={openDrawer}
          onMouseLeave={scheduleCloseDrawer}
        />
      )}

      {/* 修订说明输入弹框：手动保存时强制要求填写本次修改意图 */}
      <Modal
        title={
          <Space>
            <span
              className="ci-review-title-icon"
              style={{
                width: 28,
                height: 28,
                fontSize: 14,
                borderRadius: 7,
                color: '#2a55c4',
                background: 'linear-gradient(135deg, #eaf0ff 0%, #d6e1ff 100%)',
                borderColor: '#c5cff5',
              }}
            >
              <SaveOutlined />
            </span>
            <span>保存草稿</span>
            {selectedDraft && <Tag color="default">{selectedDraft.moduleName}</Tag>}
          </Space>
        }
        open={saveModalOpen}
        onOk={handleConfirmSave}
        onCancel={() => {
          setSaveModalOpen(false);
          setSaveRemark('');
        }}
        okText="确认保存"
        okButtonProps={{ disabled: !saveRemark.trim(), loading: saveLoading }}
        cancelButtonProps={{ disabled: saveLoading }}
        destroyOnClose
      >
        <div style={{ padding: '8px 0' }}>
          <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 8 }}>
            请简要说明本次修改的目的（必填），便于后续追溯与团队协作。1.8s 防抖的自动保存不会写入修订记录。
          </Text>
          <div style={{ marginBottom: 10 }}>
            <Text style={{ fontSize: 12, color: '#818aa0', marginRight: 8 }}>快捷标签：</Text>
            <Space wrap size={[6, 6]}>
              {SAVE_REMARK_PRESETS.map((preset) => (
                <Tag.CheckableTag
                  key={preset}
                  checked={saveRemark.trim() === preset}
                  onChange={() => handleApplyPreset(preset)}
                  style={{
                    padding: '3px 10px',
                    borderRadius: 14,
                    border: '1px solid #d8dce8',
                    background: '#ffffff',
                    fontSize: 12,
                  }}
                >
                  {preset}
                </Tag.CheckableTag>
              ))}
            </Space>
          </div>
          <TextArea
            rows={4}
            placeholder="例如：补充 ZSet 在分布式锁场景下的描述..."
            value={saveRemark}
            onChange={(event) => setSaveRemark(event.target.value)}
            disabled={saveLoading}
            autoFocus
          />
          <div style={{ marginTop: 6, fontSize: 11, color: saveRemark.trim() ? '#5258e8' : '#c4485d' }}>
            {saveRemark.trim() ? `已填写 ${saveRemark.trim().length} 字` : '修订说明不能为空'}
          </div>
        </div>
      </Modal>

      {/* 任务级「确认通过」弹框：可选填任务级通过意见；
          填了会作为 type=PASS 写入复核意见表（带 `[任务级通过]` 前缀）。 */}
      <Modal
        title={
          <Space>
            <span
              className="ci-review-title-icon"
              style={{
                width: 28,
                height: 28,
                fontSize: 14,
                borderRadius: 7,
                color: '#3a7d4e',
                background: 'linear-gradient(135deg, #ecf7ef 0%, #d3ecd9 100%)',
                borderColor: '#b9dac3',
              }}
            >
              <CheckOutlined />
            </span>
            <span>任务整体通过</span>
            {selectedTask && <Tag color="processing">任务 #{selectedTask.id}</Tag>}
          </Space>
        }
        open={confirmModalOpen}
        onOk={handleConfirmSubmit}
        onCancel={() => {
          setConfirmModalOpen(false);
          setConfirmComment('');
        }}
        okText="任务整体通过"
        okButtonProps={{ loading: confirmLoading, disabled: !allDraftsConfirmed }}
        cancelButtonProps={{ disabled: confirmLoading }}
        destroyOnClose
      >
        <div style={{ padding: '8px 0' }}>
          {/* 模块完成度统计：展示当前任务下各状态模块数量，提醒复核人确认前核对 */}
          {(() => {
            const total = flatLeaves.length;
            const draftCount = moduleStatusCounts['DRAFT'] || 0;
            const editingCount = moduleStatusCounts['EDITING'] || 0;
            const confirmedCount = moduleStatusCounts['CONFIRMED'] || 0;
            const unconfirmedCount = draftCount + editingCount;
            return (
              <div
                style={{
                  background: unconfirmedCount > 0
                    ? 'linear-gradient(135deg, #fffbeb 0%, #fef3c7 100%)'
                    : 'linear-gradient(135deg, #ecfdf5 0%, #d1fae5 100%)',
                  border: unconfirmedCount > 0 ? '1px solid #fcd34d' : '1px solid #6ee7b7',
                  borderRadius: 8,
                  padding: '10px 14px',
                  marginBottom: 12,
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
                  <Text strong style={{ fontSize: 13 }}>
                    模块完成度
                  </Text>
                  <Tag color={unconfirmedCount > 0 ? 'warning' : 'success'} style={{ fontSize: 11 }}>
                    {unconfirmedCount > 0 ? '未全部确认' : '全部已确认'}
                  </Tag>
                </div>
                <Space size={12} wrap>
                  {draftCount > 0 && (
                    <Text style={{ fontSize: 12 }}>
                      <Tag color="magenta" style={{ fontSize: 10, marginRight: 4 }}>待处理</Tag>
                      {draftCount} 个
                    </Text>
                  )}
                  {editingCount > 0 && (
                    <Text style={{ fontSize: 12 }}>
                      <Tag color="geekblue" style={{ fontSize: 10, marginRight: 4 }}>已编辑</Tag>
                      {editingCount} 个
                    </Text>
                  )}
                  <Text style={{ fontSize: 12 }}>
                    <Tag color="green" style={{ fontSize: 10, marginRight: 4 }}>已确认</Tag>
                    {confirmedCount} 个
                  </Text>
                  <Text type="secondary" style={{ fontSize: 11 }}>共 {total} 个模块</Text>
                </Space>
                {unconfirmedCount > 0 ? (
                  <div style={{ marginTop: 6, fontSize: 11, color: '#92400e' }}>
                    尚有 {unconfirmedCount} 篇文档未逐篇「通过」，请先完成逐篇确认。
                  </div>
                ) : (
                  <div style={{ marginTop: 6, fontSize: 11, color: '#047857' }}>
                    全部文档已逐篇通过，可提交任务整体确认。
                  </div>
                )}
              </div>
            );
          })()}
          <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 8 }}>
            可选填写通过说明，便于后续追溯通过理由。留空也可直接通过。
          </Text>
          <TextArea
            rows={4}
            placeholder="例如：业务描述准确，关键流程覆盖完整..."
            value={confirmComment}
            onChange={(event) => setConfirmComment(event.target.value)}
            disabled={confirmLoading}
            autoFocus
          />
          <div style={{ marginTop: 6, fontSize: 11, color: '#818aa0' }}>
            {confirmComment.trim()
              ? `已填写 ${confirmComment.trim().length} 字 · 将作为通过意见归档`
              : '本次操作不会写入复核意见'}
          </div>
        </div>
      </Modal>

      {/* 信息查询弹窗：代码来源 / 修订记录 / 复核意见 */}
      <Modal
        title={
          <div className="ci-review-title-block">
            <span
              className="ci-review-title-icon"
              style={{
                color: infoModalType === 'revision' ? '#2a55c4'
                  : infoModalType === 'comment' ? '#137e91'
                  : '#5258e8',
                background: infoModalType === 'revision'
                  ? 'linear-gradient(135deg, #eaf0ff 0%, #d6e1ff 100%)'
                  : infoModalType === 'comment'
                  ? 'linear-gradient(135deg, #e0f3f7 0%, #c1e8ef 100%)'
                  : 'linear-gradient(135deg, #eef0ff 0%, #dde3ff 100%)',
                borderColor: infoModalType === 'revision' ? '#c5cff5'
                  : infoModalType === 'comment' ? '#b6dde5'
                  : '#d8dcf2',
              }}
            >
              {infoModalType === 'source' && <CodeOutlined />}
              {infoModalType === 'revision' && <HistoryOutlined />}
              {infoModalType === 'comment' && <MessageOutlined />}
            </span>
            <div className="ci-review-title-text">
              <div className="ci-review-title-name">
                <strong>{infoModalTitle}</strong>
                {selectedDraft && <Tag color="default">{selectedDraft.moduleName}</Tag>}
              </div>
              <div className="ci-review-title-path" style={{ textTransform: 'none', letterSpacing: 0 }}>
                {infoModalType === 'source' && (() => {
                  const dot = infoLoading ? '加载中…' : `${references.length} 条源码引用`;
                  return <span>{dot} · 来自该模块的解析索引</span>;
                })()}
                {infoModalType === 'revision' && (() => {
                  const dot = infoLoading ? '加载中…' : `${revisions.length} 条修订记录`;
                  return <span>{dot} · 包含自动暂存与人工保存</span>;
                })()}
                {infoModalType === 'comment' && (() => {
                  const dot = infoLoading ? '加载中…' : `${comments.length} 条复核意见`;
                  return <span>{dot} · 来自复核人的反馈</span>;
                })()}
              </div>
            </div>
          </div>
        }
        open={infoModalType !== null}
        onCancel={() => setInfoModalType(null)}
        footer={null}
        width={720}
        destroyOnHidden
      >
        <div style={{ maxHeight: 'calc(70vh - 80px)', overflowY: 'auto', paddingRight: 4 }}>
          {renderInfoModalContent()}
        </div>
      </Modal>

      {/* 任务级「复核意见」弹窗：task 下整组草稿的意见聚合视图
          与上面 infoModalType==='comment' 的单文件弹窗并存 —— 前者面向整组（任务级），
          后者面向单篇。粒度按按钮所在组自动对齐：复核意见按钮 → 这里；infoGroup 按钮 → 上面的 infoModal。 */}
      <Modal
        title={
          <Space>
            <span
              className="ci-review-title-icon"
              style={{
                width: 28,
                height: 28,
                fontSize: 14,
                borderRadius: 7,
                color: '#137e91',
                background: 'linear-gradient(135deg, #e7f6f9 0%, #cfeaef 100%)',
                borderColor: '#b6dde5',
              }}
            >
              <MessageOutlined />
            </span>
            <span>复核意见</span>
            {selectedTask && <Tag color="processing">任务 #{selectedTask.id}</Tag>}
          </Space>
        }
        open={taskCommentsModalOpen}
        onCancel={() => setTaskCommentsModalOpen(false)}
        footer={null}
        width={760}
        destroyOnHidden
      >
        <Spin spinning={taskCommentsLoading}>
          {taskComments.length === 0 ? (
            <div className="ci-info-empty">
              <div className="ci-info-empty-illus">
                <MessageOutlined />
              </div>
              <div className="ci-info-empty-text">
                <strong>暂无复核意见</strong>
                <span>当前任务还未收到任何复核意见。点击「确认通过」会写入 1 条任务级通过意见。</span>
              </div>
            </div>
          ) : (
            <div style={{ maxHeight: 'calc(70vh - 80px)', overflowY: 'auto', paddingRight: 4 }}>
              <div className="ci-info-timeline">
                {taskComments.map((c) => {
                  const cType = (c.type || 'NORMAL').toUpperCase();
                  const isPass = cType === 'PASS';
                  const isReject = cType === 'REJECT';
                  const dotClass = isPass
                    ? 'ci-info-timeline-dot is-comment is-pass'
                    : isReject
                    ? 'ci-info-timeline-dot is-comment is-reject'
                    : 'ci-info-timeline-dot is-comment';
                  const typeLabel = isPass
                    ? { text: '通过意见', color: 'green' as const }
                    : isReject
                    ? { text: '驳回意见', color: 'red' as const }
                    : { text: '通用意见', color: 'cyan' as const };
                  const isTaskLevel = (c.comment ?? '').startsWith('[任务级通过]');
                  return (
                    <div key={String(c.id)} className="ci-info-timeline-item">
                      <div className="ci-info-timeline-rail">
                        <div className={dotClass}>
                          {isPass ? <CheckOutlined /> : isReject ? <CloseOutlined /> : <MessageOutlined />}
                        </div>
                      </div>
                      <div className="ci-info-timeline-body">
                        <div className="ci-info-timeline-title">
                          {c.author}
                          <Tag color={typeLabel.color} style={{ marginLeft: 8, fontSize: 11 }}>
                            {typeLabel.text}
                          </Tag>
                          {isTaskLevel && (
                            <Tag color="processing" style={{ marginLeft: 4, fontSize: 11 }}>
                              任务级
                            </Tag>
                          )}
                          {c.moduleName && (
                            <Tag style={{ marginLeft: 4, fontSize: 11 }}>{c.moduleName}</Tag>
                          )}
                        </div>
                        <div className="ci-info-timeline-meta">
                          <ClockCircleOutlined />
                          <span>{new Date(c.createdAt).toLocaleString()}</span>
                          {c.filePath && (
                            <>
                              <FileTextOutlined style={{ marginLeft: 12 }} />
                              <span>{c.filePath}</span>
                            </>
                          )}
                        </div>
                        <div className="ci-info-timeline-content">{c.comment}</div>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </Spin>
      </Modal>

      {/* 全屏浏览 Modal：编辑器搬到 body 层级，脱离 BasicLayout 尺寸限制
          头部复刻页面 actions 工具栏（含代码来源/修订记录/复核意见等弹窗入口），
          正文里嵌入模块抽屉，共享同一 moduleDirOpen/Pinned 状态。 */}
      <Modal
        open={isFullscreen}
        onCancel={() => setIsFullscreen(false)}
        footer={null}
        closable={true}
        width="100vw"
        style={{ top: 0, maxWidth: '100vw', padding: 0, height: '100vh' }}
        styles={{ body: { height: 'calc(100vh - 55px)', padding: 0 } }}
        destroyOnHidden
      >
        <div
          style={{
            padding: '12px 24px',
            borderBottom: '1px solid var(--premium-line-soft)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 12,
            background: 'linear-gradient(180deg, #fafbff 0%, #ffffff 100%)',
            boxShadow: '0 1px 0 rgba(15, 22, 45, 0.02)',
          }}
        >
          <div className="ci-review-title-block">
            <span className="ci-review-title-icon">
              <AppstoreOutlined />
            </span>
            <div className="ci-review-title-text">
              <div className="ci-review-title-name">
                <strong>{selectedDraft?.moduleName || '未选择模块'}</strong>
                {selectedDraft && (
                  <Tag color={statusColor[selectedDraft.status] ?? 'default'}>
                    {statusLabel[selectedDraft.status] ?? selectedDraft.status}
                  </Tag>
                )}
                {isTaskLocked && <Tag color="red">任务已锁定</Tag>}
                {isReadOnly && <Tag color="default">只读浏览</Tag>}
              </div>
              <div className="ci-review-title-path" style={{ textTransform: 'none', letterSpacing: 0 }}>
                <FullscreenOutlined />
                <span>全屏浏览模式 · 按 ESC 或点右上角 ✕ 退出</span>
              </div>
            </div>
          </div>
          {renderEditorActions()}
        </div>
        <div className="ci-fullscreen-body" style={{ height: 'calc(100vh - 110px)' }}>
          {selectedDraftId ? (
            viewMode === 'edit' ? (
              <Editor
                height="100%"
                defaultLanguage="markdown"
                theme="vs-light"
                value={editorContent}
                onChange={handleEditorChange}
                options={{
                  readOnly: isEditorReadOnly,
                  minimap: { enabled: false },
                  wordWrap: 'on',
                  lineNumbers: 'on',
                  fontSize: 14,
                  fontFamily: 'Consolas, "Courier New", monospace',
                  lineHeight: 22,
                  scrollbar: { vertical: 'visible', horizontal: 'visible' },
                }}
              />
            ) : viewMode === 'preview' ? (
              <div className="ci-md-scroll" style={{ height: '100%' }}>
                <MarkdownView content={editorContent} />
              </div>
            ) : (
              <div className="ci-editor-split" style={{ height: '100%' }}>
                <div className="ci-editor-shell ci-editor-shell-premium" style={{ minHeight: 0 }}>
                  <Editor
                    height="100%"
                    defaultLanguage="markdown"
                    theme="vs-light"
                    value={editorContent}
                    onChange={handleEditorChange}
                    options={{
                      readOnly: isEditorReadOnly,
                      minimap: { enabled: false },
                      wordWrap: 'on',
                      lineNumbers: 'on',
                      fontSize: 14,
                      fontFamily: 'Consolas, "Courier New", monospace',
                      lineHeight: 22,
                      scrollbar: { vertical: 'visible', horizontal: 'visible' },
                    }}
                  />
                </div>
                <div className="ci-md-scroll" style={{ minHeight: 0 }}>
                  <MarkdownView content={editorContent} />
                </div>
              </div>
            )
          ) : (
            <div className="ci-empty-panel" style={{ height: '100%' }}>
              <div className="ci-empty-illus">
                <FileSearchOutlined />
              </div>
              <strong>请先选择一个模块</strong>
              <span>从左侧模块目录选择一份 Markdown 草稿开始浏览或复核。</span>
            </div>
          )}

          {/* 全屏模式状态栏：与页面编辑器底部状态栏复用同一渲染逻辑 */}
          {selectedDraftId && renderStatusBar()}

          {/* 全屏下的模块目录 handle：与页面版本共享 moduleDirOpen/Pinned 状态 */}
          {workspace && (
            <Tooltip placement="right" title={drawerVisible ? '收起模块目录' : '展开模块目录（可固定）'}>
              <div
                className={`ci-module-drawer-handle ${drawerVisible ? 'is-open' : ''}`}
                onMouseEnter={openDrawer}
                onClick={() => (drawerVisible ? scheduleCloseDrawer() : openDrawer())}
              >
                <BarsOutlined />
                <span className="ci-module-drawer-handle-label">模块</span>
                <span className="ci-module-drawer-handle-count">{hierarchyFunctionCount}</span>
              </div>
            </Tooltip>
          )}

          {workspace && (
            <DraftModuleDirectory
              visible={drawerVisible}
              pinned={moduleDirPinned}
              loading={draftsLoading}
              leafCount={hierarchyFunctionCount}
              treeData={treeNodes}
              selectedDraftId={selectedDraftId}
              onSelectDraft={setSelectedDraftId}
              onPinnedChange={handlePinnedChange}
              onResetDock={handleResetModuleDir}
              onMouseEnter={openDrawer}
              onMouseLeave={scheduleCloseDrawer}
              fullscreen
            />
          )}
        </div>
      </Modal>
    </div>
  );
};

/* =====================================================================
 *  辅助函数
 * ===================================================================*/

/** 通过任务 ID 拉取工作区的草稿树（包一层 try/catch） */
async function getWorkspaceTreeForTask(taskId: number): Promise<DraftTreeNode[]> {
  try {
    // 1) 先拉工作区元信息（拿到 workspaceId）
    const wsRes = await getWorkspaceByTask(taskId);
    if (!wsRes.workspace) return [];
    // 2) 再用 workspaceId 调树接口
    return await getWorkspaceTree(wsRes.workspace.id);
  } catch {
    return [];
  }
}

/** 在 DB 树中按 id 查找草稿节点 */
function findDraftInTree(nodes: DraftTreeNode[], id: number): KnowledgeDraft | null {
  for (const n of nodes) {
    if (n.id === id) {
      // 树节点字段与 KnowledgeDraft 兼容（除 children 与 isFolder 外）
      const draft: KnowledgeDraft = {
        id: n.id,
        workspaceId: n.workspaceId,
        parentId: n.parentId,
        filePath: n.filePath,
        moduleName: n.moduleName,
        contentUri: '',
        status: n.status,
        sortOrder: n.sortOrder,
        hash: '',
        createdAt: '',
        updatedAt: '',
      };
      return draft;
    }
    const found = findDraftInTree(n.children || [], id);
    if (found) return found;
  }
  return null;
}

/**
 * 递归更新树中指定草稿节点的状态。
 */
function updateDraftStatusInTree(nodes: DraftTreeNode[], draftId: number, newStatus: string): DraftTreeNode[] {
  return nodes.map((n) => {
    if (n.id === draftId) {
      return { ...n, status: newStatus };
    }
    if (n.children && n.children.length > 0) {
      return { ...n, children: updateDraftStatusInTree(n.children, draftId, newStatus) };
    }
    return n;
  });
}

/**
 * 按文档状态统计模块数量，用于确认通过前的完成度展示。
 */
function countModulesByStatus(nodes: DraftTreeNode[]): Record<string, number> {
  const leaves = flattenDraftLeaves(nodes);
  return leaves.reduce(
    (acc, leaf) => {
      const s = leaf.status || 'DRAFT';
      acc[s] = (acc[s] || 0) + 1;
      return acc;
    },
    {} as Record<string, number>,
  );
}

export default DraftReviewWorkspace;
