package com.company.codeinsight.modules.parser.service.impl;

import com.company.codeinsight.modules.parser.model.ParsedClassInfo;
import com.company.codeinsight.modules.parser.service.JavaParserService;
import com.github.javaparser.ParseProblemException;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
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
    public void evictTaskCaches(Long taskId) {
        primary.evictTaskCaches(taskId);
        fallback.evictTaskCaches(taskId);
    }

    @Override
    public void clearAllCaches() {
        primary.clearAllCaches();
        fallback.clearAllCaches();
    }

    @Override
    public String cacheStatsSummary() {
        return "ast{" + primary.cacheStatsSummary() + "} regex{" + fallback.cacheStatsSummary() + "}";
    }

    @Override
    public List<ParsedClassInfo> parseDirectory(File directory) {
        // 逐文件走本类 parseFile（含 AST→REGEX），避免 AstJavaParserService.parseDirectory
        // 遇单文件 ParseProblemException 中断整目录（入口识别会因此得到空列表）。
        List<ParsedClassInfo> out = new ArrayList<>();
        if (directory == null || !directory.exists()) {
            return out;
        }
        walk(directory, directory, out);
        return out;
    }

    private void walk(File root, File current, List<ParsedClassInfo> out) {
        if (current.isDirectory()) {
            File[] children = current.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                walk(root, child, out);
            }
            return;
        }
        if (!current.isFile() || !current.getName().endsWith(".java")) {
            return;
        }
        try {
            ParsedClassInfo info = parseFile(current);
            if (info != null && info.getClassName() != null) {
                String rel = root.toURI().relativize(current.toURI()).getPath();
                info.setSourceRelativePath(rel.replace('\\', '/'));
                out.add(info);
            }
        } catch (RuntimeException ex) {
            log.warn("parseDirectory skip file {}: {}", current.getAbsolutePath(), ex.toString());
        }
    }
}
