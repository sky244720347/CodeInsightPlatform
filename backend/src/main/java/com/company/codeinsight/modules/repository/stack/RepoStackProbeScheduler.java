package com.company.codeinsight.modules.repository.stack;

import com.company.codeinsight.common.cluster.ClusterLeaderLock;
import com.company.codeinsight.common.cluster.ClusterProperties;
import com.company.codeinsight.common.config.RepoGitCheckProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 仓库类型/技术栈探测：集群仅 Leader 执行；dev 单机直接跑。
 * <p>整轮串行 + COUNT 调度冷却，见 docs/repo-stack-probe-plan.md。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RepoStackProbeScheduler {

    private final RepoStackProbeService probeService;
    private final RepoGitCheckProperties properties;
    private final ClusterLeaderLock clusterLeaderLock;
    private final ClusterProperties clusterProperties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(
            initialDelayString = "${code-insight.repo.stack-probe-initial-delay-ms:30000}",
            fixedDelayString = "${code-insight.repo.stack-probe-interval-ms:20000}")
    public void tick() {
        if (!properties.isStackProbeEnabled()) {
            return;
        }
        if (!shouldRunAsLeader()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            probeService.runScheduledSweep();
        } catch (Exception e) {
            log.error("stack probe scheduled tick failed: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    private boolean shouldRunAsLeader() {
        if (!clusterProperties.isEnabled()) {
            return true;
        }
        try {
            // 与跑批续租同一 TTL 下限；跑批内按「整批最坏」再抬高
            int ttl = Math.max(60, properties.getStackProbeLeaderLockTtlSeconds());
            return clusterLeaderLock.tryAcquireLeader(RepoStackProbeService.LEADER_LOCK_KEY, ttl);
        } catch (Exception e) {
            log.warn("stack probe leader check failed: {}", e.getMessage());
            return false;
        }
    }
}
