package com.company.codeinsight.modules.repository.stack;

import com.company.codeinsight.common.config.RepoGitCheckProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 仓库类型/技术栈探测：每节点都跑（无 Leader），靠 Redis 按仓锁分流。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RepoStackProbeScheduler {

    private final RepoStackProbeService probeService;
    private final RepoGitCheckProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(
            initialDelayString = "${code-insight.repo.stack-probe-initial-delay-ms:30000}",
            fixedDelayString = "${code-insight.repo.stack-probe-interval-ms:20000}")
    public void tick() {
        if (!properties.isStackProbeEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            probeService.probeBatch();
        } catch (Exception e) {
            log.error("stack probe scheduled tick failed: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }
}
