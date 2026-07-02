package com.company.codeinsight.modules.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识版本发布实体类
 * 对应数据库中的 ci_knowledge_version 表，管理对复核完成的代码知识进行确认发布、推送到 Git 版本分支及导出 ZIP 等生命周期状态。
 */
@Data
@TableName("ci_knowledge_version")
public class KnowledgeVersion {

    /**
     * 自增主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属业务系统 ID
     */
    private Long systemId;

    /**
     * 关联的 Git 代码库 ID
     */
    private Long repositoryId;

    /**
     * 关联的分析任务 ID
     */
    private Long taskId;

    /**
     * 发布的知识版本号（如 v1.0.0）
     */
    private String versionNum;

    /**
     * 来源分析代码库的 Git 分支名
     */
    private String sourceBranch;

    /**
     * 来源分析代码库的 Git Commit 哈希值
     */
    private String sourceCommit;

    /**
     * 目标文档库 Git 分支名（知识推送的接收分支）
     */
    private String targetBranch;

    /**
     * 推送完成后目标文档库产生的 Git Commit 提交哈希值
     */
    private String targetCommit;

    /**
     * 所采用的提示词模板版本号
     */
    private Integer promptVersion;

    /**
     * 所调用的 AI 大模型唯一标识
     */
    private String modelName;

    /**
     * 发布及推送状态：DRAFT-待推送, PUSHING-推送中, PUSHED-推送成功, FAILED-推送失败
     */
    private String status;

    /**
     * 推送方式：GIT（Git 推送）或 S3（对象存储推送）
     */
    private String pushMethod;

    /**
     * 操作确认人用户名
     */
    private String confirmedBy;

    /**
     * 人工确认发布时间
     */
    private LocalDateTime confirmedAt;

    /**
     * Git 推送完成时间
     */
    private LocalDateTime pushedAt;

    /**
     * 版本创建时间
     */
    private LocalDateTime createdAt;

    /** 是否为仓库当前生效的已发布版本（API 计算字段，非表列） */
    @TableField(exist = false)
    private Boolean activePublished;
}

