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
 * 孤儿流水线任务自动接管：无认领，或「租约过宽限 ∧ 认领方心跳已死」。
 * <p>禁止仅租约刚过期就抢；「仅心跳已死但租约未过宽限」也不抢。
 * 详见 docs/orphan-reclaim-lease-heartbeat-design.md。</p>
 * <p>每个节点均可扫描与续跑（有本机槽才真正拉起）；不再依赖 Leader 独占执行。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskOrphanReclaimScheduler {

    private static final Set<String> RECLAIMABLE_STATUSES = Set.of(
            TaskStatus.PULL_QUEUED.name(),
            TaskStatus.PULLING_CODE.name(),
            TaskStatus.PARSE_QUEUED.name(),
            TaskStatus.PARSING_CODE.name(),
            TaskStatus.AI_ANALYZING.name(),
            TaskStatus.MODULE_HIERARCHY.name(),
            TaskStatus.BASELINE_DOC_INHERIT.name(),
            TaskStatus.GENERATING_DOC.name(),
            TaskStatus.PUSHING.name()
    );

    /** 这些阶段重入前需要任务并发槽；先占槽再 CAS，避免抢认领后因无槽清成 NO_CLAIM。 */
    private static final Set<String> NEED_TASK_PERMIT_BEFORE_CAS = Set.of(
            TaskStatus.AI_ANALYZING.name(),
            TaskStatus.MODULE_HIERARCHY.name(),
            TaskStatus.BASELINE_DOC_INHERIT.name(),
            TaskStatus.GENERATING_DOC.name()
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
        LocalDateTime oldLease = fresh.getLeaseUntil();
        String status = fresh.getStatus();
        Long systemId = fresh.getSystemId();

        boolean permitHeld = false;
        if (NEED_TASK_PERMIT_BEFORE_CAS.contains(status)) {
            if (!taskConcurrencyLimiter.tryAcquire(systemId, taskId)) {
                log.debug("孤儿接管跳过（任务槽不足，不改认领）taskId={} status={}", taskId, status);
                return false;
            }
            permitHeld = true;
        }

        if (!claimService.tryCasTakeover(taskId, status, oldClaimed)) {
            if (permitHeld) {
                taskConcurrencyLimiter.release(systemId, taskId);
            }
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
        try {
            decompileTaskService.reclaimOrphanAndResume(taskId, oldClaimed, oldLease);
            return true;
        } catch (RuntimeException e) {
            if (permitHeld && taskConcurrencyLimiter.isHeldLocally(taskId)) {
                taskConcurrencyLimiter.release(systemId, taskId);
            }
            throw e;
        }
    }

    /**
     * 孤儿判定。
     * <ul>
     *   <li>无认领 → 孤儿</li>
     *   <li>租约过宽限 ∧ 认领方心跳已死 → 孤儿</li>
     *   <li>单机另：认领不是本进程（热重启）→ 孤儿</li>
     *   <li>仅租约刚过期 / 仅心跳已死 → <b>不</b>接管</li>
     * </ul>
     */
    boolean isOrphan(DecompileTask task) {
        String claimedBy = task.getClaimedBy();
        LocalDateTime leaseUntil = task.getLeaseUntil();
        boolean noClaimMeta = !StringUtils.hasText(claimedBy) && leaseUntil == null;
        if (noClaimMeta) {
            return true;
        }

        if (!clusterProperties.isEnabled()) {
            // 单机：认领不是本进程（热重启后旧 claimed_by）→ 立刻可接管
            if (StringUtils.hasText(claimedBy) && !clusterInstanceId.get().equals(claimedBy)) {
                return true;
            }
            // 单机不写 Redis 心跳；过宽限即可（活任务仍由 isHeldLocally / isPipelineThreadActive 挡住）
            return isLeaseExpiredWithGrace(leaseUntil);
        }

        if (!isLeaseExpiredWithGrace(leaseUntil)) {
            return false;
        }
        // 有租约无认领：异常残留，过宽限后可接管
        if (!StringUtils.hasText(claimedBy)) {
            return true;
        }
        // 集群：过宽限 ∧ 认领方心跳已死
        return !instanceHeartbeat.isAlive(claimedBy);
    }

    /** {@code now > lease_until + grace} */
    boolean isLeaseExpiredWithGrace(LocalDateTime leaseUntil) {
        if (leaseUntil == null) {
            return false;
        }
        int grace = clusterProperties.resolveTaskLeaseGraceMinutes();
        return LocalDateTime.now().isAfter(leaseUntil.plusMinutes(grace));
    }

    String describeOrphanReason(DecompileTask task) {
        String claimedBy = task.getClaimedBy();
        LocalDateTime leaseUntil = task.getLeaseUntil();
        boolean noClaimMeta = !StringUtils.hasText(claimedBy) && leaseUntil == null;
        if (noClaimMeta) {
            return "NO_CLAIM";
        }
        if (!clusterProperties.isEnabled()
                && StringUtils.hasText(claimedBy)
                && !clusterInstanceId.get().equals(claimedBy)) {
            return "CLAIMED_BY_OTHER_PROCESS";
        }
        boolean pastGrace = isLeaseExpiredWithGrace(leaseUntil);
        boolean heartbeatDead = StringUtils.hasText(claimedBy) && !instanceHeartbeat.isAlive(claimedBy);
        if (pastGrace && heartbeatDead) {
            return "LEASE_GRACE_EXPIRED+HEARTBEAT_DEAD";
        }
        if (pastGrace && !StringUtils.hasText(claimedBy)) {
            return "LEASE_GRACE_EXPIRED+NO_CLAIMER";
        }
        if (pastGrace) {
            return "LEASE_GRACE_EXPIRED";
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
