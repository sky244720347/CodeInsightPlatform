package com.company.codeinsight.modules.draft.dto;

import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识草稿目录树节点 DTO
 * 由后端根据 ci_knowledge_draft.parent_id 自引用关系递归构建。
 * 前端可直接喂给 AntD Tree 组件：key = id, title = 节点标题, children = 子节点列表。
 *
 * 注意：root=true 标记的节点不携带具体的草稿实体（仅作为目录容器），其余节点继承自 KnowledgeDraft 实体。
 */
@Data
public class DraftTreeNode {

    /**
     * 节点唯一 ID（草稿 ID）
     */
    private Long id;

    /**
     * 父节点 ID（根节点为 null）
     */
    private Long parentId;

    /**
     * 工作区 ID
     */
    private Long workspaceId;

    /**
     * 显示名称（moduleName）
     */
    private String moduleName;

    /**
     * 草稿状态
     */
    private String status;

    /**
     * 草稿文件路径
     */
    private String filePath;

    /**
     * 同级排序
     */
    private Integer sortOrder;

    /**
     * 是否为目录节点（true 表示这是一个分组/容器，自身没有可查看的 Markdown 内容；false 表示这是真正的草稿叶子节点）
     */
    private Boolean isFolder;

    /**
     * 子节点列表（递归）
     */
    private List<DraftTreeNode> children = new ArrayList<>();

    /**
     * v1: INCREMENTAL 任务的基线继承标识
     * <p>NULL = 本次生成的草稿；非空 = 从该基线任务继承（基线 workspace 合并）。</p>
     * <p>前端 Phase 4 UI 用此字段做 diff 视图（"本次新增" vs "基线继承" 分类）。</p>
     */
    private Long baselineTaskId;

    /**
     * 正文最后一次 AI/流水线生成时间（继承保留原文；重跑成功刷新）。
     */
    private java.time.LocalDateTime generatedAt;

    /**
     * 从 KnowledgeDraft 实体构造叶子节点
     */
    public static DraftTreeNode fromDraft(KnowledgeDraft d) {
        DraftTreeNode n = new DraftTreeNode();
        n.setId(d.getId());
        n.setParentId(d.getParentId());
        n.setWorkspaceId(d.getWorkspaceId());
        n.setModuleName(d.getModuleName());
        n.setStatus(d.getStatus());
        n.setFilePath(d.getFilePath());
        n.setSortOrder(d.getSortOrder() == null ? 0 : d.getSortOrder());
        n.setIsFolder(false);
        n.setBaselineTaskId(d.getBaselineTaskId());
        n.setGeneratedAt(d.getGeneratedAt());
        return n;
    }
}
