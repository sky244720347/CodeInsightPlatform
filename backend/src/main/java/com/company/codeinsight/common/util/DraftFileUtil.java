package com.company.codeinsight.common.util;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.storage.StorageProperties;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 草稿物理文件路径解析工具：支持三种 content_uri 格式
 * <ul>
 *   <li>{@code draft:{sysId}:{repoId}:task_{taskId}/name.md} — 草稿（按逻辑 URI 定位）</li>
 *   <li>{@code release:{sysId}:{repoId}:v1.0.0/relPath} — 发布产物（从 releases 目录读）</li>
 *   <li>{@code file:///absolute/path} — 绝对路径（兼容旧数据）</li>
 *   <li>{@code storage/drafts/...} — 旧相对路径（兼容旧数据）</li>
 * </ul>
 */
public final class DraftFileUtil {

    private DraftFileUtil() {
    }

    /**
     * 兼容旧调用：仅解析相对/绝对文件路径。
     * @deprecated 请使用 {@link #resolve(String, StorageProperties)}
     */
    @Deprecated
    public static Path resolveDraftPath(String contentUri, String storageLocalPath) {
        if (!StringUtils.hasText(contentUri)) throw new BusinessException("草稿 content_uri 为空");
        String t = contentUri.trim();
        if (t.startsWith("file:") || t.startsWith("FILE:")) return Paths.get(URI.create(t));
        Path base = Paths.get(storageLocalPath == null ? "./storage" : storageLocalPath)
                .toAbsolutePath().normalize();
        return base.resolve(t).normalize();
    }

    /** 新调用：解析所有格式（draft:/release:/file:/旧相对路径） */
    public static Path resolve(String contentUri, StorageProperties props) {
        if (!StringUtils.hasText(contentUri)) throw new BusinessException("草稿 content_uri 为空");
        String t = contentUri.trim();

        if (t.startsWith("draft:")) return resolveDraftUri(t, props);
        if (t.startsWith("release:")) return resolveReleaseUri(t, props);
        if (t.startsWith("file:") || t.startsWith("FILE:")) return Paths.get(URI.create(t));

        // 旧数据 fallback
        String root = props.getMode() == com.company.codeinsight.common.storage.StorageMode.SHARED
                ? props.getBasePath() : props.getLocalPath();
        return Paths.get(root == null ? "./storage" : root).toAbsolutePath().normalize()
                .resolve(t).normalize();
    }

    private static Path resolveDraftUri(String uri, StorageProperties props) {
        // draft:1:2:task_99/UserModule.md
        String body = uri.substring("draft:".length());
        String[] parts = body.split(":", 3);
        if (parts.length != 3) throw new BusinessException("无效 draft URI: " + uri);
        long sysId = Long.parseLong(parts[0]);
        long repoId = Long.parseLong(parts[1]);
        String rel = parts[2]; // task_99/name.md → draftDir already resolves taskId
        // draftDir with taskId extracted from rel
        int slash = rel.indexOf('/');
        String taskPart = slash > 0 ? rel.substring(0, slash) : rel;
        long taskId = Long.parseLong(taskPart.replace("task_", ""));
        String fileName = slash > 0 ? rel.substring(slash + 1) : "";
        return props.draftFilePath(sysId, repoId, taskId, fileName);
    }

    private static Path resolveReleaseUri(String uri, StorageProperties props) {
        // release:1:2:v1.0.0/modules/UserModule.md
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
        return props.releaseDir(sysId, repoId, ver).resolve(rel);
    }

    public static String buildReleaseUri(Long sysId, Long repoId, String ver, String relPath) {
        return "release:" + sysId + ":" + repoId + ":" + ver + "/" + relPath;
    }

    public static String buildDraftUri(Long sysId, Long repoId, Long taskId, String fileName) {
        return "draft:" + sysId + ":" + repoId + ":task_" + taskId + "/" + fileName;
    }
}
