import request from './request';

export interface OperationLog {
  id: number;
  systemId: number | null;
  taskId: number | null;
  userId: number | null;
  username: string;
  actionType: string;
  detail: string;
  ipAddress: string | null;
  exceptionMsg: string | null;
  isSuccess: number;
  createdDate: string;
}

export function listLogs(params: {
  current: number;
  size: number;
  systemId?: number;
  taskId?: number;
  username?: string;
  actionType?: string;
  isSuccess?: number;
}): Promise<{ total: number; records: OperationLog[] }> {
  return request.get('/logs', { params });
}

export function getLogDetail(id: number): Promise<OperationLog> {
  return request.get(`/logs/${id}`);
}
