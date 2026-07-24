package com.company.codeinsight.modules.scanner.model;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 仓库扫描范围：唯一 {@code scan_root} + 相对根的排除规则。
 * <p>
 * 两段过滤：先要求路径落在扫描根下，再对「相对扫描根」的路径做 exclude。
 * 落库路径仍相对<strong>仓库根</strong>。
 *
 */
@Slf4j
public final class ScanScope {

    /** 相对扫描根下默认强制排除的目录名（短名段匹配） */
    private static final Set<String> DEFAULT_EXCLUDE_DIR_NAMES = Set.of("target", "node_modules");

    private final File repoRoot;
    /** 归一化后的扫描根（相对仓根，无首尾 {@code /}）；空串 = 整仓 */
    private final String scanRootRel;
    private final File effectiveRoot;
    private final List<String> excludeDirs;
    private final Set<String> excludeFileTypes;

    private ScanScope(File repoRoot,
                      String scanRootRel,
                      File effectiveRoot,
                      List<String> excludeDirs,
                      Set<String> excludeFileTypes) {
        this.repoRoot = repoRoot;
        this.scanRootRel = scanRootRel == null ? "" : scanRootRel;
        this.effectiveRoot = effectiveRoot;
        this.excludeDirs = excludeDirs == null ? List.of() : List.copyOf(excludeDirs);
        this.excludeFileTypes = excludeFileTypes == null ? Set.of() : Set.copyOf(excludeFileTypes);
    }

    /**
     * 从仓库配置构建范围；扫描根目录不存在时抛 {@link BusinessException}。
     */
    public static ScanScope from(CodeRepository repo, File repoRoot) {
        if (repoRoot == null || !repoRoot.isDirectory()) {
            throw new BusinessException("仓库根目录无效，无法构建扫描范围");
        }
        String scanRootRel = normalizeScanRoot(repo == null ? null : repo.getScanRoot());
        File effectiveRoot = resolveEffectiveRoot(repoRoot, scanRootRel);
        List<String> excludeDirs = parseExcludeDirs(repo == null ? null : repo.getExcludeDirs());
        Set<String> excludeTypes = parseExcludeFileTypes(repo == null ? null : repo.getExcludeFileTypes());
        return new ScanScope(repoRoot, scanRootRel, effectiveRoot, excludeDirs, excludeTypes);
    }

    /** 整仓、无业务排除（仅默认敏感目录仍在 accepts 中生效） */
    public static ScanScope wholeRepository(File repoRoot) {
        if (repoRoot == null || !repoRoot.isDirectory()) {
            throw new BusinessException("仓库根目录无效，无法构建扫描范围");
        }
        return new ScanScope(repoRoot, "", repoRoot, List.of(), Set.of());
    }

    public File getRepoRoot() {
        return repoRoot;
    }

    public String getScanRootRel() {
        return scanRootRel;
    }

    public File getEffectiveRoot() {
        return effectiveRoot;
    }

    public List<String> getExcludeDirs() {
        return excludeDirs;
    }

    public Set<String> getExcludeFileTypes() {
        return excludeFileTypes;
    }

    /**
     * 文件是否在可见范围内（相对仓库根路径）。
     */
    public boolean accepts(String relativeToRepoRoot) {
        String p = normalizeRelativePath(relativeToRepoRoot);
        if (p == null) {
            return false;
        }
        if (!underScanRoot(p)) {
            return false;
        }
        String underRoot = relativizeToScanRoot(p);
        if (isExcludedDirPath(underRoot)) {
            return false;
        }
        return !isExcludedFileType(p);
    }

    /**
     * 目录是否可进入（相对仓库根）。目录不做后缀排除。
     */
    public boolean acceptsDirectory(String relativeToRepoRoot) {
        String p = normalizeRelativePath(relativeToRepoRoot);
        if (p == null) {
            // 空路径表示仓库根；仅当 scan_root 为空（整仓）时可进入
            return scanRootRel.isEmpty();
        }
        if (!underScanRoot(p)) {
            return false;
        }
        return !isExcludedDirPath(relativizeToScanRoot(p));
    }

    /**
     * 去掉扫描根前缀后的路径；不在根下时返回 {@code null}。
     */
    public String relativizeToScanRoot(String relativeToRepoRoot) {
        String p = normalizeRelativePath(relativeToRepoRoot);
        if (p == null) {
            return scanRootRel.isEmpty() ? "" : null;
        }
        if (!underScanRoot(p)) {
            return null;
        }
        if (scanRootRel.isEmpty()) {
            return p;
        }
        if (p.equals(scanRootRel)) {
            return "";
        }
        return p.substring(scanRootRel.length() + 1);
    }

