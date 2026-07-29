package com.company.codeinsight.modules.task.service;

import com.company.codeinsight.common.cluster.ClusterInstanceId;
import com.company.codeinsight.common.cluster.ClusterProperties;
import com.company.codeinsight.common.cluster.InstanceHeartbeat;
import com.company.codeinsight.modules.draft.enums.DraftStatus;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.draft.service.DraftService;
import com.company.codeinsight.modules.log.service.OperationLogService;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 孤儿流水线任务自动接管：租约过期 ∨ 认领实例心跳已死 → CAS 抢占 → 从当前阶段重入。
 * <p>判定用「或」而非「且」，避免重启后 lease 未过期却长期无法续跑。</p>
 * <p>每个节点均可扫描与续跑（有本机槽才真正拉起）；不再依赖 Leader 独占执行。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskOrphanReclaimScheduler {

    private static final Set<String> RECLAIMABLE_STATUSES = Set.of(
            TaskStatus.PULLING_CODE.name(),
            TaskStatus.PARSING_CODE.name(),
            TaskStatus.AI_ANALYZING.name(),
            TaskStatus.MODULE_HIERARCHY.name(),
            TaskStatus.BASELINE_DOC_INHERIT.name(),
            TaskStatus.GENERATING_DOC.name(),
            TaskStatus.PUSHING.name()
    );

    private final DecompileTaskMapper taskMapper;
    private final DecompileTaskService decompileTaskService;
    private final TaskQueueClaimService claimService;
    private final TaskConcurrencyLimiter taskConcurrencyLimiter;
    private final ClusterProperties clusterProperties;
    private final ClusterInstanceId clusterInstanceId;
    private final InstanceHeartbeat instanceHeartbeat;
    private final OperationLogService operationLogService;
    private final KnowledgeDraftMapper knowledgeDraftMapper;
    private final DraftService draftService;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        // 稍晚于其它 Ready 钩子，避免与调度器抢跑；失败不影响启动
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            reclaimOnce("startup");
        } catch (Exception e) {
            log.warn("启动孤儿任务扫描失败: {}", e.getMessage());
        }
        try {
            reclaimStaleRegeneratingDrafts();
        } catch (Exception e) {
            log.warn("启动草稿 REGENERATING 清理失败: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${code-insight.cluster.orphan-reclaim-interval-ms:30000}")
    public void scheduledReclaim() {
        try {
            reclaimOnce("scheduled");
        } catch (Exception e) {
            log.warn("周期孤儿任务扫描失败: {}", e.getMessage());
        }
        try {
            reclaimStaleRegeneratingDrafts();
        } catch (Exception e) {
            log.warn("周期草稿 REGENERATING 清理失败: {}", e.getMessage());
        }
    }

    public int reclaimOnce(String trigger) {
        List<DecompileTask> candidates = taskMapper.selectList(
                new LambdaQueryWrapper<DecompileTask>()
                        .in(DecompileTask::getStatus, RECLAIMABLE_STATUSES)
                        .orderByAsc(DecompileTask::getUpdatedDate)
                        .last("LIMIT 50"));
        if (candidates.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (DecompileTask task : candidates) {
            try {
                if (tryReclaimOne(task)) {
                    n++;
                }
            } catch (Exception e) {
                log.error("孤儿接管失败 taskId={} status={}: {}",
                        task.getId(), task.getStatus(), e.getMessage(), e);
            }
        }
        if (n > 0) {
            log.warn("孤儿任务接管完成 trigger={} count={}", trigger, n);
        }
        return n;
    }

    private boolean tryReclaimOne(DecompileTask snap) {
        if (snap == null || snap.getId() == null) {
            return false;
        }
        Long taskId = snap.getId();
        if (taskConcurrencyLimiter.isHeldLocally(taskId)
                || decompileTaskService.isPipelineThreadActive(taskId)) {
            return false;
        }
        DecompileTask fresh = taskMapper.selectById(taskId);
        if (fresh == null || !RECLAIMABLE_STATUSES.contains(fresh.getStatus())) {
            return false;
        }
        if (!isOrphan(fresh)) {
            return false;
        }
        String oldClaimed = fresh.getClaimedBy();
        String status = fresh.getStatus();
        if (!claimService.tryCasTakeover(taskId, status, oldClaimed)) {
            return false;
        }
        String reason = describeOrphanReason(fresh);
        operationLogService.logOperation(
                fresh.getSystemId(), taskId, "TASK_ORPHAN_RECLAIM",
                "孤儿接管 triggerStatus=" + status + " reason=" + reason
                        + " oldClaimedBy=" + oldClaimed,
                null, true);
        log.warn("孤儿接管 taskId={} status={} reason={} oldClaimedBy={}",
                taskId, status, reason, oldClaimed);
        decompileTaskService.reclaimOrphanAndResume(taskId);
        return true;
    }

    /**
     * 孤儿判定（或）：心跳已死 ∨ 租约过期 ∨ 无认领信息。
     * 本节点正在跑的任务已在上层过滤。
     */
    boolean isOrphan(DecompileTask task) {
        String claimedBy = task.getClaimedBy();
        LocalDateTime leaseUntil = task.getLeaseUntil();
        LocalDateTime now = LocalDateTime.now();
        boolean leaseExpired = leaseUntil != null && leaseUntil.isBefore(now);
        boolean noClaimMeta = !StringUtils.hasText(claimedBy) && leaseUntil == null;

        if (!clusterProperties.isEnabled()) {
            // 单机：认领不是本进程，或无认领，或租约过期
            if (noClaimMeta || leaseExpired) {
                return true;
            }
            return !clusterInstanceId.get().equals(claimedBy);
        }

        boolean heartbeatDead = StringUtils.hasText(claimedBy)
                && !instanceHeartbeat.isAlive(claimedBy);
        return noClaimMeta || heartbeatDead || leaseExpired;
    }

    private String describeOrphanReason(DecompileTask task) {
        String claimedBy = task.getClaimedBy();
        LocalDateTime leaseUntil = task.getLeaseUntil();
        boolean leaseExpired = leaseUntil != null && leaseUntil.isBefore(LocalDateTime.now());
        boolean noClaimMeta = !StringUtils.hasText(claimedBy) && leaseUntil == null;
        if (noClaimMeta) {
            return "NO_CLAIM";
        }
        if (!clusterProperties.isEnabled()) {
            if (!clusterInstanceId.get().equals(claimedBy)) {
                return "CLAIMED_BY_OTHER_PROCESS";
            }
            if (leaseExpired) {
                return "LEASE_EXPIRED";
            }
            return "UNKNOWN";
        }
        if (StringUtils.hasText(claimedBy) && !instanceHeartbeat.isAlive(claimedBy)) {
            return leaseExpired ? "HEARTBEAT_DEAD+LEASE_EXPIRED" : "HEARTBEAT_DEAD";
        }
        if (leaseExpired) {
            return "LEASE_EXPIRED";
        }
        return "UNKNOWN";
    }

    private void reclaimStaleRegeneratingDrafts() {
        int timeoutMin = Math.max(5, clusterProperties.getDraftRegenTimeoutMinutes());
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(timeoutMin);
        List<KnowledgeDraft> stale = knowledgeDraftMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDraft>()
                        .eq(KnowledgeDraft::getStatus, DraftStatus.REGENERATING.name())
                        .lt(KnowledgeDraft::getUpdatedDate, cutoff)
                        .last("LIMIT 100"));
        for (KnowledgeDraft d : stale) {
            try {
                draftService.recoverStaleRegeneratingDraft(d.getId(),
                        "服务重启或重跑超时（超过 " + timeoutMin + " 分钟）");
            } catch (Exception e) {
                log.warn("恢复超时 REGENERATING 草稿失败 draftId={}: {}", d.getId(), e.getMessage());
            }
        }
    }
}
