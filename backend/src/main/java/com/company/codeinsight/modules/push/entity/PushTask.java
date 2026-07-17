package com.company.codeinsight.modules.push.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 推送任务审计实体
 * 对应数据库中的 ci_push_task 表，记录每次知识推送任务的生命周期状态与结果。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_push_task")
public class PushTask extends BaseEntity {

    /** 自增主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的知识版本 ID */
    private Long versionId;

    /** 推送方式：GIT 或 S3 */
    private String pushMethod;

    /** 推送状态：PENDING / PROCESSING / SUCCESS / FAILED */
    private String status;

    /** 当前重试次数 */
    private Integer retryCount;

    /** 最大重试次数 */
    private Integer maxRetries;

    /** 推送目标摘要信息（JSON 格式） */
    private String targetInfo;

    /** 失败原因 */
    private String errorMessage;

    /** 入队时间 */
    private LocalDateTime enqueuedAt;

    /** 开始执行时间 */
    private LocalDateTime startedAt;

    /** 完成时间 */
    private LocalDateTime completedAt;
}
