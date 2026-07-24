import request from './request';

export interface SystemConfig {
  key: string;
  value: string;
  description?: string;
  updatedBy?: string;
  updatedDate?: string;
}

export const listSystemConfig = (): Promise<SystemConfig[]> => {
  return request.get('/system-config');
};

export const getSystemConfig = (key: string): Promise<SystemConfig> => {
  return request.get(`/system-config/${encodeURIComponent(key)}`);
};

export const putSystemConfig = (
  key: string,
  body: { value: string; description?: string },
): Promise<void> => {
  return request.put(`/system-config/${encodeURIComponent(key)}`, body);
};

export interface PermitClearResult {
  pool: string;
  removedBefore: number;
}

/** 清空任务 Redis 并发许可（运维） */
export const clearTaskPermits = (): Promise<PermitClearResult> => {
  return request.post('/system-config/permits/task/clear');
};

/** 清空 AI Redis 并发许可（运维） */
export const clearAiPermits = (): Promise<PermitClearResult> => {
  return request.post('/system-config/permits/ai/clear');
};
