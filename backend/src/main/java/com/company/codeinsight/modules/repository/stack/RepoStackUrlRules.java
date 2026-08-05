package com.company.codeinsight.modules.repository.stack;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 gitUrl 解析仓库名，按命名约定识别 DB 仓（零远程调用）。
 * <p>例：{@code https://host/git/ph_rmwp_core_db.git} → 名 {@code ph_rmwp_core_db} → DB。</p>
 */
public final class RepoStackUrlRules {

    private static final Pattern REPO_NAME = Pattern.compile(
            "(?i)(?:^|[/\\\\])([^/\\\\]+?)(?:\\.git)?/*$");

    /** 段名以 _db / -db 结尾 */
    private static final Pattern DB_SUFFIX = Pattern.compile("(?i).+[_-]db$");

    private RepoStackUrlRules() {
    }

    /** 是否应按 URL 直接判为 DB（不 ls-remote / 不拉树） */
    public static boolean isDbByUrl(String gitUrl) {
        return extractRepoName(gitUrl).filter(n -> DB_SUFFIX.matcher(n).matches()).isPresent();
    }

    /**
     * 从仓库名弱猜 DB 方言（目录内标签）；猜不到则 empty。
     */
    public static Optional<String> guessDbTechStack(String gitUrl) {
        String name = extractRepoName(gitUrl).orElse("").toLowerCase(Locale.ROOT);
        String url = gitUrl == null ? "" : gitUrl.toLowerCase(Locale.ROOT);
        String hay = name + " " + url;
        if (hay.contains("postgres") || hay.contains("_pg_") || hay.contains("-pg-")
                || hay.endsWith("_pg") || hay.endsWith("-pg")) {
            return Optional.of("PostgreSQL");
        }
        if (hay.contains("mysql") || hay.contains("mariadb")) {
            return Optional.of("MySQL");
        }
        if (hay.contains("oracle")) {
            return Optional.of("Oracle");
        }
        if (hay.contains("mongo")) {
            return Optional.of("MongoDB");
        }
        if (hay.contains("sqlserver") || hay.contains("mssql") || hay.contains("sql_server")) {
            return Optional.of("SQL Server");
        }
        return Optional.empty();
    }

    public static Optional<String> extractRepoName(String gitUrl) {
        if (!StringUtils.hasText(gitUrl)) {
            return Optional.empty();
        }
        String raw = gitUrl.trim();
        // 去掉 query / fragment
        int q = raw.indexOf('?');
        if (q >= 0) {
            raw = raw.substring(0, q);
        }
        int hash = raw.indexOf('#');
        if (hash >= 0) {
            raw = raw.substring(0, hash);
        }
        Matcher m = REPO_NAME.matcher(raw);
        if (!m.find()) {
            return Optional.empty();
        }
        String name = m.group(1).trim();
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }
}
