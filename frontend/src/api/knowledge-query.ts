import request from './request';
import type { EntrypointReviewItem, ModuleHierarchy } from '../types';

export interface KnowledgeContextView {
  systemId?: number;
  systemName?: string;
  /** 组件标识；空表示无组件 */
  component?: string;
  repositoryId?: number;
  repositoryName?: string;
  versionId?: number;
  versionNum?: string;
  taskId?: number;
  /** 版本推送完成时间（文档生成/发布时间） */
  pushedAt?: string | null;
  releaseDirExists?: boolean;
  hasPublishedEntrypoints?: boolean;
  hasPublishedHierarchy?: boolean;
}

/** 生效版本 Tag 中的推送时间：YYYY-MM-DD HH:mm */
export const formatKnowledgePushedAt = (value?: string | null): string | null => {
  if (!value) return null;
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return null;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
};

export const getKnowledgeContext = (repositoryId: number): Promise<KnowledgeContextView> => {
  return request.get('/knowledge/context', { params: { repositoryId } });
};

export const listPublishedEntrypoints = (
  repositoryId: number,
  systemId?: number,
): Promise<EntrypointReviewItem[]> => {
  return request.get('/knowledge/entrypoints', { params: { repositoryId, systemId } });
};

export const getPublishedHierarchy = (
  repositoryId: number,
  systemId?: number,
): Promise<ModuleHierarchy> => {
  return request.get('/knowledge/hierarchy', { params: { repositoryId, systemId } });
};
