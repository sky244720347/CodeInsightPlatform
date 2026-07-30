package com.company.codeinsight.modules.ai.service;

import com.company.codeinsight.common.config.AiRetryProperties;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.exception.TaskCancelledException;
import com.company.codeinsight.modules.task.service.TaskCancellationRegistry;
import com.company.codeinsight.modules.task.service.TaskExecutionLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 流水线 AI 调用统一重试器：可配置次数、指数退避，并将重试/失败写入 pipeline.log。
 * <p>AI 等槽仅在 {@link AiSummaryService} 内层实现；本类对并发类异常按普通失败消耗 attempt 重试。</p>
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
    private TaskExecutionLogger execLog;

    @Autowired
    private TaskCancellationRegistry cancellationRegistry;

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

        int attempt = 1;
        while (attempt <= maxAttempts) {
            if (cancellationRegistry != null && cancellationRegistry.isCancelled(taskId)) {
                execLog.log(taskId, String.format(
                        "[AI-CANCEL] stage=%s target=%s before attempt %d/%d",
                        stageTag, target, attempt, maxAttempts));
                throw new TaskCancelledException(taskId);
            }
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
            } catch (TaskCancelledException e) {
                execLog.log(taskId, String.format(
                        "[AI-CANCEL] stage=%s target=%s reason=%s",
                        stageTag, target, truncateReason(e.getMessage())));
                throw e;
            } catch (BusinessException e) {
                if (TaskCancelledException.isCancellation(e)) {
                    throw e instanceof TaskCancelledException
                            ? (TaskCancelledException) e
                            : new TaskCancelledException(taskId);
                }
                lastReason = e.getMessage();
                if (isNonRetryable(lastReason)) {
                    execLog.log(taskId, String.format(
                            "[AI-FAIL] stage=%s target=%s reason=%s (non-retryable)",
                            stageTag, target, truncateReason(lastReason)));
                    return CallOutcome.fail(lastReason);
                }
                // 并发类（已达上限 / 等待超时 / 被中断）：与其它可恢复失败一样消耗 attempt
            } catch (Exception e) {
                if (TaskCancelledException.isCancellation(e)) {
                    throw new TaskCancelledException(taskId);
                }
                lastReason = e.getMessage();
                log.warn("Pipeline AI call exception stage={} target={} attempt={}/{}: {}",
                        stageTag, target, attempt, maxAttempts, lastReason);
            }

            if (attempt < maxAttempts) {
                if (cancellationRegistry != null && cancellationRegistry.isCancelled(taskId)) {
                    throw new TaskCancelledException(taskId);
                }
                String tag = isConcurrencyFailure(lastReason) ? "[AI-CONCURRENCY]" : "[AI-RETRY]";
                execLog.log(taskId, String.format(
                        "%s stage=%s target=%s attempt=%d/%d reason=%s",
                        tag, stageTag, target, attempt, maxAttempts, truncateReason(lastReason)));
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

    /**
     * 重试无意义的硬失败（仅额度）。并发等待由内层负责，外层按普通重试处理。
     */
    private static boolean isNonRetryable(String reason) {
        if (!StringUtils.hasText(reason)) {
            return false;
        }
        return reason.contains("额度")
                || reason.contains("Token 消耗额度超限");
    }

    /** 并发槽相关失败：打 [AI-CONCURRENCY] 日志，仍消耗 attempt。 */
    public static boolean isConcurrencyFailure(String reason) {
        if (!StringUtils.hasText(reason)) {
            return false;
        }
        return reason.contains("并发已达上限")
                || reason.contains("并发等待超时")
                || reason.contains("并发等待被中断");
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
