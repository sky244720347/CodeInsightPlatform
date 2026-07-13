package com.company.codeinsight.modules.task;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.exception.ErrorCode;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.service.CodeRepositoryService;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.service.SystemApplicationService;
import com.company.codeinsight.modules.task.service.DecompileTaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * INCREMENTAL 任务硬性门禁测试：
 * <ul>
 *   <li>{@link com.company.codeinsight.modules.task.service.impl.DecompileTaskServiceImpl#validateIncrementalBaselineGate}</li>
 *   <li>仓库从未发布过任何知识版本 → 抛 INCREMENTAL_NO_BASELINE (code 2001)</li>
 *   <li>仓库是本地路径模式 → 抛 INCREMENTAL_LOCAL_PATH_NOT_SUPPORTED (code 2002)</li>
 * </ul>
 *
 * <p>使用 {@link MockBean} 替换 {@link CodeRepositoryService} 与 {@link SystemApplicationService}，
 * 避免依赖真实数据库。</p>
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@ActiveProfiles("test")
public class IncrementalTaskBaselineGateTest {

    private static final long SYSTEM_ID = 9001L;
    private static final long REPOSITORY_ID = 9002L;

    @Autowired
    private DecompileTaskService decompileTaskService;

    @MockBean
    private CodeRepositoryService codeRepositoryService;

    @MockBean
    private SystemApplicationService systemApplicationService;

    private File tempLocalDir;

    @BeforeEach
    void setUpSystem() {
        SystemApplication system = new SystemApplication();
        system.setId(SYSTEM_ID);
        system.setName("Incremental Gate Test System");
        system.setOwner("tester");
        // 注：SystemApplication 现版本无 setStatus 字段（状态机已删除），Mock 关键在于 getById 返回非 null
        when(systemApplicationService.getById(SYSTEM_ID)).thenReturn(system);
    }

    @AfterEach
    void tearDown() {
        reset(codeRepositoryService, systemApplicationService);
        if (tempLocalDir != null && tempLocalDir.exists()) {
            tempLocalDir.delete();
            tempLocalDir = null;
        }
    }

    @Test
    void createIncrementalTask_withNoBaseline_throwsNoBaselineError() {
        // 仓库 lastCommitId 为空 + lastPublishedVersionId 为空（从未 PUSHED 过）
        CodeRepository repo = new CodeRepository();
        repo.setId(REPOSITORY_ID);
        repo.setSystemId(SYSTEM_ID);
        repo.setGitUrl("https://gitee.com/example/repo.git");
        repo.setLastCommitId(null);
        repo.setLastPublishedVersionId(null);
        when(codeRepositoryService.getById(REPOSITORY_ID)).thenReturn(repo);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> decompileTaskService.createIncrementalTask(
                        SYSTEM_ID, REPOSITORY_ID, 1L, 1L, null, null, (Boolean) null, (Boolean) null));
        assertEquals(ErrorCode.INCREMENTAL_NO_BASELINE.getCode(), ex.getCode());
    }

    @Test
    void createIncrementalTask_withUnpublishedRepo_throwsNoBaselineError() {
        // lastCommitId 有值但 lastPublishedVersionId 为空（基线指针异常）
        CodeRepository repo = new CodeRepository();
        repo.setId(REPOSITORY_ID);
        repo.setSystemId(SYSTEM_ID);
        repo.setGitUrl("https://gitee.com/example/repo.git");
        repo.setLastCommitId("abc123def456");
        repo.setLastPublishedVersionId(null);
        when(codeRepositoryService.getById(REPOSITORY_ID)).thenReturn(repo);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> decompileTaskService.createIncrementalTask(
                        SYSTEM_ID, REPOSITORY_ID, 1L, 1L, null, null, (Boolean) null, (Boolean) null));
        assertEquals(ErrorCode.INCREMENTAL_NO_BASELINE.getCode(), ex.getCode());
    }

    @Test
    void createIncrementalTask_withLocalPathRepo_throwsLocalPathError() throws Exception {
        // 准备一个真实存在的本地目录作为 gitUrl
        tempLocalDir = Files.createTempDirectory("ci-test-local-repo").toFile();
        CodeRepository repo = new CodeRepository();
        repo.setId(REPOSITORY_ID);
        repo.setSystemId(SYSTEM_ID);
        repo.setGitUrl(tempLocalDir.getAbsolutePath());
        repo.setLastCommitId("abc123def456");
        repo.setLastPublishedVersionId(99L);   // 已发布指针，满足门禁 1
        when(codeRepositoryService.getById(REPOSITORY_ID)).thenReturn(repo);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> decompileTaskService.createIncrementalTask(
                        SYSTEM_ID, REPOSITORY_ID, 1L, 1L, null, null, (Boolean) null, (Boolean) null));
        assertEquals(ErrorCode.INCREMENTAL_LOCAL_PATH_NOT_SUPPORTED.getCode(), ex.getCode());
    }
}