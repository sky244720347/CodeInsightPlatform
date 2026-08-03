package com.company.codeinsight.modules.scanner;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.exception.ErrorCode;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.service.CodeRepositoryService;
import com.company.codeinsight.modules.scanner.service.CodeScannerService;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class CodeScannerServiceTest {

    @Autowired
    private CodeScannerService codeScannerService;

    @Autowired
    private CodeRepositoryService repositoryService;

    @Autowired
    private DecompileTaskMapper taskMapper;

    @Test
    public void testPullAndScanCloneFailureHardFails() {
        CodeRepository repo = new CodeRepository();
        repo.setSystemId(1L);
        repo.setGitUrl("https://github.com/invalid-url-to-trigger-clone-failure/repo.git");
        repo.setBranch("master");
        repo.setExcludeDirs(".git,target");
        repo.setExcludeFileTypes("class,jar");
        repo.setLastCommitId("published-baseline-should-not-change");
        repositoryService.save(repo);

        long taskId = 999L;
        DecompileTask task = new DecompileTask();
        task.setId(taskId);
        task.setSystemId(1L);
        task.setRepositoryId(repo.getId());
        task.setStatus("PENDING");
        task.setType("INITIAL");
        task.setProgress(0);
        taskMapper.insert(task);

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> codeScannerService.pullAndScan(taskId, repo.getId(), null));
        Assertions.assertEquals(ErrorCode.GIT_CLONE_FAILED.getCode(), ex.getCode());

        DecompileTask after = taskMapper.selectById(taskId);
        Assertions.assertTrue(after.getSourceCommit() == null
                        || !after.getSourceCommit().startsWith("MOCK_COMMIT_"),
                "clone 失败不得写入 MOCK_COMMIT");

        CodeRepository afterRepo = repositoryService.getById(repo.getId());
        Assertions.assertEquals("published-baseline-should-not-change", afterRepo.getLastCommitId());
    }
}
