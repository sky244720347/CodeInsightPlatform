import request from './request';
import {
  isMockEnabled,
  mockAcquireDraftEditLock,
  mockAutoSaveDraft,
  mockConfirmDraft,
  mockRenewDraftEditLock,
  mockReleaseDraftEditLock,
  mockGetComments,
  mockGetDraftContent,
  mockGetReferences,
  mockGetRevisions,
  mockGetWorkspaceByTask,
  mockGetWorkspaceTree,
  mockListAllTasksBySystem,
  mockListPreviewSystems,
  mockListReviewableTasks,
  mockListTaskComments,
  mockSaveDraft,
  setMockEnabled,
} from './mock/drafts.mock';

const DEMO_MODE_KEY = 'ci-draft-demo-mode';

/** 复核工作区演示模式：读 localStorage，与 mock store 联动 */
export function getDemoMode(): boolean {
  return localStorage.getItem(DEMO_MODE_KEY) === 'true';
}

export function toggleDemoMode(enabled: boolean): void {
  localStorage.setItem(DEMO_MODE_KEY, String(enabled));
  setMockEnabled(enabled);
}

export interface DraftWorkspace {
  id: number;
  taskId: number;
  systemId: number;
  repositoryId: number;
  status: string;
  createdDate: string;
  updatedDate: string;
}

export interface KnowledgeDraft {
  id: number;
  workspaceId: number;
  parentId: number | null;
  filePath: string;
  moduleName: string;
  contentUri: string;
  status: string;
  sortOrder: number;
  hash: string;
  createdDate: string;
  updatedDate: string;
}

/**
 * 知识草稿目录树节点 DTO
 * 后端通过 GET /api/drafts/workspace/{id}/tree 返回，可直接喂给 AntD Tree 组件。
 * children 字段递归嵌套；叶子节点的 isFolder=false。
 */
export interface DraftTreeNode {
  id: number;
  parentId: number | null;
  workspaceId: number;
  moduleName: string;
  status: string;
  filePath: string;
  sortOrder: number;
  isFolder: boolean;
  children: DraftTreeNode[];
  /** v1: INCREMENTAL 任务基线继承标识（NULL=本次新增；非空=从该基线任务继承） */
  baselineTaskId?: number;
}

/** v2: 草稿 diff 视图 DTO（前端 Phase 4 UI 用） */
export interface DraftTreeDiffDto {
  /** 本次新增草稿（baselineTaskId == null 且 module_name 不在基线中） */
  newRows: DraftTreeNode[];
  /** 本次重生成覆盖基线的草稿（baselineTaskId == null 且 module_name 在基线中） */
  modifiedRows: DraftTreeNode[];
  /** 基线继承草稿（baselineTaskId != null，直接复制未重跑） */
  inheritedRows: DraftTreeNode[];
  /** 本次删除草稿（基线 workspace 有 + 本次 workspace 无） */
  deletedRows: DraftTreeNode[];
}

/** v2: 单篇文档正文 DIFF DTO — 基线正文直接内联返回 */
export interface DocumentDiffDto {
  /** 基线正文内容（直接从 releases 目录读取，无基线匹配时为 null） */
  baselineContent: string | null;
  /** 本次草稿的 contentUri */
  currentContentUri: string;
  /** 基线 moduleName */
  baselineModuleName: string | null;
  /** 本次草稿的 moduleName */
  currentModuleName: string;
  /** 本次草稿 ID */
  currentDraftId: number;
}

export interface DraftRevision {
  id: number;
  draftId: number;
  contentUri: string;
  author: string;
  remark: string;
  createdDate: string;
}

export interface DraftReviewComment {
  id: number;
  draftId: number;
  author: string;
  comment: string;
  /** 意见类型：NORMAL=通用意见 / PASS=通过意见 / REJECT=驳回意见 */
  type?: 'NORMAL' | 'PASS' | 'REJECT' | string;
  createdDate: string;
}

/**
 * 任务级复核意见聚合 DTO
 * 后端 GET /api/drafts/task/{taskId}/comments 返回 — 整组任务的意见汇总，含来源草稿元信息。
 */
export interface TaskCommentDto {
  id: number;
  draftId: number;
  /** 来源草稿的模块名（聚合时由后端 JOIN） */
  moduleName: string | null;
  /** 来源草稿的代码文件路径 */
  filePath: string | null;
  author: string;
  comment: string;
  /** NORMAL=通用意见 / PASS=通过意见（含任务级确认的 [任务级通过]）/ REJECT=驳回意见 */
  type?: 'NORMAL' | 'PASS' | 'REJECT' | string;
  createdDate: string;
}

