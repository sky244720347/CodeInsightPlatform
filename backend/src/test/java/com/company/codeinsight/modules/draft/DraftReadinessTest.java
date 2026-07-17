package com.company.codeinsight.modules.draft;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.modules.draft.dto.RepositoryReadinessDto;
import com.company.codeinsight.modules.draft.entity.DraftWorkspace;
import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import com.company.codeinsight.modules.draft.enums.DraftStatus;
import com.company.codeinsight.modules.draft.mapper.DraftWorkspaceMapper;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.draft.service.DraftService;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
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
 * 新建任务前置条件 readiness API 的集成测试（plan A）。
 *
 * <p>判定基准已从「草稿非终态」改为「任务待知识复核」：
 * 草稿可由复核人在任务 CONFIRMED / PUSHED 后继续编辑并回流到 EDITING，
 * 但只有当所属 {@code ci_task.status} 处于「知识复核」白名单（PENDING_REVIEW / REVIEWING）
 * 之一时，草稿才算阻塞新建任务。</p>
 *
 * <p>流水线中间断点（ENTRYPOINT_REVIEW / MODULE_HIERARCHY_REVIEW）不属于「知识复核」，
 * 不阻塞新建任务——新建任务会进入独立 workspace，与中间断点状态不冲突。</p>
 *
 * <p>覆盖关键回归：</p>
 * <ul>
 *   <li>任务已 CANCELLED / FAILED / PUSHED 时，关联草稿即便仍是 DRAFT/EDITING 也不阻塞</li>
 *   <li>任务处于 PENDING_REVIEW / REVIEWING 时，关联 workspace 下的非终态草稿才计入 blocking</li>
 *   <li>流水线中间断点 ENTRYPOINT_REVIEW / MODULE_HIERARCHY_REVIEW 不阻塞</li>
 *   <li>系统+仓库作用域严格收窄（A 系统的待复核任务不阻塞 B 系统）</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@ActiveProfiles("test")
@Transactional
public class DraftReadinessTest {

    @Autowired
    private DraftService draftService;

    @Autowired
    private DecompileTaskMapper taskMapper;

    @Autowired
    private DraftWorkspaceMapper workspaceMapper;

    @Autowired
    private KnowledgeDraftMapper draftMapper;

    private static final long SYS_A = 9001L;
    private static final long REPO_A = 9001L;
    private static final long SYS_B = 9002L;
    private static final long REPO_B = 9002L;

    @BeforeEach
    public void setUp() {
        // 清理可能影响测试的残留数据
        clearPendingReviewTasks();
        clearAllDraftsAndWorkspaces();
    }

    private void clearPendingReviewTasks() {
        taskMapper.delete(
                new LambdaQueryWrapper<DecompileTask>()
                        .in(DecompileTask::getStatus, List.of(
                                TaskStatus.ENTRYPOINT_REVIEW.name(),
                                TaskStatus.MODULE_HIERARCHY_REVIEW.name(),
                                TaskStatus.PENDING_REVIEW.name(),
                                TaskStatus.REVIEWING.name()
                        ))
        );
    }

    private void clearAllDraftsAndWorkspaces() {
        draftMapper.delete(new LambdaQueryWrapper<>());
        workspaceMapper.delete(new LambdaQueryWrapper<>());
    }

    // ---------- 工具方法 ----------

