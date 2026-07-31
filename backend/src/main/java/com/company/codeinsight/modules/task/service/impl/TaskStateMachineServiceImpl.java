package com.company.codeinsight.modules.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.draft.entity.DraftWorkspace;
import com.company.codeinsight.modules.draft.enums.DraftStatus;
import com.company.codeinsight.modules.draft.mapper.DraftWorkspaceMapper;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.log.service.OperationLogService;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.support.TaskExecutionDuration;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.company.codeinsight.modules.task.service.TaskStateMachineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务状态机服务实现类
 * 负责维护反编译及分析任务生命周期中的状态流转、合法性校验、进度更新及审计日志记录。
 */
@Slf4j
@Service
public class TaskStateMachineServiceImpl implements TaskStateMachineService {

    @Autowired
    private DecompileTaskMapper decompileTaskMapper;

    @Autowired
    private OperationLogService operationLogService;

    /**
     * 草稿/工作区级联归档器：任务流转到 CANCELLED / FAILED 时，
     * 把 taskId 关联的 ci_draft_workspace.status 与 ci_knowledge_draft.status 一并置 ARCHIVED，
     * 避免「任务已终止、草稿仍 DRAFT/EDITING」的孤儿状态污染 readiness 视图。
     */
    @Autowired
    private DraftWorkspaceMapper workspaceMapper;

    @Autowired
    private KnowledgeDraftMapper draftMapper;

    /**
     * 根据任务 ID 触发状态流转
     *
     * @param taskId       任务唯一标识
     * @param targetStatus 目标状态
     * @param errorReason  失败原因（非 FAILED 状态下传 null）
     */
    @Override
    @Transactional
    public void transitTo(Long taskId, TaskStatus targetStatus, String errorReason) {
        // 优先从本地缓存查询任务状态（缓存中保存的是最新内存状态，避免事务未提交导致读到旧状态的竞态问题）
        DecompileTask task = DecompileTaskServiceImpl.taskCache.get(taskId);
        if (task == null) {
            // 缓存未命中时回退至数据库查询
            task = decompileTaskMapper.selectById(taskId);
        }
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        transitTo(task, targetStatus, errorReason);
    }

    /**
     * 触发指定任务实体的状态流转
     *
     * @param task         任务实体对象
     * @param targetStatus 目标状态
     * @param errorReason  失败原因（非 FAILED 状态下传 null）
     */
    @Override
    @Transactional
    public void transitTo(DecompileTask task, TaskStatus targetStatus, String errorReason) {
        TaskStatus currentStatus = TaskStatus.valueOf(task.getStatus());
        
        // 校验本次状态转移是否合法
        if (!canTransit(currentStatus, targetStatus)) {
            throw new BusinessException("非法的状态流转: " + currentStatus + " -> " + targetStatus);
        }

        // 更新状态与最后修改时间
        task.setStatus(targetStatus.name());
        task.setUpdatedDate(LocalDateTime.now());

        LocalDateTime now = LocalDateTime.now();

        // 任务首次进入拉代码排队/拉取时记录挂钟启动时间（仅作参考，不参与执行耗时）
        if (targetStatus == TaskStatus.PULL_QUEUED || targetStatus == TaskStatus.PULLING_CODE) {
            if (task.getStartedAt() == null
                    || currentStatus == TaskStatus.FAILED
                    || currentStatus == TaskStatus.CANCELLED) {
                task.setStartedAt(now);
            }
        }

        // 从失败/取消重入队列时清空计时
        if (targetStatus == TaskStatus.PENDING
                && (currentStatus == TaskStatus.FAILED || currentStatus == TaskStatus.CANCELLED)) {
            TaskExecutionDuration.resetTiming(task);
        }

        // 执行耗时：离开自动阶段时累加，进入自动阶段时开启新段
        TaskExecutionDuration.onStatusChange(task, currentStatus, targetStatus, now);

        // 失败/取消原因写入；其余流转一律清空，避免重试后仍展示旧错误
        if (targetStatus == TaskStatus.FAILED && errorReason != null) {
            task.setErrorReason(com.company.codeinsight.common.util.DbStringLimits.truncate(
                    errorReason, com.company.codeinsight.common.util.DbStringLimits.ERROR_REASON));
        } else if (targetStatus == TaskStatus.CANCELLED && errorReason != null) {
            task.setErrorReason(com.company.codeinsight.common.util.DbStringLimits.truncate(
                    errorReason, com.company.codeinsight.common.util.DbStringLimits.ERROR_REASON));
        } else {
            task.setErrorReason(null);
        }

        // 根据所处阶段，自动分配标准进度百分比，供前端进度条进行直观展示
        switch (targetStatus) {
            case PENDING, RESUME_QUEUED, PULL_QUEUED -> task.setProgress(0);
            case PULLING_CODE, PARSE_QUEUED -> task.setProgress(10);
            case PARSING_CODE -> task.setProgress(35);
            case SPLITTING_TASK -> task.setProgress(40);
            case ENTRYPOINT_REVIEW -> task.setProgress(45);
            case AI_ANALYZING -> task.setProgress(55);
            case MODULE_HIERARCHY -> task.setProgress(75);
            case MODULE_HIERARCHY_REVIEW -> task.setProgress(82);
            case BASELINE_DOC_INHERIT -> task.setProgress(85);
            case GENERATING_DOC -> task.setProgress(90);
            case PENDING_REVIEW -> task.setProgress(100);
            case CONFIRMED -> task.setProgress(100);
            case PUSHED -> task.setProgress(100);
            default -> { /* FAILED/CANCELLED/REVIEWING 等保持既有 progress */ }
        }

        // 持久化更新至数据库
        decompileTaskMapper.updateById(task);

        // 流水线持有的 taskCache 实例可能与本次传入实体不是同一引用
        // （例如 autoConfirmAndPublish 用 selectById 新对象流转到 CONFIRMED）。
        // transitTo(taskId) 优先读 cache，必须把可变状态同步回去，否则会出现
        // DB=CONFIRMED 而 cache 仍为 GENERATING_DOC，随后 CONFIRMED→PUSHING 被误判为非法。
        syncTaskCacheAfterTransit(task);

        // 任务级联归档：流转到 CANCELLED / FAILED 时，把关联的 workspace + draft 一并置 ARCHIVED。
        // 否则会出现「任务已终止但草稿仍 DRAFT/EDITING」的孤儿状态，污染 readiness 视图。
        if (targetStatus == TaskStatus.CANCELLED || targetStatus == TaskStatus.FAILED) {
            cascadeArchiveDraftsAndWorkspaces(task.getId());
        }

        // 记录状态流转至系统操作审计日志中
        operationLogService.logOperation(
                task.getSystemId(),
                task.getId(),
                "TASK_TRANSIT",
                "任务状态变更: " + currentStatus + " -> " + targetStatus,
                errorReason,
                true
        );
    }

