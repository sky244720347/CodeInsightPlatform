package com.company.codeinsight.modules.draft.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 评审工作区实体类
 * 对应数据库中的 ci_draft_workspace 表，对应一次成功的反编译/静态分析任务所启动的待复核协同编辑工作区。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_draft_workspace")
public class DraftWorkspace extends BaseEntity {

    /**
     * 自增主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所产生关联的知识构建任务 ID
     */
    private Long taskId;

    /**
     * 关联的业务系统 ID
     */
    private Long systemId;

    /**
     * 关联的 Git 代码库 ID
     */
    private Long repositoryId;

    /**
     * 评审状态：ACTIVE-活跃编辑中, COMPLETED-复核完成通过, ARCHIVED-历史归档
     */
    private String status;

    /**
     * v1: INCREMENTAL 任务引用的基线 workspace ID（NULL=INITIAL 任务；非空=引用最近 PUSHED 任务的 workspace）
     */
    @TableField("baseline_workspace_id")
    private Long baselineWorkspaceId;
}
