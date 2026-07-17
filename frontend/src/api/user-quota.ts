import request from './request';
import type { PageResult } from '../types';

export interface UserQuota {
  id: number;
  userId: number;
  dailyTokenLimit: number;
  monthlyTokenLimit: number;
  enabled: number;
  remark?: string;
  createdDate?: string;
  updatedDate?: string;
}

export interface UserQuotaRequest {
  userId?: number;
  dailyTokenLimit?: number;
  monthlyTokenLimit?: number;
  enabled?: number;
  remark?: string;
}

export const listUserQuotas = (params: {
  current: number;
  size: number;
  username?: string;
  enabled?: number;
}): Promise<PageResult<UserQuota>> => {
  return request.get('/user-quotas', { params });
};

export const getUserQuota = (id: number): Promise<UserQuota> => {
  return request.get(`/user-quotas/${id}`);
};

export const createUserQuota = (data: UserQuotaRequest): Promise<UserQuota> => {
  return request.post('/user-quotas', data);
};

export const updateUserQuota = (id: number, data: UserQuotaRequest): Promise<UserQuota> => {
  return request.put(`/user-quotas/${id}`, data);
};

export const deleteUserQuota = (id: number): Promise<void> => {
  return request.delete(`/user-quotas/${id}`);
};
