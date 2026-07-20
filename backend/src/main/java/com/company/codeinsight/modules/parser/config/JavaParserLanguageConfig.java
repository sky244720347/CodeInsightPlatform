package com.company.codeinsight.modules.parser.config;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;

/**
 * 统一 JavaParser 语言级别：与平台 JDK 17 对齐，覆盖 Text Block / record / sealed 等语法。
 *
 * <p>{@link StaticJavaParser} 的配置是 ThreadLocal，每个工作线程在首次解析前都应调用
 * {@link #ensureStaticJavaParserConfigured()}。</p>
 */
public final class JavaParserLanguageConfig {

    /** 文档与实现约定的目标语言级别 */
    public static final ParserConfiguration.LanguageLevel TARGET =
            ParserConfiguration.LanguageLevel.JAVA_17;

    private JavaParserLanguageConfig() {
    }

    /** 将给定配置升到 {@link #TARGET} 后返回同一实例（便于链式调用）。 */
    public static ParserConfiguration apply(ParserConfiguration cfg) {
        if (cfg == null) {
            cfg = new ParserConfiguration();
        }
        return cfg.setLanguageLevel(TARGET);
    }

    /**
     * 幂等：把当前线程的 {@link StaticJavaParser} 配置升到 {@link #TARGET}。
     */
    public static void ensureStaticJavaParserConfigured() {
        ParserConfiguration cfg = StaticJavaParser.getParserConfiguration();
        if (cfg.getLanguageLevel() != TARGET) {
            cfg.setLanguageLevel(TARGET);
        }
    }
}
