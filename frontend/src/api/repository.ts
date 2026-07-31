import request from './request';
import type { PageResult, Repository, TechStackCatalog } from '../types';

export const listRepositories = (params: {
  current: number;
  size: number;
  systemId?: number;
  gitUrl?: string;
  /** 仅返回已发布知识版本的仓库（last_published_version_id IS NOT NULL），默认 false */
  hasPublished?: boolean;
}): Promise<PageResult<Repository>> => {
  return request.get('/repositories', { params });
};

export const getRepository = (id: number): Promise<Repository> => {
  return request.get(`/repositories/${id}`);
};

export const createRepository = (data: Partial<Repository>): Promise<Repository> => {
  return request.post('/repositories', data);
};

export const updateRepository = (id: number, data: Partial<Repository>): Promise<Repository> => {
  return request.put(`/repositories/${id}`, data);
};

export const deleteRepository = (id: number): Promise<void> => {
  return request.delete(`/repositories/${id}`);
};

/** 代码库类型 → 技术栈级联目录 */
export const getTechStackCatalog = (): Promise<TechStackCatalog> => {
  return request.get('/repositories/tech-stack-catalog');
};

export interface GitConnectivityResult {
  repositoryId?: number | null;
  reachable: boolean;
  checkedAt?: string | null;
  message?: string | null;
}

export interface GitBatchCheckAccepted {
  accepted: boolean;
  systemId: number;
  repoCount: number;
  message?: string;
}

export interface GitConnectivitySummary {
  total: number;
  reachable: number;
  unreachable: number;
  unchecked: number;
}

/** 测试 Git；有 id 时后端落库。兼容旧逻辑：返回结构化结果 */
export const testRepositoryConnection = async (
  data: Partial<Repository>,
): Promise<boolean> => {
  const res = await request.post<any, GitConnectivityResult>('/repositories/test-connection', data);
  // 拦截器已解包 data；兼容万一仍返回 boolean
  if (typeof res === 'boolean') return res;
  return !!res?.reachable;
};

export const testSavedRepositoryConnection = async (id: number): Promise<boolean> => {
  const res = await request.post<any, GitConnectivityResult>(`/repositories/${id}/test-connection`);
  if (typeof res === 'boolean') return res;
  return !!res?.reachable;
};

/** 返回完整检测结果（含落库时间） */
export const testRepositoryConnectionDetailed = (
  data: Partial<Repository>,
): Promise<GitConnectivityResult> => {
  return request.post('/repositories/test-connection', data);
};

/** 打开抽屉时触发：按系统异步批量检测 */
export const batchTestRepositoryConnection = (systemId: number): Promise<GitBatchCheckAccepted> => {
  return request.post('/repositories/batch-test-connection', null, { params: { systemId } });
};

/** 监控预留：全库连通汇总 */
export const getGitConnectivitySummary = (): Promise<GitConnectivitySummary> => {
  return request.get('/repositories/git-connectivity-summary');
};
