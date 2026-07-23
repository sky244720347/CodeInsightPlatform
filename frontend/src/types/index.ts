export interface System {
  id: number;
  name: string;
  /** 组件标识；与 name 联合唯一；空表示无组件 */
  component?: string;
  nameCn?: string;
  description: string;
  owner: string;
  /** 系统级模块提取提示词 ID（FK → ci_prompt.id） */
  /** @deprecated 提示词绑定已迁移到仓库级 ci_repository.modularize_prompt_id */ modularizePromptId?: number | null;
  /** 系统级文档生成提示词 ID（FK → ci_prompt.id） */
  /** @deprecated 提示词绑定已迁移到仓库级 ci_repository.document_prompt_id */ documentPromptId?: number | null;
  createdDate: string;
  updatedDate: string;
  // 以下字段由 /systems 聚合接口返回，list 才有
  repositoryCount?: number;
  knowledgeVersionCount?: number;
  lastDecompileAt?: string;
}

export interface Repository {
  id: number;
  systemId: number;
  gitUrl: string;
  branch: string;
  username?: string;
  password?: string;
  scanRoot: string;
  excludeDirs?: string;
  excludeFileTypes?: string;
  lastCommitId?: string;
  lastDecompileAt?: string;
  /** 仓库级入口扫描配置（API 可能返回 JSON 字符串或已解析对象） */
  entryScanConfig?: EntryScanConfig | string | null;
  /** 仓库级模块提取提示词 ID（FK → ci_prompt.id） */
  modularizePromptId?: number | null;
  /** 仓库级文档生成提示词 ID（FK → ci_prompt.id） */
  documentPromptId?: number | null;
  createdDate: string;
  updatedDate: string;
}

export interface Prompt {
  id: number;
  name: string;
  content: string;
  version: number;
  isDefault: number; // 0-否, 1-是（仅 category=DEFAULT 时生效）
  /** 提示词用途：MODULARIZE-模块提取 / DOCUMENT_GENERATION-文档生成 */
  promptType?: 'MODULARIZE' | 'DOCUMENT_GENERATION' | string;
  /** 生命周期：DRAFT-草稿(可编辑) / RELEASED-已发布(锁定) / ARCHIVED-已归档 */
  lifecycle?: 'DRAFT' | 'RELEASED' | 'ARCHIVED' | string;
  /** 分类：DEFAULT-全局默认提示词 / USER-用户自定义（按 scopeId 隔离） */
  category?: 'DEFAULT' | 'USER' | string;
  /** USER 提示词的 scope ID（系统ID）；DEFAULT 为 null */
  scopeId?: number | null;
  createdDate: string;
  updatedDate: string;
}

export interface Task {
  id: number;
  systemId: number;
  repositoryId: number;
  /** 已废弃，请使用 modularizePromptVersion / documentPromptVersion */
  promptVersion?: number;
  /** 模块提取提示词版本（对应 ci_prompt.prompt_type=MODULARIZE） */
  modularizePromptVersion?: number;
  /** 文档生成提示词版本（对应 ci_prompt.prompt_type=DOCUMENT_GENERATION） */
  documentPromptVersion?: number;
  modelName?: string;
  status: string;
  type: 'INITIAL' | 'INCREMENTAL';
  progress: number;
  errorReason?: string;
  durationMs: number;
  startedAt?: string;
  endedAt?: string;
  entryScanConfig?: EntryScanConfig;
  /** 是否启用模块层级调试（人工复核断点）；undefined 时按 TRUE 处理 */
  requireHierarchyReview?: boolean;
  /** 是否启用知识入口复核（人工复核断点，介于 PARSING_CODE 与 AI_ANALYZING 之间）；undefined 时按 TRUE 处理 */
  requireEntrypointReview?: boolean;
  /** 触发来源：MANUAL 手动触发 / SCHEDULED 定时调度触发 */
  triggerSource?: 'MANUAL' | 'SCHEDULED' | string;
  /** 触发该任务的调度配置 ID（triggerSource=SCHEDULED 时非空） */
  scheduleId?: number;
  /** 队列优先级 0-100，越大越优先；TaskQueueDispatcher 按此字段排序调度 */
  priority?: number;
  createdDate: string;
  updatedDate: string;
}

/**
 * 任务流水线中单个阶段的统计摘要（来自 GET /tasks/{id}/log/summary）。
 * 前端"执行日志"卡片渲染 Timeline 时使用。
 */
export interface PipelineStageStat {
  key: string;
  label: string;
  status: 'pending' | 'running' | 'done' | 'skipped' | 'error';
  durationMs: number;
  startedAt?: string;
  endedAt?: string;
}

