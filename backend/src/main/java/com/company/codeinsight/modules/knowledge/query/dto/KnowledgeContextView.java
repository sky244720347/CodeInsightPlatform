package com.company.codeinsight.modules.knowledge.query.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 仓库当前生效知识上下文（知识查询三页共享）。
 */
@Data
public class KnowledgeContextView {

    private Long systemId;
    /** 系统名称（纯 name/nameCn，不含组件拼接） */
    private String systemName;
    /** 组件标识；空串/null 表示无组件 */
    private String component;
    private Long repositoryId;
    private String repositoryName;
    private Long versionId;
    private String versionNum;
    private Long taskId;
    /** 版本推送完成时间（文档生成/发布时间） */
    private LocalDateTime pushedAt;
    private Boolean releaseDirExists;
    private Boolean hasPublishedEntrypoints;
    private Boolean hasPublishedHierarchy;
}
