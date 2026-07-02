package com.company.codeinsight.modules.task.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class IncrementalImpactDto {
    private boolean incremental;
    private boolean available = true;
    private String message;
    private String scanMode;
    private String baselineCommitId;
    private String headCommitId;
    private List<String> changedPaths = new ArrayList<>();
    private List<String> deletedPaths = new ArrayList<>();
    private int hierarchyRetargetEntryCount;
    private List<EntryPointSummaryDto> hierarchyRetargetEntries = new ArrayList<>();
    private List<String> docRetargetModuleIds = new ArrayList<>();
    private List<ModuleSummaryDto> docRetargetModules = new ArrayList<>();
    private List<ImpactTraceDto> traces = new ArrayList<>();
    private int degradedModuleCount;
    private String computedAt;
}
