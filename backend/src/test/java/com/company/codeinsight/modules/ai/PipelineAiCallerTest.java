package com.company.codeinsight.modules.ai;

import com.company.codeinsight.common.config.AiRetryProperties;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.exception.TaskCancelledException;
import com.company.codeinsight.modules.ai.service.AiSummaryService;
import com.company.codeinsight.modules.ai.service.PipelineAiCaller;
import com.company.codeinsight.modules.task.service.TaskCancellationRegistry;
import com.company.codeinsight.modules.task.service.TaskExecutionLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.StringUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class PipelineAiCallerTest {

    @Mock
    private AiSummaryService aiSummaryService;

    @Mock
    private AiRetryProperties retryProperties;

    @Mock
    private TaskExecutionLogger execLog;

    @Mock
    private TaskCancellationRegistry cancellationRegistry;

    @InjectMocks
    private PipelineAiCaller pipelineAiCaller;

    @BeforeEach
    void setUp() {
        lenient().when(retryProperties.resolveMaxAttempts(any())).thenReturn(3);
        lenient().when(retryProperties.getBackoffMs()).thenReturn(0L);
        lenient().when(retryProperties.getConcurrencyBackoffMs()).thenReturn(0L);
        lenient().when(cancellationRegistry.isCancelled(any())).thenReturn(false);
    }

    @Test
    void succeedsOnSecondAttemptAfterEmptyResponse() {
        when(aiSummaryService.summarizeWithPrompt(eq(1L), anyString(), anyString(), any()))
                .thenReturn("{}")
                .thenReturn("{\"modules\":[]}");

        String result = pipelineAiCaller.callWithRetry(
                1L,
                "MODULE_HIERARCHY",
                "com.example.FooController",
                "prompt",
                "test-model",
                new AiSummaryService.AiCallMeta(),
                response -> {
                    if (!StringUtils.hasText(response) || "{}".equals(response.trim())) {
                        return PipelineAiCaller.ValidationResult.fail("empty");
                    }
                    return PipelineAiCaller.ValidationResult.ok(response);
                },
                null
        );

        assertEquals("{\"modules\":[]}", result);
        verify(aiSummaryService, times(2)).summarizeWithPrompt(eq(1L), anyString(), anyString(), any());
        verify(execLog).log(eq(1L), argThat(msg -> msg.contains("[AI-RETRY]")));
        verify(execLog).log(eq(1L), argThat(msg -> msg.contains("[AI-OK]")));
    }

    @Test
    void returnsEmptyAfterAllAttemptsFail() {
        when(aiSummaryService.summarizeWithPrompt(eq(2L), anyString(), anyString(), any()))
                .thenReturn("{}");

        String result = pipelineAiCaller.callWithRetry(
                2L,
                "FUNCTION_DOC",
                "账号登录",
                "prompt",
                "test-model",
                new AiSummaryService.AiCallMeta(),
                response -> PipelineAiCaller.ValidationResult.fail("empty"),
                null
        );

        assertEquals("{}", result);
        verify(aiSummaryService, times(3)).summarizeWithPrompt(eq(2L), anyString(), anyString(), any());
        verify(execLog).log(eq(2L), argThat(msg -> msg.contains("[AI-FAIL]")));
    }

    @Test
    void concurrencyLimitConsumesRetryAttempts() {
        when(aiSummaryService.summarizeWithPrompt(eq(3L), anyString(), anyString(), any()))
                .thenThrow(new BusinessException("AI 调用并发已达上限，请稍后重试"))
                .thenThrow(new BusinessException("AI 调用并发已达上限，请稍后重试"))
                .thenReturn("{\"modules\":[]}");

        String result = pipelineAiCaller.callWithRetry(
                3L,
                "MODULE_HIERARCHY",
                "com.example.BarController",
                "prompt",
                "test-model",
                new AiSummaryService.AiCallMeta(),
                response -> PipelineAiCaller.ValidationResult.ok(response),
                null
        );

        assertEquals("{\"modules\":[]}", result);
        // 方案 B：2 次并发失败消耗 attempt，第 3 次成功
        verify(aiSummaryService, times(3)).summarizeWithPrompt(eq(3L), anyString(), anyString(), any());
        verify(execLog, times(2)).log(eq(3L), argThat(msg -> msg.contains("[AI-CONCURRENCY]")));
        verify(execLog).log(eq(3L), argThat(msg -> msg.contains("[AI-OK]")));
    }

    @Test
    void doesNotRetryOnQuotaExceeded() {
        when(aiSummaryService.summarizeWithPrompt(eq(4L), anyString(), anyString(), any()))
                .thenThrow(new BusinessException("Token 消耗额度超限"));

        String result = pipelineAiCaller.callWithRetry(
                4L,
                "MODULE_HIERARCHY",
                "com.example.BazController",
                "prompt",
                "test-model",
                new AiSummaryService.AiCallMeta(),
                response -> PipelineAiCaller.ValidationResult.ok(response),
                null
        );

        assertEquals("{}", result);
        verify(aiSummaryService, times(1)).summarizeWithPrompt(eq(4L), anyString(), anyString(), any());
        verify(execLog).log(eq(4L), argThat(msg -> msg.contains("non-retryable")));
    }

    @Test
    void classifiesContextAndTimeoutFailures() {
        org.junit.jupiter.api.Assertions.assertTrue(
                PipelineAiCaller.isContextLengthFailure("HTTP 400: context_length_exceeded"));
        org.junit.jupiter.api.Assertions.assertTrue(
                PipelineAiCaller.isTimeoutFailure("java.net.http.HttpTimeoutException: request timed out"));
        org.junit.jupiter.api.Assertions.assertFalse(
                PipelineAiCaller.isContextLengthFailure("structure: 缺少章节"));
        org.junit.jupiter.api.Assertions.assertFalse(
                PipelineAiCaller.shouldShrinkOnFailure("empty response", 80_000, 50_000));
        org.junit.jupiter.api.Assertions.assertTrue(
                PipelineAiCaller.shouldShrinkOnFailure("HTTP 400: context_length_exceeded", 80_000, 50_000));
        org.junit.jupiter.api.Assertions.assertTrue(
                PipelineAiCaller.isConcurrencyFailure("AI 调用并发等待被中断"));
        org.junit.jupiter.api.Assertions.assertTrue(
                PipelineAiCaller.isConcurrencyFailure("AI 调用并发等待超时（1800s），请稍后重试"));
    }

    @Test
    void retriesWithRealHttpErrorReasonInLog() {
        when(aiSummaryService.summarizeWithPrompt(eq(5L), anyString(), anyString(), any()))
                .thenThrow(new BusinessException("HTTP 400: {\"error\":{\"code\":\"context_length_exceeded\"}}"))
                .thenReturn("## 一、\n## 二、\n## 三、\n## 四、\n## 五、\n## 六、\n");

        String result = pipelineAiCaller.callWithRetry(
                5L,
                "FUNCTION_DOC",
                "白名单查询",
                "prompt",
                "test-model",
                new AiSummaryService.AiCallMeta(),
                response -> {
                    if (!StringUtils.hasText(response) || "{}".equals(response.trim())) {
                        return PipelineAiCaller.ValidationResult.fail("empty response");
                    }
                    return PipelineAiCaller.ValidationResult.ok(response);
                },
                null
        );

        org.junit.jupiter.api.Assertions.assertTrue(result.contains("一、"));
        verify(execLog).log(eq(5L), argThat(msg ->
                msg.contains("[AI-RETRY]") && msg.contains("context_length_exceeded")));
        verify(execLog).log(eq(5L), argThat(msg -> msg.contains("[AI-OK]")));
    }

    @Test
    void retriesOnConcurrencyWaitTimeoutConsumingAttempts() {
        when(aiSummaryService.summarizeWithPrompt(eq(7L), anyString(), anyString(), any()))
                .thenThrow(new BusinessException("AI 调用并发等待超时（1800s），请稍后重试"));

        PipelineAiCaller.CallOutcome outcome = pipelineAiCaller.callWithRetryOutcome(
                7L,
                "MODULE_HIERARCHY",
                "com.example.WaitTimeout",
                "prompt",
                "test-model",
                new AiSummaryService.AiCallMeta(),
                response -> PipelineAiCaller.ValidationResult.ok(response),
                null
        );

        org.junit.jupiter.api.Assertions.assertFalse(outcome.hasPayload());
        org.junit.jupiter.api.Assertions.assertTrue(outcome.lastFailureReason().contains("并发等待超时"));
        verify(aiSummaryService, times(3)).summarizeWithPrompt(eq(7L), anyString(), anyString(), any());
        verify(execLog, times(2)).log(eq(7L), argThat(msg -> msg.contains("[AI-CONCURRENCY]")));
        verify(execLog).log(eq(7L), argThat(msg -> msg.contains("[AI-FAIL]") && msg.contains("after 3 attempts")));
        verify(execLog, never()).log(eq(7L), argThat(msg -> msg.contains("non-retryable")));
    }

    @Test
    void retriesOnConcurrencyWaitInterrupted() {
        when(aiSummaryService.summarizeWithPrompt(eq(8L), anyString(), anyString(), any()))
                .thenThrow(new BusinessException("AI 调用并发等待被中断"))
                .thenReturn("{\"modules\":[]}");

        PipelineAiCaller.CallOutcome outcome = pipelineAiCaller.callWithRetryOutcome(
                8L,
                "MODULE_HIERARCHY",
                "com.example.Interrupted",
                "prompt",
                "test-model",
                new AiSummaryService.AiCallMeta(),
                response -> PipelineAiCaller.ValidationResult.ok(response),
                null
        );

        org.junit.jupiter.api.Assertions.assertTrue(outcome.hasPayload());
        verify(aiSummaryService, times(2)).summarizeWithPrompt(eq(8L), anyString(), anyString(), any());
        verify(execLog).log(eq(8L), argThat(msg ->
                msg.contains("[AI-CONCURRENCY]") && msg.contains("并发等待被中断")));
        verify(execLog).log(eq(8L), argThat(msg -> msg.contains("[AI-OK]")));
    }

    @Test
    void callWithRetryOutcomeExposesLastFailureReason() {
        when(aiSummaryService.summarizeWithPrompt(eq(6L), anyString(), anyString(), any()))
                .thenThrow(new BusinessException("HTTP 400: context_length_exceeded"));

        PipelineAiCaller.CallOutcome outcome = pipelineAiCaller.callWithRetryOutcome(
                6L,
                "MODULE_HIERARCHY",
                "com.example.Foo",
                "prompt",
                "test-model",
                new AiSummaryService.AiCallMeta(),
                response -> PipelineAiCaller.ValidationResult.ok(response),
                null
        );

        org.junit.jupiter.api.Assertions.assertFalse(outcome.success());
        org.junit.jupiter.api.Assertions.assertFalse(outcome.hasPayload());
        org.junit.jupiter.api.Assertions.assertTrue(
                outcome.lastFailureReason().contains("context_length_exceeded"));
    }

    @Test
    void cancelledBeforeAttempt_doesNotRetry() {
        when(cancellationRegistry.isCancelled(eq(77L))).thenReturn(true);

        assertThrows(TaskCancelledException.class, () -> pipelineAiCaller.callWithRetry(
                77L,
                "MODULE_HIERARCHY",
                "com.example.Foo",
                "prompt",
                "test-model",
                new AiSummaryService.AiCallMeta(),
                response -> PipelineAiCaller.ValidationResult.ok(response),
                null
        ));

        verify(aiSummaryService, never()).summarizeWithPrompt(any(), any(), any(), any());
        verify(execLog).log(eq(77L), argThat(msg -> msg.contains("[AI-CANCEL]")));
    }

    @Test
    void cancelledDuringCall_doesNotRetry() {
        when(aiSummaryService.summarizeWithPrompt(eq(78L), anyString(), anyString(), any()))
                .thenThrow(new TaskCancelledException(78L));

        assertThrows(TaskCancelledException.class, () -> pipelineAiCaller.callWithRetry(
                78L,
                "FUNCTION_DOC",
                "登录",
                "prompt",
                "test-model",
                new AiSummaryService.AiCallMeta(),
                response -> PipelineAiCaller.ValidationResult.ok(response),
                null
        ));

        verify(aiSummaryService, times(1)).summarizeWithPrompt(eq(78L), anyString(), anyString(), any());
    }
}
