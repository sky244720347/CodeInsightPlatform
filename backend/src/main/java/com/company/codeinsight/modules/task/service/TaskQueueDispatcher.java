package com.company.codeinsight.modules.task.service;

import com.company.codeinsight.common.cluster.ClusterLeaderLock;
import com.company.codeinsight.common.cluster.ClusterProperties;
import com.company.codeinsight.modules.knowledge.remediation.KnowledgeRemediationConstants;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.company.codeinsight.modules.task.service.impl.TaskStateMachineServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识构建任务队列调度器。
 * <p>集群模式：仅 Leader 节点调度 + DB SKIP LOCKED 预留 + Redis 分布式并发。</p>
 * <p>单机模式：各节点均可调度 + JVM Semaphore（兼容开发环境）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskQueueDispatcher {

    private static final String LEADER_KEY = "ci:leader:task-dispatcher";

    private final DecompileTaskMapper taskMapper;
    private final DecompileTaskService decompileTaskService;
    private final TaskStateMachineServiceImpl stateMachineService;
    private final TaskConcurrencyLimiter limiter;
    private final ClusterProperties clusterProperties;
    private final ClusterLeaderLock leaderLock;
    private final TaskQueueClaimService claimService;

    @Scheduled(fixedDelayString = "${code-insight.task.queue-dispatch-interval-ms:5000}")
    public void dispatch() {
        if (clusterProperties.isEnabled() && !leaderLock.tryAcquireLeader(LEADER_KEY)) {
            return;
        }
        try {
            if (clusterProperties.isEnabled()) {
                dispatchCluster();
            } else {
                dispatchLocal();
            }
        } catch (Exception e) {
            log.error("任务队列调度异常", e);
        }
    }

    private void dispatchCluster() {
        int permits = limiter.globalAvailablePermits();
        if (permits <= 0) {
            return;
        }
        int attempts = Math.max(permits * 2, 4);
        for (int i = 0; i < attempts; i++) {
            DecompileTask reserved = claimService.reserveNextPending();
            if (reserved == null) {
                break;
            }
            Long taskId = reserved.getId();
            Long systemId = reserved.getSystemId();
            if (!limiter.tryAcquire(systemId, taskId)) {
                claimService.clearReservation(taskId);
                continue;
            }
            try {
                transitPendingToExecutionStart(reserved);
                decompileTaskService.runPipeline(taskId);
            } catch (Exception e) {
                limiter.release(systemId, taskId);
                claimService.clearReservation(taskId);
                log.error("dispatcher 触发任务 #{} 失败", taskId, e);
            }
        }
    }

    private void dispatchLocal() {
        int permits = limiter.globalAvailablePermits();
        if (permits <= 0) {
            return;
        }
        int limit = Math.max(permits * 2, 4);
        List<DecompileTask> pending = taskMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DecompileTask>()
                        .eq(DecompileTask::getStatus, TaskStatus.PENDING.name())
                        .orderByDesc(DecompileTask::getPriority)
                        .orderByAsc(DecompileTask::getCreatedAt)
                        .last("LIMIT " + limit)
        );
        if (pending.isEmpty()) {
            return;
        }
        for (DecompileTask t : pending) {
            if (!limiter.tryAcquire(t.getSystemId())) {
                continue;
            }
            try {
                transitPendingToExecutionStart(t);
                decompileTaskService.runPipeline(t.getId());
            } catch (Exception e) {
                limiter.release(t.getSystemId(), t.getId());
                log.error("dispatcher 触发任务 #{} 失败", t.getId(), e);
            }
        }
    }

    /**
     * 普通任务从 PULLING_CODE 起跑；知识纠错任务按 resume_from 直接进入续跑阶段。
     */
    private void transitPendingToExecutionStart(DecompileTask task) {
        if (KnowledgeRemediationConstants.TRIGGER_SOURCE.equals(task.getTriggerSource())) {
            String resume = task.getResumeFrom();
            if (KnowledgeRemediationConstants.RESUME_AI_ANALYZING.equals(resume)) {
                stateMachineService.transitTo(task, TaskStatus.AI_ANALYZING, null);
            } else if (KnowledgeRemediationConstants.RESUME_GENERATING_DOC.equals(resume)) {
                stateMachineService.transitTo(task, TaskStatus.GENERATING_DOC, null);
            } else {
                throw new IllegalStateException("未知纠错续跑起点: " + resume);
            }
            return;
        }
        stateMachineService.transitTo(task, TaskStatus.PULLING_CODE, null);
    }
}
