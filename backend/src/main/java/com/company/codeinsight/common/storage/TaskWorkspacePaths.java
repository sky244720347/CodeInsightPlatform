package com.company.codeinsight.common.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;

/**
 * 统一解析任务工作区路径（基于 EnvStorageResolver 的派生根 workspaceRoot = runtimeRoot/workspaces）。
 */
@Component
@RequiredArgsConstructor
public class TaskWorkspacePaths {

    private final EnvStorageResolver storageResolver;

    public File taskProjectDir(long taskId) {
        return storageResolver.taskWorkspaceDir(taskId).toFile();
    }

    public Path taskProjectPath(long taskId) {
        return storageResolver.taskWorkspaceDir(taskId);
    }

    public Path taskDocsCodeInsight(long taskId) {
        return taskProjectPath(taskId).resolve("docs/code-insight");
    }

    public String workspaceRoot() {
        return storageResolver.workspaceRootString();
    }

    public Path releasesRoot() {
        return storageResolver.getActiveReleasesRoot();
    }
}
