package com.company.codeinsight.modules.parser.config;

import com.company.codeinsight.modules.parser.service.JavaParserService;
import com.company.codeinsight.modules.parser.service.impl.AstJavaParserService;
import com.company.codeinsight.modules.parser.service.impl.FallbackJavaParserService;
import com.company.codeinsight.modules.parser.service.impl.RegexJavaParserService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Java 解析引擎装配配置。
 *
 * <p>通过 {@code code-insight.parser.engine}（或顶层 {@code parser.engine}）配置项选择实现：
 * <ul>
 *   <li>{@code ast-fallback-regex}（默认）：先 AST，失败无感降级到 REGEX</li>
 *   <li>{@code ast}：纯 AST（不兜底，异常会向上抛）</li>
 *   <li>{@code regex}：纯正则（保留原有行为，用于排障）</li>
 * </ul>
 *
 * <p>Pipeline 上下游模块继续 @Autowired JavaParserService，由本配置按开关决定实际注入哪个实现。</p>
 */
@Configuration
public class ParserEngineConfig {

    private static final String ENGINE_PROP = "code-insight.parser.engine";

    @Bean
    @ConditionalOnProperty(name = ENGINE_PROP, havingValue = "ast-fallback-regex", matchIfMissing = true)
    public JavaParserService fallbackJavaParserService() {
        return new FallbackJavaParserService(new AstJavaParserService(), new RegexJavaParserService());
    }

    @Bean
    @ConditionalOnProperty(name = ENGINE_PROP, havingValue = "ast")
    public JavaParserService astJavaParserService() {
        return new AstJavaParserService();
    }

    @Bean
    @ConditionalOnProperty(name = ENGINE_PROP, havingValue = "regex")
    public JavaParserService regexJavaParserService() {
        return new RegexJavaParserService();
    }
}
