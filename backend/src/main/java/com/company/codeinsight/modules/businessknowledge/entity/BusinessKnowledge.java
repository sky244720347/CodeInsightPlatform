package com.company.codeinsight.modules.businessknowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 业务知识配置实体：正文外置 NAS（content_uri），API 仍通过 {@link #content} 返回正文。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_business_knowledge")
public class BusinessKnowledge extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long systemId;

    private String contentUri;

    private String contentHash;

    /** Markdown 正文（非库字段） */
    @TableField(exist = false)
    private String content;

    private Integer version;
}
