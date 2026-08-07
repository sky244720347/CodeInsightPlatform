package com.company.codeinsight.modules.repository.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 代码库类型 → 可选技术栈目录。
 * <p>技术栈 code 与 label 一致（如 {@code Java}、{@code React}），配置白名单时无需再转换。
 * 「前后端」类型允许逗号多值（须同时含至少一侧前端栈与一侧后端栈）。</p>
 */
public final class TechStackCatalog {

    private static final Map<String, List<String>> BY_REPO_TYPE;

    static {
        Map<String, List<String>> map = new LinkedHashMap<>();
        List<String> frontend = List.of("React", "Vue", "Angular", "Next.js", "Flutter");
        List<String> backend = List.of("Java", "Python", "Go", "Node.js", "C#");
        map.put(RepoType.FRONTEND.getCode(), frontend);
        map.put(RepoType.BACKEND.getCode(), backend);
        // 下拉多选：前后端目录 = 后端 ∪ 前端（保序）
        List<String> fullstack = new ArrayList<>(backend.size() + frontend.size());
        fullstack.addAll(backend);
        fullstack.addAll(frontend);
        map.put(RepoType.FULLSTACK.getCode(), List.copyOf(fullstack));
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

    /** 逗号拆分技术栈（去空白、去空项，保序去重） */
    public static List<String> splitStacks(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String t = part == null ? "" : part.trim();
            if (!t.isEmpty()) {
                seen.add(t);
            }
        }
        return List.copyOf(seen);
    }

    /** 规范化拼接（去空白、去重，保传入顺序） */
    public static String joinStacks(String... stacks) {
        if (stacks == null || stacks.length == 0) {
            return "";
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String s : stacks) {
            if (s == null || s.isBlank()) {
                continue;
            }
            for (String t : splitStacks(s)) {
                seen.add(t);
            }
        }
        return String.join(",", seen);
    }

    public static boolean isValidPair(String repoTypeCode, String techStack) {
        if (repoTypeCode == null || techStack == null) {
            return false;
        }
        String type = repoTypeCode.trim();
        if (!RepoType.isValid(type)) {
            return false;
        }
        if (RepoType.FULLSTACK.getCode().equals(type)) {
            return isValidFullstackStacks(techStack);
        }
        List<String> tokens = splitStacks(techStack);
        if (tokens.size() != 1) {
            return false;
        }
        return stacksOf(type).contains(tokens.get(0));
    }

    /**
     * 前后端：至少 2 个 token；每个须属于前端或后端目录；且两侧至少各一。
     */
    public static boolean isValidFullstackStacks(String techStack) {
        List<String> tokens = splitStacks(techStack);
        if (tokens.size() < 2) {
            return false;
        }
        Set<String> fe = Set.copyOf(stacksOf(RepoType.FRONTEND.getCode()));
        Set<String> be = Set.copyOf(stacksOf(RepoType.BACKEND.getCode()));
        boolean hasFe = false;
        boolean hasBe = false;
        for (String t : tokens) {
            boolean inFe = fe.contains(t);
            boolean inBe = be.contains(t);
            if (!inFe && !inBe) {
                return false;
            }
            if (inFe) {
                hasFe = true;
            }
            if (inBe) {
                hasBe = true;
            }
        }
        return hasFe && hasBe;
    }
}
