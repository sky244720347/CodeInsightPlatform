package com.company.codeinsight.common.cluster;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 集群调度参数。
 * <p>{@code enabled} <b>不可外部配置</b>，由 {@link ClusterEnvAligner} 按 {@code code-insight.env} 推导：
 * dev=false，非 dev=true。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "code-insight.cluster")
public class ClusterProperties {

    /**
     * 由 {@link ClusterEnvAligner} 写入。false：JVM Semaphore + 本地调度；true：Redis 许可 + Leader + DB 认领。
     */
    private boolean enabled = false;

    /** Leader 锁 TTL（秒），持有方需周期性续租 */
    private int leaderLockTtlSeconds = 15;

    /**
     * 任务认领 lease 时长（分钟）。流水线运行中按 {@link #taskLeaseRenewIntervalSeconds} 续租。
     * <p>重启后靠「心跳已死 ∨ 租约过期」判定孤儿；过长会导致崩溃后迟迟不能接管。
     * 旧字段 {@link #taskLeaseHours} 仅作兼容，优先用本字段。</p>
     */
    private int taskLeaseMinutes = 10;

    /**
     * @deprecated 改用 {@link #taskLeaseMinutes}；若 {@code taskLeaseMinutes}≤0 时回退为 hours×60。
     */
    @Deprecated
    private int taskLeaseHours = 2;

    /** 流水线运行中续租间隔（秒），应明显小于 {@link #taskLeaseMinutes} */
    private int taskLeaseRenewIntervalSeconds = 120;

    /** 孤儿任务扫描/接管间隔（毫秒）；启动时也会立即扫一次 */
    private long orphanReclaimIntervalMs = 30_000L;

    /** 单篇草稿 REGENERATING 超时（分钟），超时后恢复上一状态 */
    private int draftRegenTimeoutMinutes = 30;

    /** 草稿编辑锁 TTL（秒） */
    private int draftEditLockTtlSeconds = 120;

    /** 草稿编辑锁续期间隔（秒），前端应小于 TTL 周期性续租 */
    private int draftEditLockRenewSeconds = 60;

    /**
     * 任务并发 Redis Set key TTL（秒）。流水线运行中会按 {@link #taskPermitRenewSeconds} 续租；
     * 崩溃残留依赖启动/周期对账清理，TTL 仅作兜底。
     */
    private int taskPermitTtlSeconds = 300;

    /** 任务并发许可续租间隔（秒），应小于 {@link #taskPermitTtlSeconds} */
    private int taskPermitRenewSeconds = 60;

    /** 任务并发许可与 DB 对账间隔（毫秒） */
    private long taskPermitReconcileIntervalMs = 60_000L;

    /**
     * 实例心跳 TTL（秒）。对账时用其判断 claimed_by / AI holder 所属节点是否仍存活。
     * 应大于 {@link #taskPermitReconcileIntervalMs} 对应秒数。
     */
    private int instanceHeartbeatTtlSeconds = 90;

    /** 解析实际 lease 时长（分钟） */
    public int resolveTaskLeaseMinutes() {
        if (taskLeaseMinutes > 0) {
            return taskLeaseMinutes;
        }
        return Math.max(1, taskLeaseHours * 60);
    }
}
