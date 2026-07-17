package com.company.codeinsight.modules.entrypoint.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识入口复核实体（对应 ci_entrypoint 表）
 * <p>流水线 SPLITTING_TASK→AI_ANALYZING 之间落表，由人工确认或驳回后流转。
 * methods_json 列存入口类下的方法列表 JSON 字符串，仅供前端只读展示。</p>
 *
 * @see com.company.codeinsight.modules.entrypoint.service.EntrypointReviewService
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_entrypoint")
public class EntrypointEntity extends BaseEntity {

    /** 自增主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联任务 ID */
    private Long taskId;

    /** 所属系统 ID（冗余 task.system_id 便于查询） */
    private Long systemId;

    /** 入口类全限定名（如 com.demo.controller.UserController） */
    private String className;

    /** 源文件相对路径 */
    @TableField("file_path")
    private String filePath;

    /** 入口类型（CONTROLLER / SCHEDULED_JOB / MQ_LISTENER / ...） */
    @TableField("entry_type")
    private String entryType;

    /** 触发该类被识别为入口的注解简称 */
    private String annotation;

    /** 附加信息（RequestMapping 一级路径 / 队列名等） */
    private String remark;

    /** 入口类下方法列表 JSON 数组（[{methodName, methodSignature, annotation, httpPath, httpMethod}, ...]） */
    @TableField("methods_json")
    private String methodsJson;

    /** 同任务内排序权重 */
    @TableField("sort_order")
    private Integer sortOrder;

    /**
     * v1: INCREMENTAL 任务基线继承（NULL=本次识别；非空=从该基线任务继承）
     */
    @TableField("baseline_task_id")
    private Long baselineTaskId;
}
