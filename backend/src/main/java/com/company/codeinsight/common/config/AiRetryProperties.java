package com.company.codeinsight.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 流水线 AI 调用重试参数。层级提炼与文档生成次数已拆分；
 * {@link #unlimitedAttempts} 打开时两者均不限次（仍受取消 / 不可恢复错误约束）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "code-insight.ai.retry")
public class AiRetryProperties {

    /**
     * 模块层级（MODULE_HIERARCHY）单次逻辑调用最大尝试次数（含首次）。默认 3。
     * {@link #unlimitedAttempts}=true 时忽略。
     */
    private int hierarchyMaxAttempts = 3;

    /**
     * 文档生成（FUNCTION_DOC / MODULE_DOC）单次逻辑调用最大尝试次数（含首次）。默认 5。
     * {@link #unlimitedAttempts}=true 时忽略。
     */
    private int docMaxAttempts = 5;

    /**
     * 相邻两次重试之间的基础退避毫秒数；实际等待 = backoffMs × 当前 attempt 序号
     * （不限次时另受 {@link #unlimitedBackoffCapMs} 封顶）。
     */
    private long backoffMs = 1000L;

    /**
     * 并发槽位不足时的退避基数（毫秒）；实际等待 = concurrencyBackoffMs × attempt 序号。
     */
    private long concurrencyBackoffMs = 2000L;

    /**
     * 为 true 时，层级与文档经 {@code PipelineAiCaller} 的重试不设次数上限。
     * 用户终止与额度等不可恢复错误仍立即停止。
     */
    private boolean unlimitedAttempts = false;

    /**
     * 不限次模式下单次退避上限（毫秒），避免 attempt 增大后睡眠过长。默认 60s。
     */
    private long unlimitedBackoffCapMs = 60_000L;

    /**
     * 有限次模式下按 stage 解析上限（至少 1）。
     * 不限次时请用 {@link #isUnlimitedAttempts()}，勿依赖本方法当作「假上限」。
     */
    public int resolveMaxAttempts(String stage) {
        if (isDocStage(stage)) {
            return Math.max(1, docMaxAttempts);
        }
        return Math.max(1, hierarchyMaxAttempts);
    }

    /** 计算本轮退避等待；不限次时封顶。 */
    public long resolveBackoffWaitMs(int attempt, boolean concurrencyFailure) {
        int n = Math.max(1, attempt);
        long base = concurrencyFailure
                ? Math.max(0L, concurrencyBackoffMs)
                : Math.max(0L, backoffMs);
        long raw = base * n;
        if (unlimitedAttempts) {
            long cap = Math.max(0L, unlimitedBackoffCapMs);
            if (cap > 0) {
                return Math.min(raw, cap);
            }
        }
        return raw;
    }

    private static boolean isDocStage(String stage) {
        if (!StringUtils.hasText(stage)) {
            return false;
        }
        String s = stage.trim().toUpperCase();
        return s.contains("DOC") || "FUNCTION_DOC".equals(s) || "MODULE_DOC".equals(s) || "GENERATING_DOC".equals(s);
    }
}
