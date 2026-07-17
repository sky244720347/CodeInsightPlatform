package com.company.codeinsight.modules.scanner.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 增量扫描结果落盘实体
 * <p>对应 {@code ci_incremental_scan} 表，存 INCREMENTAL 任务 PULLING_CODE 阶段的
 * git diff 结果（changedPaths / deletedPaths / baselineTaskId / baselineCommitId / headCommitId），
 * 供后续 PARSING_CODE / ENTRYPOINT_DISCOVERY / MODULE_HIERARCHY 阶段读取。</p>
 *
 * <p>v1 基线 + 增量模型：每个 INCREMENTAL 任务在 PULLING_CODE 末尾写入一条；INITIAL 任务
 * 也会写一条（baselineTaskId / baselineCommitId 为 NULL，scanMode = "INITIAL"）。</p>
 *
 * <p>方案 B：代理键 {@code id} + 活行唯一 {@code uk_incremental_scan_task_active(task_id) WHERE is_deleted=0}。
 * 覆盖写 = 逻辑删 + insert。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_incremental_scan")
public class IncrementalScanRecord extends BaseEntity {

    /** 代理主键（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务 ID（活行唯一，与 ci_task.id 对应） */
    private Long taskId;

    /** 系统 ID */
    private Long systemId;

    /** 仓库 ID */
    private Long repositoryId;

    /**
     * 数据复制源：最近一次 PUSHED 任务的 ID（INITIAL 任务为 NULL）
     */
    @TableField("baseline_task_id")
    private Long baselineTaskId;

    /**
     * 对比基准 commit（仓库 last_published_commit_id；INITIAL 任务为 NULL）
     */
    @TableField("baseline_commit_id")
    private String baselineCommitId;

    /** 本次扫描 HEAD commit */
    @TableField("head_commit_id")
    private String headCommitId;

    /** INITIAL / INCREMENTAL */
    @TableField("scan_mode")
    private String scanMode;

    /**
     * 本次变更文件相对路径列表（JSON 字符串，序列化为 ["src/main/java/...", ...]）
     */
    @TableField("changed_paths")
    private String changedPaths;

    /**
     * 本次删除文件相对路径列表（JSON 字符串，序列化为 ["src/main/java/...", ...]）
     */
    @TableField("deleted_paths")
    private String deletedPaths;

    /** 从基线任务继承的入口数（仅 INCREMENTAL 任务有意义） */
    @TableField("inherited_count")
    private Integer inheritedCount;
}
