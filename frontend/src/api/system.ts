import request from './request';
import type { PageResult, System } from '../types';

export const listSystems = (params: {
  current: number;
  size: number;
  name?: string;
  component?: string;
  owner?: string;
  /** 仅返回含已发布仓库（last_published_version_id IS NOT NULL）的系统，默认 false */
  hasPublished?: boolean;
}): Promise<PageResult<System>> => {
  return request.get('/systems', { params });
};

export const getSystem = (id: number): Promise<System> => {
  return request.get(`/systems/${id}`);
};

export const createSystem = (data: Partial<System>): Promise<System> => {
  return request.post('/systems', data);
};

export const updateSystem = (id: number, data: Partial<System>): Promise<System> => {
  return request.put(`/systems/${id}`, data);
};

export const deleteSystem = (id: number): Promise<void> => {
  return request.delete(`/systems/${id}`);
};