export interface DraftSourceReference {
  id: number;
  draftId: number;
  filePath: string;
  startLine: number;
  endLine: number;
  className?: string;
  methodSignature?: string;
  /** ROOT=入口；REACHABLE=调用链下游 */
  refKind?: 'ROOT' | 'REACHABLE' | string;
  /** BFS 发现序 */
  bfsOrder?: number;
  createdDate: string;
}

export interface RegenerateDraftResult {
  draftId: number;
  status: string;
  /** true = 已入队异步重跑，需轮询 regenerate-status */
  accepted?: boolean;
  contentUri?: string;
  functionNodeId?: string;
  referenceCount: number;
  errorMessage?: string;
}

/**
 * 复核工作区「可预览系统」聚合 DTO
 * 由后端 GET /api/drafts/preview-systems 返回。
 */
export interface PreviewSystemDto {
  systemId: number;
  systemName: string;
  /** 组件标识；空表示无组件 */
  component?: string;
  owner: string;
  status: number; // 1=启用, 0=停用
  pendingReviewCount: number;
  reviewingCount: number;
  confirmedCount: number;
  totalReviewableCount: number;
}

/**
 * 演示模式开关
 * 启用后所有读写接口都走本地 mock store，不发任何 HTTP 请求，方便无后端体验完整复核流程。
 * 启用方式（三选一即可）：
 *   1) .env / .env.local 中设置 VITE_USE_MOCK=true
 *   2) 页面 URL 加上 ?demo=1
 *   3) 页面顶部的「演示数据」开关（运行时切换，刷新失效）
 */




export function getWorkspaceByTask(taskId: number): Promise<{
  workspace: DraftWorkspace;
  drafts: KnowledgeDraft[];
  /** 允许未逐篇确认时直接「任务整体通过」 */
  allowPartialPass?: boolean;
}> {
  if (isMockEnabled()) return mockGetWorkspaceByTask(taskId);
  return request.get(`/drafts/workspace/task/${taskId}`);
}

/**
 * 查询工作区下的草稿目录树（DB parent_id 递归构建）
 */
export function getWorkspaceTree(workspaceId: number): Promise<DraftTreeNode[]> {
  if (isMockEnabled()) return mockGetWorkspaceTree(workspaceId);
  return request.get(`/drafts/workspace/${workspaceId}/tree`);
}

/** v2: 草稿 diff 视图（INCREMENTAL 任务 4 类：新增 / 修改 / 基线继承 / 本次删除） */
export function getWorkspaceTreeDiff(workspaceId: number): Promise<DraftTreeDiffDto> {
  return request.get(`/drafts/workspace/${workspaceId}/tree/diff`);
}

/** v2: 单篇文档正文 DIFF — 返回基线 + 本次两份正文的 contentUri */
export function getDocumentDiff(draftId: number): Promise<DocumentDiffDto> {
  return request.get(`/drafts/${draftId}/content-diff`);
}

export function getDraftContent(draftId: number): Promise<string> {
  if (isMockEnabled()) return mockGetDraftContent(draftId);
  return request.get(`/drafts/${draftId}/content`);
}

export function saveDraft(draftId: number, content: string, author?: string, remark?: string): Promise<void> {
  if (isMockEnabled()) return mockSaveDraft(draftId, content, author, remark);
  return request.post(`/drafts/${draftId}/save`, { content, author, remark });
}

export function autoSaveDraft(draftId: number, content: string, author?: string): Promise<void> {
  if (isMockEnabled()) return mockAutoSaveDraft(draftId, content, author);
  return request.post(`/drafts/${draftId}/autosave`, { content, author });
}

/** 获取草稿编辑锁（进入可编辑草稿时调用） */
export function acquireDraftEditLock(draftId: number, author?: string): Promise<void> {
  if (isMockEnabled()) return mockAcquireDraftEditLock(draftId, author);
  return request.post(`/drafts/${draftId}/edit-lock/acquire`, { author });
}

/** 续租草稿编辑锁（周期调用，间隔应小于后端 TTL） */
export function renewDraftEditLock(draftId: number, author?: string): Promise<void> {
  if (isMockEnabled()) return mockRenewDraftEditLock(draftId, author);
  return request.post(`/drafts/${draftId}/edit-lock/renew`, { author });
}

/** 释放草稿编辑锁（切换草稿 / 离开页面时调用） */
export function releaseDraftEditLock(draftId: number, author?: string): Promise<void> {
  if (isMockEnabled()) return mockReleaseDraftEditLock(draftId, author);
  return request.post(`/drafts/${draftId}/edit-lock/release`, { author });
}

