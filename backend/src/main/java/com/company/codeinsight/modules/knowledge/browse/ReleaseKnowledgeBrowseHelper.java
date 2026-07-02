package com.company.codeinsight.modules.knowledge.browse;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.util.DraftFileUtil;
import com.company.codeinsight.modules.knowledge.browse.KnowledgeBrowseSource.IndexFileEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从 NAS / 本地 releases 目录读取已发布知识文件。
 */
@Slf4j
@Component
public class ReleaseKnowledgeBrowseHelper {

    static final long SIZE_LIMIT_BYTES = 5L * 1024 * 1024;

    public List<IndexFileEntry> listFiles(ActiveKnowledgeContext ctx) {
        if (ctx == null || !ctx.isReleaseDirExists()) {
            return List.of();
        }
        Path releaseDir = ctx.getReleaseDir();
        List<IndexFileEntry> out = new ArrayList<>();
        walkForEntries(releaseDir.resolve("modules"), "INDEX", "modules", out);
        walkForEntries(releaseDir.resolve("index"), "INDEX", "index", out);
        walkForEntries(releaseDir.resolve("meta"), "MANIFEST", "meta", out);
        out.sort((a, b) -> b.updatedAt().compareTo(a.updatedAt()));
        return out;
    }

    public String readFile(ActiveKnowledgeContext ctx, String relativePath) {
        if (ctx == null || !ctx.isReleaseDirExists()) {
            throw new BusinessException("发布产物目录不存在");
        }
        if (relativePath == null || relativePath.contains("..") || relativePath.startsWith("/")) {
            throw new BusinessException("非法路径：" + relativePath);
        }
        Path releaseDir = ctx.getReleaseDir();
        Path resolved = releaseDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(releaseDir)) {
            throw new BusinessException("非法路径（越界访问）：" + relativePath);
        }
        if (!Files.exists(resolved)) {
            throw new BusinessException("文件不存在：" + relativePath);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new BusinessException("路径不是普通文件：" + relativePath);
        }
        try {
            long size = Files.size(resolved);
            if (size > SIZE_LIMIT_BYTES) {
                throw new BusinessException("文件过大（" + size + " 字节），超过 5 MB 上限");
            }
            return Files.readString(resolved);
        } catch (IOException e) {
            throw new BusinessException("读取文件失败：" + relativePath + " — " + e.getMessage());
        }
    }

    /**
     * 构建 release 目录下 modules/ 的文件索引，便于树形模式关联文档。
     */
    public ReleaseDocumentIndex indexModuleDocuments(ActiveKnowledgeContext ctx) {
        ReleaseDocumentIndex index = new ReleaseDocumentIndex();
        if (ctx == null || !ctx.isReleaseDirExists()) {
            return index;
        }
        Path modulesDir = ctx.getReleaseDir().resolve("modules");
        if (!Files.isDirectory(modulesDir)) {
            return index;
        }
        try (var stream = Files.walk(modulesDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .forEach(p -> {
                        String rel = "modules/" + modulesDir.relativize(p).toString().replace('\\', '/');
                        String baseName = p.getFileName().toString();
                        String stem = baseName.endsWith(".md")
                                ? baseName.substring(0, baseName.length() - 3) : baseName;
                        index.byRelativePath.put(rel, rel);
                        index.byBasename.put(normalize(stem), rel);
                        index.byBasename.putIfAbsent(normalize(baseName), rel);
                    });
        } catch (IOException e) {
            log.warn("indexModuleDocuments failed repoId={}: {}", ctx.getRepositoryId(), e.getMessage());
        }
        return index;
    }

    public String buildContentUri(ActiveKnowledgeContext ctx, String relativePath) {
        return DraftFileUtil.buildReleaseUri(
                ctx.getSystemId(), ctx.getRepositoryId(), ctx.getVersionNum(), relativePath);
    }

    private void walkForEntries(Path dir, String type, String prefix, List<IndexFileEntry> out) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                try {
                    String rel = prefix + "/" + dir.relativize(p).toString().replace('\\', '/');
                    long size = Files.size(p);
                    LocalDateTime mtime = LocalDateTime.ofInstant(
                            Files.getLastModifiedTime(p).toInstant(), ZoneId.systemDefault());
                    out.add(new IndexFileEntry(rel, type, size, mtime));
                } catch (IOException e) {
                    log.warn("列出发布文件失败：{} — {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("遍历发布目录失败：{} — {}", dir, e.getMessage());
        }
    }

    static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    public static class ReleaseDocumentIndex {
        final Map<String, String> byRelativePath = new HashMap<>();
        final Map<String, String> byBasename = new HashMap<>();
    }
}
