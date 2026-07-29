package com.company.codeinsight.modules.task.support;

import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * 任务「执行耗时」累加器：仅在自动流水线阶段计时，人工断点 / 排队 / 待推送等等待态不计入。
 * <p>{@code ci_task.duration_ms} 存已累计执行毫秒；{@code active_segment_started_at} 为当前执行段起点。</p>
 */
public final class TaskExecutionDuration {

    private static final Set<TaskStatus> EXECUTING = EnumSet.of(
            TaskStatus.PULLING_CODE,
            TaskStatus.PARSING_CODE,
            TaskStatus.SPLITTING_TASK,
            TaskStatus.AI_ANALYZING,
            TaskStatus.MODULE_HIERARCHY,
            TaskStatus.BASELINE_DOC_INHERIT,
            TaskStatus.GENERATING_DOC,
            TaskStatus.PUSHING
    );

    private static final Set<TaskStatus> PAUSED = EnumSet.of(
            TaskStatus.DRAFT,
            TaskStatus.PENDING,
            TaskStatus.RESUME_QUEUED,
            TaskStatus.ENTRYPOINT_REVIEW,
            TaskStatus.MODULE_HIERARCHY_REVIEW,
            TaskStatus.PENDING_REVIEW,
            TaskStatus.REVIEWING,
            TaskStatus.CONFIRMED
    );

    private static final Set<TaskStatus> TERMINAL = EnumSet.of(
            TaskStatus.FAILED,
            TaskStatus.PUSHED,
            TaskStatus.CANCELLED,
            TaskStatus.ARCHIVED
    );

    private TaskExecutionDuration() {
    }

    public static boolean isExecuting(TaskStatus status) {
        return status != null && EXECUTING.contains(status);
    }

    public static boolean isPaused(TaskStatus status) {
        return status != null && PAUSED.contains(status);
    }

    public static boolean isTerminal(TaskStatus status) {
        return status != null && TERMINAL.contains(status);
    }

    /**
     * 状态流转时维护执行耗时与当前执行段。
     */
    public static void onStatusChange(DecompileTask task, TaskStatus from, TaskStatus to, LocalDateTime now) {
        if (from != null && isExecuting(from) && (isPaused(to) || isTerminal(to))) {
            flushSegment(task, now);
        }
        if (from != null && isPaused(from) && isExecuting(to)) {
            startSegment(task, now);
        }
        if (to != null && isTerminal(to)) {
            task.setEndedAt(now);
            task.setActiveSegmentStartedAt(null);
        }
    }

    public static void flushSegment(DecompileTask task, LocalDateTime now) {
        LocalDateTime segmentStart = task.getActiveSegmentStartedAt();
        if (segmentStart == null) {
            return;
        }
        long elapsed = Duration.between(segmentStart, now).toMillis();
        if (elapsed > 0) {
            long base = task.getDurationMs() == null ? 0L : task.getDurationMs();
            task.setDurationMs(base + elapsed);
        }
        task.setActiveSegmentStartedAt(null);
    }

    public static void startSegment(DecompileTask task, LocalDateTime now) {
        if (task.getActiveSegmentStartedAt() == null) {
            task.setActiveSegmentStartedAt(now);
        }
    }

    /** 重试 / 重置时清空计时状态 */
    public static void resetTiming(DecompileTask task) {
        task.setDurationMs(null);
        task.setStartedAt(null);
        task.setEndedAt(null);
        task.setActiveSegmentStartedAt(null);
    }

    /**
     * 读取展示用执行耗时：已累加 + 当前执行段（若仍在自动阶段）。
     */
    public static long resolveLiveDurationMs(DecompileTask task, LocalDateTime now) {
        if (task == null) {
            return 0L;
        }
        long base = task.getDurationMs() == null ? 0L : task.getDurationMs();
        if (task.getActiveSegmentStartedAt() != null && task.getStatus() != null) {
            try {
                TaskStatus status = TaskStatus.valueOf(task.getStatus());
                if (isExecuting(status)) {
                    base += Duration.between(task.getActiveSegmentStartedAt(), now).toMillis();
                }
            } catch (IllegalArgumentException ignored) {
                // unknown status
            }
        }
        return Math.max(0L, base);
    }

    public static void enrichLiveDurationForRead(DecompileTask task, LocalDateTime now) {
        if (task == null || task.getStatus() == null) {
            return;
        }
        try {
            TaskStatus status = TaskStatus.valueOf(task.getStatus());
            if (isExecuting(status) || task.getActiveSegmentStartedAt() != null) {
                task.setDurationMs(resolveLiveDurationMs(task, now));
            }
        } catch (IllegalArgumentException ignored) {
            // keep persisted value
        }
    }
}
