package com.company.codeinsight.modules.draft.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 草稿评审复核意见实体类
 * 对应数据库中的 ci_draft_review_comment 表，保存评审人员在驳回或复核草稿时所填写的备注及批注意见。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_draft_review_comment")
public class DraftReviewComment extends BaseEntity {

    /**
     * 自增主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联的草稿 ID
     */
    private Long draftId;

    /**
     * 填写评审意见的人员用户名
     */
    private String author;

    /**
     * 评审反馈的具体意见内容
     */
    private String comment;

    /**
     * 意见类型
     * <ul>
     *   <li>NORMAL：通用意见（手动添加的批注）</li>
     *   <li>PASS：确认通过意见（confirmDraft 时填写）</li>
     *   <li>REJECT：驳回意见（rejectDraft 时填写）</li>
     * </ul>
     */
    private String type;
}
