package com.company.codeinsight.modules.knowledge.browse.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识查看树形节点：模块 → 子模块 → 功能（叶子）。
 */
@Data
public class KnowledgeBrowseTreeNode {

    /** 节点唯一 key（module / subModule / function id） */
    private String key;

    /** MODULE / SUB_MODULE / FUNCTION */
    private String nodeType;

    /** 展示标题 */
    private String title;

    /** 关联草稿 ID（未发布草稿模式时有值） */
    private Long draftId;

    /** 发布产物 content_uri（release:...，已发布模式时有值） */
    private String contentUri;

    /** 相对 releases 根目录的路径（如 modules/UserModule.md） */
    private String documentPath;

    /** 是否已有可查看的知识文档 */
    private Boolean hasDocument;

    /** 文档状态（PUSHED / CONFIRMED 等） */
    private String draftStatus;

    /**
     * 文档粒度：function = 一功能一文档；module = 模块级共享文档
     * 仅 FUNCTION 叶子节点有值
     */
    private String documentGranularity;

    private List<KnowledgeBrowseTreeNode> children = new ArrayList<>();
}
