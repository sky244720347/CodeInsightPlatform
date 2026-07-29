package com.company.codeinsight.modules.task;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.log.service.OperationLogService;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.service.CodeRepositoryService;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.service.SystemApplicationService;
import com.company.codeinsight.modules.task.dto.BatchInitialItemResult;
import com.company.codeinsight.modules.task.dto.BatchInitialTriggerResult;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.company.codeinsight.modules.task.service.DecompileTaskService;
import com.company.codeinsight.modules.task.service.impl.BatchInitialTriggerServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BatchInitialTriggerServiceImplTest {

    @Mock
    private CodeRepositoryService codeRepositoryService;
    @Mock
    private SystemApplicationService systemApplicationService;
    @Mock
    private DecompileTaskService decompileTaskService;
    @Mock
    private DecompileTaskMapper decompileTaskMapper;
    @Mock
    private OperationLogService operationLogService;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, String> redis = new ConcurrentHashMap<>();
    private final Executor syncExecutor = Runnable::run;

    private BatchInitialTriggerServiceImpl service;

    @BeforeEach
    void setUp() {
        redis.clear();
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), any(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (redis.containsKey(key)) {
                return false;
            }
            redis.put(key, inv.getArgument(1));
            return true;
        });
        doAnswer(inv -> {
            redis.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOperations).set(any(), any(), any(Duration.class));
        doAnswer(inv -> redis.get(inv.getArgument(0))).when(valueOperations).get(any());
        doAnswer(inv -> redis.remove(inv.getArgument(0)) != null).when(stringRedisTemplate).delete(any(String.class));

        service = new BatchInitialTriggerServiceImpl(
                codeRepositoryService,
                systemApplicationService,
                decompileTaskService,
                decompileTaskMapper,
                operationLogService,
                stringRedisTemplate,
                objectMapper,
                syncExecutor);
    }

    @Test
    void submitAsyncTriggersWhenRepoIdle() {
        CodeRepository repo = repo(1L, 10L, "https://git/a.git");
        stubRepos(repo);
        when(systemApplicationService.getById(1L)).thenReturn(sys(1L, "SYS-A"));
        when(decompileTaskMapper.selectCount(any())).thenReturn(0L);

        DecompileTask created = new DecompileTask();
        created.setId(100L);
        when(decompileTaskService.createInitialTask(
                eq(1L), eq(10L), isNull(), isNull(), isNull(), isNull(),
                eq(Boolean.FALSE), eq(Boolean.FALSE))).thenReturn(created);

        BatchInitialTriggerResult accepted = service.submitAsync();
        assertEquals(BatchInitialTriggerResult.STATUS_ACCEPTED, accepted.getStatus());
        assertEquals(1, accepted.getTotalRepos());

        // sync executor already finished
        BatchInitialTriggerResult done = service.getJob(accepted.getJobId());
        assertEquals(BatchInitialTriggerResult.STATUS_COMPLETED, done.getStatus());
        assertEquals(1, done.getTriggered());
        assertEquals(BatchInitialItemResult.STATUS_TRIGGERED, done.getItems().get(0).getStatus());
        verify(decompileTaskService).startTask(100L);
    }

    @Test
    void skipsWhenUnfinishedTaskExists() {
        CodeRepository repo = repo(1L, 10L, "https://git/a.git");
        stubRepos(repo);
        when(systemApplicationService.getById(1L)).thenReturn(sys(1L, "SYS-A"));
        when(decompileTaskMapper.selectCount(any())).thenReturn(1L);

        BatchInitialTriggerResult accepted = service.submitAsync();
        BatchInitialTriggerResult done = service.getJob(accepted.getJobId());

        assertEquals(1, done.getSkipped());
        assertEquals("同仓已有未完成任务，跳过", done.getItems().get(0).getMessage());
        verify(decompileTaskService, never()).createInitialTask(
                any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void rejectsWhenLockHeld() {
        redis.put("ci:batch-initial:lock", "busy");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.submitAsync());
        assertTrue(ex.getMessage().contains("进行中"));
    }

    private void stubRepos(CodeRepository... repos) {
        doReturn(List.of(repos)).when(codeRepositoryService)
                .list(ArgumentMatchers.<Wrapper<CodeRepository>>any());
    }

    private static CodeRepository repo(Long systemId, Long id, String gitUrl) {
        CodeRepository r = new CodeRepository();
        r.setId(id);
        r.setSystemId(systemId);
        r.setGitUrl(gitUrl);
        return r;
    }

    private static SystemApplication sys(Long id, String name) {
        SystemApplication s = new SystemApplication();
        s.setId(id);
        s.setName(name);
        return s;
    }
}