export function confirmDraft(draftId: number, author?: string, comment?: string): Promise<void> {
  if (isMockEnabled()) return mockConfirmDraft(draftId, author, comment);
  return request.post(`/drafts/${draftId}/confirm`, { author, comment });
}

export function getRevisions(draftId: number): Promise<DraftRevision[]> {
  if (isMockEnabled()) return mockGetRevisions(draftId);
  return request.get(`/drafts/${draftId}/revisions`);
}

export function getComments(draftId: number): Promise<DraftReviewComment[]> {
  if (isMockEnabled()) return mockGetComments(draftId);
  return request.get(`/drafts/${draftId}/comments`);
}

/**
 * 任务级复核意见聚合：把 task 下整组草稿的复核意见一次性取出。
 * 后端对应接口：GET /api/drafts/task/{taskId}/comments
 *
 * <p>这是复核工作区「复核意见」按钮的真实语义入口 —
 * 操作粒度是任务，不是单文件；输出补齐来源草稿的 moduleName / filePath。</p>
 */
export function listTaskComments(taskId: number): Promise<TaskCommentDto[]> {
  if (isMockEnabled()) {
    // 演示模式：聚合 mock 单文件评论，按 createdDate desc 合并
    return mockListTaskComments(taskId);
  }
  return request.get(`/drafts/task/${taskId}/comments`);
}

export function getReferences(draftId: number): Promise<DraftSourceReference[]> {
  if (isMockEnabled()) return mockGetReferences(draftId);
  return request.get(`/drafts/${draftId}/references`);
}

/**
 * 复核工作区首页：列出所有「可预览」系统（至少有一条可复核任务），
 * 返回各阶段任务计数（待复核 / 复核中 / 已确认）。
 */
export function listPreviewSystems(): Promise<PreviewSystemDto[]> {
  if (isMockEnabled()) return mockListPreviewSystems();
  return request.get(`/drafts/preview-systems`);
}

/**
 * 复核工作区二级筛选：列出指定系统下处于可复核状态的任务。
 * @param systemId 可选；不传则返回所有系统
 * @param status   可选，逗号分隔多状态；不传则默认 PENDING_REVIEW/REVIEWING/CONFIRMED
 */
export function listReviewableTasks(
  params: { systemId?: number; status?: string } = {},
): Promise<import('../types').Task[]> {
  if (isMockEnabled()) return mockListReviewableTasks(params);
  return request.get(`/drafts/review-tasks`, { params });
}

/**
 * 复核工作区历史任务浏览：列出指定系统下的所有任务（含 PUSHED / ARCHIVED / FAILED / CANCELLED），
 * 用于切换到历史任务做只读浏览。
 *
 * <p>后端无单独 endpoint，复用 GET /api/tasks 拉 size=200 的列表；该接口返回 PageResult，
 * 调用方需要从 .records 里取数组（不能直接当数组用，否则 .map 会报 TypeError）。</p>
 */
export async function listAllTasksBySystem(systemId: number): Promise<import('../types').Task[]> {
  if (isMockEnabled()) return mockListAllTasksBySystem(systemId);
  // 后端 GET /tasks 返回 ApiResponse<PageResult<Task>>，拦截器解包后 res.data 是 PageResult。
  // 这里显式断言返回结构并取 .records，避免历史上把 PageResult 强转成 Task[] 导致的
  // `tasks.map is not a function` 崩溃。
  // request.get<T> 在 axios 类型上仍标为 AxiosResponse<T>，response 拦截器实际解包为 T；
  // 这里用 unknown 中转一次拿到 Page，再安全取 records。
  type Page = { records: import('../types').Task[]; total: number; size: number; current: number };
  const page = await request.get<Page, Page>(`/tasks`, { params: { current: 1, size: 200, systemId } });
  return page?.records ?? [];
}

/** 单文档审核通过（锁定） */
export const approveDraft = (id: number, author?: string): Promise<void> => {
  return request.post(`/drafts/${id}/confirm`, { author });
};
/** 单文档重跑（异步接受，随后轮询 regenerate-status） */
export const regenerateDraft = (
  id: number,
  body?: { author?: string; remark?: string },
): Promise<RegenerateDraftResult> => {
  return request.post(`/drafts/${id}/regenerate`, body ?? {});
};

/** 单文档重跑进度 */
export const getRegenerateStatus = (id: number): Promise<RegenerateDraftResult> => {
  return request.get(`/drafts/${id}/regenerate-status`);
};
