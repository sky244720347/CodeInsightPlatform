package com.company.codeinsight.modules.hierarchy.dto;

import lombok.Data;

/**
 * 模块层级 diff DTO
 * <p>v1: INCREMENTAL 任务的模块层级 diff 视图（前端 Phase 4 UI 用）。</p>
 * <p>INITIAL 任务或无基线时各组为空；INCREMENTAL 每次请求按 moduleId（及同名配对）现场重算：</p>
 * <ul>
 *   <li>{@link #newHierarchy}：本次新增（基线无 + 本次有）</li>
 *   <li>{@link #modifiedHierarchy}：基线有 + 本次有 + 入口类/方法签名/功能名有变</li>
 *   <li>{@link #inheritedHierarchy}：基线有 + 本次有 + 内容相对基线无变化</li>
 *   <li>{@link #deletedHierarchy}：本次删除（基线有 + 本次无）</li>
 * </ul>
 */
@Data
public class ModuleHierarchyDiffDto {

    /** 本次新增的模块层级（基线无，本次有） */
    private com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy newHierarchy;

    /** 本次变更（入口类、方法签名或功能名相对基线有变） */
    private com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy modifiedHierarchy;

    /** 基线继承（相对基线无内容变化） */
    private com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy inheritedHierarchy;

    /** 本次删除的模块层级（基线有 + 本次无） */
    private com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy deletedHierarchy;
}