package com.company.codeinsight.modules.system.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Excel 批量导入汇总结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemImportResult {

    private int totalRows;
    private int systemCreated;
    private int systemReused;
    private int repoCreated;
    private int skipped;
    private int failed;

    @Builder.Default
    private List<SystemImportItemResult> items = new ArrayList<>();
}
