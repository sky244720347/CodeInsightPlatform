package com.company.codeinsight.modules.draft.dto;

import lombok.Data;

/**
 * 复核工作区「可预览系统」聚合 DTO
 * 列出至少有一条可复核任务的系统（任务状态在 PENDING_REVIEW / REVIEWING / CONFIRMED 之一），
 * 同时按状态汇总各阶段任务计数，用于前端"复核工作区"首页下拉与角标展示。
 */
@Data
public class PreviewSystemDto {

    /**
     * 业务系统 ID
     */
    private Long systemId;

    /**
     * 业务系统名称（纯 name，不含组件拼接）
     */
    private String systemName;

    /**
     * 组件标识；空串/null 表示无组件
     */
    private String component;

    /**
     * 系统负责人
     */
    private String owner;

    /**
     * 系统启用状态（1=启用, 0=停用）
     */
    private Integer status;

    /**
     * 待复核任务数（task.status = PENDING_REVIEW）
     */
    private Long pendingReviewCount;

    /**
     * 复核中任务数（task.status = REVIEWING）
     */
    private Long reviewingCount;

    /**
     * 已确认待推送任务数（task.status = CONFIRMED）
     */
    private Long confirmedCount;

    /**
     * 该系统下所有可复核任务数（PENDING_REVIEW + REVIEWING + CONFIRMED）
     */
    private Long totalReviewableCount;
}
