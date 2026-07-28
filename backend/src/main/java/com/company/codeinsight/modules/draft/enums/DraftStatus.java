package com.company.codeinsight.modules.draft.enums;

/**
 * 知识草稿流转状态枚举
 *
 * <p>与 {@code com.company.codeinsight.modules.task.enums.TaskStatus} 词汇解耦：
 * 任务级状态（DRAFT / PENDING / PULLING_CODE / PARSING_CODE / … / PENDING_REVIEW / REVIEWING / CONFIRMED …）
 * 与文档级状态（此处枚举）字面值不再共享，避免出现「同字面含义不同、互不联动」的语义错位。</p>
 *
 * <p>状态流转：</p>
 * <ul>
 *   <li>AI 生成完毕 → {@link #DRAFT}（默认；流水线落库也可能写 AI_GENERATED 字面值）</li>
 *   <li>单篇异步重跑进行中 → {@link #REGENERATING}</li>
 *   <li>复核人保存修改 → {@link #EDITING}</li>
 *   <li>复核人确认通过 → {@link #CONFIRMED}（允许复核人继续编辑，状态回流到 EDITING）</li>
 *   <li>推送模块写入 Git/ZIP → {@link #PUSHED}（任务级 PUSHING/PUSHED 时锁定）</li>
 *   <li>历史归档 → {@link #ARCHIVED}</li>
 * </ul>
 *
 * <p>v0.3 起移除 REJECTED 状态：复核人通过直接编辑修改草稿，不再走「驳回」流程。
 * 历史 REJECTED 草稿由 schema.sql 末尾的迁移自动改为 DRAFT。</p>
 */
public enum DraftStatus {

    /**
     * AI 已生成 / 待处理（创建后默认状态）
     */
    DRAFT,

    /**
     * 单篇「重跑此篇」异步执行中（接受请求后写入，完成后由 AI 落库覆盖为 AI_GENERATED / DRAFT / PENDING_REVIEW 等）
     */
    REGENERATING,

    /**
     * 复核人已编辑（保存修改后流转至此）
     */
    EDITING,

    /**
     * 复核确认通过（可被推送模块读取；仍允许复核人继续编辑回流到 EDITING）
     */
    CONFIRMED,

    /**
     * 已成功推送至 Git 仓库 / ZIP 导出（任务级 PUSHING/PUSHED 时由 service 层强制锁定）
     */
    PUSHED,

    /**
     * 已归档（历史终态）
     */
    ARCHIVED
}