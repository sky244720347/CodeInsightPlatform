package com.company.codeinsight.modules.draft.dto;

import lombok.Data;

/**
 * 单篇知识文档的正文 DIFF DTO。
 * <p>基线正文直接从 releases 目录读取并内联返回（{@link #baselineContent}），
 * 因为基线任务的 draft 物理文件在推送后已被 NasPushStrategy 删除，不可复用。
 * 前端用 Monaco DiffEditor 自行计算 diff 并左右对比。</p>
 * <p>无基线匹配时 {@link #baselineContent} 为 null，前端降级为只读当前版本。</p>
 */
@Data
public class DocumentDiffDto {

    /** 基线正文内容（直接从 releases 目录读取，无基线匹配时为 null） */
    private String baselineContent;

    /** 本次草稿的 contentUri */
    private String currentContentUri;

    /** 基线 moduleName（用于前端标题展示） */
    private String baselineModuleName;

    /** 本次草稿的 moduleName */
    private String currentModuleName;

    /** 本次草稿 ID */
    private Long currentDraftId;
}