    /**
     * 将本次流转后的可变字段同步到流水线 {@link DecompileTaskServiceImpl#taskCache} 中的实例。
     * 仅在 cache 命中且与传入实体不是同一引用时拷贝；同引用已就地更新，无需处理。
     */
    private void syncTaskCacheAfterTransit(DecompileTask task) {
        if (task == null || task.getId() == null) {
            return;
        }
        DecompileTask cached = DecompileTaskServiceImpl.taskCache.get(task.getId());
        if (cached == null || cached == task) {
            return;
        }
        cached.setStatus(task.getStatus());
        cached.setProgress(task.getProgress());
        cached.setErrorReason(task.getErrorReason());
        cached.setUpdatedDate(task.getUpdatedDate());
        cached.setStartedAt(task.getStartedAt());
        cached.setEndedAt(task.getEndedAt());
        cached.setDurationMs(task.getDurationMs());
        cached.setActiveSegmentStartedAt(task.getActiveSegmentStartedAt());
    }

    /**
     * 把 taskId 关联的 ci_draft_workspace 与 ci_knowledge_draft 一并置 ARCHIVED。
     * 幂等：仅修改状态非 ARCHIVED 的行（避免重复触发时 update_count 假阳性）。
     */
    private void cascadeArchiveDraftsAndWorkspaces(Long taskId) {
        // 1. 查 taskId 关联的所有 workspace
        List<DraftWorkspace> workspaces = workspaceMapper.selectList(
                new LambdaQueryWrapper<DraftWorkspace>()
                        .eq(DraftWorkspace::getTaskId, taskId)
        );
        if (workspaces.isEmpty()) {
            return;
        }
        List<Long> workspaceIds = workspaces.stream()
                .map(DraftWorkspace::getId)
                .collect(java.util.stream.Collectors.toList());

        // 2. 批量归档 workspace
        int wsArchived = workspaceMapper.update(null,
                new LambdaUpdateWrapper<DraftWorkspace>()
                        .in(DraftWorkspace::getId, workspaceIds)
                        .ne(DraftWorkspace::getStatus, "ARCHIVED")
                        .set(DraftWorkspace::getStatus, "ARCHIVED")
                        .set(DraftWorkspace::getUpdatedDate, LocalDateTime.now())
        );

        // 3. 批量归档 workspace 下的非终态草稿
        int draftArchived = draftMapper.update(null,
                new LambdaUpdateWrapper<com.company.codeinsight.modules.draft.entity.KnowledgeDraft>()
                        .in(com.company.codeinsight.modules.draft.entity.KnowledgeDraft::getWorkspaceId, workspaceIds)
                        .notIn(com.company.codeinsight.modules.draft.entity.KnowledgeDraft::getStatus,
                                java.util.List.of(
                                        DraftStatus.CONFIRMED.name(),
                                        DraftStatus.PUSHED.name(),
                                        DraftStatus.ARCHIVED.name()
                                ))
                        .set(com.company.codeinsight.modules.draft.entity.KnowledgeDraft::getStatus,
                                DraftStatus.ARCHIVED.name())
                        .set(com.company.codeinsight.modules.draft.entity.KnowledgeDraft::getUpdatedDate,
                                LocalDateTime.now())
        );

        log.info("cascadeArchiveDraftsAndWorkspaces: taskId={} workspaces={} drafts={}",
                taskId, wsArchived, draftArchived);
    }

