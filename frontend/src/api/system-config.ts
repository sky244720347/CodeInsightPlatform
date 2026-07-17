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
