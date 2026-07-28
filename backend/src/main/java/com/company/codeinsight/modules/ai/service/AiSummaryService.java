package com.company.codeinsight.modules.ai.service;

import com.company.codeinsight.modules.callchain.model.IncrementalImpact;
import com.company.codeinsight.modules.scanner.model.IncrementalContext;
import lombok.Data;

/**
 * 大模型归纳与总结核心服务接口
 * 负责调度 AI 大模型进行模块层级提炼与知识文档生成。
 */
public interface AiSummaryService {

    /**
     * 聚合模块/功能级分析结果并生成正式 Markdown 知识草稿，写入 ci_knowledge_draft
     *
     * @param taskId        关联的任务 ID
     * @param promptContent 提示词模版正文
     */
    void generateDraftDocument(Long taskId, String promptContent);

    /**
     * 增量感知的草稿生成。{@code ctx.isIncremental()} 为 false 时等价于 {@link #generateDraftDocument(Long, String)}。
     */
    void generateDraftDocument(Long taskId, String promptContent, IncrementalContext ctx);

    /**
     * 增量感知 + 影响面裁剪的草稿生成。
     * {@code impact} 非空且 {@code impact.isIncremental()} 时，按 {@code docRetargetModuleIds} 决定模块重跑范围。
     */
    void generateDraftDocument(Long taskId, String promptContent, IncrementalContext ctx, IncrementalImpact impact);

    /**
     * 用任意已组装好的 prompt 字符串直接调用大模型
     *
     * @param taskId      关联任务 ID（用于 Token 流控和审计）
     * @param promptInput 已渲染的完整 prompt
     * @param modelName   所用模型标识
     * @param callMeta    调用元数据（callStage / classPath），用于审计
     * @return AI 响应文本；调用失败或 Mock 时返回 "{}"
     */
    String summarizeWithPrompt(Long taskId, String promptInput, String modelName, AiCallMeta callMeta);

    /**
     * 返回当前是否启用了 AI Mock 本地降级（{@code code-insight.ai.mock}）。
     */
    boolean isAiMock();

    /**
     * 大模型敏感数据脱敏过滤器（供测试与 PipelineAiCaller 复用）。
     */
    String filterSensitiveInfo(String input);

    /** 单篇功能文档重跑（原地覆盖）。返回更新后的草稿状态信息。 */
    com.company.codeinsight.modules.draft.dto.RegenerateDraftResult regenerateFunctionDocument(Long draftId);

    /**
     * 调用元数据
     */
    @Data
    class AiCallMeta {
        /** 调用阶段标签，如 "MODULE_HIERARCHY" / "MODULE_DOC" */
        private String callStage;
        /** 当前分析对象标识（如入口类全限定名） */
        private String classPath;
    }
}
