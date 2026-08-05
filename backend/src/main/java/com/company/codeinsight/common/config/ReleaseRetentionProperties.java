package com.company.codeinsight.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识 release 保留近 N 版清理配置。
 * <p>详见 docs/release-retention-prune-plan.md。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "code-insight.publish")
public class ReleaseRetentionProperties {

    /** 每仓保留最近 N 个 PUSHED 版本（含生效指针兜底） */
    private int releaseKeepCount = 3;

    /** 单个目录删盘最大 attempt（含首次） */
    private int releasePruneMaxAttempts = 3;

    /** 删盘重试退避基数（毫秒）；等待 = backoff × attempt */
    private long releasePruneBackoffMs = 2000L;

    /** 是否启用扫盘对账 */
    private boolean releasePruneReconcileEnabled = true;

    /** 对账间隔（毫秒），默认 1h */
    private long releasePruneReconcileIntervalMs = 3_600_000L;
}
