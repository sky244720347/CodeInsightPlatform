package com.company.codeinsight.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 仓库 Git 连通性探测配置（定时轮询 / 超时 / 并发 / 分批 TTL）。
 * <p>含类型/技术栈 Leader 串行探测（docs/repo-stack-probe-plan.md）。</p>
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

    // ── 类型/技术栈 Leader 串行探测（docs/repo-stack-probe-plan.md）──

    /** 是否启用 */
    private boolean stackProbeEnabled = true;

    /** 醒来检查 next-run-at 的频率（毫秒） */
    private long stackProbeIntervalMs = 20_000L;

    /** 启动后首次延迟（毫秒） */
    private long stackProbeInitialDelayMs = 30_000L;

    /** 每批列表 LIMIT */
    private int stackProbeBatchSize = 30;

    /** 单仓拉树/浅克隆超时（毫秒） */
    private long stackProbeTreeTimeoutMs = 45_000L;

    /** 单轮墙钟上限（秒）；可多批直到到点或列表空 */
    private int stackProbeMaxSweepSeconds = 1_800;

    /**
     * 本功能专用 Leader 锁 TTL 下限（秒）。实际续租 TTL = max(本值, batchSize×tree-timeout+120)。
     * 每批列表处理前续租一次（非每仓），以降低 Redis 访问。默认 1800s。
     */
    private int stackProbeLeaderLockTtlSeconds = 1_800;

    /** COUNT&gt;0 跑完后，下次允许执行的最短间隔（毫秒） */
    private long stackProbeActiveDelayMs = 20_000L;

    /**
     * COUNT=0 时调度冷却阶梯（毫秒，逗号分隔）。
     * 写入 Redis {@code ci:stack-probe:next-run-at}。
     */
    private String stackProbeIdleBackoffMs = "60000,300000,900000";

    /** 新建/更新真空是否唤醒调度（只清 next-run-at，不并行 clone） */
    private boolean stackProbeOnCreate = true;

    /** 孤儿 stack_probe_run_* 目录最大保留小时数 */
    private int stackProbeOrphanMaxAgeHours = 2;
}
