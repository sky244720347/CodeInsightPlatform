package com.company.codeinsight.modules.knowledge.remediation.dto;

import lombok.Data;

@Data
public class RemediationTaskResponse {

    private Long taskId;
    private String remediationKind;
    private String resumeFrom;
}
