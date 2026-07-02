package com.company.codeinsight.modules.parser.service.impl;

import com.company.codeinsight.modules.parser.model.ParsedClassInfo;
import com.company.codeinsight.modules.parser.service.JavaParserService;
import com.github.javaparser.ParseProblemException;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.List;

/**
 * 解析引擎回退装饰器（AST → REGEX 回退）。
 *
 * <p>默认装配（{@code parser.engine=ast-fallback-regex}）下使用：先走 AST，碰到 ParseProblemException
 * 或其它解析异常时无感降级到正则实现，保证流水线不中断。</p>
 *
 * <p>不直接暴露为 Spring Bean，由 ParserEngineConfig 在 {@code ast-fallback-regex} 模式下装配。</p>
 */
@Slf4j
public class FallbackJavaParserService implements JavaParserService {

    private final AstJavaParserService primary;
    private final RegexJavaParserService fallback;

    public FallbackJavaParserService() {
        this(new AstJavaParserService(), new RegexJavaParserService());
    }

    public FallbackJavaParserService(AstJavaParserService primary, RegexJavaParserService fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public ParsedClassInfo parseFile(File file) {
        try {
            return primary.parseFile(file);
        } catch (ParseProblemException ex) {
            log.warn("AST parse failed for {}, fallback to REGEX: {}",
                    file.getAbsolutePath(), ex.getMessage());
            return fallback.parseFile(file);
        } catch (RuntimeException ex) {
            // 兜底：捕获一切运行时异常（包括 IllegalStateException 等非 ParseProblemException）
            // 避免单文件解析错误打断整条流水线
            log.warn("AST runtime exception for {}, fallback to REGEX: {}",
                    file.getAbsolutePath(), ex.toString());
            return fallback.parseFile(file);
        }
    }

    @Override
    public List<ParsedClassInfo> parseDirectory(File directory) {
        // 目录扫描统一委托给 AST；失败时逐文件 parseFile 已自带回退
        return primary.parseDirectory(directory);
    }
}