export type ImpactTraceKind = 'ENTRY_DIRECT' | 'REVERSE_BFS' | 'DEGRADED_CLASS_PATH';

export interface ImpactTraceDto {
  changedFqcn: string;
  path: string;
  moduleId: string;
  moduleName: string;
  kind: ImpactTraceKind | string;
}

/** 增量影响分析（GET /tasks/{id}/incremental-impact） */
export interface IncrementalImpactDto {
  incremental: boolean;
  available?: boolean;
  message?: string;
  scanMode?: string;
  baselineCommitId?: string;
  headCommitId?: string;
  changedPaths?: string[];
  deletedPaths?: string[];
  hierarchyRetargetEntryCount?: number;
  docRetargetModuleIds?: string[];
  traces?: ImpactTraceDto[];
  degradedModuleCount?: number;
  computedAt?: string;
}

/**
 * 任务执行日志的结构化摘要（来自 GET /tasks/{id}/log/summary）。
 * 同时驱动知识构建任务页的"执行日志"卡片与"查看完整日志"模态框顶栏。
 */
export interface TaskLogSummary {
  taskId: number;
  status: string;
  progress: number;
  durationMs: number;
  startedAt?: string;
  endedAt?: string;
  modelName?: string;
  /** 是否启用 AI 本地 Mock（来自后端 code-insight.ai.mock） */
  aiMock: boolean;
  pipeline: PipelineStageStat[];
  counters: {
    totalFiles: number;
  };
  aiCalls: { total: number; success: number; failed: number };
  /** AI_ANALYZING / MODULE_HIERARCHY 阶段（第一段 AI）的调用统计 */
  hierarchyAiCalls?: { total: number; success: number; failed: number };
  /** GENERATING_DOC 阶段（第二段 AI）的调用统计 */
  docAiCalls?: { total: number; success: number; failed: number };
  /** 当前正在处理的进度索引；-1 表示未知 */
  current: {
    totalFiles: number;
    moduleIndex: number;
    moduleTotal: number;
  };
  /** 失败原因的单行摘要（无堆栈），失败时用于"执行日志"卡片友好提示 */
  lastError?: string;
}

/**
 * 任务级入口扫描配置
 */
export type EntryScanTypeKey = 'CONTROLLER' | 'SCHEDULED_JOB' | 'MQ_LISTENER' | 'OTHER';

export interface TypeIncludeRules {
  includeAnnotations?: string[];
  includeClasspaths?: string[];
  includeExtends?: string[];
}

export interface ExcludeTarget {
  className: string;
  methodSignature?: string;
}

export interface EntryScanConfig {
  includesByType: Record<EntryScanTypeKey, TypeIncludeRules>;
  excludeClasspaths?: string[];
  excludePackages?: string[];
  excludeAnnotations?: string[];
  excludeTargets?: ExcludeTarget[];
}

/** 模块层级（人工复核断点编辑对象），与后端 ModuleHierarchy DTO 对应 */
export interface ModuleHierarchy {
  taskId?: number;
  systemId?: number;
  modules?: Record<string, ModuleNode>;
}

/** 知识入口复核视图中的单个方法（只读展示用） */
export interface EntrypointMethodView {
  methodName: string;
  methodSignature?: string;
  annotation?: string;
  httpPath?: string;
  httpMethod?: string;
  /** v1: 方法级 diff（new / modified / unchanged / deleted），仅 INCREMENTAL 任务有值 */
  diffStatus?: string;
  /** 方法体内容哈希（同签名内容变更对比） */
  bodyHash?: string;
}

/** 知识入口复核视图中的单个入口类（只读展示用） */
export interface EntrypointReviewItem {
  id: number;
  taskId: number;
  systemId: number;
  className: string;
  filePath?: string;
  entryType?: string;
  annotation?: string;
  remark?: string;
  enabled: boolean;
  sortOrder: number;
  methods: EntrypointMethodView[];
  /** v1: INCREMENTAL 任务基线继承标识（NULL=本次新增，非空=从该基线任务继承） */
  baselineTaskId?: number;
}

export interface ModuleNode {
  id: string;
  moduleName: string;
  keywords?: string[];
  /** 人工逐项复核确认标记：true = 已确认，false/undefined = 未确认；JSON 中以 "Y"/"N" 字符串呈现 */
  confirmed?: boolean;
  subModules?: Record<string, SubModuleNode>;
  /** v1: INCREMENTAL 任务 AI 重提炼标识 */
  sourceEntryClass?: string;
}

