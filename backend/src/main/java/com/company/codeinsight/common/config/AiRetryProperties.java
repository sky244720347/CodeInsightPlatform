package com.company.codeinsight.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 流水线 AI 调用重试参数。层级提炼与文档生成次数已拆分。
 */
@Data
@Component
@ConfigurationProperties(prefix = "code-insight.ai.retry")
public class AiRetryProperties {

    /**
     * 模块层级（MODULE_HIERARCHY）单次逻辑调用最大尝试次数（含首次）。默认 3。
     */
    private int hierarchyMaxAttempts = 3;

    /**
     * 文档生成（FUNCTION_DOC / MODULE_DOC）单次逻辑调用最大尝试次数（含首次）。默认 5。
     */
    private int docMaxAttempts = 5;

    /**
     * 相邻两次重试之间的基础退避毫秒数；实际等待 = backoffMs × 当前 attempt 序号。
     */
    private long backoffMs = 1000L;

    /**
     * 并发槽位不足时的退避基数（毫秒）；实际等待 = concurrencyBackoffMs × attempt 序号。
     */
    private long concurrencyBackoffMs = 2000L;

    public int resolveMaxAttempts(String stage) {
        if (isDocStage(stage)) {
            return Math.max(1, docMaxAttempts);
        }
        return Math.max(1, hierarchyMaxAttempts);
    }

    private static boolean isDocStage(String stage) {
        if (!StringUtils.hasText(stage)) {
            return false;
        }
        String s = stage.trim().toUpperCase();
        return s.contains("DOC") || "FUNCTION_DOC".equals(s) || "MODULE_DOC".equals(s) || "GENERATING_DOC".equals(s);
    }
}
