package com.company.codeinsight.modules.entrypoint.dto;

import com.company.codeinsight.modules.entrypoint.model.EntrypointReviewView;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 入口 diff DTO
 * <p>v1: INCREMENTAL 任务的入口 diff 视图（前端 Phase 4 UI 用）。</p>
 * <p>INITIAL 任务返回 4 个空 list；INCREMENTAL 任务返回 4 类：</p>
 * <ul>
 *   <li>{@link #newRows}：本次新增（baselineTaskId == null）</li>
 *   <li>{@link #modifiedRows}：类不变 + 方法有变化（baselineTaskId != null + 方法 diff 有变化）</li>
 *   <li>{@link #inheritedRows}：基线继承（类+方法全不变）</li>
 *   <li>{@link #deletedRows}：本次删除（基线有 + 本次无）</li>
 * </ul>
 */
@Data
public class EntrypointDiffDto {

    /** 本次新增入口 */
    private List<EntrypointReviewView> newRows = new ArrayList<>();

    /** 类不变 + 方法有变化 */
    private List<EntrypointReviewView> modifiedRows = new ArrayList<>();

    /** 类+方法全不变的基线继承入口 */
    private List<EntrypointReviewView> inheritedRows = new ArrayList<>();

    /** 本次删除入口（基线有 + 本次无） */
    private List<EntrypointReviewView> deletedRows = new ArrayList<>();
}