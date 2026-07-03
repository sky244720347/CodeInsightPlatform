package com.company.codeinsight.modules.ai;

import com.company.codeinsight.common.config.AiRetryProperties;
import com.company.codeinsight.modules.ai.service.AiSummaryService;
import com.company.codeinsight.modules.ai.service.PipelineAiCaller;
import com.company.codeinsight.modules.task.service.TaskExecutionLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.StringUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipelineAiCallerTest {

    @Mock
    private AiSummaryService aiSummaryService;

    @Mock
    private AiRetryProperties retryProperties;

    @Mock
    private TaskExecutionLogger execLog;

    @InjectMocks
    private PipelineAiCaller pipelineAiCaller;

    @BeforeEach
    void setUp() {
        when(retryProperties.getMaxAttempts()).thenReturn(3);
        when(retryProperties.getBackoffMs()).thenReturn(0L);
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
}
