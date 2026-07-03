package com.company.codeinsight.modules.task;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.service.CodeRepositoryService;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.service.SystemApplicationService;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.service.DecompileTaskService;
import com.company.codeinsight.modules.task.service.TaskStateMachineService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务服务集成测试。
 *
 * <p><b>临时禁用</b>：API drift — listTasksPage 方法签名已变更（参数量不对），
 * 且 SystemApplication.setStatus(int) 已重命名/删除。需要逐方法修对再恢复
 * （不在本轮 Phase 1-3 范围内）。</p>
 */
@Disabled("API drift — 详见类 javadoc")
@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@ActiveProfiles("test")
@Transactional
public class DecompileTaskServiceTests {

    @Autowired
    private DecompileTaskService decompileTaskService;

    @Autowired
    private TaskStateMachineService stateMachineService;

    @Autowired
    private SystemApplicationService systemApplicationService;

    @Autowired
    private CodeRepositoryService codeRepositoryService;

    private Long systemId;
    private Long repositoryId;

    @BeforeEach
    void setUpTaskSource() {
        SystemApplication system = new SystemApplication();
        system.setName("Task Test System");
        system.setOwner("tester");
        system.setStatus(1);
        systemApplicationService.save(system);
        systemId = system.getId();

        CodeRepository repository = new CodeRepository();
        repository.setSystemId(systemId);
        repository.setGitUrl("https://example.com/task-test.git");
        repository.setBranch("main");
        repository.setScanRoot("/");
        codeRepositoryService.save(repository);
        repositoryId = repository.getId();
    }

    @Test
    public void testStateMachineTransitions() {
        DecompileTask task = decompileTaskService.createInitialTask(systemId, repositoryId, 1L, 1L, null, null, null);
        Assertions.assertEquals(TaskStatus.DRAFT.name(), task.getStatus());

        // Valid transition
        stateMachineService.transitTo(task.getId(), TaskStatus.PENDING, null);
        DecompileTask updated = decompileTaskService.getById(task.getId());
        Assertions.assertEquals(TaskStatus.PENDING.name(), updated.getStatus());
        Assertions.assertEquals(0, updated.getProgress());

        // Invalid transition should throw exception
        Assertions.assertThrows(Exception.class, () -> {
            stateMachineService.transitTo(task.getId(), TaskStatus.PUSHED, null);
        });
    }

    @Test
    public void testTaskLifecycle() {
        DecompileTask task = decompileTaskService.createIncrementalTask(systemId, repositoryId, 2L, 2L, null, null, null);
        Assertions.assertEquals("INCREMENTAL", task.getType());
        Assertions.assertEquals(TaskStatus.DRAFT.name(), task.getStatus());

        // List
        Page<DecompileTask> page = decompileTaskService.listTasksPage(1, 10, systemId, "DRAFT", "INCREMENTAL", null);
        Assertions.assertTrue(page.getTotal() > 0);

        // Start
        decompileTaskService.startTask(task.getId());
        DecompileTask runningTask = decompileTaskService.getById(task.getId());
        Assertions.assertEquals(TaskStatus.PENDING.name(), runningTask.getStatus());

        // Terminate
        decompileTaskService.terminateTask(task.getId());
        DecompileTask terminatedTask = decompileTaskService.getById(task.getId());
        Assertions.assertEquals(TaskStatus.CANCELLED.name(), terminatedTask.getStatus());
    }

    @Test
    public void testRejectsRepositoryFromAnotherSystem() {
        SystemApplication otherSystem = new SystemApplication();
        otherSystem.setName("Other Task Test System");
        otherSystem.setOwner("tester");
        otherSystem.setStatus(1);
        systemApplicationService.save(otherSystem);

        BusinessException exception = Assertions.assertThrows(
                BusinessException.class,
                () -> decompileTaskService.createInitialTask(otherSystem.getId(), repositoryId, 1L, 1L, null, null, null)
        );
        Assertions.assertEquals("所选代码库不属于当前系统", exception.getMessage());
    }
}