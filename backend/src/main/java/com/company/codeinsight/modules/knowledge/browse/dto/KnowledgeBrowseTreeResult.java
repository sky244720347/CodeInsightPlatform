package com.company.codeinsight.modules.knowledge.browse.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识查看树形模式响应：基准任务元数据 + 模块层级树。
 */
@Data
public class KnowledgeBrowseTreeResult {

    private Long systemId;
    private String systemName;
    private Long repositoryId;
    private String repositoryName;

    /** 当前生效的已发布知识版本 ID */
    private Long versionId;

    /** 当前生效的已发布知识版本号 */
    private String versionNum;

    /** 是否读取仓库当前生效的已发布版本（默认 true） */
    private Boolean activeVersion;

    /** 关联的源任务 ID（来自发布版本） */
    private Long taskId;

    /** 文档生成粒度：function / module */
    private String documentGranularity;

    private List<KnowledgeBrowseTreeNode> nodes = new ArrayList<>();
}
