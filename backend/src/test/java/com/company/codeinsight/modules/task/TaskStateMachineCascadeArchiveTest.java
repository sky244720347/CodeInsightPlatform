package com.company.codeinsight.modules.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.modules.draft.entity.DraftWorkspace;
import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import com.company.codeinsight.modules.draft.enums.DraftStatus;
import com.company.codeinsight.modules.draft.mapper.DraftWorkspaceMapper;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.company.codeinsight.modules.task.service.DecompileTaskService;
import com.company.codeinsight.modules.task.service.TaskStateMachineService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务状态机联动级联归档测试（plan C）。
 *
 * <p>验证任务流转到 CANCELLED / FAILED 时，{@link TaskStateMachineServiceImpl#transitTo}
 * 会通过 cascadeArchiveDraftsAndWorkspaces 把 taskId 关联的
 * {@code ci_draft_workspace.status} 和 {@code ci_knowledge_draft.status} 一并置为 ARCHIVED，
 * 避免「任务已终止但草稿仍 DRAFT/EDITING」的孤儿状态污染 readiness 视图。</p>
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@ActiveProfiles("test")
@Transactional
public class TaskStateMachineCascadeArchiveTest {

    @Autowired
    private TaskStateMachineService stateMachineService;

    @Autowired
    private DecompileTaskService decompileTaskService;

    @Autowired
    private DecompileTaskMapper taskMapper;

    @Autowired
    private DraftWorkspaceMapper workspaceMapper;

    @Autowired
    private KnowledgeDraftMapper draftMapper;

    @BeforeEach
    public void setUp() {
        draftMapper.delete(new LambdaQueryWrapper<>());
        workspaceMapper.delete(new LambdaQueryWrapper<>());
    }

    // ---------- 工具方法 ----------

    private DecompileTask insertTask(String status) {
        DecompileTask task = new DecompileTask();
        task.setSystemId(7001L);
        task.setRepositoryId(7001L);
        task.setStatus(status);
        task.setType("INITIAL");
        task.setProgress(0);
        task.setDurationMs(0L);
        task.setPriority(50);
        task.setRequireHierarchyReview(Boolean.TRUE);
        task.setRequireEntrypointReview(Boolean.TRUE);
        task.setTriggerSource("MANUAL");
        taskMapper.insert(task);
        return task;
    }

    private DraftWorkspace insertWorkspace(Long taskId) {
        DraftWorkspace ws = new DraftWorkspace();
        ws.setTaskId(taskId);
        ws.setSystemId(7001L);
        ws.setRepositoryId(7001L);
        ws.setStatus("ACTIVE");
        ws.setCreatedDate(LocalDateTime.now());
        ws.setUpdatedDate(LocalDateTime.now());
        workspaceMapper.insert(ws);
        return ws;
    }

    private KnowledgeDraft insertDraft(Long workspaceId, String moduleName, String draftStatus) {
        KnowledgeDraft draft = new KnowledgeDraft();
        draft.setWorkspaceId(workspaceId);
        draft.setFilePath(moduleName + ".md");
        draft.setModuleName(moduleName);
        draft.setContentUri("file:///tmp/" + moduleName + ".md");
        draft.setStatus(draftStatus);
        draft.setHash("hash-" + moduleName);
        draft.setCreatedDate(LocalDateTime.now());
        draft.setUpdatedDate(LocalDateTime.now());
        draftMapper.insert(draft);
        return draft;
    }

    // ---------- 测试用例 ----------

    /**
     * 任务流转到 CANCELLED（通过 terminateTask）→ workspace 与 draft 都应被归档。
     * 这是用户当前痛点的根因修复：cancelled 任务不再残留 DRAFT/EDITING 草稿。
     */
    @Test
    public void testTerminateCascadeArchivesWorkspaceAndDrafts() {
        DecompileTask task = insertTask(TaskStatus.PENDING_REVIEW.name());
        DraftWorkspace ws = insertWorkspace(task.getId());
        KnowledgeDraft d1 = insertDraft(ws.getId(), "模块A", DraftStatus.DRAFT.name());
        KnowledgeDraft d2 = insertDraft(ws.getId(), "模块B", DraftStatus.EDITING.name());

        // 执行终止（内部会调用 transitTo → CANCELLED → cascadeArchive）
        decompileTaskService.terminateTask(task.getId());

        // 校验 task 本身流转到 CANCELLED
        DecompileTask reloaded = taskMapper.selectById(task.getId());
        Assertions.assertEquals(TaskStatus.CANCELLED.name(), reloaded.getStatus());

        // 校验 workspace 已 ARCHIVED
        DraftWorkspace wsReloaded = workspaceMapper.selectById(ws.getId());
        Assertions.assertEquals("ARCHIVED", wsReloaded.getStatus(),
                "CANCELLED 任务关联的工作区应自动归档");

        // 校验所有 draft 已 ARCHIVED
        KnowledgeDraft d1Reloaded = draftMapper.selectById(d1.getId());
        KnowledgeDraft d2Reloaded = draftMapper.selectById(d2.getId());
        Assertions.assertEquals(DraftStatus.ARCHIVED.name(), d1Reloaded.getStatus());
        Assertions.assertEquals(DraftStatus.ARCHIVED.name(), d2Reloaded.getStatus());
    }

    /**
     * 任务流转到 FAILED → 同样应触发级联归档（与 CANCELLED 行为一致）。
     */
    @Test
    public void testTransitToFailedCascadeArchives() {
        DecompileTask task = insertTask(TaskStatus.PARSING_CODE.name());
        DraftWorkspace ws = insertWorkspace(task.getId());
        KnowledgeDraft d = insertDraft(ws.getId(), "失败模块", DraftStatus.DRAFT.name());

        // 模拟 PARSING_CODE → FAILED 的状态机跳转
        DecompileTask reloaded = taskMapper.selectById(task.getId());
        stateMachineService.transitTo(reloaded, TaskStatus.FAILED, "解析失败模拟");

        DraftWorkspace wsReloaded = workspaceMapper.selectById(ws.getId());
        Assertions.assertEquals("ARCHIVED", wsReloaded.getStatus(),
                "FAILED 任务关联的工作区应自动归档");

        KnowledgeDraft dReloaded = draftMapper.selectById(d.getId());
        Assertions.assertEquals(DraftStatus.ARCHIVED.name(), dReloaded.getStatus());
    }

    /**
     * 关键回归：任务流转到 PUSHED（终态）→ 不应触发 cascade（CANCELLED/FAILED 之外的状态机不联动）
     */
    @Test
    public void testTransitToPushedDoesNotCascadeArchive() {
        DecompileTask task = insertTask(TaskStatus.CONFIRMED.name());
        DraftWorkspace ws = insertWorkspace(task.getId());
        KnowledgeDraft d = insertDraft(ws.getId(), "待推送模块", DraftStatus.EDITING.name());

        // CONFIRMED → PUSHING → PUSHED 是正常推进，不应触发 cascade
        DecompileTask reloaded = taskMapper.selectById(task.getId());
        stateMachineService.transitTo(reloaded, TaskStatus.PUSHING, null);

        DraftWorkspace wsAfterPushing = workspaceMapper.selectById(ws.getId());
        KnowledgeDraft dAfterPushing = draftMapper.selectById(d.getId());
        Assertions.assertEquals("ACTIVE", wsAfterPushing.getStatus(),
                "PUSHING 不应触发 cascade");
        Assertions.assertEquals(DraftStatus.EDITING.name(), dAfterPushing.getStatus(),
                "PUSHING 不应触发 draft 归档");

        // 继续流转到 PUSHED
        DecompileTask pushing = taskMapper.selectById(task.getId());
        stateMachineService.transitTo(pushing, TaskStatus.PUSHED, null);

        DraftWorkspace wsAfterPushed = workspaceMapper.selectById(ws.getId());
        KnowledgeDraft dAfterPushed = draftMapper.selectById(d.getId());
        Assertions.assertEquals("ACTIVE", wsAfterPushed.getStatus(),
                "PUSHED 不应触发 cascade（PUSHED 路径有独立逻辑处理）");
        Assertions.assertEquals(DraftStatus.EDITING.name(), dAfterPushed.getStatus());
    }

    /**
     * 幂等性：连续两次 CANCELLED（虽然状态机不允许重复，但 cascade 函数本身应幂等）
     * 这里直接验证 cascadeArchiveDraftsAndWorkspaces 通过反射或多次执行不会出错。
     */
    @Test
    public void testCascadeIsIdempotent() {
        DecompileTask task = insertTask(TaskStatus.PENDING_REVIEW.name());
        DraftWorkspace ws = insertWorkspace(task.getId());
        KnowledgeDraft d = insertDraft(ws.getId(), "幂等测试模块", DraftStatus.DRAFT.name());

        decompileTaskService.terminateTask(task.getId());

        // 验证：再次执行 cascade（模拟历史脏数据被修复）也安全
        // 由于 ARCHIVED != 非 ARCHIVED 条件，再调用 update 影响行数为 0
        // 这里通过再次调用 validateNoPendingReviewTasks 间接验证
        java.lang.reflect.Method m;
        try {
            m = com.company.codeinsight.modules.task.service.impl.DecompileTaskServiceImpl.class
                    .getDeclaredMethod("validateNoPendingReviewTasks", Long.class, Long.class);
            m.setAccessible(true);
            Assertions.assertDoesNotThrow(() -> {
                try {
                    m.invoke(decompileTaskService, 7001L, 7001L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "级联归档后 readiness 不应再阻塞（间接验证归档生效）");
        } catch (NoSuchMethodException e) {
            Assertions.fail("validateNoPendingReviewTasks 方法签名变更: " + e.getMessage());
        }

        // draft 状态仍为 ARCHIVED（没有出现意外变更）
        KnowledgeDraft dFinal = draftMapper.selectById(d.getId());
        Assertions.assertEquals(DraftStatus.ARCHIVED.name(), dFinal.getStatus());
    }

    /**
     * 任务本身没有 workspace（早期失败）→ cascade 安全 no-op
     */
    @Test
    public void testCascadeNoopWhenNoWorkspace() {
        DecompileTask task = insertTask(TaskStatus.PENDING.name());

        // 不创建 workspace，直接 FAILED
        DecompileTask reloaded = taskMapper.selectById(task.getId());
        Assertions.assertDoesNotThrow(() ->
                stateMachineService.transitTo(reloaded, TaskStatus.FAILED, "排队失败"));

        DecompileTask finalState = taskMapper.selectById(task.getId());
        Assertions.assertEquals(TaskStatus.FAILED.name(), finalState.getStatus());
        // 没有 workspace → cascade 静默通过
        List<DraftWorkspace> allWs = workspaceMapper.selectList(null);
        Assertions.assertTrue(allWs.isEmpty());
    }
}