package com.company.codeinsight.modules.task;

import com.company.codeinsight.modules.knowledge.remediation.KnowledgeRemediationConstants;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.service.TaskQueueDispatcher;
import com.company.codeinsight.modules.task.service.impl.TaskStateMachineServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class TaskQueueDispatcherRemediationTest {

    private TaskQueueDispatcher dispatcher;
    private TaskStateMachineServiceImpl stateMachineService;

    @BeforeEach
    void setUp() {
        dispatcher = new TaskQueueDispatcher(
                Mockito.mock(com.company.codeinsight.modules.task.service.DecompileTaskService.class),
                Mockito.mock(TaskStateMachineServiceImpl.class),
                Mockito.mock(com.company.codeinsight.modules.task.service.TaskConcurrencyLimiter.class),
                Mockito.mock(com.company.codeinsight.modules.task.service.TaskQueueClaimService.class)
        );
        stateMachineService = Mockito.mock(TaskStateMachineServiceImpl.class);
        ReflectionTestUtils.setField(dispatcher, "stateMachineService", stateMachineService);
    }

    @Test
    void entryRemediationStartsAtAiAnalyzing() throws Exception {
        DecompileTask task = remediationTask(KnowledgeRemediationConstants.RESUME_AI_ANALYZING);
        invokeTransit(task);
        Mockito.verify(stateMachineService).transitTo(task, TaskStatus.AI_ANALYZING, null);
    }

    @Test
    void documentRemediationStartsAtGeneratingDoc() throws Exception {
        DecompileTask task = remediationTask(KnowledgeRemediationConstants.RESUME_GENERATING_DOC);
        invokeTransit(task);
        Mockito.verify(stateMachineService).transitTo(task, TaskStatus.GENERATING_DOC, null);
    }

    @Test
    void manualTaskStartsAtPullingCode() throws Exception {
        DecompileTask task = new DecompileTask();
        task.setTriggerSource("MANUAL");
        invokeTransit(task);
        Mockito.verify(stateMachineService).transitTo(task, TaskStatus.PULL_QUEUED, null);
    }

    @Test
    void unknownResumeThrows() {
        DecompileTask task = remediationTask("UNKNOWN");
        Exception ex = Assertions.assertThrows(Exception.class, () -> invokeTransit(task));
        Throwable cause = ex instanceof java.lang.reflect.InvocationTargetException
                ? ex.getCause() : ex;
        Assertions.assertInstanceOf(IllegalStateException.class, cause);
    }

    private DecompileTask remediationTask(String resumeFrom) {
        DecompileTask task = new DecompileTask();
        task.setTriggerSource(KnowledgeRemediationConstants.TRIGGER_SOURCE);
        task.setResumeFrom(resumeFrom);
        return task;
    }

    private void invokeTransit(DecompileTask task) throws Exception {
        var method = TaskQueueDispatcher.class.getDeclaredMethod("transitPendingToExecutionStart", DecompileTask.class);
        method.setAccessible(true);
        method.invoke(dispatcher, task);
    }
}
