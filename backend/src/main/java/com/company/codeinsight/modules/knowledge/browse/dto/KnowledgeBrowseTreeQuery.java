package com.company.codeinsight.modules.knowledge.browse.dto;

import lombok.Data;

/**
 * 知识查看树形模式查询入参。
 * <p>systemId + repositoryId 必填；读取仓库当前生效的已发布版本（{@code last_published_version_id}）。</p>
 */
@Data
public class KnowledgeBrowseTreeQuery {

    /** 必填：系统 ID */
    private Long systemId;

    /** 必填：仓库 ID */
    private Long repositoryId;
}
