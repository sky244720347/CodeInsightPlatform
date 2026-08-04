package com.company.codeinsight.modules.ai.support;

import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 将类名解析为 workspace 下真实存在的源文件相对路径。
 * <p>候选路径必须 {@link File#exists()}；禁止「瞎拼路径」当成功。</p>
 */
public final class SourceFileLocator {

    /** binding 类级锚点占位签名（无具体方法时仍保证表里有行） */
    public static final String CLASS_ANCHOR_SIGNATURE = "$CLASS_ANCHOR$";

    private SourceFileLocator() {
    }

    /**
     * 按优先级挑选第一个存在且通过 scope 的相对路径。
     *
     * @param candidates 已按优先级排好的候选相对路径（可为 null 元素）
     * @param projectDir 任务 workspace 根
     * @param scopeAccept 相对路径是否在扫描范围内；null 表示不过滤
     */
    public static String firstExisting(List<String> candidates, File projectDir,
                                       Predicate<String> scopeAccept) {
        if (projectDir == null || !projectDir.isDirectory() || candidates == null) {
            return null;
        }
        for (String raw : candidates) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String rel = normalizeRel(raw);
            if (scopeAccept != null && !scopeAccept.test(rel)) {
                continue;
            }
            File f = new File(projectDir, rel.replace('/', File.separatorChar));
            if (f.isFile()) {
                return rel;
            }
        }
        return null;
    }

    /**
     * 在 projectDir 下按简单类名查找 {@code SimpleName.java}（最多返回 limit 个）。
     */
    public static List<String> findBySimpleName(File projectDir, String classNameOrFq,
                                                Predicate<String> scopeAccept, int limit) {
        List<String> hits = new ArrayList<>();
        if (projectDir == null || !projectDir.isDirectory() || !StringUtils.hasText(classNameOrFq)) {
            return hits;
        }
        String simple = stripPackage(classNameOrFq);
        if (!StringUtils.hasText(simple)) {
            return hits;
        }
        String target = simple + ".java";
        int max = Math.max(1, limit);
        try {
            Files.walkFileTree(projectDir.toPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (hits.size() >= max) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (!target.equalsIgnoreCase(file.getFileName().toString())) {
                        return FileVisitResult.CONTINUE;
                    }
                    String rel = projectDir.toPath().relativize(file).toString().replace('\\', '/');
                    if (scopeAccept != null && !scopeAccept.test(rel)) {
                        return FileVisitResult.CONTINUE;
                    }
                    hits.add(rel);
                    return hits.size() >= max ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // 返回已收集命中
        }
        return hits;
    }

    /**
     * FQ 推断的标准 Maven 路径（仅作候选，调用方须 exists 校验）。
     */
    public static String inferMavenMainPath(String classNameOrFq) {
        if (!StringUtils.hasText(classNameOrFq) || !classNameOrFq.contains(".")) {
            return null;
        }
        return "src/main/java/" + classNameOrFq.trim().replace('.', '/') + ".java";
    }

    public static String stripPackage(String fqOrShort) {
        if (fqOrShort == null) {
            return null;
        }
        int dot = fqOrShort.lastIndexOf('.');
        return dot >= 0 ? fqOrShort.substring(dot + 1) : fqOrShort;
    }

    public static boolean isClassAnchorSignature(String methodSignature) {
        return CLASS_ANCHOR_SIGNATURE.equals(methodSignature);
    }

    private static String normalizeRel(String raw) {
        String p = raw.trim().replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }
}
