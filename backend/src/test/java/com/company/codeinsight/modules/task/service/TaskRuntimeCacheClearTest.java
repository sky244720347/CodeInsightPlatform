package com.company.codeinsight.modules.task.service;

import com.company.codeinsight.modules.callchain.model.IncrementalImpact;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.service.impl.DecompileTaskServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 运行态三份 Map 清理契约：终态 / 磁盘回收路径必须清干净。
 */
class TaskRuntimeCacheClearTest {

    private static final Long TASK_ID = 900_001L;

    private final DecompileTaskServiceImpl service = new DecompileTaskServiceImpl();

    @BeforeEach
    void clear() {
        service.clearRuntimeCaches(TASK_ID);
    }

    @Test
    void clearRuntimeCaches_removesAllThreeMaps() {
        DecompileTaskServiceImpl.taskCache.put(TASK_ID, new DecompileTask());
        DecompileTaskServiceImpl.pipelineContextCache.put(
                TASK_ID, DecompileTaskServiceImpl.PipelineContext.fullScan(new java.io.File(".")));
        DecompileTaskServiceImpl.impactCache.put(TASK_ID, IncrementalImpact.fullScan());

        service.clearRuntimeCaches(TASK_ID);

        Assertions.assertFalse(DecompileTaskServiceImpl.taskCache.containsKey(TASK_ID));
        Assertions.assertFalse(DecompileTaskServiceImpl.pipelineContextCache.containsKey(TASK_ID));
        Assertions.assertFalse(DecompileTaskServiceImpl.impactCache.containsKey(TASK_ID));
    }

    @Test
    void clearRuntimeCaches_nullSafe() {
        Assertions.assertDoesNotThrow(() -> service.clearRuntimeCaches(null));
    }
}
