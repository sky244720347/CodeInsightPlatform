package com.company.codeinsight.modules.task.service.impl;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.log.service.OperationLogService;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.company.codeinsight.modules.task.service.TaskStateMachineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 任务状态机服务实现类
 * 负责维护反编译及分析任务生命周期中的状态流转、合法性校验、进度更新及审计日志记录。
 */
@Service
public class TaskStateMachineServiceImpl implements TaskStateMachineService {

    @Autowired
    private DecompileTaskMapper decompileTaskMapper;

    @Autowired
    private OperationLogService operationLogService;

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
        task.setUpdatedAt(LocalDateTime.now());

        // 任务开始执行时记录启动时间
        if (targetStatus == TaskStatus.PENDING || targetStatus == TaskStatus.PULLING_CODE) {
            if (task.getStartedAt() == null
                    || currentStatus == TaskStatus.FAILED
                    || currentStatus == TaskStatus.CANCELLED) {
                task.setStartedAt(LocalDateTime.now());
            }
        }

        // 从失败/取消重入队列时清空结束时间与耗时
        if (targetStatus == TaskStatus.PENDING
                && (currentStatus == TaskStatus.FAILED || currentStatus == TaskStatus.CANCELLED)) {
            task.setEndedAt(null);
            task.setDurationMs(null);
        }

        // 任务进入终态时记录结束时间，并计算运行耗时（毫秒）
        if (targetStatus == TaskStatus.FAILED || targetStatus == TaskStatus.PUSHED || targetStatus == TaskStatus.CANCELLED || targetStatus == TaskStatus.ARCHIVED) {
            task.setEndedAt(LocalDateTime.now());
            if (task.getStartedAt() != null) {
                long duration = java.time.Duration.between(task.getStartedAt(), task.getEndedAt()).toMillis();
                task.setDurationMs(duration);
            }
        }

        // 失败/取消原因写入；其余流转一律清空，避免重试后仍展示旧错误
        if (targetStatus == TaskStatus.FAILED && errorReason != null) {
            task.setErrorReason(errorReason);
        } else if (targetStatus == TaskStatus.CANCELLED && errorReason != null) {
            task.setErrorReason(errorReason);
        } else {
            task.setErrorReason(null);
        }

        // 根据所处阶段，自动分配标准进度百分比，供前端进度条进行直观展示
        switch (targetStatus) {
            case PENDING -> task.setProgress(0);
            case PULLING_CODE -> task.setProgress(10);
            case PARSING_CODE -> task.setProgress(30);
            case SPLITTING_TASK -> task.setProgress(50);
            case ENTRYPOINT_REVIEW -> task.setProgress(60);
            case AI_ANALYZING -> task.setProgress(70);
            case MODULE_HIERARCHY -> task.setProgress(85);
            case MODULE_HIERARCHY_REVIEW -> task.setProgress(88);
            case GENERATING_DOC -> task.setProgress(90);
            case PENDING_REVIEW -> task.setProgress(100);
            case CONFIRMED -> task.setProgress(100);
            case PUSHED -> task.setProgress(100);
        }

        // 持久化更新至数据库
        decompileTaskMapper.updateById(task);

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
            case PENDING -> target == TaskStatus.PULLING_CODE || target == TaskStatus.AI_ANALYZING
                    || target == TaskStatus.GENERATING_DOC || target == TaskStatus.CANCELLED || target == TaskStatus.FAILED;
            case PULLING_CODE -> target == TaskStatus.PARSING_CODE || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
            case PARSING_CODE -> target == TaskStatus.SPLITTING_TASK || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
            case SPLITTING_TASK -> target == TaskStatus.ENTRYPOINT_REVIEW || target == TaskStatus.AI_ANALYZING || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
            case ENTRYPOINT_REVIEW -> target == TaskStatus.AI_ANALYZING || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
            case AI_ANALYZING -> target == TaskStatus.MODULE_HIERARCHY || target == TaskStatus.GENERATING_DOC || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
            case MODULE_HIERARCHY -> target == TaskStatus.MODULE_HIERARCHY_REVIEW || target == TaskStatus.GENERATING_DOC || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
            case MODULE_HIERARCHY_REVIEW -> target == TaskStatus.GENERATING_DOC || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
            case GENERATING_DOC -> target == TaskStatus.PENDING_REVIEW || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
            case PENDING_REVIEW -> target == TaskStatus.REVIEWING || target == TaskStatus.CONFIRMED || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
            case REVIEWING -> target == TaskStatus.CONFIRMED || target == TaskStatus.PENDING_REVIEW || target == TaskStatus.CANCELLED;
            case CONFIRMED -> target == TaskStatus.PUSHING || target == TaskStatus.ARCHIVED || target == TaskStatus.CANCELLED;
            case PUSHING -> target == TaskStatus.PUSHED || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
            case FAILED -> target == TaskStatus.PENDING || target == TaskStatus.ARCHIVED;
            case CANCELLED -> target == TaskStatus.PENDING || target == TaskStatus.ARCHIVED;
            case PUSHED, ARCHIVED -> false; // 已推送或已归档是最终结算状态，不可流转回其他状态
        };
    }
}
