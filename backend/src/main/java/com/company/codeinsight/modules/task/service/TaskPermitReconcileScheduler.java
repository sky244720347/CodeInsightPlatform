package com.company.codeinsight.modules.task.service;

import com.company.codeinsight.common.cluster.ClusterProperties;
import com.company.codeinsight.common.cluster.InstanceHeartbeat;
import com.company.codeinsight.modules.quotacontrol.service.AiConcurrencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 集群模式下：实例心跳 + AI 集群闸对账续租 + 系统级任务闸（maxConcurrentTasks）对账续租。
 * 本机 task.concurrency / pull.concurrency / parse.concurrency 不经 Redis。
 * 另对流水线任务 DB 租约做续租（单机/集群均执行）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskPermitReconcileScheduler {

    private final TaskConcurrencyLimiter taskConcurrencyLimiter;
    private final AiConcurrencyService aiConcurrencyService;
    private final ClusterProperties clusterProperties;
    private final InstanceHeartbeat instanceHeartbeat;
    private final TaskLeaseRenewer taskLeaseRenewer;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!clusterProperties.isEnabled()) {
            return;
        }
        try {
            instanceHeartbeat.touch();
        } catch (Exception e) {
            log.warn("启动实例心跳失败: {}", e.getMessage());
        }
        try {
            int n = taskConcurrencyLimiter.reconcileWithDatabase();
            log.info("启动系统任务闸对账完成，清理 {} 个僵尸 holder", n);
        } catch (Exception e) {
            log.warn("启动系统任务闸对账失败: {}", e.getMessage());
        }
        try {
            int n = aiConcurrencyService.reconcileLocalOrphans();
            log.info("启动 AI 并发许可对账完成，清理 {} 个僵尸 holder", n);
        } catch (Exception e) {
            log.warn("启动 AI 并发许可对账失败: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${code-insight.cluster.task-permit-reconcile-interval-ms:60000}")
    public void scheduledReconcileAndRenew() {
        if (!clusterProperties.isEnabled()) {
            return;
        }
        try {
            instanceHeartbeat.touch();
        } catch (Exception e) {
            log.warn("周期实例心跳失败: {}", e.getMessage());
        }
        try {
            taskConcurrencyLimiter.reconcileWithDatabase();
        } catch (Exception e) {
            log.warn("周期系统任务闸对账失败: {}", e.getMessage());
        }
        try {
            aiConcurrencyService.reconcileLocalOrphans();
        } catch (Exception e) {
            log.warn("周期 AI 并发许可对账失败: {}", e.getMessage());
        }
        try {
            taskConcurrencyLimiter.renewLocalHeldPermits();
        } catch (Exception e) {
            log.warn("系统任务闸续租失败: {}", e.getMessage());
        }
        try {
            aiConcurrencyService.renewLocalHeldPermits();
        } catch (Exception e) {
            log.warn("AI 并发许可续租失败: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${code-insight.cluster.task-lease-renew-interval-ms:120000}")
    public void scheduledRenewTaskLeases() {
        try {
            taskLeaseRenewer.renewLocalHeld();
        } catch (Exception e) {
            log.warn("任务 DB 租约续租失败: {}", e.getMessage());
        }
    }
}
