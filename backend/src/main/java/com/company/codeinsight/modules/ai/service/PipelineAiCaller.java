package com.company.codeinsight.modules.ai.service;

import com.company.codeinsight.common.config.AiDocBudgetProperties;
import com.company.codeinsight.common.config.AiRetryProperties;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.task.service.TaskExecutionLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 流水线 AI 调用统一重试器：可配置次数、指数退避，并将重试/失败写入 pipeline.log。
 * <p>AI 并发抢槽轮询不计入 {@code maxAttempts}；仅真实调模型后的失败才消耗重试次数。</p>
 */
@Slf4j
@Component
public class PipelineAiCaller {

    @Autowired
    @Lazy
    private AiSummaryService aiSummaryService;

    @Autowired
    private AiRetryProperties retryProperties;

    @Autowired
    private AiDocBudgetProperties docBudgetProperties;

    @Autowired
    private TaskExecutionLogger execLog;

    /** 校验 AI 原始响应；成功时可返回规范化后的文本（如提取 JSON 后的 payload）。 */
    @FunctionalInterface
    public interface ResponseValidator {
        ValidationResult validate(String response);
    }

    /** 重试前按失败原因调整 prompt（可选）。 */
    @FunctionalInterface
    public interface PromptMutator {
        String mutate(String originalPrompt, String currentPrompt, int failedAttempt, String reason);
    }

    public record ValidationResult(boolean success, String normalizedResponse, String failureReason) {
        public static ValidationResult ok(String normalized) {
            return new ValidationResult(true, normalized, null);
        }

        public static ValidationResult fail(String reason) {
            return new ValidationResult(false, null, reason);
        }
    }

    /**
     * 带末次失败原因的调用结果，供调用方写入口级 / 篇章级结论日志。
     */
    public record CallOutcome(String response, String lastFailureReason, boolean success) {
        public static CallOutcome ok(String response) {
            return new CallOutcome(response, null, true);
        }

        public static CallOutcome fail(String lastFailureReason) {
            return new CallOutcome("{}", lastFailureReason != null ? lastFailureReason : "unknown", false);
        }

        public boolean hasPayload() {
            return success && StringUtils.hasText(response) && !"{}".equals(response.trim());
        }
    }

    /**
     * 带重试的 AI 调用；全部失败返回 {@code "{}"}（与 {@link AiSummaryService#summarizeWithPrompt} 失败语义一致）。
     */
    public String callWithRetry(Long taskId,
                                String stage,
                                String targetLabel,
                                String promptInput,
                                String modelName,
                                AiSummaryService.AiCallMeta callMeta,
                                ResponseValidator validator) {
        return callWithRetryOutcome(taskId, stage, targetLabel, promptInput, modelName, callMeta, validator, null)
                .response();
    }

    public String callWithRetry(Long taskId,
                                String stage,
                                String targetLabel,
                                String initialPrompt,
                                String modelName,
                                AiSummaryService.AiCallMeta callMeta,
                                ResponseValidator validator,
                                PromptMutator promptMutator) {
        return callWithRetryOutcome(taskId, stage, targetLabel, initialPrompt, modelName, callMeta, validator, promptMutator)
                .response();
    }

    /**
     * 同 {@link #callWithRetry}，额外返回末次失败原因，便于上层打入口级结论日志。
     */
    public CallOutcome callWithRetryOutcome(Long taskId,
                                            String stage,
                                            String targetLabel,
                                            String initialPrompt,
                                            String modelName,
                                            AiSummaryService.AiCallMeta callMeta,
                                            ResponseValidator validator,
                                            PromptMutator promptMutator) {
        if (taskId == null || !StringUtils.hasText(initialPrompt)) {
            return CallOutcome.fail("empty prompt or taskId");
        }
        int maxAttempts = retryProperties.resolveMaxAttempts(stage);
        long backoffMs = Math.max(0L, retryProperties.getBackoffMs());
        String stageTag = StringUtils.hasText(stage) ? stage : "AI";
        String target = StringUtils.hasText(targetLabel) ? targetLabel : "-";
        String currentPrompt = initialPrompt;
        String lastReason = "unknown";

        int acquireWaitSec = resolveAcquireWaitSeconds();
        long pollMs = resolveAcquirePollIntervalMs();
        long concurrencyDeadlineNs = System.nanoTime() + Duration.ofSeconds(acquireWaitSec).toNanos();
        boolean loggedConcurrencyWait = false;

        int attempt = 1;
        while (attempt <= maxAttempts) {
            try {
                String response = aiSummaryService.summarizeWithPrompt(
                        taskId, currentPrompt, modelName, callMeta);
                ValidationResult vr = validator.validate(response);
                if (vr.success()) {
                    if (attempt > 1) {
                        execLog.log(taskId, String.format(
                                "[AI-OK] stage=%s target=%s recovered on attempt %d/%d",
                                stageTag, target, attempt, maxAttempts));
                    }
                    String payload = StringUtils.hasText(vr.normalizedResponse()) ? vr.normalizedResponse() : response;
                    return CallOutcome.ok(payload);
                }
                lastReason = vr.failureReason();
            } catch (BusinessException e) {
                lastReason = e.getMessage();
                if (isNonRetryable(lastReason)) {
                    execLog.log(taskId, String.format(
                            "[AI-FAIL] stage=%s target=%s reason=%s (non-retryable)",
                            stageTag, target, truncateReason(lastReason)));
                    return CallOutcome.fail(lastReason);
                }
                // 抢槽失败：只轮询等槽，不消耗 attempt
                if (isConcurrencyLimit(lastReason)) {
                    long remainingNs = concurrencyDeadlineNs - System.nanoTime();
                    if (remainingNs <= 0) {
                        String timeoutMsg = "AI 调用并发等待超时（" + acquireWaitSec + "s），请稍后重试";
                        execLog.log(taskId, String.format(
                                "[AI-FAIL] stage=%s target=%s reason=%s (concurrency wait exhausted, attempts unused=%d/%d)",
                                stageTag, target, truncateReason(timeoutMsg), attempt, maxAttempts));
                        return CallOutcome.fail(timeoutMsg);
                    }
                    if (!loggedConcurrencyWait) {
                        loggedConcurrencyWait = true;
                        execLog.log(taskId, String.format(
                                "[AI-WAIT] stage=%s target=%s ai.concurrency busy; polling every %dms, maxWait=%ds (does not consume retry)",
                                stageTag, target, pollMs, acquireWaitSec));
                    }
                    sleepBackoff(Math.min(pollMs, Math.max(50L, TimeUnit.NANOSECONDS.toMillis(remainingNs))));
                    continue;
                }
            } catch (Exception e) {
                lastReason = e.getMessage();
                log.warn("Pipeline AI call exception stage={} target={} attempt={}/{}: {}",
                        stageTag, target, attempt, maxAttempts, lastReason);
            }

            if (attempt < maxAttempts) {
                execLog.log(taskId, String.format(
                        "[AI-RETRY] stage=%s target=%s attempt=%d/%d reason=%s",
                        stageTag, target, attempt, maxAttempts, truncateReason(lastReason)));
                sleepBackoff(backoffMs * attempt);
                if (promptMutator != null) {
                    currentPrompt = promptMutator.mutate(initialPrompt, currentPrompt, attempt, lastReason);
                }
            }
            attempt++;
        }

        execLog.log(taskId, String.format(
                "[AI-FAIL] stage=%s target=%s reason=%s after %d attempts",
                stageTag, target, truncateReason(lastReason), maxAttempts));
        return CallOutcome.fail(lastReason);
    }