    /**
     * 判断状态是否允许流转
     * 遵循严格的任务生命周期闭环流转规范
     *
     * @param current 当前所处状态
     * @param target  目标期望状态
     * @return 是否允许流转
     */
    @Override
    public boolean canTransit(TaskStatus current, TaskStatus target) {
        // 状态相同属于幂等操作，直接通过
        if (current == target) {
            return true;
        }
        // 根据状态转移矩阵判定流转合法性
        return switch (current) {
            case DRAFT -> target == TaskStatus.PENDING || target == TaskStatus.CANCELLED;
            case PENDING -> target == TaskStatus.PULL_QUEUED || target == TaskStatus.PULLING_CODE
                    || target == TaskStatus.AI_ANALYZING || target == TaskStatus.GENERATING_DOC
                    || target == TaskStatus.CANCELLED || target == TaskStatus.FAILED;
            case PULL_QUEUED -> target == TaskStatus.PULLING_CODE || target == TaskStatus.FAILED
                    || target == TaskStatus.CANCELLED;
            case PULLING_CODE -> target == TaskStatus.PARSE_QUEUED || target == TaskStatus.PARSING_CODE
                    || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
            case PARSE_QUEUED -> target == TaskStatus.PARSING_CODE || target == TaskStatus.FAILED
                    || target == TaskStatus.CANCELLED;
            case PARSING_CODE -> target == TaskStatus.ENTRYPOINT_REVIEW || target == TaskStatus.AI_ANALYZING
                    || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
            case SPLITTING_TASK -> target == TaskStatus.ENTRYPOINT_REVIEW || target == TaskStatus.AI_ANALYZING
                    || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
            case ENTRYPOINT_REVIEW -> target == TaskStatus.RESUME_QUEUED || target == TaskStatus.AI_ANALYZING
                    || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
            case AI_ANALYZING -> target == TaskStatus.MODULE_HIERARCHY || target == TaskStatus.GENERATING_DOC || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
            case MODULE_HIERARCHY -> target == TaskStatus.MODULE_HIERARCHY_REVIEW || target == TaskStatus.BASELINE_DOC_INHERIT || target == TaskStatus.GENERATING_DOC || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
            case MODULE_HIERARCHY_REVIEW -> target == TaskStatus.RESUME_QUEUED || target == TaskStatus.BASELINE_DOC_INHERIT
                    || target == TaskStatus.GENERATING_DOC || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
            case RESUME_QUEUED -> target == TaskStatus.AI_ANALYZING || target == TaskStatus.BASELINE_DOC_INHERIT
                    || target == TaskStatus.GENERATING_DOC || target == TaskStatus.CANCELLED || target == TaskStatus.FAILED;
            case BASELINE_DOC_INHERIT -> target == TaskStatus.GENERATING_DOC || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
            case GENERATING_DOC -> target == TaskStatus.PENDING_REVIEW || target == TaskStatus.CONFIRMED
                    || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
            case PENDING_REVIEW -> target == TaskStatus.REVIEWING || target == TaskStatus.CONFIRMED || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
            case REVIEWING -> target == TaskStatus.CONFIRMED || target == TaskStatus.PENDING_REVIEW || target == TaskStatus.CANCELLED;
            case CONFIRMED -> target == TaskStatus.PUSHING || target == TaskStatus.ARCHIVED || target == TaskStatus.CANCELLED;
            case PUSHING -> target == TaskStatus.PUSHED || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
            case FAILED -> target == TaskStatus.PENDING || target == TaskStatus.BASELINE_DOC_INHERIT || target == TaskStatus.ARCHIVED;
            case CANCELLED -> target == TaskStatus.PENDING || target == TaskStatus.ARCHIVED;
            case PUSHED, ARCHIVED -> false; // 已推送或已归档是最终结算状态，不可流转回其他状态
        };
    }
}
