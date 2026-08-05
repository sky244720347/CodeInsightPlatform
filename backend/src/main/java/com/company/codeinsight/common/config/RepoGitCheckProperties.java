package com.company.codeinsight.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 仓库 Git 连通性探测配置（定时轮询 / 超时 / 并发 / 分批 TTL）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "code-insight.repo")
public class RepoGitCheckProperties {

    /** 调度 tick 间隔（毫秒），默认 3 分钟；单轮有界后不再全库扫完才返回 */
    private long gitCheckIntervalMs = 180_000L;

    /** 启动后首次 tick 延迟（毫秒） */
    private long gitCheckInitialDelayMs = 180_000L;

    /** 单次 ls-remote 超时（秒） */
    private int gitCheckTimeoutSeconds = 15;

    /** 本机并发探测数 */
    private int gitCheckConcurrency = 3;

    /** 每轮最多探测仓库数（再受墙钟约束） */
    private int gitCheckBatchSize = 40;

    /** 单轮墙钟上限（秒），应 &lt; interval，防止与下一 tick 重叠 */
    private int gitCheckMaxSweepSeconds = 120;

    /** 已连通复查 TTL（毫秒），默认 6 小时 */
    private long gitCheckReachableTtlMs = 21_600_000L;

    /** 不通复查 TTL（毫秒），默认 30 分钟 */
    private long gitCheckUnreachableTtlMs = 1_800_000L;

    // ── 类型/技术栈多机探测（docs/repo-stack-probe-plan.md）──

    /** 是否启用真空仓类型/技术栈探测（每节点都跑，无 Leader） */
    private boolean stackProbeEnabled = true;

    /** 每节点调度间隔（毫秒） */
    private long stackProbeIntervalMs = 20_000L;

    /** 启动后首次延迟（毫秒） */
    private long stackProbeInitialDelayMs = 30_000L;

    /** 每 tick 最多尝试仓数 */
    private int stackProbeBatchSize = 40;

    /** 本机轻量树并发 */
    private int stackProbeTreeConcurrency = 2;

    /** 按仓 Redis 锁 TTL（秒） */
    private int stackProbeLockTtlSeconds = 120;

    /** 单仓拉树/浅克隆超时（毫秒） */
    private long stackProbeTreeTimeoutMs = 45_000L;

    /** 新建真空仓后是否异步立即探测 */
    private boolean stackProbeOnCreate = true;

    /**
     * 连续空结果退避阶梯（毫秒，逗号分隔）。
     * 例：60s,5m,15m → 空库后逐步少查 SQL。
     */
    private String stackProbeIdleBackoffMs = "60000,300000,900000";

    /** 未连通 SKIP 冷却（毫秒） */
    private long stackProbeCooldownUnreachableMs = 600_000L;

    /** 拉树失败 / 低置信冷却（毫秒） */
    private long stackProbeCooldownFailMs = 300_000L;

    /** 多机共享 Redis idle-until（空库时其它节点也跳过查库） */
    private boolean stackProbeGlobalIdle = false;
}
