package com.company.codeinsight.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 业务接入系统应用实体类
 * 对应数据库中的 ci_system 表，记录系统的基本信息、负责人以及启用停用状态。
 * 软删除通过 BaseEntity.isDeleted + MyBatis-Plus @TableLogic 实现：所有查询自动过滤已删除记录。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_system")
public class SystemApplication extends BaseEntity {

    /**
     * 自增主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 接入业务系统的名称（如 “电子商城系统”、“统一认证系统”）
     */
    private String name;

    /**
     * 业务系统的中文名称（如 “电子商城系统”、“统一身份认证平台”）
     */
    private String nameCn;

    /**
     * 业务系统的描述说明
     */
    private String description;

    /**
     * 系统的核心技术负责人/管理员用户名
     */
    private String owner;

    /**
     * 系统级模块提取提示词 ID（FK → ci_prompt.id）。运行时未设置则回退到默认提示词（is_default=1）。
     */
    private Long modularizePromptId;

    /**
     * 系统级文档生成提示词 ID（FK → ci_prompt.id）。运行时未设置则回退到默认提示词（is_default=1）。
     */
    private Long documentPromptId;

    /**
     * 同时在跑任务上限（系统级并发闸门，TaskQueueDispatcher 调度时取此值控制 Semaphore）。默认 1。
     */
    private Integer maxConcurrentTasks;
}
