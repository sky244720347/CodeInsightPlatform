package com.company.codeinsight.modules.knowledge.remediation.dto;

import com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy;
import lombok.Data;

import java.util.List;

@Data
public class HierarchyRemediationRequest {

    private Long repositoryId;
    private Long systemId;
    private ModuleHierarchy hierarchy;
    private List<String> moduleIds;
    private String operator;
}
