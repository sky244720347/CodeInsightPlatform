package com.company.codeinsight.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 流水线 AI 调用重试参数（模块提取 / 文档生成等共用）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "code-insight.ai.retry")
public class AiRetryProperties {

    /**
     * 单次逻辑 AI 调用的最大尝试次数（含首次）。默认 3。
     */
    private int maxAttempts = 3;

    /**
     * 相邻两次重试之间的基础退避毫秒数；实际等待 = backoffMs × 当前 attempt 序号。
     */
    private long backoffMs = 1000L;

    /**
     * 并发槽位不足时的退避基数（毫秒）；实际等待 = concurrencyBackoffMs × attempt 序号。
     */
    private long concurrencyBackoffMs = 2000L;
}
