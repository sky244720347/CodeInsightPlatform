import request from './request';
import type {
  EntrypointReviewItem,
  EntryScanConfig,
  IncrementalImpactDto,
  ModuleHierarchy,
  PageResult,
  Task,
  TaskLogSummary,
} from '../types';

/** v1: 入口 diff DTO（前端 Phase 4 UI 用） */
export interface EntrypointDiffDto {
  /** 本次新增 */
  newRows: EntrypointReviewItem[];
  /** 类不变 + 方法有变化 */
  modifiedRows: EntrypointReviewItem[];
  /** 类+方法全不变的基线继承 */
  inheritedRows: EntrypointReviewItem[];
  /** 本次删除 */
  deletedRows: EntrypointReviewItem[];
}

/** v1: 模块层级 diff DTO */
export interface ModuleHierarchyDiffDto {
  /** 本次新增的模块层级（基线无 + 本次有） */
  newHierarchy: ModuleHierarchy;
  /** 本次变更（基线有 + 本次有 + FUNCTION classPaths 集合不同） */
  modifiedHierarchy: ModuleHierarchy;
  /** 基线继承（基线有 + 本次有 + classPaths 完全相同） */
  inheritedHierarchy: ModuleHierarchy;
  /** 本次删除（基线有 + 本次无） */
  deletedHierarchy: ModuleHierarchy;
}

export interface TaskProgress {
  status: string;
  progress: number;
  errorReason?: string;
}

export interface CreateTaskPayload {
  systemId: number;
  repositoryId: number;
  modelName?: string;
  /** 入口扫描配置（可选；不传则走默认 Controller/JOB/MQ 兜底） */
  entryScanConfig?: EntryScanConfig;
  /** 是否启用模块层级调试（人工复核断点）；不传则按默认 TRUE 处理 */
  requireHierarchyReview?: boolean;
  /** 是否启用知识入口复核；不传则按默认 TRUE */
  requireEntrypointReview?: boolean;
  /** 是否启用知识文档复核；不传则按默认 TRUE；下发页 UI 默认 false */
  requireKnowledgeReview?: boolean;
}

export const listTasks = (params: {
  current: number;
  size: number;
  systemId?: number;
  status?: string;
  /** 多状态过滤（与 status 互斥，AXIOS 会自动序列化为 ?statuses=A&statuses=B） */
  statuses?: string[];
  type?: string;
  /** 按 scheduleId 过滤（用于定时任务详情页联动） */
  scheduleId?: number;
  /** 按触发来源过滤：MANUAL / SCHEDULED */
  triggerSource?: 'MANUAL' | 'SCHEDULED' | string;
  /** @deprecated 任务查询简单搜索已改为按 systemId 筛选 */
  keyword?: string;
  /** 精准搜索：模型名精确匹配 */
  modelName?: string;
  /** 精准搜索：创建时间下界（ISO timestamp，可空） */
  createdDateStart?: string;
  /** 精准搜索：创建时间上界（ISO timestamp，可空） */
  createdDateEnd?: string;
}): Promise<PageResult<Task>> => {
  return request.get('/tasks', { params });
};

/**
 * 任务中心顶部状态分组 chips 数据：
 * - ALL：所有任务
 * - RUNNING：进行中（PENDING / PULLING_CODE / ... / PUSHING）
 * - PENDING_REVIEW：待复核 + 复核中
 * - CONFIRMED：已确认 + 已推送
 * - CLOSED：已终止（FAILED / CANCELLED / ARCHIVED）
 */
export interface TaskStatusSummary {
  ALL: number;
  RUNNING: number;
  PENDING_REVIEW: number;
  CONFIRMED: number;
  CLOSED: number;
}

export const getTaskSummary = (params: { systemId?: number } = {}): Promise<TaskStatusSummary> => {
  return request.get('/tasks/summary', { params });
};

/**
 * 全局新建任务前置条件查询的响应体：
 * - ready=true：可以新建任务
 * - ready=false：blockingDrafts 列出所有非终态草稿，前端弹窗引导复核人去处理
 */
