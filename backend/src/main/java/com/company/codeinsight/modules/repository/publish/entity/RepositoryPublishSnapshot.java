package com.company.codeinsight.modules.repository.publish.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ci_repository_publish_snapshot")
public class RepositoryPublishSnapshot {

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

    @TableField("entrypoints_json")
    private String entrypointsJson;

    @TableField("module_hierarchy_json")
    private String moduleHierarchyJson;

    private LocalDateTime publishedAt;
    private String publishedBy;
}
