package com.company.codeinsight.common.util;

import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * 草稿存储路径校验（推送前使用）。
 * <p>{@code moduleName} 可为 UI 面包屑（含 {@code  / }），真正落盘与推送依赖 {@code filePath}。</p>
 */
public final class DraftPathValidator {

    /** Windows / 通用文件系统禁止出现在路径段内的字符（{@code /} 为分隔符，不在此列） */
    private static final Pattern ILLEGAL_PATH_SEGMENT = Pattern.compile("[\\\\:*?\"<>|]");

    private DraftPathValidator() {
    }

    /**
     * @return 校验失败时的错误描述；通过则返回 {@code null}
     */
    public static String validatePushFilePath(String filePath) {
        if (!StringUtils.hasText(filePath)) {
            return "存储路径为空";
        }
        if (filePath.contains("..") || filePath.startsWith("/") || filePath.startsWith("\\")) {
            return "存储路径非法: " + filePath;
        }
        String normalized = filePath.replace('\\', '/');
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (ILLEGAL_PATH_SEGMENT.matcher(segment).find()) {
                return "路径段「" + segment + "」包含非法字符";
            }
        }
        return null;
    }
}
