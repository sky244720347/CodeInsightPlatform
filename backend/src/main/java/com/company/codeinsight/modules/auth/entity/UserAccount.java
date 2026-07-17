package com.company.codeinsight.modules.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 用户账号实体（对应 ci_user 表）
 *
 * <p>MVP 阶段仅 admin 一个账号；后续接入真实 UM/SSO 时扩展多账号。
 * 软删除通过 BaseEntity.isDeleted + MyBatis-Plus @TableLogic。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_user")
public class UserAccount extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录账号（唯一） */
    private String username;

    /** 显示名 */
    private String displayName;

    /** 角色：ADMIN-管理员 / USER-普通用户 */
    private String role;

    /** 0-停用，1-启用 */
    private Integer status;

    /** 最近一次登录时间 */
    private LocalDateTime lastLoginAt;
}
