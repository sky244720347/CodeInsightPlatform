package com.company.codeinsight.modules.task.service;

import com.company.codeinsight.modules.parser.service.TaskParseMemoryService;
import com.company.codeinsight.modules.task.service.impl.DecompileTaskServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.mockito.Mockito.verify;

/**
 * 无需复核终态路径必须能调用解析缓存驱逐（锁住 releaseParsePermitAndEvict 契约）。
 */
@ExtendWith(MockitoExtension.class)
class TaskParseMemoryEvictContractTest {

    private static final Long TASK_ID = 55_001L;

    @Mock
    private TaskParseMemoryService taskParseMemoryService;
    @Mock
    private ParseConcurrencyLimiter parseConcurrencyLimiter;

    @Test
    void releaseParsePermitAndEvict_invokesTaskParseMemoryService() throws Exception {
        DecompileTaskServiceImpl service = new DecompileTaskServiceImpl();

        Field memoryField = DecompileTaskServiceImpl.class.getDeclaredField("taskParseMemoryService");
        memoryField.setAccessible(true);
        memoryField.set(service, taskParseMemoryService);

        Field parseLimiter = DecompileTaskServiceImpl.class.getDeclaredField("parseConcurrencyLimiter");
        parseLimiter.setAccessible(true);
        parseLimiter.set(service, parseConcurrencyLimiter);

        Method m = DecompileTaskServiceImpl.class.getDeclaredMethod("releaseParsePermitAndEvict", Long.class);
        m.setAccessible(true);
        m.invoke(service, TASK_ID);

        verify(taskParseMemoryService).evict(TASK_ID);
        verify(parseConcurrencyLimiter).release(TASK_ID);
    }
}
