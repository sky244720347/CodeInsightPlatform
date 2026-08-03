package com.company.codeinsight.modules.task;

import com.company.codeinsight.modules.ai.mapper.AiCallRecordMapper;
import com.company.codeinsight.modules.hierarchy.mapper.ModuleHierarchyNodeMapper;
import com.company.codeinsight.modules.scanner.mapper.CodeFileSnapshotMapper;
import com.company.codeinsight.modules.task.dto.PipelineStageStatDto;
import com.company.codeinsight.modules.task.dto.TaskLogSummaryDto;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.company.codeinsight.modules.task.service.TaskExecutionLogger;
import com.company.codeinsight.modules.task.service.impl.TaskLogSummaryServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务详情看板摘要：文档 AI 阶段统计与流水线终态对齐。
 */
@ExtendWith(MockitoExtension.class)
class TaskLogSummaryServiceImplTest {

    @Mock
    private DecompileTaskMapper decompileTaskMapper;
    @Mock
    private AiCallRecordMapper aiCallRecordMapper;
    @Mock
    private CodeFileSnapshotMapper codeFileSnapshotMapper;
    @Mock
    private ModuleHierarchyNodeMapper moduleHierarchyNodeMapper;
    @Mock
    private TaskExecutionLogger taskExecutionLogger;

    @InjectMocks
    private TaskLogSummaryServiceImpl service;

    @Test
    void docAiCallsAggregatesFunctionAndModuleDocStages() {
        DecompileTask task = new DecompileTask();
        task.setId(42L);
        task.setStatus(TaskStatus.PUSHED.name());
        task.setProgress(100);
        Mockito.when(decompileTaskMapper.selectById(42L)).thenReturn(task);
        Mockito.when(codeFileSnapshotMapper.selectCount(ArgumentMatchers.any())).thenReturn(18L);
        Mockito.when(moduleHierarchyNodeMapper.selectCount(ArgumentMatchers.any())).thenReturn(3L);
        Mockito.when(taskExecutionLogger.readLastRunContent(42L)).thenReturn("");

        // summarize 中 aiCallRecordMapper.selectCount 顺序：
        // aiTotal, aiOk, hierarchyTotal, hierarchyOk, docTotal, docOk
        Mockito.when(aiCallRecordMapper.selectCount(ArgumentMatchers.any()))
                .thenReturn(8L, 8L, 3L, 3L, 5L, 5L);

        ReflectionTestUtils.setField(service, "aiMock", false);
        TaskLogSummaryDto dto = service.summarize(42L);

        Assertions.assertEquals(5, dto.getDocAiCalls().getTotal());
        Assertions.assertEquals(5, dto.getDocAiCalls().getSuccess());
        Assertions.assertEquals(0, dto.getDocAiCalls().getFailed());
        Assertions.assertEquals(3, dto.getHierarchyAiCalls().getTotal());
        Assertions.assertEquals(3, dto.getHierarchyAiCalls().getSuccess());
        Assertions.assertEquals(18, dto.getCounters().getTotalFiles());
        Assertions.assertEquals(3, dto.getCurrent().getModuleTotal());
        Assertions.assertEquals(8, dto.getAiCalls().getTotal());
    }

    @Test
    void reconcileMarksLingeringRunningDoneWhenPushed() {
        List<PipelineStageStatDto> stages = new ArrayList<>();
        PipelineStageStatDto generating = new PipelineStageStatDto();
        generating.setKey("GENERATING_DOC");
        generating.setStatus("running");
        stages.add(generating);

        TaskLogSummaryServiceImpl.reconcilePipelineWithTaskStatus(stages, TaskStatus.PUSHED.name());
        Assertions.assertEquals("done", generating.getStatus());
    }

    @Test
    void reconcileMarksLingeringRunningErrorWhenFailed() {
        List<PipelineStageStatDto> stages = new ArrayList<>();
        PipelineStageStatDto generating = new PipelineStageStatDto();
        generating.setKey("GENERATING_DOC");
        generating.setStatus("running");
        stages.add(generating);

        TaskLogSummaryServiceImpl.reconcilePipelineWithTaskStatus(stages, TaskStatus.FAILED.name());
        Assertions.assertEquals("error", generating.getStatus());
    }
}
