package com.company.codeinsight.modules.entrypoint.trial;

import com.company.codeinsight.common.storage.EnvStorageResolver;
import com.company.codeinsight.modules.parser.service.TaskParseMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入口试跑结束后必须驱逐解析缓存（堵住 static SymbolSolver 残留）。
 */
@ExtendWith(MockitoExtension.class)
class TrialRunParseMemoryEvictTest {

    private static final Long TRIAL_ID = 77_001L;

    @Mock
    private EntryScanTrialMapper trialMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private EnvStorageResolver storageResolver;
    @Mock
    private TaskParseMemoryService taskParseMemoryService;

    private TrialRunServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new TrialRunServiceImpl(trialMapper, stringRedisTemplate, storageResolver);
        Field f = TrialRunServiceImpl.class.getDeclaredField("taskParseMemoryService");
        f.setAccessible(true);
        f.set(service, taskParseMemoryService);
    }

    @Test
    void executeAsyncInternal_evictsParseMemoryEvenWhenTrialMissing() throws Exception {
        when(trialMapper.selectById(TRIAL_ID)).thenReturn(null);

        invokeExecuteAsyncInternal(TRIAL_ID, 9L);

        verify(taskParseMemoryService, atLeastOnce()).evict(TRIAL_ID);
    }

    @Test
    void executeAsyncInternal_evictsParseMemoryOnTerminalEarlyReturn() throws Exception {
        EntryScanTrialEntity trial = new EntryScanTrialEntity();
        trial.setId(TRIAL_ID);
        trial.setRepositoryId(9L);
        trial.setStatus(EntryScanTrialEntity.STATUS_SUCCESS);
        when(trialMapper.selectById(TRIAL_ID)).thenReturn(trial);

        invokeExecuteAsyncInternal(TRIAL_ID, 9L);

        verify(taskParseMemoryService, atLeastOnce()).evict(TRIAL_ID);
    }

    private void invokeExecuteAsyncInternal(Long trialId, Long repositoryIdHint) throws Exception {
        Method m = TrialRunServiceImpl.class.getDeclaredMethod(
                "executeAsyncInternal", Long.class, Long.class);
        m.setAccessible(true);
        m.invoke(service, trialId, repositoryIdHint);
    }
}
