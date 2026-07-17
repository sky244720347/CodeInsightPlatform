package com.company.codeinsight.modules.entrypoint.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识入口复核视图 DTO
 * <p>对应数据库行 ci_entrypoint，由 controller 层在序列化前把 methods_json 反序列化为 {@link EntrypointMethodView} 列表。
 * 避免 DB 实体（EntrypointEntity）直接暴露给前端，并使 methods_json 字段在视图层变成强类型数组。</p>
 */
@Data
public class EntrypointReviewView {

    /** DB 主键 */
    private Long id;

    /** 任务 ID */
    private Long taskId;

    /** 所属系统 ID */
    private Long systemId;

    /** 入口类全限定名 */
    private String className;

    /** 源文件相对路径 */
    private String filePath;

    /** 入口类型（CONTROLLER / SCHEDULED_JOB / MQ_LISTENER / ...） */
    private String entryType;

    /** 触发该类被识别为入口的注解简称 */
    private String annotation;

    /** 附加信息（如 RequestMapping 一级路径 / 队列名） */
    private String remark;

    /** 是否启用（默认 TRUE；当前 UI 只读，但字段保留以便未来扩展） */
    private Boolean enabled;

    /** 同任务内排序权重 */
    private Integer sortOrder;

    /** 该入口类下的方法列表（由 controller 层从 methods_json 反序列化得到） */
    private List<EntrypointMethodView> methods = new ArrayList<>();

    /**
     * v1: INCREMENTAL 任务的基线继承标识
     * <p>NULL = 本次新增（AI 重新识别或 PUSHED 任务不存在）；非空 = 从该基线任务 ID 继承。</p>
     * <p>前端 Phase 4 UI 用此字段做 diff 视图（"本次新增" vs "基线继承" 分类）。</p>
     */
    private Long baselineTaskId;
}