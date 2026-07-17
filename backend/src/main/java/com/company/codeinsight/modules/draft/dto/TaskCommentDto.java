package com.company.codeinsight.modules.draft.dto;

import com.company.codeinsight.modules.draft.entity.DraftReviewComment;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务级复核意见聚合 DTO
 * <p>复核工作区「复核意见」按钮的真实语义入口 — 按任务粒度聚合整个工作区下所有草稿的复核意见，
 * 附带来源草稿的模块名和文件路径，便于复核人一次性浏览整组意见（包含任务级 [任务级通过] 记录）。</p>
 *
 * <p>与 {@link DraftReviewComment} 的区别：DTO 多了 {@code moduleName} 和 {@code filePath}
 * 两个字段，前端无需再按 draftId 反查草稿树即可在 UI 上展示意见归属。</p>
 */
@Data
public class TaskCommentDto {

    /** 复核意见主键 ID */
    private Long id;

    /** 关联的草稿 ID（意见实际挂在那篇草稿的评论表里） */
    private Long draftId;

    /** 来源草稿的模块名（聚合时 JOIN ci_knowledge_draft.module_name） */
    private String moduleName;

    /** 来源草稿的代码文件路径 */
    private String filePath;

    /** 填写人 */
    private String author;

    /** 意见正文 */
    private String comment;

    /**
     * 意见类型
     * <ul>
     *   <li>NORMAL：通用意见</li>
     *   <li>PASS：确认通过意见（含任务级确认时挂的 `[任务级通过]` 前缀）</li>
     *   <li>REJECT：驳回意见（v0.3 起移除驳回流程，存量数据可能保留）</li>
     * </ul>
     */
    private String type;

    /** 创建时间 */
    private LocalDateTime createdDate;
}