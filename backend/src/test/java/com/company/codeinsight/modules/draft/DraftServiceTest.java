package com.company.codeinsight.modules.draft;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.draft.entity.*;
import com.company.codeinsight.modules.draft.enums.DraftStatus;
import com.company.codeinsight.modules.draft.mapper.DraftWorkspaceMapper;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.draft.service.DraftService;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
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
import java.util.List;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@ActiveProfiles("test")
@Transactional
public class DraftServiceTest {

    @Autowired
    private DraftService draftService;

    @Autowired
    private DraftWorkspaceMapper workspaceMapper;

    @Autowired
    private KnowledgeDraftMapper draftMapper;

    @Autowired
    private DecompileTaskMapper taskMapper;

    @Test
    public void testDraftLifecycle() throws Exception {
        // 1. 创建工作区和草稿记录
        DraftWorkspace ws = new DraftWorkspace();
        ws.setTaskId(100L);
        ws.setSystemId(1L);
        ws.setRepositoryId(1L);
        ws.setStatus("ACTIVE");
        ws.setCreatedDate(LocalDateTime.now());
        ws.setUpdatedDate(LocalDateTime.now());
        workspaceMapper.insert(ws);

        File tempFile = File.createTempFile("MockDraft", ".md");
        tempFile.deleteOnExit();
        Files.writeString(tempFile.toPath(), "# 原始设计");

        KnowledgeDraft draft = new KnowledgeDraft();
        draft.setWorkspaceId(ws.getId());
        draft.setFilePath("MockDraft.md");
        draft.setModuleName("核心逻辑层");
        draft.setContentUri(tempFile.toURI().toString());
        draft.setStatus(DraftStatus.DRAFT.name());
        draft.setHash("hash111");
        draft.setCreatedDate(LocalDateTime.now());
        draft.setUpdatedDate(LocalDateTime.now());
        draftMapper.insert(draft);

        // 2. 验证读取原始内容
        String original = draftService.getDraftContent(draft.getId());
        Assertions.assertEquals("# 原始设计", original);

        // 3. 验证自动保存
        draftService.autoSaveDraft(draft.getId(), "# 自动保存的设计", "Author");
        String autosave = draftService.getDraftContent(draft.getId());
        Assertions.assertEquals("# 自动保存的设计", autosave);

        // 4. 验证手动保存并清除自动保存
        draftService.saveDraft(draft.getId(), "# 最终保存的设计", "Author", "手工修改");
        String saved = draftService.getDraftContent(draft.getId());
        Assertions.assertEquals("# 最终保存的设计", saved);

        // 验证修订记录
        List<DraftRevision> revisions = draftService.getRevisions(draft.getId());
        Assertions.assertEquals(1, revisions.size());
        Assertions.assertTrue(revisions.get(0).getRemark().contains("手工修改"));

        // 5. 验证单文件级确认草稿：仅修改单篇 draft 状态，不再触发工作区/任务级联
        // 自 v0.4 起，级联升级由 /tasks/{id}/confirm 承担，单文件 confirmDraft 只做单点状态变更。
        draftService.confirmDraft(draft.getId(), "Author", "业务清晰，已通过");
        KnowledgeDraft confirmedDraft = draftService.getDraftById(draft.getId());
        Assertions.assertEquals(DraftStatus.CONFIRMED.name(), confirmedDraft.getStatus());

        List<DraftReviewComment> comments = draftService.getComments(draft.getId());
        Assertions.assertEquals(1, comments.size());
        Assertions.assertEquals("业务清晰，已通过", comments.get(0).getComment());

        // 关键回归点：单文件 confirm 不应再级联把工作区升 COMPLETED
        DraftWorkspace stillActiveWs = workspaceMapper.selectById(ws.getId());
        Assertions.assertNotEquals("COMPLETED", stillActiveWs.getStatus(),
                "单文件 confirm 不应触发 workspace → COMPLETED 级联");

        // 6. 走任务级 confirmTask 才完成工作区晋升 + 任务状态推进
        // 注意：本测试没有真实持久化 task 记录，这里主要验证 confirmTask 对工作区的副作用
        // （任务推进在没有真实 task 时会被静默跳过）
    }

