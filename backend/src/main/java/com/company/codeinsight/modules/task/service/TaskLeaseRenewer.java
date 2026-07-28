package com.company.codeinsight.modules.task.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 对本节点持有的流水线任务续写 {@code ci_task.lease_until}，避免活任务被孤儿接管误拉。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskLeaseRenewer {

    private final TaskConcurrencyLimiter taskConcurrencyLimiter;
    private final TaskQueueClaimService claimService;

    public void renewLocalHeld() {
        for (Long taskId : taskConcurrencyLimiter.localHeldTaskIds()) {
            try {
                claimService.renewLease(taskId);
            } catch (Exception e) {
                log.warn("续租失败 taskId={}: {}", taskId, e.getMessage());
            }
        }
    }
}
