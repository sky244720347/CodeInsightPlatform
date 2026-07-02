package com.company.codeinsight.modules.knowledge.remediation.dto;

import lombok.Data;

import java.util.List;

@Data
public class DocumentRemediationRequest {

    private Long repositoryId;
    private Long systemId;
    private List<String> moduleIds;
    private String operator;
}
