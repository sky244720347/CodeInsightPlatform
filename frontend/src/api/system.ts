import request from './request';
import type { PageResult, System } from '../types';

export interface SystemImportItemResult {
  row: number;
  systemName?: string;
  gitUrl?: string;
  status: 'CREATED' | 'SYSTEM_REUSED' | 'SKIPPED' | 'FAILED' | string;
  systemId?: number;
  repositoryId?: number;
  message?: string;
}

export interface SystemImportResult {
  totalRows: number;
  systemCreated: number;
  systemReused: number;
  repoCreated: number;
  skipped: number;
  failed: number;
  items: SystemImportItemResult[];
}

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

/** Excel 批量导入系统与仓库（仅上传文件） */
export const importSystemsFromExcel = (file: File): Promise<SystemImportResult> => {
  const form = new FormData();
  form.append('file', file);
  return request.post('/systems/import-excel', form, {
    timeout: 120000,
  });
};

/** 下载系统批量导入 Excel 模板 */
export function downloadSystemImportTemplate(): void {
  const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api';
  window.open(`${baseUrl}/systems/import-excel/template`);
}
