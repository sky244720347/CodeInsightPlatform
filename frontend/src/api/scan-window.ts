import request from './request';
import type { ScanWindow } from '../types';

export const getScanWindow = (repositoryId: number): Promise<ScanWindow | null> =>
  request.get(`/scan-windows/by-repository/${repositoryId}`);

export const upsertScanWindow = (dto: Partial<ScanWindow> & { repositoryId: number }): Promise<ScanWindow> =>
  request.post('/scan-windows', dto);

export const deleteScanWindow = (repositoryId: number): Promise<void> =>
  request.delete(`/scan-windows/by-repository/${repositoryId}`);

export const listScanWindows = (): Promise<ScanWindow[]> =>
  request.get('/scan-windows');

/** 调度器 cron 配置 */
export const getSchedulerCron = (): Promise<{ cron: string; nextRuns: string[]; enabled: boolean }> =>
  request.get('/scan-windows/scheduler/cron');

export const updateSchedulerCron = (cron: string): Promise<{ cron: string; nextRuns: string[] }> =>
  request.put('/scan-windows/scheduler/cron', { cron });

/** 调度器启停 */
export const getSchedulerEnabled = (): Promise<boolean> =>
  request.get('/scan-windows/scheduler/enabled');

export const updateSchedulerEnabled = (enabled: boolean): Promise<boolean> =>
  request.put('/scan-windows/scheduler/enabled', { enabled });
