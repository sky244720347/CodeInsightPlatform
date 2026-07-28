package com.company.codeinsight.modules.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.auth.OperatorContext;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识确认完成后的统一编排：组装发布包 → 清源码/drafts → 自增建版 → NAS 入队推送。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgePublishFacade {

    private final KnowledgeService knowledgeService;
    private final PushService pushService;
    private final TaskDiskCleanupService diskCleanupService;
    private final TaskStateMachineService stateMachineService;
    private final DecompileTaskMapper taskMapper;
    private final DraftWorkspaceMapper workspaceMapper;
    private final KnowledgeDraftMapper draftMapper;

    /**
     * 跳过知识复核：草稿自动 CONFIRMED，任务 GENERATING_DOC → CONFIRMED，再发布。
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
        onKnowledgeConfirmed(taskId, resolveOperator());
    }

    /**
     * 人工/自动确认完成后调用（任务须已是 CONFIRMED）。
     */
    public void onKnowledgeConfirmed(Long taskId, String confirmedBy) {
        DecompileTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        if (!TaskStatus.CONFIRMED.name().equals(task.getStatus())) {
            throw new BusinessException("任务尚未确认，无法发布: " + task.getStatus());
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
