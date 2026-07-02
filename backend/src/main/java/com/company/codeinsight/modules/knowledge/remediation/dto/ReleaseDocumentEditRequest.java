package com.company.codeinsight.modules.knowledge.remediation.dto;

import lombok.Data;

@Data
public class ReleaseDocumentEditRequest {

    private Long repositoryId;
    private String relativePath;
    private String content;
    private String operator;
}
