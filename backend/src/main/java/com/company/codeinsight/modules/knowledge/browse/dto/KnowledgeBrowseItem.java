package com.company.codeinsight.modules.knowledge.browse.dto;

import lombok.Data;

/**
 * 知识查看列表行 DTO。
 * <p>聚合 {@code ci_knowledge_draft} 行 + 推送阶段生成的索引/清单文件，对前端展示统一形态。</p>
 */
@Data
public class KnowledgeBrowseItem {

    /** 复合主键（用于前端表格 rowKey）：
     *  - draft 行："draft:{draftId}"
     *  - index 文件："index:{taskId}:{relPath}"
     *  - manifest 文件："manifest:{taskId}:{relPath}" */
    private String id;

    /** 文件名（不包含父路径） */
    private String name;

    /** 文件类型：DRAFT / INDEX / MANIFEST */
    private String type;

    /** 关联任务 ID（draft 行必有；index/manifest 来自 temp_repos 必有） */
    private Long taskId;

    /** 关联知识版本 ID（draft 行查 KnowledgeVersion，index/manifest 留空——还没和具体版本绑定） */
    private Long versionId;

    /** 版本号字符串（v1.0.0 等），仅 draft 关联版本时有值 */
    private String versionNum;

    /** 相对路径（draft = ci_knowledge_draft.filePath；index/manifest = docs/code-insight 下的相对路径，如 index/module-index.md） */
    private String filePath;

    /** 文件字节数 */
    private Long size;

    /** 状态：draft 行 = DRAFT/EDITING/CONFIRMED/PUSHED/ARCHIVED；index/manifest = GENERATED */
    private String status;

    /** 更新时间（ISO 字符串；index/manifest 用文件 lastModified） */
    private String updatedDate;

    /** 数据源标识：DB（draft 行）/ TEMP_REPOS（index/manifest 文件）/ RELEASE（已发布产物） */
    private String source;

    /** 已发布产物 content_uri（source=RELEASE 时有值） */
    private String contentUri;

    /** 所属系统 ID（列表跨系统展示） */
    private Long systemId;

    /** 所属系统名称 */
    private String systemName;

    /** 所属仓库 ID */
    private Long repositoryId;

    /** 所属仓库展示名（gitUrl 末段 + 分支） */
    private String repositoryName;
}