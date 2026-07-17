package com.company.codeinsight.modules.prompt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 代码分析提示词模板实体类
 * 对应数据库中的 ci_prompt 表；正文外置 NAS（content_uri），API 层仍通过 {@link #content} 返回正文。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_prompt")
public class DecompilePrompt extends BaseEntity {

    public static final String TYPE_MODULARIZE = "MODULARIZE";
    public static final String TYPE_DOCUMENT_GENERATION = "DOCUMENT_GENERATION";

    public static final String LIFECYCLE_DRAFT = "DRAFT";
    public static final String LIFECYCLE_RELEASED = "RELEASED";
    public static final String LIFECYCLE_ARCHIVED = "ARCHIVED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 正文 URI（库内）：prompt:{id}/content.md */
    private String contentUri;

    /** 正文 MD5 */
    private String contentHash;

    /** 提示词正文（非库字段，由 Service hydrate / 请求体写入） */
    @TableField(exist = false)
    private String content;

    private Integer version;

    private Integer isDefault;

    @TableField("prompt_type")
    private String promptType;

    private String lifecycle;

    private String category;

    private Long scopeId;

    public boolean isReleased() {
        return LIFECYCLE_RELEASED.equals(lifecycle);
    }
}
