package com.company.codeinsight.modules.push.retention;

import com.company.codeinsight.common.config.ReleaseRetentionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 小时级扫盘对账：删掉不在保留集合内的 release / publish-snapshot。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "code-insight.publish", name = "release-prune-reconcile-enabled",
        havingValue = "true", matchIfMissing = true)
public class ReleaseRetentionReconcileScheduler {

    private final ReleaseRetentionService releaseRetentionService;
    private final ReleaseRetentionProperties properties;

    @Scheduled(fixedDelayString = "${code-insight.publish.release-prune-reconcile-interval-ms:3600000}")
    public void reconcile() {
        if (!properties.isReleasePruneReconcileEnabled()) {
            return;
        }
        try {
            releaseRetentionService.reconcileAll();
        } catch (Exception e) {
            log.error("release 保留对账异常: {}", e.getMessage(), e);
        }
    }
}
