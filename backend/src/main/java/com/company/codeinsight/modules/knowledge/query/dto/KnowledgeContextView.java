package com.company.codeinsight.modules.knowledge.query.dto;

import lombok.Data;

/**
 * 仓库当前生效知识上下文（知识查询三页共享）。
 */
@Data
public class KnowledgeContextView {

    private Long systemId;
    private String systemName;
    private Long repositoryId;
    private String repositoryName;
    private Long versionId;
    private String versionNum;
    private Long taskId;
    private Boolean releaseDirExists;
    private Boolean hasPublishedEntrypoints;
    private Boolean hasPublishedHierarchy;
}
