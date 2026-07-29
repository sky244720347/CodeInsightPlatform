package com.company.codeinsight.modules.system.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Excel 导入单行结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemImportItemResult {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_SYSTEM_REUSED = "SYSTEM_REUSED";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_FAILED = "FAILED";

    /** Excel 行号（含表头，从 2 起） */
    private int row;

    private String systemName;
    private String gitUrl;

    /** CREATED / SYSTEM_REUSED / SKIPPED / FAILED */
    private String status;

    private Long systemId;
    private Long repositoryId;
    private String message;
}
