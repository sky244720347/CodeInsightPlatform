package com.company.codeinsight.common.cluster;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 集群调度参数。
 * <p>{@code enabled} <b>不可外部配置</b>，由 {@link ClusterEnvAligner} 按 {@code code-insight.env} 推导：
 * dev=false，非 dev=true。</p>
 * <p>孤儿接管：无认领，或「租约过宽限 ∧ 认领方心跳已死」。
 * 禁止仅租约刚过期就抢；「仅心跳已死但租约仍有效」也不抢。</p>
 * <p>详见 docs/orphan-reclaim-lease-heartbeat-design.md。</p>
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
     * 任务认领 lease 时长（分钟）。流水线运行中按续租间隔延长。
     * <p>需明显大于续租间隔，以覆盖续租失败重试窗口。
     * 旧字段 {@link #taskLeaseHours} 仅作兼容，优先用本字段。</p>
     */
    private int taskLeaseMinutes = 30;

    /**
     * @deprecated 改用 {@link #taskLeaseMinutes}；若 {@code taskLeaseMinutes}≤0 时回退为 hours×60。
     */
    @Deprecated
    private int taskLeaseHours = 2;

    /**
     * 租约过期后的宽限（分钟）。{@code now > lease_until + grace} 才进入「租约失效可抢」窗口，
     * 再与心跳已死组合判定，避免刚过期立刻误抢。
     */
    private int taskLeaseGraceMinutes = 10;

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

    /** 任务并发许可与 DB 对账间隔（毫秒）；同时续写实例心跳 */
    private long taskPermitReconcileIntervalMs = 30_000L;

    /**
     * 实例心跳 TTL（秒）。对账时用其判断 claimed_by / AI holder 所属节点是否仍存活。
     * 应大于 {@link #taskPermitReconcileIntervalMs} 对应秒数；孤儿接管不再单靠心跳死判定。
     */
    private int instanceHeartbeatTtlSeconds = 180;

    /** 解析实际 lease 时长（分钟） */
    public int resolveTaskLeaseMinutes() {
        if (taskLeaseMinutes > 0) {
            return taskLeaseMinutes;
        }
        return Math.max(1, taskLeaseHours * 60);
    }

    /** 租约过期宽限（分钟），至少为 0 */
    public int resolveTaskLeaseGraceMinutes() {
        return Math.max(0, taskLeaseGraceMinutes);
    }
}
