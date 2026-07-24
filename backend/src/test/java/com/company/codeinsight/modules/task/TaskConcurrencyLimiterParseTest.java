package com.company.codeinsight.modules.task;

import com.company.codeinsight.modules.task.service.TaskConcurrencyLimiter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TaskConcurrencyLimiterParseTest {

    @Test
    void parseTaskId_ok() {
        assertEquals(123L, TaskConcurrencyLimiter.parseTaskId("task:123"));
    }

    @Test
    void parseTaskId_invalid() {
        assertNull(TaskConcurrencyLimiter.parseTaskId(null));
        assertNull(TaskConcurrencyLimiter.parseTaskId(""));
        assertNull(TaskConcurrencyLimiter.parseTaskId("ai:1"));
        assertNull(TaskConcurrencyLimiter.parseTaskId("task:abc"));
    }
}
