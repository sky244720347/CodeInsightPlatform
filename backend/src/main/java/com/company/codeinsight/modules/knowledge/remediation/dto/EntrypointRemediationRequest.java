package com.company.codeinsight.modules.knowledge.remediation.dto;

import com.company.codeinsight.modules.entrypoint.model.ExcludeTarget;
import lombok.Data;

import java.util.List;

@Data
public class EntrypointRemediationRequest {

    private Long repositoryId;
    private Long systemId;
    private List<ExcludeTarget> excludeTargets;
    private String operator;
}
