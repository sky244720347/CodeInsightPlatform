package com.company.codeinsight.modules.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.draft.entity.DraftWorkspace;
import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import com.company.codeinsight.modules.draft.enums.DraftStatus;
import com.company.codeinsight.modules.draft.mapper.DraftWorkspaceMapper;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.knowledge.entity.KnowledgeVersion;
import com.company.codeinsight.modules.knowledge.service.KnowledgeService;
import com.company.codeinsight.modules.push.enums.PushMethod;
import com.company.codeinsight.modules.push.service.PushService;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.service.CodeRepositoryService;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@ActiveProfiles("test")
@Transactional
public class KnowledgeServiceTest {

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private PushService pushService;

    @Autowired
    private DecompileTaskMapper taskMapper;

    @Autowired
    private CodeRepositoryService repositoryService;

    @Autowired
    private DraftWorkspaceMapper workspaceMapper;

    @Autowired
    private KnowledgeDraftMapper draftMapper;

    @Test
    public void testKnowledgeVersionCreateAndExport() throws Exception {
        Long taskId = 666L;

        // 1. 创建 Repository 与 Task
        CodeRepository repo = new CodeRepository();
        repo.setSystemId(1L);
        repo.setGitUrl("https://github.com/dummy/repo.git");
        repo.setBranch("main");
        repo.setLastCommitId("published-baseline-old");
        repositoryService.save(repo);

        DecompileTask task = new DecompileTask();
        task.setId(taskId);
        task.setSystemId(1L);
        task.setRepositoryId(repo.getId());
        task.setStatus("CONFIRMED");
        task.setType("INITIAL");
        task.setProgress(0);
        task.setSourceCommit("task-scan-commit-abc");
        taskMapper.insert(task);

        // 2. 创建草稿工作区和草稿文件
        DraftWorkspace ws = new DraftWorkspace();
        ws.setTaskId(taskId);
        ws.setSystemId(1L);
        ws.setRepositoryId(repo.getId());
        ws.setStatus("ACTIVE");
        ws.setCreatedDate(LocalDateTime.now());
        ws.setUpdatedDate(LocalDateTime.now());
        workspaceMapper.insert(ws);

        File tempFile = File.createTempFile("MockDraft", ".md");
        tempFile.deleteOnExit();
        Files.writeString(tempFile.toPath(), "# 测试归纳内容");

        KnowledgeDraft draft = new KnowledgeDraft();
        draft.setWorkspaceId(ws.getId());
        draft.setFilePath("MockDraft.md");
        draft.setModuleName("订单模块");
        draft.setContentUri(tempFile.toURI().toString());
        draft.setStatus(DraftStatus.CONFIRMED.name());
        draft.setHash("hashabc");
        draft.setCreatedDate(LocalDateTime.now());
        draft.setUpdatedDate(LocalDateTime.now());
        draftMapper.insert(draft);

        // 3. 创建版本
        KnowledgeVersion version = knowledgeService.createVersion(taskId, "v1.0.0", "Tester");
        Assertions.assertNotNull(version);
        Assertions.assertEquals("v1.0.0", version.getVersionNum());
        Assertions.assertEquals("task-scan-commit-abc", version.getSourceCommit());
        Assertions.assertEquals("DRAFT", version.getStatus());
        Assertions.assertEquals("NAS", version.getPushMethod());

        // 4. 推送任务入队（Redis 不可用时抛出 BusinessException）
        BusinessException ex = Assertions.assertThrows(BusinessException.class, () -> {
            pushService.enqueuePush(version.getId(), PushMethod.NAS);
        });
        Assertions.assertTrue(ex.getMessage().contains("Redis") || ex.getMessage().contains("推送"),
                "Should fail due to Redis not available in test");

        // 5. 导出 ZIP
        byte[] zipBytes = knowledgeService.exportZip(version.getId());
        Assertions.assertNotNull(zipBytes);
        Assertions.assertTrue(zipBytes.length > 0);
    }

    @Autowired
    private com.company.codeinsight.modules.knowledge.mapper.KnowledgeVersionMapper testVersionMapper;

    private KnowledgeVersion versionMapperSelect(Long id) {
        return testVersionMapper.selectById(id);
    }

    @Test
    public void testPushToGitValidations() throws Exception {
        Long taskId = 999L;

        CodeRepository repo = new CodeRepository();
        repo.setSystemId(1L);
        repo.setGitUrl("https://github.com/dummy/repo.git");
        repo.setBranch("main");
        repo.setLastCommitId("published-baseline-old");
        repositoryService.save(repo);

        DecompileTask task = new DecompileTask();
        task.setId(taskId);
        task.setSystemId(1L);
        task.setRepositoryId(repo.getId());
        task.setStatus("CONFIRMED");
        task.setType("INITIAL");
        task.setProgress(0);
        task.setSourceCommit("task-scan-commit-abc");
        taskMapper.insert(task);

        DraftWorkspace ws = new DraftWorkspace();
        ws.setTaskId(taskId);
        ws.setSystemId(1L);
        ws.setRepositoryId(repo.getId());
        ws.setStatus("ACTIVE");
        ws.setCreatedDate(LocalDateTime.now());
        ws.setUpdatedDate(LocalDateTime.now());
        workspaceMapper.insert(ws);

        // 创建包含 `- [ ]` 待确认项的草稿
        File tempFile = File.createTempFile("MockDraft", ".md");
        tempFile.deleteOnExit();
        Files.writeString(tempFile.toPath(), "# 待确认内容\n- [ ] 并发事务锁机制");

        KnowledgeDraft draft = new KnowledgeDraft();
        draft.setWorkspaceId(ws.getId());
        draft.setFilePath("MockDraft.md");
        draft.setModuleName("订单模块");
        draft.setContentUri(tempFile.toURI().toString());
        draft.setStatus(DraftStatus.CONFIRMED.name());
        draft.setHash("hashabc");
        draft.setCreatedDate(LocalDateTime.now());
        draft.setUpdatedDate(LocalDateTime.now());
        draftMapper.insert(draft);

        KnowledgeVersion version = knowledgeService.createVersion(taskId, "v2.0.0", "Tester");
        Assertions.assertNotNull(version);

        // 校验1: 草稿中有 `- [ ]` 待确认项 → enqueuePush 应抛出 BusinessException
        Assertions.assertThrows(BusinessException.class, () -> {
            pushService.enqueuePush(version.getId(), PushMethod.NAS);
        });

        // 修复内容但改为非 CONFIRMED 状态
        draft.setStatus(DraftStatus.DRAFT.name());
        draftMapper.updateById(draft);
        Files.writeString(tempFile.toPath(), "# 已解决待确认项");

        // 校验2: 草稿状态不是 CONFIRMED → enqueuePush 应抛出 BusinessException
        Assertions.assertThrows(BusinessException.class, () -> {
            pushService.enqueuePush(version.getId(), PushMethod.NAS);
        });
    }
}
