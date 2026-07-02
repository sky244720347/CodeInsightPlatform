import request from './request';
import type { PageResult } from '../types';

export interface EntryScanTrialSummary {
  id: number;
  repositoryId: number;
  userId?: string;
  status: string;
  startedAt: string;
  finishedAt?: string;
  entryCount?: number;
  errorMessage?: string;
}

export interface EntryScanTrial extends EntryScanTrialSummary {
  systemId: number;
  configSnapshot?: string;
  resultJson?: string;
}

export const getActiveTrial = (repoId: number): Promise<EntryScanTrial | null> =>
  request.get(`/repositories/${repoId}/trial-run/active`);

export const getLatestTrial = (repoId: number): Promise<EntryScanTrialSummary | null> =>
  request.get(`/repositories/${repoId}/trial-run/latest`);

export const listTrialHistory = (
  repoId: number,
  params: { current?: number; size?: number } = {},
): Promise<PageResult<EntryScanTrialSummary>> =>
  request.get(`/repositories/${repoId}/trial-run/history`, { params });

export const triggerTrial = (
  repoId: number,
  systemId: number,
  config: unknown,
): Promise<EntryScanTrial> =>
  request.post(`/repositories/${repoId}/trial-run`, config, { params: { systemId } });

export const getTrial = (repoId: number, trialId: number): Promise<EntryScanTrial> =>
  request.get(`/repositories/${repoId}/trial-run/${trialId}`);

export const getTrialEntries = (repoId: number, trialId: number): Promise<unknown[]> =>
  request.get(`/repositories/${repoId}/trial-run/${trialId}/entries`);

export const cancelTrial = (repoId: number, trialId: number): Promise<boolean> =>
  request.delete(`/repositories/${repoId}/trial-run/${trialId}`);

export const isTrialLocked = (repoId: number): Promise<boolean> =>
  request.get(`/repositories/${repoId}/trial-run/lock`);
