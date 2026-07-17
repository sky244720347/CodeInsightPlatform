package com.company.codeinsight.modules.task.enums;

/**
 * 任务状态机核心状态枚举
 * 映射 DecompileTask 任务生命周期中的所有阶段状态。
 */
public enum TaskStatus {
    /**
     * 草稿新建
     */
    DRAFT,
    /**
     * 待执行（入排队队列）
     */
    PENDING,
    /**
     * 代码拉取中（克隆 Git 库或复制本地文件）
     */
    PULLING_CODE,
    /**
     * 代码静态解析中（执行 JavaParser 静态 AST 解析）
     */
    PARSING_CODE,
    /**
     * @deprecated 历史任务可能仍停留在此状态；新流水线已不再进入该阶段。
     */
    @Deprecated
    SPLITTING_TASK,
    /**
     * 知识入口人工复核断点（介于 PARSING_CODE 与 AI_ANALYZING 之间）。
     * 流水线在静态解析完成后、调用 AI 提取模块层级之前，把识别到的入口类与方法落表 ci_entrypoint，
     * 等待用户在页面上确认（继续）或驳回（终止任务）。
     */
    ENTRYPOINT_REVIEW,
    /**
     * AI 归纳分析中（增量影响分析 + 模块层级提炼）
     */
    AI_ANALYZING,
    /**
     * AI 模块层级提炼中（从每个入口提交 AI 提炼并维护 module_hierarchy DTO）
     */
    MODULE_HIERARCHY,
    /**
     * 模块层级人工复核断点（AI 提炼完成后等待用户在页面上编辑 module_hierarchy，确认后继续生成文档）
     */
    MODULE_HIERARCHY_REVIEW,
    /**
     * 基线文档继承中（仅 INCREMENTAL 任务：将基线 workspace 的知识文档复制到本次 workspace，
     * 使本次 workspace 自包含，修复版本创建/ZIP/推送残缺缺陷）。
     * 失败后支持「重新继承基线文档」按钮单独重跑此步骤。
     */
    BASELINE_DOC_INHERIT,
    /**
     * 知识生成中（整合模块/功能级 Markdown 并写入 ci_knowledge_draft 进行版本归档）
     */
    GENERATING_DOC,
    /**
     * 待人工复核评审
     */
    PENDING_REVIEW,
    /**
     * 复核人工编辑中
     */
    REVIEWING,
    /**
     * 评审已确认通过
     */
    CONFIRMED,
    /**
     * 知识推送中（将 Markdown 分批写入 Git）
     */
    PUSHING,
    /**
     * 知识已成功推送
     */
    PUSHED,
    /**
     * 任务执行异常失败
     */
    FAILED,
    /**
     * 被用户手动终止/取消
     */
    CANCELLED,
    /**
     * 历史数据已归档
     */
    ARCHIVED
}

