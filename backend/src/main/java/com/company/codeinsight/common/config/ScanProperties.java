package com.company.codeinsight.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 定时 commit 轮询扫描配置（docs/scheduled-commit-poll-scan-plan.md）。
 * <p>{@code global-poll-enabled} / {@code force-full-on-unchanged} 等仅通过配置文件或阿波罗修改，
 * 无管理 API；tick 时直接读本 Bean。
 * {@code enabled}/{@code cron} 仅在 {@code ci_system_config} 尚无对应 key 时作 bootstrap；
 * 页面/API 写入后以库为准（Redis 经 {@code SystemConfigService} 只做读缓存）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "code-insight.scan")
public class ScanProperties {

    /** 调度总开关 bootstrap（库无 scan.scheduler.enabled 时） */
    private boolean enabled = true;

    /** cron bootstrap（库无 scan.scheduler.cron 时；6 段，含秒） */
    private String cron = "0 */5 * * * *";

    /**
     * true：全局 cron 扫全部远程仓；false：仅命中 {@code ci_scan_window} 的仓。
     */
    private boolean globalPollEnabled = true;

    /**
     * 验证开关：有基线且 HEAD 无变化时仍下发 INITIAL；关闭则跳过。
     */
    private boolean forceFullOnUnchanged = true;

    /**
     * 全局轮询时按自然日覆盖：已成功探测的仓当日不再重复；失败/超时不记完成，后续 tick 重试。
     */
    private boolean dailyCoverageEnabled = true;

    /** 单轮 ls-remote / 建任务并发（稳健优先，宜小） */
    private int pollConcurrency = 2;

    /** 单轮最多处理仓数 */
    private int pollBatchSize = 30;

    /** 单轮墙钟上限（秒）；到点后不再开新波次，但会等当前波次跑完，不强制 cancel */
    private int maxSweepSeconds = 300;

    /** 探测用 ls-remote 单次超时（秒）；与连通性探测超时独立，可更长 */
    private int probeTimeoutSeconds = 30;

    /** 单仓探测最大尝试次数（超时/不确定结论才重试） */
    private int probeMaxAttempts = 3;

    /** 探测重试退避基数（毫秒），实际等待 = backoff × attempt */
    private long probeRetryBackoffMs = 2000L;
}
