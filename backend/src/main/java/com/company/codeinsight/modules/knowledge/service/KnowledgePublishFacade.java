package com.company.codeinsight.modules.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.auth.OperatorContext;
import com.company.codeinsight.common.config.AsyncExecutorConfig;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.draft.entity.DraftWorkspace;
import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import com.company.codeinsight.modules.draft.enums.DraftStatus;
import com.company.codeinsight.modules.draft.mapper.DraftWorkspaceMapper;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.knowledge.entity.KnowledgeVersion;
import com.company.codeinsight.modules.push.enums.PushMethod;
import com.company.codeinsight.modules.push.service.PushService;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.company.codeinsight.modules.task.service.TaskDiskCleanupService;
import com.company.codeinsight.modules.task.service.TaskStateMachineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 知识确认完成后的统一编排：组装发布包 → 清源码/drafts → 自增建版 → NAS 入队推送。
 *
 * <p>人工 {@code confirmTask} / 跳过复核 {@link #autoConfirmAndPublish} 均先完成 CONFIRMED，
 * 再经 {@link #schedulePublishAfterConfirmed} 在事务提交后异步执行 {@link #onKnowledgeConfirmed}，
 * 避免 HTTP / 流水线线程被组包与清盘拖过前端超时。</p>
 */
@Slf4j
@Service
public class KnowledgePublishFacade {

    private final KnowledgeService knowledgeService;
    private final PushService pushService;
    private final TaskDiskCleanupService diskCleanupService;
    private final TaskStateMachineService stateMachineService;
    private final DecompileTaskMapper taskMapper;
    private final DraftWorkspaceMapper workspaceMapper;
    private final KnowledgeDraftMapper draftMapper;
    private final Executor knowledgePublishExecutor;

    public KnowledgePublishFacade(
            KnowledgeService knowledgeService,
            PushService pushService,
            TaskDiskCleanupService diskCleanupService,
            TaskStateMachineService stateMachineService,
            DecompileTaskMapper taskMapper,
            DraftWorkspaceMapper workspaceMapper,
            KnowledgeDraftMapper draftMapper,
            @Qualifier(AsyncExecutorConfig.KNOWLEDGE_PUBLISH_EXECUTOR) Executor knowledgePublishExecutor) {
        this.knowledgeService = knowledgeService;
        this.pushService = pushService;
        this.diskCleanupService = diskCleanupService;
        this.stateMachineService = stateMachineService;
        this.taskMapper = taskMapper;
        this.workspaceMapper = workspaceMapper;
        this.draftMapper = draftMapper;
        this.knowledgePublishExecutor = knowledgePublishExecutor;
    }

    /**
     * 跳过知识复核：草稿自动 CONFIRMED，任务 GENERATING_DOC → CONFIRMED，再异步发布。
     */
    @Transactional(rollbackFor = Exception.class)
    public void autoConfirmAndPublish(Long taskId) {
        DecompileTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        markDraftsConfirmed(taskId);
        if (TaskStatus.GENERATING_DOC.name().equals(task.getStatus())) {
            stateMachineService.transitTo(task, TaskStatus.CONFIRMED, "跳过知识复核，自动确认");
        } else if (!TaskStatus.CONFIRMED.name().equals(task.getStatus())) {
            throw new BusinessException("任务状态不允许自动确认发布: " + task.getStatus());
        }
        schedulePublishAfterConfirmed(taskId, resolveOperator());
    }

    /**
     * 在当前事务提交后异步执行 {@link #onKnowledgeConfirmed}；无事务时立即投递线程池。
     * 发布失败只记日志，不回滚已完成的 CONFIRMED。
     */
    public void schedulePublishAfterConfirmed(Long taskId, String confirmedBy) {
        String operator = StringUtils.hasText(confirmedBy) ? confirmedBy : resolveOperator();
        Runnable job = () -> {
            try {
                onKnowledgeConfirmed(taskId, operator);
            } catch (Exception e) {
                log.error("异步知识发布失败（任务已 CONFIRMED，可稍后重试） taskId={}", taskId, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    knowledgePublishExecutor.execute(job);
                    log.info("schedulePublishAfterConfirmed: afterCommit 已投递 taskId={}", taskId);
                }
            });
            log.info("schedulePublishAfterConfirmed: 已注册 afterCommit taskId={}", taskId);
        } else {
            knowledgePublishExecutor.execute(job);
            log.info("schedulePublishAfterConfirmed: 无事务，立即投递 taskId={}", taskId);
        }
    }

    /**
     * 人工/自动确认完成后调用（任务须已是 CONFIRMED）。
     * 幂等：已 PUSHING / PUSHED 直接跳过。
     */
    public void onKnowledgeConfirmed(Long taskId, String confirmedBy) {
        DecompileTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        String status = task.getStatus();
        if (TaskStatus.PUSHING.name().equals(status) || TaskStatus.PUSHED.name().equals(status)) {
            log.info("onKnowledgeConfirmed skip: taskId={} already {}", taskId, status);
            return;
        }
        if (!TaskStatus.CONFIRMED.name().equals(status)) {
            throw new BusinessException("任务尚未确认，无法发布: " + status);
        }
        String operator = StringUtils.hasText(confirmedBy) ? confirmedBy : resolveOperator();
        // 先组包并建版（仍可读 drafts），再清源码/drafts，最后入队（推送只读 docs）
        knowledgeService.assemblePublishPackage(taskId);
        String versionNum = knowledgeService.nextSimpleVersionNum(task.getRepositoryId());
        KnowledgeVersion version = knowledgeService.createVersion(taskId, versionNum, operator);
        diskCleanupService.cleanupAfterKnowledgeConfirmed(taskId);
        pushService.enqueuePush(version.getId(), PushMethod.NAS);
        log.info("onKnowledgeConfirmed: taskId={} version={} → NAS enqueue", taskId, versionNum);
    }

    private void markDraftsConfirmed(Long taskId) {
        DraftWorkspace ws = workspaceMapper.selectOne(
                new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getTaskId, taskId));
        if (ws == null) {
            throw new BusinessException("草稿工作区不存在，无法自动确认");
        }
        LocalDateTime now = LocalDateTime.now();
        List<KnowledgeDraft> drafts = draftMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDraft>().eq(KnowledgeDraft::getWorkspaceId, ws.getId()));
        if (drafts.isEmpty()) {
            throw new BusinessException("工作区无草稿，无法自动确认");
        }
        for (KnowledgeDraft d : drafts) {
            if (!DraftStatus.CONFIRMED.name().equals(d.getStatus())
                    && !DraftStatus.PUSHED.name().equals(d.getStatus())
                    && !DraftStatus.ARCHIVED.name().equals(d.getStatus())) {
                d.setStatus(DraftStatus.CONFIRMED.name());
                d.setUpdatedDate(now);
                draftMapper.updateById(d);
            }
        }
        ws.setStatus("COMPLETED");
        ws.setUpdatedDate(now);
        workspaceMapper.updateById(ws);
    }

    private static String resolveOperator() {
        try {
            String op = OperatorContext.get();
            return StringUtils.hasText(op) ? op : "system";
        } catch (Exception e) {
            return "system";
        }
    }
}
