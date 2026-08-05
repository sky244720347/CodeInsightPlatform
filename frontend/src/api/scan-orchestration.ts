import request from './request';

export type ScanOrchestrationSummary = {
  probeDate: string;
  /** 探测总数 */
  probeTargetTotal: number;
  /** 已探测 = 下发成功 + 待重试 */
  probedCount: number;
  /** 下发成功 / 当日了结 */
  settledSuccessCount: number;
  /** 待重试 */
  retryPendingCount: number;
  /** 未探测 */
  unprobedCount: number;
  attemptCount: number;
  dispatchedCount: number;
  schedulerEnabled: boolean;
  cron: string;
  nextRuns: string[];
  globalPollEnabled: boolean;
  forceFullOnUnchanged: boolean;
  dailyCoverageEnabled: boolean;
};

export type ScanProbeRecord = {
  id: number;
  repositoryId: number;
  systemId?: number;
  gitUrl?: string;
  attemptNo?: number;
  status: string;
  remoteHead?: string;
  baselineCommit?: string;
  dispatchAction?: string;
  taskId?: number;
  message?: string;
  probedAt?: string;
};

export type PageResult<T> = {
  records: T[];
  total: number;
  current: number;
  size: number;
};

export const getScanOrchestrationSummary = (date?: string): Promise<ScanOrchestrationSummary> =>
  request.get('/scan/orchestration/summary', { params: date ? { date } : undefined });

export const listScanProbeRecords = (params: {
  date?: string;
  status?: string;
  keyword?: string;
  current?: number;
  size?: number;
}): Promise<PageResult<ScanProbeRecord>> =>
  request.get('/scan/orchestration/records', { params });

export const updateScanOrchestrationCron = (cron: string): Promise<{ cron: string; nextRuns: string[] }> =>
  request.put('/scan/orchestration/cron', { cron });

export const updateScanOrchestrationEnabled = (enabled: boolean): Promise<boolean> =>
  request.put('/scan/orchestration/enabled', { enabled });