    private int resolveAcquireWaitSeconds() {
        if (docBudgetProperties == null) {
            return 1800;
        }
        return Math.max(docBudgetProperties.getHttpTimeoutSeconds() * 2,
                Math.max(60, docBudgetProperties.getAcquireWaitSeconds()));
    }

    private long resolveAcquirePollIntervalMs() {
        if (docBudgetProperties == null) {
            return 5_000L;
        }
        long configured = docBudgetProperties.getAcquirePollIntervalMs();
        // ≤0：单测/显式关闭休眠；生产默认 5000，且至少 1s
        if (configured <= 0L) {
            return 0L;
        }
        return Math.max(1_000L, configured);
    }

    /**
     * 重试无意义的硬失败。
     * <p>「并发已达上限」在调用方循环内轮询，不走本方法。
     * 「并发等待超时」表示等槽预算已耗尽。</p>
     */
    private static boolean isNonRetryable(String reason) {
        if (!StringUtils.hasText(reason)) {
            return false;
        }
        return reason.contains("额度")
                || reason.contains("Token 消耗额度超限")
                || reason.contains("并发等待超时")
                || reason.contains("并发等待被中断");
    }

    private static boolean isConcurrencyLimit(String reason) {
        return StringUtils.hasText(reason)
                && reason.contains("并发已达上限")
                && !reason.contains("并发等待超时");
    }

    /**
     * 上下文过长：重试前应裁剪 prompt。
     * <p>不含纯超时（超时可先原样重试）。</p>
     */
    public static boolean isContextLengthFailure(String reason) {
        if (!StringUtils.hasText(reason)) {
            return false;
        }
        String r = reason.toLowerCase();
        return r.contains("context_length")
                || r.contains("context length")
                || r.contains("maximum context")
                || r.contains("too long")
                || r.contains("max tokens")
                || r.contains("token limit")
                || r.contains("prompt is too long")
                || r.contains("prompt_too_long")
                || r.contains("413")
                || reason.contains("上下文")
                || reason.contains("过长");
    }

    public static boolean isTimeoutFailure(String reason) {
        if (!StringUtils.hasText(reason)) {
            return false;
        }
        String r = reason.toLowerCase();
        return r.contains("timeout")
                || r.contains("timed out")
                || reason.contains("超时");
    }

    /**
     * @deprecated 请用 {@link #isContextLengthFailure} / {@link #isTimeoutFailure} 区分策略
     */
    public static boolean isContextOrTimeoutFailure(String reason) {
        return isContextLengthFailure(reason) || isTimeoutFailure(reason);
    }

    /**
     * 是否应在重试前裁剪源码：仅上下文超限；纯超时 / 空响应不据此裁剪。
     */
    public static boolean shouldShrinkOnFailure(String reason, int promptChars, int shrinkThresholdChars) {
        return isContextLengthFailure(reason);
    }

    public static String truncateReason(String text) {
        if (!StringUtils.hasText(text)) {
            return "unknown";
        }
        String t = text.replace('\n', ' ').trim();
        return t.length() <= 200 ? t : t.substring(0, 200) + "...";
    }

    private static void sleepBackoff(long waitMs) {
        if (waitMs <= 0) {
            return;
        }
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
