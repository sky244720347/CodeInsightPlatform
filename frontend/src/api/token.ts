import request from './request';

export interface TokenStats {
  totalInputTokens: number;
  totalOutputTokens: number;
  totalTokens: number;
  totalCost: number;
  systemRanking: {
    systemId: number;
    name: string;
    tokens: number;
  }[];
  modelRatio: {
    name: string;
    value: number;
  }[];
  dailyTrends: {
    date: string;
    tokens: number;
  }[];
}

export interface TokenUsageAudit {
  id: number;
  systemId: number;
  taskId: number;
  userId: number;
  promptVersion: number;
  modelName: string;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  cost: number;
  type: string;
  status: number;
  createdDate: string;
}

export function getTokenStats(systemId?: number): Promise<TokenStats> {
  return request.get('/token-audit/stats', { params: { systemId } });
}

export function getTokenPage(params: {
  current: number;
  size: number;
  systemId?: number;
  modelName?: string;
  type?: string;
}): Promise<{ total: number; records: TokenUsageAudit[] }> {
  return request.get('/token-audit/page', { params });
}
