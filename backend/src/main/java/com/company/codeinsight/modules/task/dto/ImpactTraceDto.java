package com.company.codeinsight.modules.task.dto;

import lombok.Data;

@Data
public class ImpactTraceDto {
    private String changedFqcn;
    private String path;
    private String moduleId;
    private String moduleName;
    private String kind;
}
