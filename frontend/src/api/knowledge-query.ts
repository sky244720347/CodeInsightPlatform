import request from './request';
import type { EntrypointReviewItem, ModuleHierarchy } from '../types';

export interface KnowledgeContextView {
  systemId?: number;
  systemName?: string;
  repositoryId?: number;
  repositoryName?: string;
  versionId?: number;
  versionNum?: string;
  taskId?: number;
  releaseDirExists?: boolean;
  hasPublishedEntrypoints?: boolean;
  hasPublishedHierarchy?: boolean;
}

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
