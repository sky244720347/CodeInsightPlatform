package com.company.codeinsight.modules.log.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统操作审计日志实体类
 * 映射 ci_operation_log 表，记录包含用户登录、工作区更新、推送 Git 以及任务发布等的历史操作。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_operation_log")
public class OperationLog extends BaseEntity {

    /**
     * 自增主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联的系统 ID
     */
    private Long systemId;

    /**
     * 关联的任务 ID（若有）
     */
    private Long taskId;

    /**
     * 执行操作的用户 ID
     */
    private Long userId;

    /**
     * 执行操作的用户用户名
     */
    private String username;

    /**
     * 操作类型（例如：LOGIN, CREATE_TASK, SAVE_DRAFT, PUSH_GIT 等）
     */
    private String actionType;

    /**
     * 详细的操作入参或备注信息
     */
    private String detail;

    /**
     * 操作客户端的 IP 地址
     */
    private String ipAddress;

    /**
     * 若操作失败，在此处记录具体的异常信息
     */
    private String exceptionMsg;

    /**
     * 操作是否成功：0-失败, 1-成功
     */
    private Integer isSuccess;
}
