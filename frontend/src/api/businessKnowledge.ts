import request from './request';

/**
 * 业务知识配置（按系统维度）
 * <p>用于在 AI 调用模块提取时，替换提示词模板中的 {@code {business_knowledge.md}} 占位符。</p>
 */
export interface BusinessKnowledge {
  id: number;
  systemId: number;
  /** Markdown 正文 */
  content: string;
  /** 保存次数（每次保存 +1） */
  version: number;
  /** 最后修改人 */
  updatedBy?: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * 按系统 ID 获取业务知识配置。
 * <p>无配置时后端返回 null，前端按空内容处理。</p>
 */
export const getBusinessKnowledge = (systemId: number): Promise<BusinessKnowledge | null> => {
  return request.get('/business-knowledge', { params: { systemId } });
};

/**
 * 覆盖式保存业务知识。
 * <p>后端存在则更新（version+1），否则插入。</p>
 */
export const upsertBusinessKnowledge = (params: {
  systemId: number;
  content: string;
  updatedBy?: string;
}): Promise<BusinessKnowledge> => {
  return request.put('/business-knowledge', params);
};