export interface SubModuleNode {
  id: string;
  subModuleName: string;
  keywords?: string[];
  /** 人工逐项复核确认标记 */
  confirmed?: boolean;
  functions?: Record<string, FunctionNode>;
}

export interface FunctionNode {
  id: string;
  functionName: string;
  /** 入口类全限定名集合 */
  classPaths?: string[];
  /** 方法签名 methodName(ParamTypes)，不含返回类型 */
  methodSignatures?: string[];
  /** v1: INCREMENTAL 任务 AI 重提炼标识（非空=本次 AI 重提炼的入口类全限定名；空=基线继承） */
  sourceEntryClass?: string;
  /** 人工逐项复核确认标记 */
  confirmed?: boolean;
}

export interface PushTask {
  id: number;
  versionId: number;
  pushMethod: string;
  status: string;
  retryCount: number;
  maxRetries: number;
  targetInfo: string;
  errorMessage?: string;
  enqueuedAt: string;
  startedAt?: string;
  completedAt?: string;
  createdDate: string;
}

/** 知识查看 - 文件类型枚举（与后端 KnowledgeBrowseServiceImpl 常量对齐） */
export type KnowledgeBrowseFileType = 'DRAFT' | 'INDEX' | 'MANIFEST' | 'ALL';

/** 知识查看 - 单条文件视图（与后端 KnowledgeBrowseItem 对齐） */
export interface KnowledgeBrowseItem {
  /** 复合主键：draft:<id> / index:<taskId>:<path> / manifest:<taskId>:<path> */
  id: string;
  /** 文件名（不包含父路径） */
  name: string;
  /** DRAFT / INDEX / MANIFEST */
  type: KnowledgeBrowseFileType;
  taskId?: number;
  versionId?: number;
  versionNum?: string;
  /** 相对路径：draft = ci_knowledge_draft.filePath；index/manifest = docs/code-insight 下的相对路径 */
  filePath: string;
  /** 文件字节数 */
  size: number;
  /** DRAFT: DRAFT/EDITING/CONFIRMED/PUSHED/ARCHIVED；INDEX/MANIFEST: GENERATED */
  status: string;
  /** ISO timestamp */
  updatedDate: string;
  /** 数据源标识：DB（draft 行）/ TEMP_REPOS（index/manifest 文件）/ RELEASE（已发布产物） */
  source: 'DB' | 'TEMP_REPOS' | 'RELEASE';
  /** 已发布产物 URI（source=RELEASE 时有值） */
  contentUri?: string;
  systemId?: number;
  systemName?: string;
  /** 组件标识；空表示无组件 */
  component?: string;
  repositoryId?: number;
  repositoryName?: string;
}

/** 知识查看 - 列表查询入参 */
export interface KnowledgeBrowseQuery {
  systemId?: number;
  repositoryId?: number;
  type?: KnowledgeBrowseFileType;
  keyword?: string;
  taskId?: number;
  versionId?: number;
  status?: string;
  createdDateStart?: string;
  createdDateEnd?: string;
  current?: number;
  size?: number;
}

/** 知识查看 - 树形节点 */
export interface KnowledgeBrowseTreeNode {
  key: string;
  nodeType: 'MODULE' | 'SUB_MODULE' | 'FUNCTION';
  title: string;
  draftId?: number;
  contentUri?: string;
  documentPath?: string;
  hasDocument?: boolean;
  draftStatus?: string;
  documentGranularity?: 'module' | 'function';
  children?: KnowledgeBrowseTreeNode[];
}

/** 知识查看 - 树形模式响应 */
export interface KnowledgeBrowseTreeResult {
  systemId: number;
  systemName?: string;
  /** 组件标识；空表示无组件 */
  component?: string;
  repositoryId: number;
  repositoryName?: string;
  versionId?: number;
  versionNum?: string;
  activeVersion?: boolean;
  taskId?: number;
  documentGranularity?: 'module' | 'function';
  nodes: KnowledgeBrowseTreeNode[];
}

export interface KnowledgeDraft {
  id: number;
  workspaceId: number;
  filePath: string;
  moduleName: string;
  contentUri: string;
  status: string;
  hash: string;
  createdDate: string;
  updatedDate: string;
}

export interface TokenUsageAudit {
  id: number;
  systemId: number;
  taskId: number;
  userId?: number;
  promptVersion?: number;
  modelName: string;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  cost: number;
  type: string;
  status: number;
  createdDate: string;
}

export interface OperationLog {
  id: number;
  systemId?: number;
  taskId?: number;
  userId?: number;
  username: string;
  actionType: string;
  detail: string;
  ipAddress?: string;
  exceptionMsg?: string;
  isSuccess: number;
  createdDate: string;
}

