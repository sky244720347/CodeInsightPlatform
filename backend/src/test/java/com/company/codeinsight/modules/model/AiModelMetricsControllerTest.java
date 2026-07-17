package com.company.codeinsight.modules.model;

import com.company.codeinsight.common.response.ApiResponse;
import com.company.codeinsight.modules.model.controller.AiModelController;
import com.company.codeinsight.modules.model.dto.AiModelMetricSummary;
import com.company.codeinsight.modules.model.dto.AiModelMetricTrendPoint;
import com.company.codeinsight.modules.model.entity.AiModel;
import com.company.codeinsight.modules.token.entity.TokenUsageAudit;
import com.company.codeinsight.modules.token.mapper.TokenUsageAuditMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@ActiveProfiles("test")
@Transactional
public class AiModelMetricsControllerTest {

    @Autowired
    private AiModelController aiModelController;

    @Autowired
    private TokenUsageAuditMapper tokenUsageAuditMapper;

    @Test
    public void testModelMetricsSummaryAndTrend() {
        AiModel model = new AiModel();
        model.setName("Metrics Model");
        model.setIdentifier("metrics-model");
        model.setProvider("TestProvider");
        model.setApiKey("metrics-key");
        model.setBaseUrl("https://api.example.com");
        model.setIsDefault("false");
        model.setCapabilities("text");
        model.setDescription("Metrics model");
        model.setSortOrder(90);

        ApiResponse<AiModel> createResponse = aiModelController.createModel(model);
        Assertions.assertEquals(0, createResponse.getCode());

        insertAudit("metrics-model", 100, "0.0200", LocalDateTime.now().minusDays(1));
        insertAudit("metrics-model", 50, "0.0100", LocalDateTime.now());
        insertAudit("other-model", 999, "9.9900", LocalDateTime.now());

        ApiResponse<List<AiModelMetricSummary>> summaryResponse = aiModelController.listModelMetricSummaries();

        AiModelMetricSummary summary = summaryResponse.getData().stream()
            .filter(item -> "metrics-model".equals(item.getModelName()))
            .findFirst()
            .orElseThrow();
        Assertions.assertEquals(2L, summary.getTotalCalls());
        Assertions.assertEquals(150L, summary.getTotalTokens());
        Assertions.assertEquals(0, new BigDecimal("0.0300").compareTo(summary.getTotalCost()));

        ApiResponse<List<AiModelMetricTrendPoint>> trendResponse =
            aiModelController.getModelMetricTrend(createResponse.getData().getId(), 7);

        Assertions.assertEquals(7, trendResponse.getData().size());
        long trendCalls = trendResponse.getData().stream().mapToLong(AiModelMetricTrendPoint::getCalls).sum();
        long trendTokens = trendResponse.getData().stream().mapToLong(AiModelMetricTrendPoint::getTokens).sum();
        Assertions.assertEquals(2L, trendCalls);
        Assertions.assertEquals(150L, trendTokens);
    }

    private void insertAudit(String modelName, int totalTokens, String cost, LocalDateTime createdAt) {
        TokenUsageAudit audit = new TokenUsageAudit();
        audit.setSystemId(0L);
        audit.setTaskId(0L);
        audit.setPromptVersion(1);
        audit.setModelName(modelName);
        audit.setInputTokens(totalTokens / 2);
        audit.setOutputTokens(totalTokens - audit.getInputTokens());
        audit.setTotalTokens(totalTokens);
        audit.setCost(new BigDecimal(cost));
        audit.setType("TEST");
        audit.setStatus(1);
        audit.setCreatedDate(createdAt);
        tokenUsageAuditMapper.insert(audit);
    }
}
