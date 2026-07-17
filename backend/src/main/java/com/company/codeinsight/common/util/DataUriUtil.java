package com.company.codeinsight.common.util;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.storage.EnvStorageResolver;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * C 组大正文逻辑 URI：落在 {@link EnvStorageResolver#getActiveRuntimeRoot()} 下。
 * <ul>
 *   <li>{@code prompt:{id}/content.md} → prompts/{id}/content.md</li>
 *   <li>{@code business-knowledge:{systemId}/content.md}</li>
 *   <li>{@code release-edit:{id}/content.md}</li>
 *   <li>{@code trial:{id}/result.json}</li>
 *   <li>{@code snapshot:{repoId}:{versionId}/entrypoints.json}</li>
 *   <li>{@code snapshot:{repoId}:{versionId}/module_hierarchy.json}</li>
 * </ul>
 */
public final class DataUriUtil {

    private DataUriUtil() {
    }

    public static String buildPromptUri(Long id) {
        requireId(id, "promptId");
        return "prompt:" + id + "/content.md";
    }

    public static String buildBusinessKnowledgeUri(Long systemId) {
        requireId(systemId, "systemId");
        return "business-knowledge:" + systemId + "/content.md";
    }

    public static String buildReleaseEditUri(Long id) {
        requireId(id, "editId");
        return "release-edit:" + id + "/content.md";
    }

    public static String buildTrialUri(Long id) {
        requireId(id, "trialId");
        return "trial:" + id + "/result.json";
    }

    public static String buildSnapshotEntrypointsUri(Long repoId, Long versionId) {
        requireId(repoId, "repoId");
        requireId(versionId, "versionId");
        return "snapshot:" + repoId + ":" + versionId + "/entrypoints.json";
    }

    public static String buildSnapshotModuleHierarchyUri(Long repoId, Long versionId) {
        requireId(repoId, "repoId");
        requireId(versionId, "versionId");
        return "snapshot:" + repoId + ":" + versionId + "/module_hierarchy.json";
    }

    public static Path resolve(String uri, EnvStorageResolver resolver) {
        if (!StringUtils.hasText(uri)) {
            throw new BusinessException("data content_uri 为空");
        }
        if (resolver == null || resolver.getActiveRuntimeRoot() == null) {
            throw new BusinessException("EnvStorageResolver 未就绪");
        }
        String t = uri.trim();
        Path runtimeRoot = resolver.getActiveRuntimeRoot();

        if (t.startsWith("prompt:")) {
            return resolvePrefixed(t, "prompt:", "prompts/", runtimeRoot);
        }
        if (t.startsWith("business-knowledge:")) {
            return resolvePrefixed(t, "business-knowledge:", "business-knowledge/", runtimeRoot);
        }
        if (t.startsWith("release-edit:")) {
            return resolvePrefixed(t, "release-edit:", "release-edits/", runtimeRoot);
        }
        if (t.startsWith("trial:")) {
            return resolvePrefixed(t, "trial:", "trials/", runtimeRoot);
        }
        if (t.startsWith("snapshot:")) {
            return resolveSnapshot(t, runtimeRoot);
        }
        throw new BusinessException("无效 data URI: " + uri);
    }

    /**
     * 写入 UTF-8 正文，返回 MD5 十六进制摘要。
     */
    public static String writeUtf8(String uri, String content, EnvStorageResolver resolver) {
        Path path = resolve(uri, resolver);
        try {
            Files.createDirectories(path.getParent());
            byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes);
            return DigestUtils.md5DigestAsHex(bytes);
        } catch (IOException e) {
            throw new BusinessException("写入 data URI 失败: " + uri + " — " + e.getMessage());
        }
    }

    public static String readUtf8(String uri, EnvStorageResolver resolver) {
        if (!StringUtils.hasText(uri)) {
            return "";
        }
        Path path = resolve(uri, resolver);
        if (!Files.isRegularFile(path)) {
            return "";
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException("读取 data URI 失败: " + uri + " — " + e.getMessage());
        }
    }

    private static Path resolvePrefixed(String uri, String scheme, String dirPrefix, Path runtimeRoot) {
        String body = uri.substring(scheme.length());
        int slash = body.indexOf('/');
        if (slash <= 0) {
            throw new BusinessException("无效 data URI: " + uri);
        }
        String idPart = body.substring(0, slash);
        String filePart = body.substring(slash + 1);
        if (!StringUtils.hasText(idPart) || !StringUtils.hasText(filePart) || filePart.contains("..")) {
            throw new BusinessException("无效 data URI: " + uri);
        }
        return runtimeRoot.resolve(dirPrefix).resolve(idPart).resolve(filePart).normalize();
    }

    private static Path resolveSnapshot(String uri, Path runtimeRoot) {
        // snapshot:{repoId}:{versionId}/entrypoints.json
        String body = uri.substring("snapshot:".length());
        int c1 = body.indexOf(':');
        if (c1 <= 0) {
            throw new BusinessException("无效 snapshot URI: " + uri);
        }
        String repoId = body.substring(0, c1);
        String rest = body.substring(c1 + 1);
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            throw new BusinessException("无效 snapshot URI: " + uri);
        }
        String versionId = rest.substring(0, slash);
        String filePart = rest.substring(slash + 1);
        if (!StringUtils.hasText(filePart) || filePart.contains("..")) {
            throw new BusinessException("无效 snapshot URI: " + uri);
        }
        return runtimeRoot.resolve("publish-snapshots")
                .resolve(repoId)
                .resolve(versionId)
                .resolve(filePart)
                .normalize();
    }

    private static void requireId(Long id, String name) {
        if (id == null) {
            throw new BusinessException(name + " 不能为空");
        }
    }
}
