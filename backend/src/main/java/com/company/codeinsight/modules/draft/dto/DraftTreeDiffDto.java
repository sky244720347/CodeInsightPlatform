package com.company.codeinsight.modules.draft.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 草稿 diff DTO
 * <p>v2: INCREMENTAL 任务的草稿 diff 视图（前端 Phase 4 UI 用）。</p>
 * <p>INITIAL 任务或无基线时所有字段为空 list；INCREMENTAL 任务返回 4 类：</p>
 * <ul>
 *   <li>{@link #newRows}：本次新增草稿（baselineTaskId == null 且 module_name 不在基线中）</li>
 *   <li>{@link #modifiedRows}：本次重生成覆盖基线的草稿（baselineTaskId == null 且 module_name 在基线中）</li>
 *   <li>{@link #inheritedRows}：基线继承草稿（baselineTaskId != null，直接从基线复制未重跑）</li>
 *   <li>{@link #deletedRows}：本次删除草稿（基线有 + 本次无）</li>
 * </ul>
 *
 * <p>v2 变更：新增 {@link #modifiedRows}；{@link #deletedRows} 语义回归为「真正删除」。
 * 区分依据为草稿行的 baseline_task_id 字段（BASELINE_DOC_INHERIT 阶段复制的草稿带此标记，
 * AI 重生成的草稿 baseline_task_id 为 null）。</p>
 */
@Data
public class DraftTreeDiffDto {

    /** 本次新增草稿（树形结构） */
    private List<DraftTreeNode> newRows = new ArrayList<>();

    /** 本次重生成覆盖基线的草稿（树形结构） */
    private List<DraftTreeNode> modifiedRows = new ArrayList<>();

    /** 基线继承草稿（树形结构） */
    private List<DraftTreeNode> inheritedRows = new ArrayList<>();

    /** 本次删除草稿（基线有 + 本次无；树形结构） */
    private List<DraftTreeNode> deletedRows = new ArrayList<>();
}