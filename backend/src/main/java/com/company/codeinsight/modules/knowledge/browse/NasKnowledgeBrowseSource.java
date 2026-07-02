package com.company.codeinsight.modules.knowledge.browse;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.storage.TaskWorkspacePaths;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * NAS releases 目录数据源（shared 模式）。
 * <p>优先读取仓库 {@code last_published_version_id} 对应的 release 目录；否则回退 temp_repos。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "code-insight.storage.mode", havingValue = "shared")
public class NasKnowledgeBrowseSource implements KnowledgeBrowseSource {

    static final long SIZE_LIMIT_BYTES = ReleaseKnowledgeBrowseHelper.SIZE_LIMIT_BYTES;

    private final TaskWorkspacePaths taskWorkspacePaths;
    private final DecompileTaskMapper taskMapper;
    private final RepositoryActiveKnowledgeResolver activeKnowledgeResolver;
    private final ReleaseKnowledgeBrowseHelper releaseBrowseHelper;

    @Override
    public List<IndexFileEntry> listIndexFiles(Long taskId) {
        Optional<ActiveKnowledgeContext> ctx = activeContextForTask(taskId);
        if (ctx.isPresent()) {
            return releaseBrowseHelper.listFiles(ctx.get());
        }
        return listFromTempRepos(taskId);
    }

    @Override
    public String readIndexFile(Long taskId, String filePath) {
        Optional<ActiveKnowledgeContext> ctx = activeContextForTask(taskId);
        if (ctx.isPresent()) {
            try {
                return releaseBrowseHelper.readFile(ctx.get(), filePath);
            } catch (BusinessException e) {
                log.debug("read release file fallback taskId={} path={}: {}", taskId, filePath, e.getMessage());
            }
        }
        return readFromTempRepos(taskId, filePath);
    }

    private Optional<ActiveKnowledgeContext> activeContextForTask(Long taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        DecompileTask task = taskMapper.selectById(taskId);
        if (task == null || task.getRepositoryId() == null) {
            return Optional.empty();
        }
        return activeKnowledgeResolver.resolve(task.getRepositoryId())
                .filter(ActiveKnowledgeContext::isReleaseDirExists);
    }

    private List<IndexFileEntry> listFromTempRepos(Long taskId) {
        Path docsRoot = taskWorkspacePaths.taskDocsCodeInsight(taskId);
        if (!Files.isDirectory(docsRoot)) {
            return Collections.emptyList();
        }
        List<IndexFileEntry> out = new ArrayList<>();
        walkForEntries(docsRoot.resolve("index"), "INDEX", "index", out);
        walkForEntries(docsRoot.resolve("modules"), "INDEX", "modules", out);
        walkForEntries(docsRoot.resolve("meta"), "MANIFEST", "meta", out);
        out.sort((a, b) -> b.updatedAt().compareTo(a.updatedAt()));
        return out;
    }

    private String readFromTempRepos(Long taskId, String filePath) {
        if (filePath == null || filePath.contains("..") || filePath.startsWith("/")) {
            throw new BusinessException("非法路径：" + filePath);
        }
        Path docsRoot = taskWorkspacePaths.taskDocsCodeInsight(taskId);
        Path resolved = docsRoot.resolve(filePath).normalize();
        if (!resolved.startsWith(docsRoot)) {
            throw new BusinessException("非法路径：" + filePath);
        }
        if (!Files.exists(resolved)) {
            throw new BusinessException("索引文件不存在或已被清理");
        }
        try {
            long size = Files.size(resolved);
            if (size > SIZE_LIMIT_BYTES) {
                throw new BusinessException("文件过大");
            }
            return Files.readString(resolved);
        } catch (IOException e) {
            throw new BusinessException("读取失败：" + filePath);
        }
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
                    log.warn("列出索引文件失败：{} — {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("遍历目录失败：{} — {}", dir, e.getMessage());
        }
    }
}