/** 仓库扫描时间窗口 */
export interface ScanWindow {
  id?: number;
  repositoryId: number;
  weekDays: number;
  hour: number;
  minute: number;
  enabled: boolean;
  lastFiredAt?: string;
  createdDate?: string;
  updatedDate?: string;
}

export interface PageResult<T> {
  total: number;
  size: number;
  current: number;
  records: T[];
}

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface LoginRequest {
  username: string;
  password: string;
  token: string;
}

export interface LoginResponse {
  token: string;
  username: string;
  displayName: string;
  role: string;
  expiresInSeconds: number;
}

export interface AiModel {
  id: number;
  name: string;
  identifier: string;
  provider: string;
  apiKey?: string;
  hasApiKey?: boolean;
  baseUrl?: string;
  isDefault: 'true' | 'false';
  capabilities?: string;
  description?: string;
  sortOrder: number;
  status?: number; // 0-停用 1-启用（未返回时按启用处理）
  createdDate?: string;
  updatedDate?: string;
}

export interface AiModelPreset {
  id: number;
  name: string;
  identifier: string;
  provider: string;
  baseUrl?: string;
  capabilities?: string;
  description?: string;
  sortOrder: number;
  status?: number;
  createdDate?: string;
  updatedDate?: string;
}

export interface AiModelMetricSummary {
  modelName: string;
  totalCalls: number;
  totalTokens: number;
  totalCost: number;
}

export interface AiModelMetricTrendPoint {
  date: string;
  calls: number;
  tokens: number;
  cost: number;
}

export interface AiModelTestResult {
  success: boolean;
  durationMs: number;
  message: string;
  responseSummary?: string;
}

/* ===========================================================
 * 定时任务调度（schedule）
 * =========================================================== */

/** 触发策略：INCREMENTAL 增量扫描 / INITIAL 全量扫描 */
export type FireStrategy = 'INCREMENTAL' | 'INITIAL';

/** 冲突策略：SKIP 上一次未结束则跳过 / QUEUE 排队等待 / PARALLEL 允许并发 */
export type OverlapStrategy = 'SKIP' | 'QUEUE' | 'PARALLEL';

/** 触发状态：CREATED / RUNNING / SUCCESS / FAILED / SKIPPED / QUEUED */
export type FireStatus =
  | 'CREATED'
  | 'RUNNING'
  | 'SUCCESS'
  | 'FAILED'
  | 'SKIPPED'
  | 'QUEUED'
  | string;

/**
 * 定时任务调度配置（对应 ci_schedule_task 表）
 */
export interface ScheduleTask {
  id: number;
  systemId: number;
  repositoryId: number;
  name: string;
  description?: string;
  /** Spring 6 位 cron 表达式：秒 分 时 日 月 周 */
  cronExpression: string;
  /** 时区，默认 Asia/Shanghai */
  timezone: string;
  /** 是否启用：0-禁用 1-启用 */
  enabled: number;
  fireStrategy: FireStrategy;
  overlapStrategy: OverlapStrategy;
  modularizePromptId?: number;
  documentPromptId?: number;
  modelName?: string;
  entryScanConfig?: EntryScanConfig;
  /** 是否启用模块层级调试断点：0-否 1-是 */
  requireHierarchyReview?: number;
  /** 是否启用知识入口复核断点：0-否 1-是；触发任务时复制到 ci_task */
  requireEntrypointReview?: number;
  lastFiredAt?: string;
  /** 最近一次触发产生的知识构建任务 ID */
  lastTaskId?: number;
  /** 最近一次触发状态 */
  lastStatus?: FireStatus;
  /** 下一次触发时间（cron 计算结果） */
  nextFireAt?: string;
  totalFired: number;
  totalSuccess: number;
  totalFailed: number;
  totalSkipped: number;
  createdBy?: number;
  createdDate: string;
  updatedDate: string;
}

/** 定时任务触发记录（对应 ci_schedule_fire_record 表） */
export interface ScheduleFireRecord {
  id: number;
  scheduleId: number;
  /** 本次触发创建的知识构建任务 ID（SKIPPED 时为空） */
  taskId?: number;
  fireTime: string;
  plannedTime: string;
  status: FireStatus;
  skipReason?: string;
  errorMessage?: string;
  durationMs?: number;
  createdDate: string;
}

/** 立即触发接口返回 */
export interface TriggerNowResult {
  scheduleId: number;
  taskId?: number;
  fireRecordId?: number;
  status?: FireStatus;
}

