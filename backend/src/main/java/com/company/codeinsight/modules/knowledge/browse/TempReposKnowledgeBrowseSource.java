package com.company.codeinsight.modules.knowledge.browse;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.storage.TaskWorkspacePaths;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link KnowledgeBrowseSource} 的工作区回退实现（未发布时读 workspace docs）。
 * <p>已由 {@link NasKnowledgeBrowseSource} 统一承担「优先 release、回退 workspace」；
 * 本类保留实现供参考，不再注册为 Bean，避免与 Nas 源冲突。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class TempReposKnowledgeBrowseSource implements KnowledgeBrowseSource {

    private final TaskWorkspacePaths taskWorkspacePaths;

    /** 单文件大小上限（字节）；超过此大小拒绝读取 */
    static final long SIZE_LIMIT_BYTES = 5L * 1024 * 1024;

    /** 临时仓库根目录（与 KnowledgeServiceImpl.createVersion 一致） */

    /** 允许访问的子路径前缀（相对 task 工作区根） */
    private static final String DOCS_INSIGHT_SUBDIR = "docs" + java.io.File.separatorChar + "code-insight";

    /** 索引文件目录（在 docs/code-insight 下） */
    private static final String INDEX_SUBDIR = "index";

    /** 模块拷贝目录（在 docs/code-insight 下）—— 视为索引副本 */
    private static final String MODULES_SUBDIR = "modules";

    /** 清单文件目录（在 docs/code-insight 下） */
    private static final String META_SUBDIR = "meta";

    @Override
    public List<IndexFileEntry> listIndexFiles(Long taskId) {
        if (taskId == null) return Collections.emptyList();
        Path docsRoot = locateDocsInsightRoot(taskId);
        if (docsRoot == null || !Files.isDirectory(docsRoot)) {
            // temp_repos 被清理或不存在 → 静默返回空列表（UI 列表可空，由 service 决定如何提示）
            return Collections.emptyList();
        }
        List<IndexFileEntry> out = new ArrayList<>();
        walkForEntries(docsRoot.resolve(INDEX_SUBDIR), "INDEX", out);
        walkForEntries(docsRoot.resolve(MODULES_SUBDIR), "INDEX", out);
        walkForEntries(docsRoot.resolve(META_SUBDIR), "MANIFEST", out);
        // 排序：按 updatedDate DESC
        out.sort((a, b) -> b.updatedDate().compareTo(a.updatedDate()));
        return out;
    }

    @Override
    public String readIndexFile(Long taskId, String filePath) {
        Path resolved = resolveIndexFile(taskId, filePath);
        if (!Files.exists(resolved)) {
            throw new BusinessException("索引文件不存在或已被清理：" + filePath + "。如需访问请到推送页面下载对应版本 ZIP。");
        }
        if (!Files.isRegularFile(resolved)) {
            throw new BusinessException("路径不是普通文件：" + filePath);
        }
        long size;
        try {
            size = Files.size(resolved);
        } catch (IOException e) {
            throw new BusinessException("无法读取文件大小：" + filePath + " — " + e.getMessage());
        }
        if (size > SIZE_LIMIT_BYTES) {
            throw new BusinessException("文件过大（" + size + " 字节），超过 5 MB 上限；请到推送页面下载 ZIP 后本地查看。");
        }
        try {
            return Files.readString(resolved);
        } catch (IOException e) {
            throw new BusinessException("读取文件失败：" + filePath + " — " + e.getMessage());
        }
    }

    /**
     * 把 {@code temp_repos/task_{id}/docs/code-insight/} 子树下的所有普通文件加入 out，并标记 type。
     */
    private void walkForEntries(Path dir, String type, List<IndexFileEntry> out) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                try {
                    String rel = dir.relativize(p).toString().replace('\\', '/');
                    long size = Files.size(p);
                    LocalDateTime mtime = LocalDateTime.ofInstant(
                            Files.getLastModifiedTime(p).toInstant(), ZoneId.systemDefault());
                    out.add(new IndexFileEntry(rel, type, size, mtime));
                } catch (IOException e) {
                    log.warn("列出索引文件失败：{} — {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("遍历目录失败：{} — {}", dir, e.getMessage());
        }
    }

    /**
     * 解析 taskId 对应的 docs/code-insight 根目录；不存在返回 null。
     */
    private Path locateDocsInsightRoot(Long taskId) {
        return taskWorkspacePaths.taskDocsCodeInsight(taskId).toAbsolutePath().normalize();
    }

    /**
     * 把 filePath 解析为 temp_repos/task_{id}/docs/code-insight/{filePath} 的绝对路径，含越权校验。
     * <p>双重保险：① 拒绝含 {@code ..} 或以 {@code /} 开头的 filePath；② 解析后路径必须在 docs/code-insight 子树下。</p>
     */
    private Path resolveIndexFile(Long taskId, String filePath) {
        if (!org.springframework.util.StringUtils.hasText(filePath)) {
            throw new BusinessException("filePath 不能为空");
        }
        if (filePath.contains("..") || filePath.startsWith("/") || filePath.startsWith("\\")) {
            throw new BusinessException("非法路径：" + filePath);
        }
        Path docsRoot = locateDocsInsightRoot(taskId);
        if (docsRoot == null) {
            throw new BusinessException("任务目录不存在或已被清理：taskId=" + taskId);
        }
        Path resolved = docsRoot.resolve(filePath).normalize();
        if (!resolved.startsWith(docsRoot)) {
            throw new BusinessException("非法路径（越界访问）：" + filePath);
        }
        return resolved;
    }
}