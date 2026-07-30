package com.company.codeinsight.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 可执行知识生成的技术栈白名单（配置文件 / 环境变量；后续可接阿波罗同名 key）。
 * <p>值与目录 label 一致，逗号分隔，例如 {@code Java} 或 {@code Java,Python}。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "code-insight.task")
public class TaskTechStackProperties {

    /**
     * 可下发任务的技术栈白名单（逗号分隔，精确匹配目录 code/label）。
     */
    private String supportedTechStacks = "Java";

    /** 解析后的白名单集合（去空白、去空项，保序） */
    public Set<String> supportedSet() {
        if (!StringUtils.hasText(supportedTechStacks)) {
            return Collections.emptySet();
        }
        return Arrays.stream(supportedTechStacks.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean isSupported(String techStack) {
        if (!StringUtils.hasText(techStack)) {
            return false;
        }
        return supportedSet().contains(techStack.trim());
    }
}
