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
}
