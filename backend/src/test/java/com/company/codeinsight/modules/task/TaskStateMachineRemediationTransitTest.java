package com.company.codeinsight.modules.task;

import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.service.impl.TaskStateMachineServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class TaskStateMachineRemediationTransitTest {

    private TaskStateMachineServiceImpl stateMachineService;

    @BeforeEach
    void setUp() {
        stateMachineService = new TaskStateMachineServiceImpl();
        ReflectionTestUtils.setField(stateMachineService, "decompileTaskMapper",
                Mockito.mock(com.company.codeinsight.modules.task.mapper.DecompileTaskMapper.class));
        ReflectionTestUtils.setField(stateMachineService, "operationLogService",
                Mockito.mock(com.company.codeinsight.modules.log.service.OperationLogService.class));
    }

    @Test
    void pendingToAiAnalyzingAllowed() {
        Assertions.assertTrue(stateMachineService.canTransit(TaskStatus.PENDING, TaskStatus.AI_ANALYZING));
    }

    @Test
    void pendingToGeneratingDocAllowed() {
        Assertions.assertTrue(stateMachineService.canTransit(TaskStatus.PENDING, TaskStatus.GENERATING_DOC));
    }

    @Test
    void pendingToModuleHierarchyNotAllowed() {
        Assertions.assertFalse(stateMachineService.canTransit(TaskStatus.PENDING, TaskStatus.MODULE_HIERARCHY));
    }

    @Test
    void generatingDocIdempotent() {
        Assertions.assertTrue(stateMachineService.canTransit(TaskStatus.GENERATING_DOC, TaskStatus.GENERATING_DOC));
    }
}
