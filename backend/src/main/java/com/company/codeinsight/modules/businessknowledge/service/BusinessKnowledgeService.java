package com.company.codeinsight.modules.businessknowledge.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.company.codeinsight.modules.businessknowledge.entity.BusinessKnowledge;

/**
 * 业务知识配置服务接口
 *
 * <p>对外暴露：</p>
 * <ul>
 *     <li>{@link #getBySystemId(Long)} — 供前端获取完整对象（含 version / updatedAt 等元数据）</li>
 *     <li>{@link #getContentBySystemId(Long)} — 供 AI 调用方取纯文本（无配置时返回空串）</li>
 *     <li>{@link #upsert(Long, String, String)} — 前端保存入口（覆盖式，自动 version+1）</li>
 * </ul>
 */
public interface BusinessKnowledgeService extends IService<BusinessKnowledge> {

    /**
     * 根据系统 ID 获取完整业务知识记录
     *
     * @param systemId 业务系统 ID
     * @return 记录（无配置时返回 null）
     */
    BusinessKnowledge getBySystemId(Long systemId);

    /**
     * AI 调用方使用的轻量接口：按 systemId 取 Markdown 正文。
     *
     * <p>无配置或 systemId 为 null 时返回空串——PromptTemplateLoader 替换占位符时空串行为与"未配置"完全一致。</p>
     *
     * @param systemId 业务系统 ID（可为 null，null 直接返回 ""）
     * @return Markdown 正文（永不为 null）
     */
    String getContentBySystemId(Long systemId);

    /**
     * 覆盖式保存：存在则更新（version+1），不存在则插入（version=1）。
     *
     * @param systemId 业务系统 ID（必填）
     * @param content  Markdown 正文（可为 null，内部规范化为空串）
     * @param updatedBy 修改人（可为 null）
     * @return 保存后的完整记录
     */
    BusinessKnowledge upsert(Long systemId, String content, String updatedBy);
}