    public Set<String> filterPaths(Set<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return paths == null ? Set.of() : paths;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String path : paths) {
            if (accepts(path)) {
                out.add(normalizeRelativePath(path));
            }
        }
        return out;
    }

    // -------------------- normalize / match --------------------

    public static String normalizeScanRoot(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String s = raw.trim().replace('\\', '/');
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty() || ".".equals(s)) {
            return "";
        }
        if (s.contains("..")) {
            throw new BusinessException("扫描根不允许包含 '..': " + raw);
        }
        return s;
    }

    static String normalizeRelativePath(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().replace('\\', '/');
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.contains("..")) {
            return null;
        }
        return s.isEmpty() ? null : s;
    }

    static List<String> parseExcludeDirs(String csv) {
        if (!StringUtils.hasText(csv)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String n = normalizeScanRoot(part);
            if (StringUtils.hasText(n)) {
                out.add(n);
            }
        }
        return Collections.unmodifiableList(out);
    }

    static Set<String> parseExcludeFileTypes(String csv) {
        if (!StringUtils.hasText(csv)) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        boolean ignoredJava = false;
        for (String part : csv.split(",")) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            String ext = part.trim().toLowerCase(Locale.ROOT);
            if (ext.startsWith(".")) {
                ext = ext.substring(1);
            }
            if (!StringUtils.hasText(ext)) {
                continue;
            }
            if ("java".equals(ext)) {
                ignoredJava = true;
                continue;
            }
            out.add(ext);
        }
        if (ignoredJava) {
            log.warn("exclude_file_types 含 .java，已忽略，避免误配导致扫描为空");
        }
        return Collections.unmodifiableSet(out);
    }

    private static File resolveEffectiveRoot(File repoRoot, String scanRootRel) {
        File effective = scanRootRel.isEmpty() ? repoRoot : new File(repoRoot, scanRootRel.replace('/', File.separatorChar));
        if (!effective.exists() || !effective.isDirectory()) {
            throw new BusinessException("扫描根不存在: " + (scanRootRel.isEmpty() ? "/" : "/" + scanRootRel)
                    + "（仓库根: " + repoRoot.getAbsolutePath() + "）");
        }
        try {
            Path repoCanon = repoRoot.getCanonicalFile().toPath().normalize();
            Path effCanon = effective.getCanonicalFile().toPath().normalize();
            if (!effCanon.startsWith(repoCanon)) {
                throw new BusinessException("扫描根越出仓库根: " + scanRootRel);
            }
        } catch (IOException e) {
            throw new BusinessException("扫描根路径校验失败: " + e.getMessage());
        }
        return effective;
    }

    private boolean underScanRoot(String normalizedRepoRel) {
        if (scanRootRel.isEmpty()) {
            return true;
        }
        return normalizedRepoRel.equals(scanRootRel)
                || normalizedRepoRel.startsWith(scanRootRel + "/");
    }

    private boolean isExcludedDirPath(String underRoot) {
        if (underRoot == null) {
            return true;
        }
        if (underRoot.isEmpty()) {
            return false;
        }
        String[] segments = underRoot.split("/");
        for (String seg : segments) {
            if (!StringUtils.hasText(seg)) {
                continue;
            }
            if (seg.startsWith(".") || DEFAULT_EXCLUDE_DIR_NAMES.contains(seg)) {
                return true;
            }
        }
        for (String ex : excludeDirs) {
            if (matchesExclude(underRoot, ex)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 相对扫描根匹配：前缀相等，或短名排除项命中任一路段。
     */
    public static boolean matchesExclude(String underRoot, String exclude) {
        if (!StringUtils.hasText(underRoot) || !StringUtils.hasText(exclude)) {
            return false;
        }
        if (underRoot.equals(exclude) || underRoot.startsWith(exclude + "/")) {
            return true;
        }
        if (!exclude.contains("/")) {
            for (String seg : underRoot.split("/")) {
                if (exclude.equals(seg)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isExcludedFileType(String repoRelPath) {
        if (excludeFileTypes.isEmpty()) {
            return false;
        }
        int dot = repoRelPath.lastIndexOf('.');
        int slash = repoRelPath.lastIndexOf('/');
        if (dot < 0 || dot < slash) {
            return false;
        }
        String ext = repoRelPath.substring(dot + 1).toLowerCase(Locale.ROOT);
        return excludeFileTypes.contains(ext);
    }
}