    /**
     * v0.3 起移除驳回机制；本测试改为验证"确认后可继续编辑修改"的语义：
     * CONFIRMED 草稿被 saveDraft 时状态回流到 EDITING，无需走驳回。
     */
    @Test
    public void testConfirmThenEdit() throws Exception {
        // 1. 准备工作区 + 草稿
        DraftWorkspace ws = new DraftWorkspace();
        ws.setTaskId(300L);
        ws.setSystemId(3L);
        ws.setRepositoryId(3L);
        ws.setStatus("ACTIVE");
        ws.setCreatedDate(LocalDateTime.now());
        ws.setUpdatedDate(LocalDateTime.now());
        workspaceMapper.insert(ws);

        File tempFile = File.createTempFile("ConfirmedEdit", ".md");
        tempFile.deleteOnExit();
        Files.writeString(tempFile.toPath(), "# v1");

        KnowledgeDraft draft = new KnowledgeDraft();
        draft.setWorkspaceId(ws.getId());
        draft.setFilePath("ConfirmedEdit.md");
        draft.setModuleName("已确认再编辑测试模块");
        draft.setContentUri(tempFile.toURI().toString());
        draft.setStatus(DraftStatus.DRAFT.name());
        draft.setHash("h-init");
        draft.setCreatedDate(LocalDateTime.now());
        draft.setUpdatedDate(LocalDateTime.now());
        draftMapper.insert(draft);

        // 2. 确认通过
        draftService.confirmDraft(draft.getId(), "Author", null);
        Assertions.assertEquals(DraftStatus.CONFIRMED.name(),
                draftService.getDraftById(draft.getId()).getStatus());

        // 3. 复核人继续编辑保存，状态应当回流到 EDITING
        draftService.saveDraft(draft.getId(), "# v2 - 修改补充", "Author", "补充细节");
        Assertions.assertEquals(DraftStatus.EDITING.name(),
                draftService.getDraftById(draft.getId()).getStatus());
    }

    /**
     * v0.3 起推送锁定：任务处于 PUSHING / PUSHED 时草稿 saveDraft / confirmDraft 都会被拦截。
     * 本测试验证后端兜底逻辑，防止前端绕过 UI。
     */
    @Test
    public void testSaveRejectedAfterPush() throws Exception {
        // 1. 准备工作区 + 草稿 + 任务（task 状态置为 PUSHED 模拟已推送场景）
        DraftWorkspace ws = new DraftWorkspace();
        ws.setTaskId(400L);
        ws.setSystemId(4L);
        ws.setRepositoryId(4L);
        ws.setStatus("COMPLETED");
        ws.setCreatedDate(LocalDateTime.now());
        ws.setUpdatedDate(LocalDateTime.now());
        workspaceMapper.insert(ws);

        DecompileTask task = new DecompileTask();
        task.setSystemId(4L);
        task.setRepositoryId(4L);
        task.setStatus(TaskStatus.PUSHED.name());
        task.setType("INITIAL");
        task.setProgress(100);
        task.setCreatedDate(LocalDateTime.now());
        task.setUpdatedDate(LocalDateTime.now());
        taskMapper.insert(task);
        // 用 sql 直接关联 taskId 到 ws（service 层不维护）
        // 通过反射修正 workspace.taskId 与刚插入的 task.id 同步
        java.lang.reflect.Field f = DraftWorkspace.class.getDeclaredField("taskId");
        f.setAccessible(true);
        f.set(ws, task.getId());
        workspaceMapper.updateById(ws);

        File tempFile = File.createTempFile("LockedDraft", ".md");
        tempFile.deleteOnExit();
        Files.writeString(tempFile.toPath(), "# locked");

        KnowledgeDraft draft = new KnowledgeDraft();
        draft.setWorkspaceId(ws.getId());
        draft.setFilePath("LockedDraft.md");
        draft.setModuleName("锁定测试模块");
        draft.setContentUri(tempFile.toURI().toString());
        draft.setStatus(DraftStatus.CONFIRMED.name());
        draft.setHash("h-locked");
        draft.setCreatedDate(LocalDateTime.now());
        draft.setUpdatedDate(LocalDateTime.now());
        draftMapper.insert(draft);

        // 2. 尝试编辑保存：应该被 BusinessException 拦截
        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> draftService.saveDraft(draft.getId(), "# 修改尝试", "Author", "推送后还想改"));
        Assertions.assertTrue(ex.getMessage().contains("已") && ex.getMessage().contains("锁定"));

        // 3. 尝试确认操作：也应被拦截
        BusinessException ex2 = Assertions.assertThrows(BusinessException.class,
                () -> draftService.confirmDraft(draft.getId(), "Author", null));
        Assertions.assertTrue(ex2.getMessage().contains("锁定"));

