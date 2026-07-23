import request from './request';

/** 任务概览聚合统计响应 */
export interface TaskStats {
  total: number;
  successCount: number;
  failedCount: number;
  avgDurationMs: number;
  byStatus: Record<string, number>;
  byType: Record<string, number>;
  bySystem: Record<string, number>;
  dailyCount: Record<string, number>;
  dailyDuration: Record<string, number>;
}

/** AI 模型用量统计响应（模型级条目） */
export interface AiModelStat {
  name: string;
  calls: number;
  inputTokens: number;
  outputTokens: number;
  success: number;
  failed: number;
}

/** AI 模型用量统计响应（阶段级条目） */
export interface AiStageStat {
  stage: string;
  calls: number;
  inputTokens: number;
  outputTokens: number;
  success: number;
  failed: number;
}

/** AI 模型用量统计响应 */
export interface AiUsageStats {
  totalCalls: number;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalDurationMs: number;
  successCount: number;
  successRate: number;
  byModel: AiModelStat[];
  byStage: AiStageStat[];
}

/** 流水线阶段统计条目 */
export interface PipelineStageStat {
  status: string;
  count: number;
  avgDurationMs: number;
}

/** 系统覆盖报表条目 */
export interface SystemCoverageItem {
  systemId: number;
  systemName: string;
  /** 组件标识；空表示无组件 */
  component?: string;
  taskCount: number;
  draftCount: number;
  versionCount: number;
  lastDecompileAt: string | null;
}

/** 获取任务概览统计 */
export const getTaskStats = (days = 30): Promise<TaskStats> =>
  request.get('/dashboard/tasks/stats', { params: { days } });

/** 获取 AI 模型用量统计 */
export const getAiUsageStats = (systemId?: number): Promise<AiUsageStats> =>
  request.get('/dashboard/ai-usage/stats', { params: { systemId } });

/** 获取流水线阶段分析 */
export const getPipelineStats = (): Promise<PipelineStageStat[]> =>
  request.get('/dashboard/pipeline/stats');

/** 获取系统覆盖报表 */
export const getSystemCoverage = (): Promise<SystemCoverageItem[]> =>
  request.get('/dashboard/coverage');
