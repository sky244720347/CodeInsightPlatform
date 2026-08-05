package com.company.codeinsight.modules.task.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

/**
 * 反编译及扫描分析任务实体类
 * 对应数据库中的 ci_task 表，管理任务状态机迁移（DRAFT, PENDING, PULLING_CODE, PARSING, SPLITTING, AI_ANALYZING, GENERATING_DOC, COMPLETED, FAILED 等）、任务进度百分比、时长等。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_task")
public class DecompileTask extends BaseEntity {

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
     * 模块提取提示词 ID（AI_ANALYZING / MODULE_HIERARCHY 阶段使用）
     * <p>按 ci_prompt 主键精准定位。
     */
    @TableField("modularize_prompt_id")
    private Long modularizePromptId;

    /**
     * 文档生成提示词 ID（GENERATING_DOC 阶段使用）
     * <p>按 ci_prompt 主键精准定位。
     */
    @TableField("document_prompt_id")
    private Long documentPromptId;

    /**
     * 运行任务时所选定的 AI 大模型唯一标识标识
     */
    private String modelName;

    /**
     * 任务当前状态：DRAFT-草稿, PENDING-排队等待中, PULLING_CODE-拉取代码中, PARSING-代码解析中, SPLITTING-切片进行中, AI_ANALYZING-AI分析归纳中, GENERATING_DOC-生成文档中, COMPLETED-任务完成, FAILED-分析失败
     */
    private String status;

    /**
     * 任务分析类型：INITIAL-全量初始化, INCREMENTAL-增量差分分析
     */
    private String type;

    /**
     * 任务执行完成度百分比数值（范围：0 ~ 100）
     */
    private Integer progress;

    /**
     * 任务执行异常失败的具体错误日志原因
     * <p>重试时需显式置 null 落库，故 updateStrategy=ALWAYS。</p>
     */
    @TableField(value = "error_reason", updateStrategy = FieldStrategy.ALWAYS)
    private String errorReason;

    /**
     * 流水线自动执行累计耗时（毫秒），不含人工断点 / 排队 / 待推送等待。
     * 挂钟历时可按 {@link #startedAt} 与 {@link #endedAt} 另行计算。
     * <p>重试 / 重跑时需显式置 null 落库（避免基于旧值累加），故 updateStrategy=ALWAYS。</p>
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long durationMs;

    /**
     * 当前自动执行段的起点；断点或排队时为 null。
     * <p>重试时需显式置 null 落库（避免下一次起算从断点残留值继续累加），故 updateStrategy=ALWAYS。</p>
     */
    @TableField(value = "active_segment_started_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime activeSegmentStartedAt;

    /**
     * 任务启动时间
     * <p>重试时需显式置 null 落库（否则 MyBatis-Plus 默认 NOT_NULL 策略会保留旧值），
     * 待 PENDING → PULLING_CODE 时由状态机重新写入当前时间。故 updateStrategy=ALWAYS。</p>
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime startedAt;

    /**
     * 任务结束时间（完成或失败）
     * <p>重试时需显式置 null 落库（避免详情页「结束时间」一直展示上次运行的值），
     * 故 updateStrategy=ALWAYS。</p>
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime endedAt;

    /**
     * 任务级入口扫描配置 JSON 字符串（整体序列化 EntryPointConfig）
     * null 表示使用系统默认行为（注解驱动 Controller/JOB/MQ 等）
     */
    @TableField("entry_scan_config")
    private String entryScanConfig;

    /**
     * 是否启用模块层级调试（人工复核断点）
     * TRUE - 模块层级提炼完成后停在 MODULE_HIERARCHY_REVIEW，等待用户在页面上编辑 module_hierarchy 后再继续
     * FALSE - 跳过断点，由 MODULE_HIERARCHY 直接推进至 GENERATING_DOC
     * 默认 TRUE
     */
    @TableField("require_hierarchy_review")
    private Boolean requireHierarchyReview;

    /**
     * 是否启用知识入口复核（人工复核断点，介于 SPLITTING_TASK 与 AI_ANALYZING 之间）
     * TRUE - 切片完成后停在 ENTRYPOINT_REVIEW，等待用户在页面上确认入口清单后再继续调用 AI
     * FALSE - 跳过断点，由 SPLITTING_TASK 直接推进至 AI_ANALYZING
     * 默认 TRUE
     */
    @TableField("require_entrypoint_review")
    private Boolean requireEntrypointReview;

    /**
     * 是否启用知识文档复核断点（人工复核，介于 GENERATING_DOC 与 CONFIRMED 之间）
     * TRUE - 文档生成后停在 PENDING_REVIEW，等待人工确认后再建版推送
     * FALSE - 跳过断点，自动确认并建版 + NAS 推送
     * 默认 TRUE；手动下发页 UI 默认 false，以创建请求体为准
     */
    @TableField("require_knowledge_review")
    private Boolean requireKnowledgeReview;

    /**
     * 任务触发来源：
     * <ul>
     *   <li>MANUAL - 前端用户手动创建并启动（默认）</li>
     *   <li>SCHEDULED - 由定时 commit 轮询（{@link com.company.codeinsight.modules.scanwindow.scheduler.ScanWindowScheduler}）触发</li>
     * </ul>
     */
    @TableField("trigger_source")
    private String triggerSource;

    /**
     * 队列优先级 0-100，越大越优先。
     * <p>SCHEDULED 默认 60（高于手动），MANUAL 默认 50；TaskQueueDispatcher 按此字段 + created_date ASC 排序。</p>
     */
    @TableField("priority")
    private Integer priority;

    /** 集群：认领该任务的实例 ID（{@link com.company.codeinsight.common.cluster.ClusterInstanceId}） */
    @TableField(value = "claimed_by", updateStrategy = FieldStrategy.ALWAYS)
    private String claimedBy;

    /** 认领时间 */
    @TableField(value = "claimed_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime claimedAt;

    /** 认领租约到期时间；过期后其他节点可重新认领 PENDING 预留 */
    @TableField(value = "lease_until", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime leaseUntil;

    /** 知识纠错类型：ENTRYPOINT / HIERARCHY / DOCUMENT */
    @TableField("remediation_kind")
    private String remediationKind;

    @TableField("base_version_id")
    private Long baseVersionId;

    @TableField("base_task_id")
    private Long baseTaskId;

    /** 纠错续跑起点：AI_ANALYZING / GENERATING_DOC */
    @TableField("resume_from")
    private String resumeFrom;

    @TableField("remediation_scope_json")
    private String remediationScopeJson;

    /**
     * 本任务扫描时的源代码 Commit ID（{@code pullAndScan} 成功后写入一次）。
     * <p>知识版本 {@code source_commit} 与增量影响分析均以此为准，不读仓库级字段。</p>
     */
    @TableField("source_commit")
    private String sourceCommit;

    /**
     * 创建时后端是否处于 dev（{@code CODE_INSIGHT_ENV=dev}）。
     * <p>本地 dev 进程只自动/手动执行 {@code is_dev=true} 的任务，避免误连共享库抢 STG 任务。</p>
     */
    @TableField("is_dev")
    private Boolean isDev;
}

