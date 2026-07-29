package com.company.codeinsight.modules.task;

import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.service.impl.DecompileTaskServiceImpl;
import com.company.codeinsight.modules.task.service.impl.TaskStateMachineServiceImpl;
import org.junit.jupiter.api.AfterEach;
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
        ReflectionTestUtils.setField(stateMachineService, "workspaceMapper",
                Mockito.mock(com.company.codeinsight.modules.draft.mapper.DraftWorkspaceMapper.class));
        ReflectionTestUtils.setField(stateMachineService, "draftMapper",
                Mockito.mock(com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper.class));
    }

    @AfterEach
    void tearDown() {
        DecompileTaskServiceImpl.taskCache.clear();
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

    /**
     * 回归：autoConfirm 用 DB 新实体 GENERATING_DOC→CONFIRMED 时，须同步流水线 taskCache，
     * 否则随后 transitTo(taskId, PUSHING) 会读到过期 GENERATING_DOC 并报非法流转。
     */
    @Test
    void transitOnDbEntitySyncsTaskCacheForSubsequentPushing() {
        Long taskId = 99001L;
        DecompileTask cached = new DecompileTask();
        cached.setId(taskId);
        cached.setSystemId(1L);
        cached.setStatus(TaskStatus.GENERATING_DOC.name());
        cached.setProgress(90);
        DecompileTaskServiceImpl.taskCache.put(taskId, cached);

        DecompileTask fromDb = new DecompileTask();
        fromDb.setId(taskId);
        fromDb.setSystemId(1L);
        fromDb.setStatus(TaskStatus.GENERATING_DOC.name());
        fromDb.setProgress(90);

        stateMachineService.transitTo(fromDb, TaskStatus.CONFIRMED, "跳过知识复核，自动确认");

        Assertions.assertEquals(TaskStatus.CONFIRMED.name(), cached.getStatus());
        Assertions.assertEquals(TaskStatus.CONFIRMED.name(), fromDb.getStatus());

        Assertions.assertDoesNotThrow(() ->
                stateMachineService.transitTo(taskId, TaskStatus.PUSHING, null));
        Assertions.assertEquals(TaskStatus.PUSHING.name(), cached.getStatus());
    }
}