export interface BlockingDraft {
  draftId: number;
  moduleName: string;
  status: string;
  workspaceId: number;
  taskId?: number;
  systemId?: number;
  repositoryId?: number;
  updatedDate: string;
}

export interface RepositoryReadiness {
  ready: boolean;
  /** 系统是否已绑定模块提取 + 文档生成提示词 */
  promptsConfigured?: boolean;
  /** 提示词未配置时的说明 */
  promptsMessage?: string;
  unconfirmedCount: number;
  blockingDrafts: BlockingDraft[];
}

/**
 * 新建任务前置条件查询：可选按系统+仓库收窄作用域。
 *
 * 当 systemId 和 repositoryId 均不传时退化为全局查询（兼容旧场景）。
 * 建议在创建任务向导步骤 0 选择系统+仓库后带参调用，实现精确校验，
 * 避免 A 系统的未确认草稿阻塞 B 系统创建任务。
 *
 * 后端对应接口：GET /api/drafts/readiness?systemId=X&repositoryId=Y
 */
export const getRepositoryReadiness = (
  params: { systemId?: number; repositoryId?: number } = {},
): Promise<RepositoryReadiness> => {
  return request.get('/drafts/readiness', { params });
};

export const getTaskIncrementalImpact = (id: number): Promise<IncrementalImpactDto> => {
  return request.get(`/tasks/${id}/incremental-impact`);
};

export const getTask = (id: number): Promise<Task> => {
  return request.get(`/tasks/${id}`);
};

export const createInitialTask = (data: CreateTaskPayload): Promise<Task> => {
  return request.post('/tasks/initial', data);
};

export const createIncrementalTask = (data: CreateTaskPayload): Promise<Task> => {
  return request.post('/tasks/incremental', data);
};

export interface BatchInitialItemResult {
  systemId?: number;
  systemName?: string;
  repositoryId?: number;
  gitUrl?: string;
  status: 'TRIGGERED' | 'SKIPPED' | 'FAILED' | string;
  taskId?: number;
  message?: string;
}

export interface BatchInitialTriggerResult {
  jobId?: string;
  status?: 'ACCEPTED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | string;
  totalRepos: number;
  processedRepos?: number;
  triggered: number;
  skipped: number;
  failed: number;
  message?: string;
  /** 作业选用的 AI 模型 identifier */
  modelName?: string;
  items: BatchInitialItemResult[];
}

/** 异步提交一键全量，立刻返回 jobId */
export const batchTriggerInitial = (modelName?: string): Promise<BatchInitialTriggerResult> => {
  return request.post('/tasks/batch-initial', null, {
    params: modelName ? { modelName } : undefined,
  });
};

/** 轮询一键全量作业进度 */
export const getBatchInitialJob = (jobId: string): Promise<BatchInitialTriggerResult> => {
  return request.get(`/tasks/batch-initial/${jobId}`);
};

export const startTask = (id: number): Promise<void> => {
  return request.post(`/tasks/${id}/start`);
};

/**
 * 任务级「确认通过」：整组草稿置 CONFIRMED，工作区升 COMPLETED，任务升 CONFIRMED。
 * 建版与 NAS 入队由后端在确认成功后异步触发，本接口只等待确认完成。
 * 这是复核工作区工具栏「确认通过」按钮的真实语义入口 —
 * 操作粒度是任务，不是单文件。
 *
 * 后端对应接口：POST /api/tasks/{id}/confirm
 */
export const confirmTask = (id: number, author?: string, comment?: string): Promise<void> => {
  return request.post(`/tasks/${id}/confirm`, { author, comment });
};

export const terminateTask = (id: number): Promise<void> => {
  return request.post(`/tasks/${id}/terminate`);
};

export const retryTask = (id: number): Promise<void> => {
  return request.post(`/tasks/${id}/retry`);
};

export const getTaskProgress = (id: number): Promise<TaskProgress> => {
  return request.get(`/tasks/${id}/progress`);
};

