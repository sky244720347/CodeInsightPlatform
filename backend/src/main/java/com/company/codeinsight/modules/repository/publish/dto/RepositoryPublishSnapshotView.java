package com.company.codeinsight.modules.repository.publish.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 仓库发布快照视图：在实体基础上增加 release 目录存在性与生效标记。
 */
@Data
public class RepositoryPublishSnapshotView {

    private Long id;
    private Long repositoryId;
    private Long systemId;
    private Long taskId;
    private Long versionId;
    private String versionNum;
    private Long modularizePromptId;
    private Long documentPromptId;
    private String modelName;
    private LocalDateTime publishedAt;
    private String publishedBy;

    /** NAS/local releases 目录是否存在 */
    private Boolean releaseDirExists;

    /** 是否为仓库当前生效发布版本 */
    private Boolean activePublished;
}
