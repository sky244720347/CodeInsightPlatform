package com.company.codeinsight.common.cluster;

import com.company.codeinsight.common.config.CodeInsightEnvProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 按 {@code code-insight.env} 推导集群开关（已取消 {@code CLUSTER_ENABLED}）：
 * <ul>
 *   <li>dev → {@code enabled=false}</li>
 *   <li>非 dev → {@code enabled=true}</li>
 * </ul>
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class ClusterEnvAligner {

    private final CodeInsightEnvProperties envProperties;
    private final ClusterProperties clusterProperties;

    @PostConstruct
    void align() {
        warnIfLegacyClusterEnabledSet();
        boolean cluster = !envProperties.isDev();
        clusterProperties.setEnabled(cluster);
        log.info("cluster.enabled={}（由 code-insight.env={} 推导）",
                cluster, envProperties.getEnv());
    }

    private static void warnIfLegacyClusterEnabledSet() {
        if (StringUtils.hasText(System.getenv("CLUSTER_ENABLED"))
                || StringUtils.hasText(System.getProperty("CLUSTER_ENABLED"))) {
            log.warn("已忽略废弃配置 CLUSTER_ENABLED；集群开关仅由 CODE_INSIGHT_ENV / code-insight.env 决定");
        }
    }
}
