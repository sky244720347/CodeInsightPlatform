package com.company.codeinsight.modules.ai.service;

import com.company.codeinsight.common.config.AiRetryProperties;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.task.service.TaskExecutionLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 流水线 AI 调用统一重试器：可配置次数、指数退避，并将重试/失败写入 pipeline.log。
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
     * 带重试的 AI 调用；全部失败返回 {@code "{}"}（与 {@link AiSummaryService#summarizeWithPrompt} 失败语义一致）。
     */
    public String callWithRetry(Long taskId,
                                String stage,
                                String targetLabel,
                                String promptInput,
                                String modelName,
                                AiSummaryService.AiCallMeta callMeta,
                                ResponseValidator validator) {
        return callWithRetry(taskId, stage, targetLabel, promptInput, modelName, callMeta, validator, null);
    }

    public String callWithRetry(Long taskId,
                                String stage,
                                String targetLabel,
                                String initialPrompt,
                                String modelName,
                                AiSummaryService.AiCallMeta callMeta,
                                ResponseValidator validator,
                                PromptMutator promptMutator) {
        if (taskId == null || !StringUtils.hasText(initialPrompt)) {
            return "{}";
        }
        int maxAttempts = Math.max(1, retryProperties.getMaxAttempts());
        long backoffMs = Math.max(0L, retryProperties.getBackoffMs());
        long concurrencyBackoffMs = Math.max(0L, retryProperties.getConcurrencyBackoffMs());
        String stageTag = StringUtils.hasText(stage) ? stage : "AI";
        String target = StringUtils.hasText(targetLabel) ? targetLabel : "-";
        String currentPrompt = initialPrompt;
        String lastReason = "unknown";

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String response = aiSummaryService.summarizeWithPrompt(
                        taskId, currentPrompt, modelName, callMeta);
                // TEMP: 排查空响应 / 截断 JSON 用，确认后删除
                boolean sentinel = response != null && "{}".equals(response.trim());
                log.warn("[AI-DEBUG-TEMP] stage={} target={} attempt={}/{} len={} sentinel={} resp=\n{}",
                        stageTag, target, attempt, maxAttempts,
                        response == null ? -1 : response.length(), sentinel, response);
                execLog.log(taskId, String.format(
                        "[AI-DEBUG-TEMP] stage=%s target=%s attempt=%d/%d len=%d sentinel=%s resp=\n%s",
                        stageTag, target, attempt, maxAttempts,
                        response == null ? -1 : response.length(),
                        sentinel,
                        response == null ? "null" : response));
                ValidationResult vr = validator.validate(response);
                if (vr.success()) {
                    if (attempt > 1) {
                        execLog.log(taskId, String.format(
                                "[AI-OK] stage=%s target=%s recovered on attempt %d/%d",
                                stageTag, target, attempt, maxAttempts));
                    }
                    return StringUtils.hasText(vr.normalizedResponse()) ? vr.normalizedResponse() : response;
                }
                lastReason = vr.failureReason();
            } catch (BusinessException e) {
                lastReason = e.getMessage();
                if (isNonRetryable(lastReason)) {
                    execLog.log(taskId, String.format(
                            "[AI-FAIL] stage=%s target=%s reason=%s (non-retryable)",
                            stageTag, target, truncate(lastReason)));
                    return "{}";
                }
            } catch (Exception e) {
                lastReason = e.getMessage();
                log.warn("Pipeline AI call exception stage={} target={} attempt={}/{}: {}",
                        stageTag, target, attempt, maxAttempts, lastReason);
            }

            if (attempt < maxAttempts) {
                execLog.log(taskId, String.format(
                        "[AI-RETRY] stage=%s target=%s attempt=%d/%d reason=%s",
                        stageTag, target, attempt, maxAttempts, truncate(lastReason)));
                long waitMs = isConcurrencyLimit(lastReason)
                        ? concurrencyBackoffMs * attempt
                        : backoffMs * attempt;
                sleepBackoff(waitMs);
                if (promptMutator != null) {
                    currentPrompt = promptMutator.mutate(initialPrompt, currentPrompt, attempt, lastReason);
                }
            }
        }

        execLog.log(taskId, String.format(
                "[AI-FAIL] stage=%s target=%s reason=%s after %d attempts",
                stageTag, target, truncate(lastReason), maxAttempts));
        return "{}";
    }

    /** 额度 / Token 硬限制：重试无意义。并发槽位不足可重试，不在此列。 */
    private static boolean isNonRetryable(String reason) {
        if (!StringUtils.hasText(reason)) {
            return false;
        }
        return reason.contains("额度")
                || reason.contains("Token 消耗额度超限");
    }

    private static boolean isConcurrencyLimit(String reason) {
        return StringUtils.hasText(reason) && reason.contains("并发已达上限");
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

    private static String truncate(String text) {
        if (!StringUtils.hasText(text)) {
            return "unknown";
        }
        String t = text.replace('\n', ' ').trim();
        return t.length() <= 200 ? t : t.substring(0, 200) + "...";
    }
}
