package com.company.codeinsight.modules.task.service;

import com.company.codeinsight.common.cluster.ClusterInstanceId;
import com.company.codeinsight.common.cluster.ClusterProperties;
import com.company.codeinsight.common.config.CodeInsightEnvProperties;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 在事务内预留 PENDING / RESUME_QUEUED 任务行（{@code FOR UPDATE SKIP LOCKED}），避免多节点重复调度；
 * 并提供租约续租 / CAS 接管（孤儿任务恢复）。
 * <p>每个调度节点均可调用；认领写入本机 {@link ClusterInstanceId}。</p>
 * <p>dev 时仅认领 {@code is_dev=true} 的任务。</p>
 */
@Service
@RequiredArgsConstructor
public class TaskQueueClaimService {

    private final DecompileTaskMapper taskMapper;
    private final ClusterInstanceId instanceId;
    private final ClusterProperties clusterProperties;
    private final CodeInsightEnvProperties envProperties;

    /**
     * 预留一条 PENDING 或 RESUME_QUEUED 任务：写入 claimed_by / lease，状态不变。
     */
    @Transactional
    public DecompileTask reserveNextPending() {
        Boolean devOnly = envProperties.isDev() ? Boolean.TRUE : null;
        Long id = taskMapper.selectNextPendingIdForUpdate(devOnly);
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
     * 孤儿接管已 CAS 到本节点，但因任务槽不足暂缓时：把认领写回 CAS 前快照，避免变成 NO_CLAIM。
     * <p>previousClaimedBy 为空时清认领（原本就是无主）。</p>
     */
    @Transactional
    public void restoreClaimAfterDeferredReclaim(Long taskId,
                                                 String previousClaimedBy,
                                                 LocalDateTime previousLeaseUntil) {
        if (taskId == null) {
            return;
        }
        DecompileTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        String self = instanceId.get();
        if (task.getClaimedBy() != null && !self.equals(task.getClaimedBy())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (!org.springframework.util.StringUtils.hasText(previousClaimedBy)) {
            task.setClaimedBy(null);
            task.setClaimedAt(null);
            task.setLeaseUntil(null);
        } else {
            task.setClaimedBy(previousClaimedBy);
            task.setLeaseUntil(previousLeaseUntil);
        }
        task.setUpdatedDate(now);
        taskMapper.updateById(task);
    }

    /**
     * CAS 接管孤儿任务认领权：仅当 status / claimed_by 与期望一致时成功。
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
