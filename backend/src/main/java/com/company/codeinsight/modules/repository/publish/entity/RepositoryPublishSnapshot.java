package com.company.codeinsight.modules.repository.publish.entity;

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
@TableName("ci_repository_publish_snapshot")
public class RepositoryPublishSnapshot extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repositoryId;
    private Long systemId;
    private Long taskId;
    private Long versionId;
    private String versionNum;

    @TableField("entry_scan_config")
    private String entryScanConfig;

    private Long modularizePromptId;
    private Long documentPromptId;
    private String modelName;

    @TableField("entrypoints_uri")
    private String entrypointsUri;

    @TableField("module_hierarchy_uri")
    private String moduleHierarchyUri;

    /** 入口快照 JSON（非库字段） */
    @TableField(exist = false)
    private String entrypointsJson;

    /** 层级快照 JSON（非库字段） */
    @TableField(exist = false)
    private String moduleHierarchyJson;

    private LocalDateTime publishedAt;
    private String publishedBy;
}
