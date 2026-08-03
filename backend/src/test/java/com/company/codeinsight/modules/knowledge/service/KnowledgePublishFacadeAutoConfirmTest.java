package com.company.codeinsight.modules.knowledge.service;

import com.company.codeinsight.modules.draft.mapper.DraftWorkspaceMapper;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.push.service.PushService;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.company.codeinsight.modules.task.service.TaskDiskCleanupService;
import com.company.codeinsight.modules.task.service.TaskStateMachineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgePublishFacadeAutoConfirmTest {

    @Mock
    private KnowledgeService knowledgeService;
    @Mock
    private PushService pushService;
    @Mock
    private TaskDiskCleanupService diskCleanupService;
    @Mock
    private TaskStateMachineService stateMachineService;
    @Mock
    private DecompileTaskMapper taskMapper;
    @Mock
    private DraftWorkspaceMapper workspaceMapper;
    @Mock
    private KnowledgeDraftMapper draftMapper;

    private KnowledgePublishFacade facade;

    @BeforeEach
    void setUp() {
        facade = new KnowledgePublishFacade(
                knowledgeService,
                pushService,
                diskCleanupService,
                stateMachineService,
                taskMapper,
                workspaceMapper,
                draftMapper,
                Runnable::run);
    }

    @Test
    void autoConfirm_pushed_isIdempotentSkip() {
        DecompileTask task = new DecompileTask();
        task.setId(91L);
        task.setStatus(TaskStatus.PUSHED.name());
        when(taskMapper.selectById(91L)).thenReturn(task);

        assertDoesNotThrow(() -> facade.autoConfirmAndPublish(91L));
        verify(stateMachineService, never()).transitTo(any(DecompileTask.class), any(), anyString());
        verify(workspaceMapper, never()).selectOne(any());
    }

    @Test
    void autoConfirm_pushing_isIdempotentSkip() {
        DecompileTask task = new DecompileTask();
        task.setId(92L);
        task.setStatus(TaskStatus.PUSHING.name());
        when(taskMapper.selectById(92L)).thenReturn(task);

        assertDoesNotThrow(() -> facade.autoConfirmAndPublish(92L));
        verify(stateMachineService, never()).transitTo(any(DecompileTask.class), any(), anyString());
        verify(pushService, never()).enqueuePush(anyLong(), any());
    }
}
