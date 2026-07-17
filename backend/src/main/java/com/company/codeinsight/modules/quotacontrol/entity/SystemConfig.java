package com.company.codeinsight.modules.quotacontrol.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统配置（key-value）。对应 ci_system_config。
 * 业务侧按 key 自行 parse value（text）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_system_config")
public class SystemConfig extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private String key;

    private String value;
    private String description;
}
