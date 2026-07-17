package com.company.codeinsight.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 运行环境开关：{@code code-insight.env}（{@code CODE_INSIGHT_ENV}）。
 * <p>{@code dev}：写死本机路径 + 单机；非 {@code dev}：三根路径必配 + 一律集群。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "code-insight")
public class CodeInsightEnvProperties {

    /** {@code dev} | {@code prod} | {@code staging} 等；默认 {@code dev} */
    private String env = "dev";

    public boolean isDev() {
        return env == null || env.isBlank() || "dev".equalsIgnoreCase(env.trim());
    }
}
