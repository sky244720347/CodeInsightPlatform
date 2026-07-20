package com.company.codeinsight.modules.knowledge.remediation;



import com.baomidou.mybatisplus.annotation.IdType;

import com.baomidou.mybatisplus.annotation.TableField;

import com.baomidou.mybatisplus.annotation.TableId;

import com.baomidou.mybatisplus.annotation.TableName;

import com.company.codeinsight.common.model.BaseEntity;

import lombok.Data;

import lombok.EqualsAndHashCode;



import java.time.LocalDateTime;



@Data

@EqualsAndHashCode(callSuper = true)

@TableName("ci_knowledge_release_edit")

public class KnowledgeReleaseEditEntity extends BaseEntity {



    @TableId(type = IdType.AUTO)

    private Long id;



    private Long repositoryId;

    private Long versionId;

    private String relativePath;



    private String contentUri;

    private String hash;



    /** 待审正文（非库字段） */

    @TableField(exist = false)

    private String contentText;



    private String status;

    private String submittedBy;

    private String approvedBy;

    private LocalDateTime approvedAt;

}

