package com.company.codeinsight.modules.repository.service;

import com.company.codeinsight.common.cluster.ClusterLeaderLock;
import com.company.codeinsight.common.cluster.ClusterProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 仓库 Git 连通性全库轮询：集群下仅 Leader 执行，dev 单机直接跑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RepoGitCheckScheduler {

    public static final String LEADER_LOCK_KEY = "ci:leader:repo-git-check";

    private final RepoGitConnectivityService connectivityService;
    private final ClusterLeaderLock clusterLeaderLock;
    private final ClusterProperties clusterProperties;

    @Scheduled(
            initialDelayString = "${code-insight.repo.git-check-initial-delay-ms:180000}",
            fixedDelayString = "${code-insight.repo.git-check-interval-ms:180000}")
    public void sweep() {
        try {
            if (!shouldRun()) {
                return;
            }
            connectivityService.checkAllRepositories(
                    () -> clusterLeaderLock.tryAcquireLeader(LEADER_LOCK_KEY));
        } catch (Exception e) {
            log.error("Git connectivity scheduled sweep failed", e);
        }
    }

    private boolean shouldRun() {
        if (!clusterProperties.isEnabled()) {
            return true;
        }
        return clusterLeaderLock.tryAcquireLeader(LEADER_LOCK_KEY);
    }
}
