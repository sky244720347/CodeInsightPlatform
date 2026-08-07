package com.company.codeinsight.modules.repository.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * 代码库类型。code 与展示文案一致，便于配置与 Excel 直接填写。
 */
public enum RepoType {

    FRONTEND("前端"),
    BACKEND("后端"),
    /** 单仓前后端信号冲突（monorepo）；tech_stack 可为逗号多值，任务门禁按 token 交集 */
    FULLSTACK("前后端"),
    DB("DB");

    private final String code;

    RepoType(String code) {
        this.code = code;
    }

    /** 存库 / 下拉 / Excel 使用的编码（与 label 相同） */
    public String getCode() {
        return code;
    }

    public static Optional<RepoType> fromCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        return Arrays.stream(values())
                .filter(t -> t.code.equals(trimmed))
                .findFirst();
    }

    public static boolean isValid(String raw) {
        return fromCode(raw).isPresent();
    }
}
