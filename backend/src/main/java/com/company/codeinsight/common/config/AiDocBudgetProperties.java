package com.company.codeinsight.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文档生成阶段的 prompt 预算与 HTTP 超时（方案 A：单次调用内裁剪）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "code-insight.ai.doc")
public class AiDocBudgetProperties {

    /**
     * 整段 prompt 字符目标上限：仅在「上下文超限」失败后裁剪时使用（首调不预裁）。
     */
    private int maxPromptChars = 100_000;

    /**
     * 单个 // === Class === 块正文上限；超出截断并标注 truncated。
     */
    private int maxMethodBodyChars = 12_000;

    /**
     * AI HTTP 请求超时（秒）。文档生成偏长，默认 120（原硬编码 45）。
     */
    private int httpTimeoutSeconds = 120;

    /**
     * 重试时每次将源码预算缩到当前的该比例（相对「上一次源码长度」或配置上限）。
     */
    private double shrinkFactor = 0.5;
}