    private DecompileTask insertTask(long systemId, long repositoryId, String status) {
        DecompileTask task = new DecompileTask();
        task.setSystemId(systemId);
        task.setRepositoryId(repositoryId);
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

    private DraftWorkspace insertWorkspace(long taskId, long systemId, long repositoryId) {
        DraftWorkspace ws = new DraftWorkspace();
        ws.setTaskId(taskId);
        ws.setSystemId(systemId);
        ws.setRepositoryId(repositoryId);
        ws.setStatus("ACTIVE");
        ws.setCreatedDate(LocalDateTime.now());
        ws.setUpdatedDate(LocalDateTime.now());
        workspaceMapper.insert(ws);
        return ws;
    }

    private KnowledgeDraft insertDraft(long workspaceId, String moduleName, String draftStatus) {
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
     * 全局就绪度：无任何「待复核」任务 → 直接放行
     */
    @Test
    public void testGlobalReadinessReadyWhenNoPendingReviewTasks() {
        RepositoryReadinessDto dto = draftService.findGlobalReadiness();
        Assertions.assertTrue(dto.isReady(), "无待复核任务时应 ready=true");
        Assertions.assertEquals(0, dto.getUnconfirmedCount());
        Assertions.assertTrue(dto.getBlockingDrafts().isEmpty());
    }

    /**
     * 全局就绪度：有 PENDING_REVIEW 任务 + DRAFT 草稿 → 阻塞，草稿列入 blockingDrafts
     */
    @Test
    public void testGlobalReadinessBlocksOnPendingReviewTaskWithDrafts() {
        DecompileTask task = insertTask(SYS_A, REPO_A, TaskStatus.PENDING_REVIEW.name());
        DraftWorkspace ws = insertWorkspace(task.getId(), SYS_A, REPO_A);
        insertDraft(ws.getId(), "用户管理", DraftStatus.DRAFT.name());
        insertDraft(ws.getId(), "权限模块", DraftStatus.EDITING.name());

        RepositoryReadinessDto dto = draftService.findGlobalReadiness();
        Assertions.assertFalse(dto.isReady(), "存在待复核任务时应 ready=false");
        Assertions.assertEquals(2, dto.getUnconfirmedCount());
        Assertions.assertEquals(2, dto.getBlockingDrafts().size());
    }

    /**
     * 关键回归：任务已 CANCELLED → 关联草稿即便仍是 DRAFT/EDITING 也不阻塞
     */
    @Test
    public void testGlobalReadinessIgnoresDraftsFromCancelledTask() {
        DecompileTask task = insertTask(SYS_A, REPO_A, TaskStatus.CANCELLED.name());
        DraftWorkspace ws = insertWorkspace(task.getId(), SYS_A, REPO_A);
        insertDraft(ws.getId(), "已取消模块", DraftStatus.DRAFT.name());
        insertDraft(ws.getId(), "另一模块", DraftStatus.EDITING.name());

        RepositoryReadinessDto dto = draftService.findGlobalReadiness();
        Assertions.assertTrue(dto.isReady(),
                "任务 CANCELLED 后残留草稿不应阻塞（关键 bug 回归保护）");
        Assertions.assertEquals(0, dto.getUnconfirmedCount());
        Assertions.assertTrue(dto.getBlockingDrafts().isEmpty());
    }

    /**
     * 关键回归：任务已 CONFIRMED → 草稿被复核人回退 EDITING 也不阻塞（防止旧逻辑误判）
     */
    @Test
    public void testGlobalReadinessIgnoresDraftsFromConfirmedTask() {
        DecompileTask task = insertTask(SYS_A, REPO_A, TaskStatus.CONFIRMED.name());
        DraftWorkspace ws = insertWorkspace(task.getId(), SYS_A, REPO_A);
        insertDraft(ws.getId(), "草稿回流模块", DraftStatus.EDITING.name());

        RepositoryReadinessDto dto = draftService.findGlobalReadiness();
        Assertions.assertTrue(dto.isReady(),
                "任务 CONFIRMED 后草稿回流 EDITING 不应阻塞（防止 draft.status 误判）");
    }

    /**
     * 系统+仓库作用域：A 系统有 pending review，B 系统无 → A 阻塞、B 放行
     */
    @Test
    public void testScopedReadinessSeparatesSystems() {
        DecompileTask taskA = insertTask(SYS_A, REPO_A, TaskStatus.PENDING_REVIEW.name());
        DraftWorkspace wsA = insertWorkspace(taskA.getId(), SYS_A, REPO_A);
        insertDraft(wsA.getId(), "A系统模块", DraftStatus.DRAFT.name());

        RepositoryReadinessDto dtoA = draftService.findReadiness(SYS_A, REPO_A);
        Assertions.assertFalse(dtoA.isReady(), "A 系统应有 pending review 阻塞");

        RepositoryReadinessDto dtoB = draftService.findReadiness(SYS_B, REPO_B);
        Assertions.assertTrue(dtoB.isReady(), "B 系统不应被 A 系统的 pending review 影响");
        Assertions.assertEquals(0, dtoB.getUnconfirmedCount());
    }

    /**
     * 「知识复核」白名单内的两种状态都应触发阻塞
     */
    @Test
    public void testScopedReadinessBlocksForKnowledgeReviewStatuses() {
        for (String blocking : new String[]{
                TaskStatus.PENDING_REVIEW.name(),
                TaskStatus.REVIEWING.name()
        }) {
            clearPendingReviewTasks();
            clearAllDraftsAndWorkspaces();

            DecompileTask t = insertTask(SYS_A, REPO_A, blocking);
            DraftWorkspace ws = insertWorkspace(t.getId(), SYS_A, REPO_A);
            insertDraft(ws.getId(), "模块-" + blocking, DraftStatus.DRAFT.name());

            RepositoryReadinessDto dto = draftService.findReadiness(SYS_A, REPO_A);
            Assertions.assertFalse(dto.isReady(),
                    "状态 " + blocking + " 应触发阻塞（属知识复核白名单）");
            Assertions.assertEquals(1, dto.getUnconfirmedCount());
        }
    }

    /**
     * 关键回归：流水线中间断点（ENTRYPOINT_REVIEW / MODULE_HIERARCHY_REVIEW）
     * 不阻塞新建任务——它们是流水线可跳过的中间环节，新建任务会进入独立 workspace、不冲突。
     * 即便 workspace 下有非终态草稿，也不应被 readiness 视为阻塞。
     */
    @Test
    public void testScopedReadinessDoesNotBlockForPipelineBreakpoints() {
        for (String nonBlocking : new String[]{
                TaskStatus.ENTRYPOINT_REVIEW.name(),
                TaskStatus.MODULE_HIERARCHY_REVIEW.name()
        }) {
            clearPendingReviewTasks();
            clearAllDraftsAndWorkspaces();

            DecompileTask t = insertTask(SYS_A, REPO_A, nonBlocking);
            DraftWorkspace ws = insertWorkspace(t.getId(), SYS_A, REPO_A);
            insertDraft(ws.getId(), "中间断点模块-" + nonBlocking, DraftStatus.DRAFT.name());

            RepositoryReadinessDto dto = draftService.findReadiness(SYS_A, REPO_A);
            Assertions.assertTrue(dto.isReady(),
                    "流水线中间断点 " + nonBlocking + " 不应阻塞（新建任务与中间断点不冲突）");
            Assertions.assertEquals(0, dto.getUnconfirmedCount());
        }
    }
}