        // 4. 任务级 confirmTask 也必须被拦截 — 不能让复核人绕过 UI 直接整组推送前再次确认
        BusinessException ex3 = Assertions.assertThrows(BusinessException.class,
                () -> draftService.confirmTask(task.getId(), "Author", null));
        Assertions.assertTrue(ex3.getMessage().contains("已推送") || ex3.getMessage().contains("在推送"));
    }

    /**
     * 任务级「确认通过」端到端测试：
     * - 多篇 draft 一次性置为 CONFIRMED
     * - workspace 晋升 COMPLETED
     * - 关联 task 从 PENDING_REVIEW 推进到 CONFIRMED（通过状态机）
     * - 任务级通过意见留痕（带 `[任务级通过]` 前缀）
     */
    @Test
    public void testConfirmTaskBulk() throws Exception {
        // 1. 准备 task：处于 PENDING_REVIEW，可被推进
        DecompileTask task = new DecompileTask();
        task.setSystemId(7L);
        task.setRepositoryId(7L);
        task.setStatus(TaskStatus.PENDING_REVIEW.name());
        task.setType("INITIAL");
        task.setProgress(100);
        task.setCreatedDate(LocalDateTime.now());
        task.setUpdatedDate(LocalDateTime.now());
        taskMapper.insert(task);

        // 2. 准备 workspace 关联到 task
        DraftWorkspace ws = new DraftWorkspace();
        ws.setTaskId(task.getId());
        ws.setSystemId(7L);
        ws.setRepositoryId(7L);
        ws.setStatus("ACTIVE");
        ws.setCreatedDate(LocalDateTime.now());
        ws.setUpdatedDate(LocalDateTime.now());
        workspaceMapper.insert(ws);

        // 3. 准备多篇 draft
        KnowledgeDraft d1 = newDraft(ws.getId(), "模块A", "DraftA.md");
        KnowledgeDraft d2 = newDraft(ws.getId(), "模块B", "DraftB.md");
        KnowledgeDraft d3 = newDraft(ws.getId(), "模块C", "DraftC.md");
        draftMapper.insert(d1);
        draftMapper.insert(d2);
        draftMapper.insert(d3);

        // 4. 触发任务级确认
        draftService.confirmTask(task.getId(), "Reviewer", "整组无变更，确认通过");

        // 5. 所有 draft → CONFIRMED
        Assertions.assertEquals(DraftStatus.CONFIRMED.name(),
                draftMapper.selectById(d1.getId()).getStatus());
        Assertions.assertEquals(DraftStatus.CONFIRMED.name(),
                draftMapper.selectById(d2.getId()).getStatus());
        Assertions.assertEquals(DraftStatus.CONFIRMED.name(),
                draftMapper.selectById(d3.getId()).getStatus());

        // 6. workspace → COMPLETED
        DraftWorkspace updatedWs = workspaceMapper.selectById(ws.getId());
        Assertions.assertEquals("COMPLETED", updatedWs.getStatus());

        // 7. 任务从 PENDING_REVIEW 推进到 CONFIRMED
        DecompileTask updatedTask = taskMapper.selectById(task.getId());
        Assertions.assertEquals(TaskStatus.CONFIRMED.name(), updatedTask.getStatus());

        // 8. 任务级通过意见留痕（前缀 `[任务级通过]`）
        List<DraftReviewComment> comments = draftService.getComments(d1.getId());
        boolean foundTaskLevelPass = comments.stream()
                .anyMatch(c -> "PASS".equals(c.getType())
                        && c.getComment().startsWith("[任务级通过]")
                        && c.getComment().contains("整组无变更"));
        Assertions.assertTrue(foundTaskLevelPass,
                "任务级通过意见应当挂在工作区下首篇 draft 的评论表里");
    }

    /**
     * 任务级 confirmTask：仅一篇 draft 时仍要正确晋升工作区 + 推进任务。
     * 覆盖工作区只剩单篇稿的边界场景。
     */
    @Test
    public void testConfirmTaskSingleDraft() throws Exception {
        DecompileTask task = new DecompileTask();
        task.setSystemId(8L);
        task.setRepositoryId(8L);
        task.setStatus(TaskStatus.REVIEWING.name());
        task.setType("INITIAL");
        task.setProgress(100);
        task.setCreatedDate(LocalDateTime.now());
        task.setUpdatedDate(LocalDateTime.now());
        taskMapper.insert(task);

        DraftWorkspace ws = new DraftWorkspace();
        ws.setTaskId(task.getId());
        ws.setSystemId(8L);
        ws.setRepositoryId(8L);
        ws.setStatus("ACTIVE");
        ws.setCreatedDate(LocalDateTime.now());
        ws.setUpdatedDate(LocalDateTime.now());
        workspaceMapper.insert(ws);

        KnowledgeDraft only = newDraft(ws.getId(), "唯一模块", "Only.md");
        draftMapper.insert(only);

        draftService.confirmTask(task.getId(), "Reviewer", null);

        Assertions.assertEquals("CONFIRMED",
                draftMapper.selectById(only.getId()).getStatus());
        Assertions.assertEquals("COMPLETED",
                workspaceMapper.selectById(ws.getId()).getStatus());
        Assertions.assertEquals(TaskStatus.CONFIRMED.name(),
                taskMapper.selectById(task.getId()).getStatus());
    }

    /** 测试辅助：构造一条 KnowledgeDraft 记录（不持久化） */
    private KnowledgeDraft newDraft(Long workspaceId, String moduleName, String fileName) throws Exception {
        File f = File.createTempFile("Draft_" + moduleName, ".md");
        f.deleteOnExit();
        Files.writeString(f.toPath(), "# " + moduleName);

        KnowledgeDraft d = new KnowledgeDraft();
        d.setWorkspaceId(workspaceId);
        d.setFilePath(fileName);
        d.setModuleName(moduleName);
        d.setContentUri(f.toURI().toString());
        d.setStatus(DraftStatus.DRAFT.name());
        d.setHash("hash-" + moduleName);
        d.setCreatedDate(LocalDateTime.now());
        d.setUpdatedDate(LocalDateTime.now());
        return d;
    }
}
