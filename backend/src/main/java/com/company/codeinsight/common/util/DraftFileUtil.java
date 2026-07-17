package com.company.codeinsight.common.util;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.storage.EnvStorageResolver;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 草稿/发布产物物理路径解析：支持 URI 格式
 * <ul>
 *   <li>{@code draft:{sysId}:{repoId}:task_{taskId}/...} — 草稿（落在 runtimeRoot/drafts）</li>
 *   <li>{@code release:{sysId}:{repoId}:v1.0.0/relPath} — 发布产物（releasesRoot）</li>
 *   <li>{@code file:///absolute/path} — 绝对路径（兼容旧数据）</li>
 *   <li>相对路径 — 相对 runtimeRoot（兼容旧数据）</li>
 * </ul>
 */
public final class DraftFileUtil {

    private DraftFileUtil() {
    }

    public static Path resolve(String contentUri, EnvStorageResolver resolver) {
        if (!StringUtils.hasText(contentUri)) throw new BusinessException("草稿 content_uri 为空");
        String t = contentUri.trim();

        if (t.startsWith("draft:")) return resolveDraftUri(t, resolver);
        if (t.startsWith("release:")) return resolveReleaseUri(t, resolver);
        if (t.startsWith("file:") || t.startsWith("FILE:")) return Paths.get(URI.create(t));

        return resolver.getActiveRuntimeRoot().resolve(t).normalize();
    }

    private static Path resolveDraftUri(String uri, EnvStorageResolver resolver) {
        // draft:1:2:task_99/task_99/Module/x.md 或 draft:1:2:task_99/Module/x.md
        String body = uri.substring("draft:".length());
        String[] parts = body.split(":", 3);
        if (parts.length != 3) throw new BusinessException("无效 draft URI: " + uri);
        String rel = parts[2];
        int slash = rel.indexOf('/');
        String afterTask = slash > 0 ? rel.substring(slash + 1) : "";
        // 与历史写入一致：物理路径 = drafts/{afterTask}，afterTask 常含 task_{id}/...
        return resolver.draftFilePath(afterTask);
    }

    private static Path resolveReleaseUri(String uri, EnvStorageResolver resolver) {
        String body = uri.substring("release:".length());
        int c1 = body.indexOf(':');
        int c2 = body.indexOf(':', c1 + 1);
        if (c1 < 0 || c2 < 0) throw new BusinessException("无效 release URI: " + uri);
        long sysId = Long.parseLong(body.substring(0, c1));
        long repoId = Long.parseLong(body.substring(c1 + 1, c2));
        String rest = body.substring(c2 + 1);
        int slash = rest.indexOf('/');
        String ver = slash > 0 ? rest.substring(0, slash) : rest;
        String rel = slash > 0 ? rest.substring(slash + 1) : "";
        return resolver.releaseDir(sysId, repoId, ver).resolve(rel);
    }

    public static String buildReleaseUri(Long sysId, Long repoId, String ver, String relPath) {
        return "release:" + sysId + ":" + repoId + ":" + ver + "/" + relPath;
    }

    public static String buildDraftUri(Long sysId, Long repoId, Long taskId, String fileName) {
        return "draft:" + sysId + ":" + repoId + ":task_" + taskId + "/" + fileName;
    }
}
