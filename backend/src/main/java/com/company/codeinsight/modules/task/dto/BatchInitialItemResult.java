package com.company.codeinsight.modules.task.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一键全量触发 — 单仓结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchInitialItemResult {

    public static final String STATUS_TRIGGERED = "TRIGGERED";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_FAILED = "FAILED";

    private Long systemId;
    private String systemName;
    private Long repositoryId;
    private String gitUrl;
    /** TRIGGERED / SKIPPED / FAILED */
    private String status;
    private Long taskId;
    private String message;
}
