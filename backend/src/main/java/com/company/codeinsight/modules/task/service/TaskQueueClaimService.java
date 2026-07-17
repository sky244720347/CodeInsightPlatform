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
 * 集群模式下在事务内预留 PENDING 任务行（SKIP LOCKED），避免多节点重复调度。
 */
@Service
@RequiredArgsConstructor
public class TaskQueueClaimService {

    private final DecompileTaskMapper taskMapper;
    private final ClusterInstanceId instanceId;
    private final ClusterProperties clusterProperties;

    /**
     * 预留一条 PENDING 任务：写入 claimed_by / lease，状态仍为 PENDING。
     * 调用方在拿到并发许可后应 {@link #commitReservation(DecompileTask)}。
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
        task.setLeaseUntil(now.plusHours(clusterProperties.getTaskLeaseHours()));
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
}
