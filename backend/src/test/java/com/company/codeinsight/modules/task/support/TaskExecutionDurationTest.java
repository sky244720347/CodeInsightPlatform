package com.company.codeinsight.modules.task.support;

import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

class TaskExecutionDurationTest {

    @Test
    void executingToPausedAccumulatesWithoutWallClockGap() {
        DecompileTask task = new DecompileTask();
        task.setDurationMs(0L);
        LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 10, 0, 0);

        TaskExecutionDuration.onStatusChange(task, TaskStatus.PENDING, TaskStatus.PULLING_CODE, t0);
        Assertions.assertNotNull(task.getActiveSegmentStartedAt());

        LocalDateTime t1 = t0.plusSeconds(120);
        TaskExecutionDuration.onStatusChange(task, TaskStatus.PULLING_CODE, TaskStatus.ENTRYPOINT_REVIEW, t1);

        Assertions.assertNull(task.getActiveSegmentStartedAt());
        Assertions.assertEquals(120_000L, task.getDurationMs());

        // 断点等待 2 天不应改变 duration_ms
        LocalDateTime t2 = t1.plusDays(2);
        TaskExecutionDuration.onStatusChange(task, TaskStatus.ENTRYPOINT_REVIEW, TaskStatus.AI_ANALYZING, t2);
        TaskExecutionDuration.onStatusChange(task, TaskStatus.AI_ANALYZING, TaskStatus.PENDING_REVIEW, t2.plusSeconds(30));

        Assertions.assertEquals(150_000L, task.getDurationMs());
    }

    @Test
    void resolveLiveDurationIncludesRunningSegment() {
        DecompileTask task = new DecompileTask();
        task.setStatus(TaskStatus.PULLING_CODE.name());
        task.setDurationMs(1000L);
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
        task.setActiveSegmentStartedAt(start);
        LocalDateTime now = start.plusSeconds(5);

        Assertions.assertEquals(6000L, TaskExecutionDuration.resolveLiveDurationMs(task, now));
    }

    @Test
    void pausedStatusDoesNotInflateLiveDuration() {
        DecompileTask task = new DecompileTask();
        task.setStatus(TaskStatus.ENTRYPOINT_REVIEW.name());
        task.setDurationMs(90_000L);
        LocalDateTime now = LocalDateTime.of(2026, 1, 3, 10, 0, 0);

        Assertions.assertEquals(90_000L, TaskExecutionDuration.resolveLiveDurationMs(task, now));
    }
}
