package com.company.codeinsight.common.util;

/**
 * PostgreSQL VARCHAR 写入上限（方案：schema-text-field-remediation A 组）。
 */
public final class DbStringLimits {

    public static final int ERROR_REASON = 2000;
    public static final int EXCEPTION_MSG = 4000;
    public static final int COMMENT = 2000;
    public static final int CONFIG_VALUE = 1000;
    public static final int DEPENDENCY_CANDIDATES = 4000;

    private DbStringLimits() {
    }

    /**
     * 截断到 {@code maxLen}；null 原样返回。
     */
    public static String truncate(String value, int maxLen) {
        if (value == null || maxLen < 0 || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }
}
