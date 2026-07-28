package com.company.codeinsight.modules.task.service;

import com.company.codeinsight.common.cluster.ClusterInstanceId;
import com.company.codeinsight.common.cluster.ClusterProperties;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 集群模式下在事务内预留 PENDING 任务行（SKIP LOCKED），避免多节点重复调度；
 * 并提供租约续租 / CAS 接管（孤儿任务恢复）。
 */
@Service
@RequiredArgsConstructor
public class TaskQueueClaimService {

    private final DecompileTaskMapper taskMapper;
    private final ClusterInstanceId instanceId;
    private final ClusterProperties clusterProperties;

    /**
     * 预留一条 PENDING 任务：写入 claimed_by / lease，状态仍为 PENDING。
     */
    @Transactional
    public DecompileTask reserveNextPending() {
        Long id = taskMapper.selectNextPendingIdForUpdate();
        if (id == null) {
            return null;
        }
        DecompileTask task = taskMapper.selectById(id);
        if (task == null) {
            return null;
        }
        String worker = instanceId.get();
        LocalDateTime now = LocalDateTime.now();
        task.setClaimedBy(worker);
        task.setClaimedAt(now);
        task.setLeaseUntil(now.plusMinutes(clusterProperties.resolveTaskLeaseMinutes()));
        task.setUpdatedDate(now);
        taskMapper.updateById(task);
        return task;
    }

    @Transactional
    public void clearReservation(Long taskId) {
        if (taskId == null) {
            return;
        }
        DecompileTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setClaimedBy(null);
        task.setClaimedAt(null);
        task.setLeaseUntil(null);
        task.setUpdatedDate(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    /** 流水线运行中续租：仅当 claimed_by 为本节点时延长 lease_until */
    @Transactional
    public boolean renewLease(Long taskId) {
        if (taskId == null) {
            return false;
        }
        DecompileTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return false;
        }
        String self = instanceId.get();
        if (task.getClaimedBy() != null && !self.equals(task.getClaimedBy())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (task.getClaimedBy() == null) {
            task.setClaimedBy(self);
            task.setClaimedAt(now);
        }
        task.setLeaseUntil(now.plusMinutes(clusterProperties.resolveTaskLeaseMinutes()));
        task.setUpdatedDate(now);
        taskMapper.updateById(task);
        return true;
    }

    /**
     * CAS 接管孤儿任务认领权：仅当 status / claimed_by 与快照一致时成功。
     *
     * @return true 表示本节点已拿到认领
     */
    @Transactional
    public boolean tryCasTakeover(Long taskId, String expectedStatus, String expectedClaimedBy) {
        if (taskId == null || expectedStatus == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime newLease = now.plusMinutes(clusterProperties.resolveTaskLeaseMinutes());
        int n = taskMapper.casTakeoverClaim(
                taskId,
                expectedStatus,
                expectedClaimedBy,
                instanceId.get(),
                now,
                newLease);
        return n > 0;
    }
}
