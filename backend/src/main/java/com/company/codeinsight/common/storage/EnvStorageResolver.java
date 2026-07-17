package com.company.codeinsight.common.storage;

import com.company.codeinsight.common.config.CodeInsightEnvProperties;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 存储路径门面：按 {@code code-insight.env} 解析两个根目录（runtime-root + releases-root），
 * 并在内部派生出 {@code workspaceRoot = runtimeRoot/workspaces}，供业务按既有 API 拼接后缀。
 * <p>dev：写死 {@code ./storage}，派生 {@code ./storage/workspaces}、{@code ./storage/releases}
 * （resolve 为绝对路径）。非 dev：只读配置，无默认。</p>
 *
 * <p>对外暴露的 {@link #getActiveRuntimeRoot()} / {@link #getActiveWorkspaceRoot()} /
 * {@link #getActiveReleasesRoot()} 与 {@link #draftsRoot()} / {@link #taskDataDir()} /
 * {@link #aiLogDir()} / {@link #taskWorkspaceDir()} 等方法签名保持不变；业务侧 28 个调用文件无需调整。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EnvStorageResolver {

    private final CodeInsightEnvProperties envProperties;
    private final StorageProperties storageProperties;

    @Getter
    private Path activeRuntimeRoot;
    @Getter
    private Path activeWorkspaceRoot;
    @Getter
    private Path activeReleasesRoot;

    @PostConstruct
    void resolve() {
        if (envProperties.isDev()) {
            activeRuntimeRoot = Paths.get("./storage").toAbsolutePath().normalize();
            activeReleasesRoot = Paths.get("./storage/releases").toAbsolutePath().normalize();
            if (hasConfiguredStoragePaths()) {
                log.warn("code-insight.env=dev：已忽略 STORAGE_RUNTIME_ROOT / RELEASES_ROOT 配置，使用写死本机路径");
            }
        } else {
            activeRuntimeRoot = requireAbsoluteConfigured("runtime-root", storageProperties.getRuntimeRoot());
            activeReleasesRoot = requireAbsoluteConfigured("releases-root", storageProperties.getReleasesRoot());
        }
        // 派生：workspaceRoot = runtimeRoot/workspaces（dev / 非 dev 一致）
        activeWorkspaceRoot = activeRuntimeRoot.resolve("workspaces").normalize();
        log.info("存储根已解析 env={} runtimeRoot={} (workspaceRoot={}) releasesRoot={}",
                envProperties.getEnv(), activeRuntimeRoot, activeWorkspaceRoot, activeReleasesRoot);
    }

    /** 单测注入三根（绕过 env 写死逻辑；runtimeRoot = dataRoot 兼容入参） */
    public void overrideRootsForTest(Path dataRoot, Path workspaceRoot, Path releasesRoot) {
        this.activeRuntimeRoot = dataRoot.toAbsolutePath().normalize();
        this.activeWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.activeReleasesRoot = releasesRoot.toAbsolutePath().normalize();
    }

    public boolean isDev() {
        return envProperties.isDev();
    }

    /** 草稿物理目录：{@code {runtimeRoot}/drafts} */
    public Path draftsRoot() {
        return activeRuntimeRoot.resolve("drafts");
    }

    /**
     * 草稿文件：{@code {runtimeRoot}/drafts/{relativeUnderDrafts}}。
     * relative 通常含 {@code task_{id}/...} 前缀（与历史 URI 兼容）。
     */
    public Path draftFilePath(String relativeUnderDrafts) {
        return draftsRoot().resolve(relativeUnderDrafts).normalize();
    }

    /** 发布产物目录：{@code {releasesRoot}/{sys}/{repo}/{version}} */
    public Path releaseDir(Long systemId, Long repositoryId, String versionNum) {
        return activeReleasesRoot
                .resolve(String.valueOf(systemId))
                .resolve(String.valueOf(repositoryId))
                .resolve(versionNum)
                .normalize();
    }

    /** 任务工作区：{@code {workspaceRoot}/task_{id}} */
    public Path taskWorkspaceDir(long taskId) {
        return activeWorkspaceRoot.resolve("task_" + taskId).normalize();
    }

    public String taskWorkspaceDirString(long taskId) {
        return taskWorkspaceDir(taskId).toString();
    }

    /** 任务运行数据目录：{@code {runtimeRoot}/task_{id}}（pipeline.log / incremental-impact.json） */
    public Path taskDataDir(long taskId) {
        return activeRuntimeRoot.resolve("task_" + taskId).normalize();
    }

    /** AI 调用日志目录：{@code {runtimeRoot}/ai_logs/task_{id}} */
    public Path aiLogDir(long taskId) {
        return activeRuntimeRoot.resolve("ai_logs").resolve("task_" + taskId).normalize();
    }

    public String workspaceRootString() {
        return activeWorkspaceRoot.toString();
    }

    /** runtimeRoot 字符串 */
    public String runtimeRootString() {
        return activeRuntimeRoot.toString();
    }

    private boolean hasConfiguredStoragePaths() {
        return StringUtils.hasText(storageProperties.getRuntimeRoot())
                || StringUtils.hasText(storageProperties.getReleasesRoot());
    }

    private static Path requireAbsoluteConfigured(String name, String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalStateException(
                    "非 dev 环境必须配置 code-insight.storage." + name + "（环境变量 STORAGE_*）");
        }
        Path p = Paths.get(raw.trim()).toAbsolutePath().normalize();
        if (!p.isAbsolute()) {
            throw new IllegalStateException("code-insight.storage." + name + " 必须是绝对路径: " + raw);
        }
        return p;
    }
}
