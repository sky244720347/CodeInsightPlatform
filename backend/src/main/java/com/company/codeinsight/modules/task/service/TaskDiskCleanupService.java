package com.company.codeinsight.modules.task.service;

import com.company.codeinsight.common.storage.EnvStorageResolver;
import com.company.codeinsight.common.storage.TaskWorkspacePaths;
import com.company.codeinsight.common.util.DirectoryCleanupUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 任务 NAS / runtimeRoot 磁盘回收。
 * <ul>
 *   <li>{@link #cleanupAfterKnowledgeConfirmed}：确认后 — 删源码与 drafts，保留 docs/code-insight</li>
 *   <li>{@link #cleanupAfterPush}：推送成功后 — 删源码与 drafts，保留执行日志与 AI 日志</li>
 *   <li>{@link #cleanupAllRuntimeArtifacts}：终止/取消/删除/重试 — 整包清空（含日志）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskDiskCleanupService {

    private final EnvStorageResolver storageResolver;
    private final TaskWorkspacePaths taskWorkspacePaths;

    /** 知识确认完成后：删 drafts + 工作区源码，保留 docs/code-insight。 */
    public void cleanupAfterKnowledgeConfirmed(Long taskId) {
        if (taskId == null) {
            return;
        }
        deleteQuietly("drafts", storageResolver.draftsRoot().resolve("task_" + taskId), taskId);
        stripWorkspaceKeepDocs(taskId);
    }

    /**
     * 推送成功后：删源码工作区与 drafts；保留 {@code task_{id}/}（pipeline.log）与 {@code ai_logs/}。
     */
    public void cleanupAfterPush(Long taskId) {
        if (taskId == null) {
            return;
        }
        deleteQuietly("workspace", taskWorkspacePaths.taskProjectPath(taskId), taskId);
        deleteQuietly("drafts", storageResolver.draftsRoot().resolve("task_" + taskId), taskId);
    }

    /** 终止 / 取消 / 删除 / 重试时的整包清理（含执行日志与 AI 日志）。 */
    public void cleanupAllRuntimeArtifacts(Long taskId) {
        if (taskId == null) {
            return;
        }
        deleteQuietly("workspace", taskWorkspacePaths.taskProjectPath(taskId), taskId);
        deleteQuietly("taskData", storageResolver.taskDataDir(taskId), taskId);
        deleteQuietly("drafts", storageResolver.draftsRoot().resolve("task_" + taskId), taskId);
        deleteQuietly("aiLogs", storageResolver.aiLogDir(taskId), taskId);
    }

    private void stripWorkspaceKeepDocs(Long taskId) {
        Path workspace = taskWorkspacePaths.taskProjectPath(taskId);
        Path docsKeep = taskWorkspacePaths.taskDocsCodeInsight(taskId).toAbsolutePath().normalize();
        if (!Files.isDirectory(workspace)) {
            return;
        }
        if (!Files.isDirectory(docsKeep)) {
            log.warn("cleanupAfterKnowledgeConfirmed: docs/code-insight 不存在，整目录清理 workspace taskId={}", taskId);
            deleteQuietly("workspace", workspace, taskId);
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(workspace)) {
            for (Path child : stream) {
                Path abs = child.toAbsolutePath().normalize();
                if (docsKeep.startsWith(abs)) {
                    stripKeepSubtree(child, docsKeep, taskId);
                } else if (!abs.startsWith(docsKeep)) {
                    deleteQuietly("workspace-child:" + child.getFileName(), child, taskId);
                }
            }
        } catch (IOException e) {
            log.warn("stripWorkspaceKeepDocs 列举失败 taskId={} path={}: {}",
                    taskId, workspace.toAbsolutePath(), e.getMessage());
        }
    }

    private void stripKeepSubtree(Path root, Path keep, Long taskId) throws IOException {
        Path rootAbs = root.toAbsolutePath().normalize();
        Path keepAbs = keep.toAbsolutePath().normalize();
        if (!keepAbs.startsWith(rootAbs)) {
            deleteQuietly("strip:" + root.getFileName(), root, taskId);
            return;
        }
        if (rootAbs.equals(keepAbs)) {
            return;
        }
        if (!Files.isDirectory(root)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                Path childAbs = child.toAbsolutePath().normalize();
                if (keepAbs.startsWith(childAbs) || childAbs.equals(keepAbs)) {
                    stripKeepSubtree(child, keepAbs, taskId);
                } else {
                    deleteQuietly("strip-child:" + child.getFileName(), child, taskId);
                }
            }
        }
    }

    private void deleteQuietly(String label, Path path, Long taskId) {
        if (path == null) {
            return;
        }
        try {
            if (!Files.exists(path)) {
                return;
            }
            DirectoryCleanupUtil.deleteRecursively(path);
            log.info("TaskDiskCleanup: taskId={} {} path={}", taskId, label, path.toAbsolutePath());
        } catch (Exception e) {
            log.warn("TaskDiskCleanup failed taskId={} {} path={}: {}",
                    taskId, label, path.toAbsolutePath(), e.getMessage());
        }
    }
}
