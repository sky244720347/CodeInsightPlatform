package com.company.codeinsight.modules.repository.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 代码库类型 → 可选技术栈目录。
 * <p>技术栈 code 与 label 一致（如 {@code Java}、{@code React}），配置白名单时无需再转换。</p>
 */
public final class TechStackCatalog {

    private static final Map<String, List<String>> BY_REPO_TYPE;

    static {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put(RepoType.FRONTEND.getCode(), List.of(
                "React", "Vue", "Angular", "Next.js", "Flutter"));
        map.put(RepoType.BACKEND.getCode(), List.of(
                "Java", "Python", "Go", "Node.js", "C#"));
        map.put(RepoType.DB.getCode(), List.of(
                "MySQL", "PostgreSQL", "Oracle", "SQL Server", "MongoDB"));
        BY_REPO_TYPE = Collections.unmodifiableMap(map);
    }

    private TechStackCatalog() {
    }

    /** 完整级联目录（只读） */
    public static Map<String, List<String>> all() {
        return BY_REPO_TYPE;
    }

    public static List<String> stacksOf(String repoTypeCode) {
        return Optional.ofNullable(BY_REPO_TYPE.get(repoTypeCode == null ? null : repoTypeCode.trim()))
                .orElse(List.of());
    }

    public static boolean isValidPair(String repoTypeCode, String techStack) {
        if (repoTypeCode == null || techStack == null) {
            return false;
        }
        String type = repoTypeCode.trim();
        String stack = techStack.trim();
        if (!RepoType.isValid(type) || stack.isEmpty()) {
            return false;
        }
        return stacksOf(type).contains(stack);
    }
}
