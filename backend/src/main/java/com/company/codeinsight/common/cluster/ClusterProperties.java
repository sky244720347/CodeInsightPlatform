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

    /** 任务认领后 lease 时长（小时），用于断点恢复亲和校验 */
    private int taskLeaseHours = 2;

    /** 草稿编辑锁 TTL（秒） */
    private int draftEditLockTtlSeconds = 120;

    /** 草稿编辑锁续期间隔（秒），前端应小于 TTL 周期性续租 */
    private int draftEditLockRenewSeconds = 60;
}
