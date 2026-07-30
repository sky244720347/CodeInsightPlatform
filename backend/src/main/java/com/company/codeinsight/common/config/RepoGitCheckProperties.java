package com.company.codeinsight.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 仓库 Git 连通性探测配置（定时轮询 / 超时 / 并发）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "code-insight.repo")
public class RepoGitCheckProperties {

    /** 全库轮询间隔（毫秒），默认 3 分钟 */
    private long gitCheckIntervalMs = 180_000L;

    /** 单次 ls-remote 超时（秒） */
    private int gitCheckTimeoutSeconds = 15;

    /** 本机并发探测数 */
    private int gitCheckConcurrency = 3;
}
