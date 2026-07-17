package com.company.codeinsight.common.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据库实体类基类
 * 抽离并封装关系型数据表中通用的审计跟踪属性（逻辑删除、操作人、时间戳）。
 * 子类实体继承此类后，可通过 MyBatis-Plus 自动填充与逻辑删除实现零手动干预。
 */
@Data
public class BaseEntity {

    /** 逻辑删除：0=未删除，1=已删除 */
    @TableLogic(value = "0", delval = "1")
    @TableField(fill = FieldFill.INSERT)
    private Integer isDeleted;

    /** 创建人：insert 时自动填充，无登录态时为 sys */
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    /** 最后修改人：insert/update 时自动填充，无登录态时为 sys */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    /** 创建时间：insert 时自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdDate;

    /** 更新时间：insert/update 时自动填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedDate;
}
