package com.company.codeinsight.modules.prompt.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.company.codeinsight.modules.prompt.dto.PromptTestResultDto;
import com.company.codeinsight.modules.prompt.dto.PromptTestStreamEventDto;
import com.company.codeinsight.modules.prompt.entity.DecompilePrompt;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 提示词模板管理服务接口
 * 定义提示词分页查询、克隆备份、启用状态更改、变量替换渲染以及 AI 测试运行业务规范。
 */
public interface DecompilePromptService extends IService<DecompilePrompt> {

    /**
     * 分页、条件查询提示词模板列表
     *
     * @param current     页码（从 1 开始）
     * @param size        每页条数
     * @param name        模板名称模糊关键字（可为 null）
     * @param promptType  提示词用途过滤：{@code MODULARIZE} / {@code DOCUMENT_GENERATION}（可为 null 表示全部）
     * @param lifecycle   生命周期过滤：{@code DRAFT} / {@code RELEASED} / {@code ARCHIVED}（可为 null 表示全部）
     * @param category    提示词分类：{@code DEFAULT} / {@code USER}（可为 null 表示全部）
     * @param scopeId     USER 提示词的 scope ID（与 category=USER 配合使用，null 表示不按 scope 过滤）
     */
    Page<DecompilePrompt> listPromptsPage(int current, int size, String name,
                                         String promptType, String lifecycle, String category,
                                         Long scopeId, Integer isDefault);

    /**
     * 克隆复制指定 ID 提示词模板以创建一条全新副本
     *
     * @param id 源模板 ID
     * @return 克隆创建出的新模板对象
     */
    DecompilePrompt clonePrompt(Long id);

    /**
     * 发布草稿：DRAFT → RELEASED,锁定。
     */
    DecompilePrompt publishPrompt(Long id);

    /**
     * 归档已发布：RELEASED → ARCHIVED,历史保留。
     */
    DecompilePrompt archivePrompt(Long id);

    /**
     * 对提示词模板中的各种自定义占位符变量（如 ${code} 等）进行动态文本替换
     *
     * @param template  提示词模板正文
     * @param variables 变量键值对
     * @return 渲染后的可用提示词
     */
    String replaceVariables(String template, Map<String, String> variables);

    /**
     * 用测试代码和指定的模型测试试跑该提示词模板并输出大模型分析结果与 Token 耗时开销统计
     *
     * @param id              提示词模板 ID
     * @param sampleCode      用于分析测试的 Java 类/方法代码片段
     * @param modelId         指定的模型 ID
     * @param resolvedContent 前端已替换占位符的最终 prompt 正文。
     *                        若非空,直接作为最终 prompt 调用 AI,不再做占位符替换/class/method 解析。
     *                        若为空/null,则按老流程从 prompt.content + sampleCode 拼装。
     * @return 试跑报告数据传输对象
     */
    PromptTestResultDto testRun(Long id, String sampleCode, Long modelId, String resolvedContent);

    /**
     * 解析任务运行时指定类型的提示词正文（仅使用任务快照的提示词 ID，无系统/默认兜底）。
     *
     * @param task       任务实体
     * @param promptType 提示词用途：{@code MODULARIZE} 或 {@code DOCUMENT_GENERATION}
     * @return 提示词正文；找不到或无效时返回 null
     */
    String resolveTaskPromptContent(com.company.codeinsight.modules.task.entity.DecompileTask task, String promptType);

    /**
     * 按任务快照的提示词 ID 解析正文；缺失或无效时抛 {@link com.company.codeinsight.common.exception.BusinessException}。
     */
    String requireTaskPromptContent(com.company.codeinsight.modules.task.entity.DecompileTask task, String promptType);

    /**
     * 校验仓库已绑定模块提取 + 文档生成提示词（均为 RELEASED 且类型匹配）。
     */
    void validateRepositoryPromptBinding(Long repositoryId);

    /**
     * 校验任务快照的两类提示词 ID 可用。
     */
    void validateTaskPromptBinding(Long modularizePromptId, Long documentPromptId);

    /**
     * 仓库提示词是否已完整绑定（不抛异常，供就绪度 API 使用）。
     */
    boolean isRepositoryPromptsConfigured(Long repositoryId);

    /**
     * 仓库提示词未配置时的错误说明；已配置时返回 null。
     */
    String getRepositoryPromptsConfigurationMessage(Long repositoryId);

    /**
     * 流式试跑提示词模板，按内容增量和结束事件输出。
     *
     * @param resolvedContent 前端已替换占位符的最终 prompt 正文;为空时走老流程
     */
    void testRunStream(Long id, String sampleCode, Long modelId, String resolvedContent,
                       Consumer<PromptTestStreamEventDto> eventConsumer);
}

