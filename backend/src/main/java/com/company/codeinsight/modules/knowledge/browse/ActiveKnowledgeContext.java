package com.company.codeinsight.modules.knowledge.browse;

import lombok.Data;

import java.nio.file.Path;

/**
 * 仓库当前生效的已发布知识上下文（由 {@code ci_repository.last_published_version_id} 解析）。
 */
@Data
public class ActiveKnowledgeContext {

    private Long systemId;
    private Long repositoryId;
    private Long versionId;
    private String versionNum;
    private Long taskId;
    private Path releaseDir;
    private boolean releaseDirExists;
}
