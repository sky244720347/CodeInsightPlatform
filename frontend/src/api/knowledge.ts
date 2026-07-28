import request from './request';

export interface KnowledgeVersion {
  id: number;
  systemId: number;
  repositoryId: number;
  taskId: number;
  versionNum: string;
  sourceBranch: string;
  sourceCommit: string;
  targetBranch: string;
  targetCommit: string | null;
  promptVersion: number;
  modelName: string;
  status: string;
  pushMethod: string;
  confirmedBy: string;
  confirmedAt: string;
  pushedAt: string | null;
  createdDate: string;
  /** 是否为仓库当前生效的已发布版本 */
  activePublished?: boolean;
}

export interface PushTask {
  id: number;
  versionId: number;
  pushMethod: string;
  status: string;
  retryCount: number;
  maxRetries: number;
  targetInfo: string;
  errorMessage?: string;
  enqueuedAt: string;
  startedAt?: string;
  completedAt?: string;
  createdDate: string;
}

export function createVersion(taskId: number, versionNum: string, confirmedBy?: string): Promise<KnowledgeVersion> {
  return request.post('/knowledge/version', null, {
    params: { taskId, versionNum, confirmedBy },
  });
}

export function pushVersion(versionId: number, method: string = 'NAS'): Promise<void> {
  return request.post(`/knowledge/${versionId}/push`, null, {
    params: { method },
  });
}

export function rollbackRepositoryPublish(versionId: number): Promise<void> {
  return request.post(`/knowledge/${versionId}/rollback-repository`);
}

export function listPushTasks(versionId: number): Promise<PushTask[]> {
  return request.get(`/push/version/${versionId}/tasks`);
}

export function listVersions(params: {
  current: number;
  size: number;
  systemId?: number;
  repositoryId?: number;
}): Promise<{ total: number; records: KnowledgeVersion[] }> {
  return request.get('/knowledge/page', { params });
}

/** 打开浏览器下载知识版本 ZIP（后端 GET /knowledge/{id}/export） */
export function downloadVersionZip(versionId: number): void {
  const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api';
  window.open(`${baseUrl}/knowledge/${versionId}/export`);
}

export interface RepositoryPublishSnapshotView {
  id: number;
  repositoryId: number;
  systemId: number;
  taskId: number;
  versionId: number;
  versionNum: string;
  modularizePromptId?: number;
  documentPromptId?: number;
  modelName?: string;
  publishedAt: string;
  publishedBy?: string;
  releaseDirExists?: boolean;
  activePublished?: boolean;
}

export function listRepositoryPublishSnapshots(
  repositoryId: number,
): Promise<RepositoryPublishSnapshotView[]> {
  return request.get(`/repositories/${repositoryId}/publish/snapshots`);
}
