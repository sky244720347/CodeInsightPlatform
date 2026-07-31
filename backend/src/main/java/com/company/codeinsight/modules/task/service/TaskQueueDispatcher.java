package com.company.codeinsight.modules.task.service;

import com.company.codeinsight.modules.knowledge.remediation.KnowledgeRemediationConstants;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.service.impl.TaskStateMachineServiceImpl;
import com.company.codeinsight.modules.task.support.TaskResumeConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 知识构建任务队列调度器。
 * <p>每个节点均可调度：用 DB {@code FOR UPDATE SKIP LOCKED} 抢 {@code PENDING}/{@code RESUME_QUEUED}，
 * 再占用<strong>本机</strong> {@link TaskConcurrencyLimiter} 槽位后拉起流水线或断点续跑。
 * 任务 / 拉代码 / 解析并发按机器限流；AI 并发仍为集群总闸。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskQueueDispatcher {

    private final DecompileTaskService decompileTaskService;
    private final TaskStateMachineServiceImpl stateMachineService;
    private final TaskConcurrencyLimiter limiter;
    private final TaskQueueClaimService claimService;

    @Scheduled(fixedDelayString = "${code-insight.task.queue-dispatch-interval-ms:5000}")
    public void dispatch() {
        try {
            dispatchPending();
        } catch (Exception e) {
            log.error("任务队列调度异常", e);
        }
    }

    /**
     * 本机有空槽才抢库；抢到后 tryAcquire，失败则退回认领，供其他节点或下个 tick 再抢。
     */
    private void dispatchPending() {
        int permits = limiter.globalAvailablePermits();
        if (permits <= 0) {
            return;
        }
        int attempts = Math.max(permits * 2, 4);
        for (int i = 0; i < attempts; i++) {
            if (limiter.globalAvailablePermits() <= 0) {
                break;
            }
            DecompileTask reserved = claimService.reserveNextPending();
            if (reserved == null) {
                break;
            }
            Long taskId = reserved.getId();
            Long systemId = reserved.getSystemId();
            if (!limiter.tryAcquire(systemId, taskId)) {
                claimService.clearReservation(taskId);
                break;
            }
            try {
                if (TaskStatus.RESUME_QUEUED.name().equals(reserved.getStatus())) {
                    startResumeQueued(reserved);
                } else {
                    transitPendingToExecutionStart(reserved);
                    decompileTaskService.runPipeline(taskId);
                }
            } catch (Exception e) {
                limiter.release(systemId, taskId);
                claimService.clearReservation(taskId);
                log.error("dispatcher 触发任务 #{} 失败", taskId, e);
            }
        }
    }

    private void startResumeQueued(DecompileTask task) {
        String resume = task.getResumeFrom();
        if (TaskResumeConstants.AFTER_ENTRYPOINT.equals(resume)) {
            stateMachineService.transitTo(task, TaskStatus.AI_ANALYZING, null);
            decompileTaskService.runResumeAfterEntrypoint(task.getId());
        } else if (TaskResumeConstants.AFTER_HIERARCHY.equals(resume)) {
            decompileTaskService.runResumeAfterHierarchy(task.getId());
        } else {
            throw new IllegalStateException("未知断点续跑起点: " + resume);
        }
    }

    /**
     * 普通任务进入 PULL_QUEUED（等拉代码槽）；知识纠错任务按 resume_from 直接进入续跑阶段。
     */
    void transitPendingToExecutionStart(DecompileTask task) {
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
        stateMachineService.transitTo(task, TaskStatus.PULL_QUEUED, null);
    }
}
