import request from './request';
import type { PageResult } from '../types';
import type {
  KnowledgeBrowseFileType,
  KnowledgeBrowseItem,
  KnowledgeBrowseQuery,
  KnowledgeBrowseTreeResult,
} from '../types';

/**
 * 分页列出知识文档 / 索引 / 清单文件（支持跨系统）
 * 后端：GET /api/knowledge/browse
 */
export const listKnowledgeBrowse = (
  query: KnowledgeBrowseQuery,
): Promise<PageResult<KnowledgeBrowseItem>> => {
  return request.get('/knowledge/browse', { params: query });
};

/**
 * 树形模式：按模块层级展示功能与关联知识文档
 * 后端：GET /api/knowledge/browse/tree
 */
export const getKnowledgeBrowseTree = (params: {
  systemId: number;
  repositoryId: number;
}): Promise<KnowledgeBrowseTreeResult> => {
  return request.get('/knowledge/browse/tree', { params });
};

/**
 * 读取单条目的原始文本内容
 * 后端：GET /api/knowledge/browse/content
 */
export const getKnowledgeBrowseContent = (params: {
  type?: KnowledgeBrowseFileType;
  id?: number;
  taskId?: number;
  filePath?: string;
  contentUri?: string;
}): Promise<string> => {
  return request.get('/knowledge/browse/content', { params });
};