/** 读取任务真实执行日志（pipeline 写入的 pipeline.log 文件内容） */
export const getTaskExecutionLog = (id: number): Promise<string> => {
  return request.get(`/tasks/${id}/log`);
};

/**
 * 读取任务执行日志的结构化摘要（阶段耗时、文件/切片计数、AI 成功失败数、Mock 标记、当前进度）。
 * 与 getTaskExecutionLog 互补：前者返回全文，后者返回聚合结构，供"执行日志"卡片快速展示。
 */
export const getTaskLogSummary = (id: number): Promise<TaskLogSummary> => {
  return request.get(`/tasks/${id}/log/summary`);
};

/** 拉取任务当前模块层级（人工复核断点用） */
export const getModuleHierarchy = (id: number): Promise<ModuleHierarchy> => {
  return request.get(`/tasks/${id}/module-hierarchy`);
};

/** 整体替换任务模块层级（人工复核断点提交用） */
export const replaceModuleHierarchy = (id: number, payload: ModuleHierarchy): Promise<ModuleHierarchy> => {
  return request.put(`/tasks/${id}/module-hierarchy`, payload);
};

/** 模块层级复核完成后恢复流水线 */
export const resumeModuleHierarchyReview = (id: number): Promise<void> => {
  return request.post(`/tasks/${id}/module-hierarchy/resume`);
};

/** 重新继承基线文档（仅 INCREMENTAL 任务 BASELINE_DOC_INHERIT 失败时可用） */
export const retryBaselineInherit = (id: number): Promise<void> => {
  return request.post(`/tasks/${id}/retry-baseline-inherit`);
};

/** 拉取任务的知识入口复核清单（人工复核断点用，只读） */
export const getEntrypointReview = (id: number): Promise<EntrypointReviewItem[]> => {
  return request.get(`/tasks/${id}/entrypoints`);
};

/** v1: 入口 diff 视图（INCREMENTAL 任务 4 种分类；INITIAL 任务返回 3 个空 list） */
export const getEntrypointDiff = (id: number): Promise<EntrypointDiffDto> => {
  return request.get(`/tasks/${id}/entrypoints/diff`);
};

/** v1: 模块层级 diff 视图（INCREMENTAL 任务 3 类 hierarchy） */
export const getModuleHierarchyDiff = (id: number): Promise<ModuleHierarchyDiffDto> => {
  return request.get(`/tasks/${id}/module-hierarchy/diff`);
};

/** 知识入口复核完成后恢复流水线（确认并继续） */
export const resumeEntrypointReview = (
  id: number,
  excludeTargets?: import('../types').ExcludeTarget[],
): Promise<void> => {
  return request.post(`/tasks/${id}/entrypoints/resume`, excludeTargets?.length ? { excludeTargets } : undefined);
};

/** 知识入口复核驳回（终止任务） */
export const rejectEntrypointReview = (id: number, reason?: string): Promise<void> => {
  return request.post(`/tasks/${id}/entrypoints/reject`, { reason });
};

// ========== 任务队列管控 ==========

/** 取消队列中的 PENDING 任务（PENDING → CANCELLED） */
export const cancelQueuedTask = (id: number): Promise<void> => {
  return request.post(`/tasks/${id}/cancel`);
};

/** 删除任务（仅 DRAFT / PENDING / FAILED / CANCELLED / ARCHIVED） */
export const deleteTask = (id: number): Promise<void> => {
  return request.delete(`/tasks/${id}`);
};

/** 调整任务优先级（0-100，仅 PENDING 可调） */
export const setTaskPriority = (id: number, priority: number): Promise<void> => {
  return request.put(`/tasks/${id}/priority`, { priority });
};

/** 队列列表（PENDING 任务，priority DESC + created_at ASC） */
export const listQueuedTasks = (params: {
  current: number;
  size: number;
  systemId?: number;
}): Promise<PageResult<Task>> => {
  return request.get('/tasks/queue', { params });
};

/** 队列总览（总数 + 平均等待时长） */
export const getQueueSummary = (): Promise<{ total: number; avgWaitSeconds: number }> => {
  return request.get('/tasks/queue/summary');
};
