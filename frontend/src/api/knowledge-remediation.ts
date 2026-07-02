import request from './request';
import type { EntrypointReviewItem, ExcludeTarget, ModuleHierarchy } from '../types';

export interface RemediationTaskResponse {
  taskId: number;
  remediationKind: string;
  resumeFrom: string;
}

export const remediateEntrypoints = (body: {
  repositoryId: number;
  systemId?: number;
  excludeTargets?: ExcludeTarget[];
  operator?: string;
}): Promise<RemediationTaskResponse> => {
  return request.post('/knowledge/remediation/entrypoints', body);
};

export const remediateHierarchy = (body: {
  repositoryId: number;
  systemId?: number;
  hierarchy: ModuleHierarchy;
  moduleIds: string[];
  operator?: string;
}): Promise<RemediationTaskResponse> => {
  return request.post('/knowledge/remediation/hierarchy', body);
};

export const remediateDocuments = (body: {
  repositoryId: number;
  systemId?: number;
  moduleIds: string[];
  operator?: string;
}): Promise<RemediationTaskResponse> => {
  return request.post('/knowledge/remediation/documents', body);
};

export const submitReleaseDocumentEdit = (body: {
  repositoryId: number;
  relativePath: string;
  content: string;
  operator?: string;
}): Promise<{ editId: number }> => {
  return request.post('/knowledge/remediation/documents/edit', body);
};

export const approveReleaseDocumentEdit = (
  editId: number,
  operator?: string,
): Promise<void> => {
  return request.post(`/knowledge/remediation/documents/edit/${editId}/approve`, null, {
    params: { operator },
  });
};

export type { EntrypointReviewItem };
