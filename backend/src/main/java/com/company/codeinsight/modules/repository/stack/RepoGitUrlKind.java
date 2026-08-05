package com.company.codeinsight.modules.repository.stack;

import org.springframework.util.StringUtils;

import java.io.File;
import java.util.Locale;

/**
 * 判断 gitUrl 是远程地址还是本地目录（与扫描侧一致，避免 Windows 对 {@code https://…} 调 Path.of 抛错）。
 */
public final class RepoGitUrlKind {

    private RepoGitUrlKind() {
    }

    /** http(s) / git@ / ssh / git:// 等远程形态 */
    public static boolean isRemoteUrl(String gitUrl) {
        if (!StringUtils.hasText(gitUrl)) {
            return false;
        }
        String u = gitUrl.trim().toLowerCase(Locale.ROOT);
        return u.startsWith("http://")
                || u.startsWith("https://")
                || u.startsWith("git://")
                || u.startsWith("ssh://")
                || u.startsWith("git@");
    }

    /** 本地已存在的目录（用 File，不对 URL 调 Path.of） */
    public static boolean isExistingLocalDirectory(String gitUrl) {
        if (!StringUtils.hasText(gitUrl) || isRemoteUrl(gitUrl)) {
            return false;
        }
        File dir = new File(gitUrl.trim());
        return dir.exists() && dir.isDirectory();
    }
}
