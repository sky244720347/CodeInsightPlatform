package com.company.codeinsight.modules.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.modules.ai.entity.AiCallRecord;
import com.company.codeinsight.modules.ai.mapper.AiCallRecordMapper;
import com.company.codeinsight.modules.callchain.entity.MethodCall;
import com.company.codeinsight.modules.callchain.mapper.MethodCallMapper;
import com.company.codeinsight.modules.draft.entity.DraftReviewComment;
import com.company.codeinsight.modules.draft.entity.DraftRevision;
import com.company.codeinsight.modules.draft.entity.DraftSourceReference;
import com.company.codeinsight.modules.draft.entity.DraftWorkspace;
import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import com.company.codeinsight.modules.draft.enums.DraftStatus;
import com.company.codeinsight.modules.draft.mapper.DraftReviewCommentMapper;
import com.company.codeinsight.modules.draft.mapper.DraftRevisionMapper;
import com.company.codeinsight.modules.draft.mapper.DraftSourceReferenceMapper;
import com.company.codeinsight.modules.draft.mapper.DraftWorkspaceMapper;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.entrypoint.entity.EntrypointEntity;
import com.company.codeinsight.modules.entrypoint.mapper.EntrypointMapper;
import com.company.codeinsight.modules.hierarchy.entity.MethodFunctionBinding;
import com.company.codeinsight.modules.hierarchy.entity.ModuleHierarchyNode;
import com.company.codeinsight.modules.hierarchy.mapper.MethodFunctionBindingMapper;
import com.company.codeinsight.modules.hierarchy.mapper.ModuleHierarchyNodeMapper;
import com.company.codeinsight.modules.scanner.entity.CodeFileSnapshot;
import com.company.codeinsight.modules.scanner.mapper.CodeFileSnapshotMapper;
import com.company.codeinsight.common.storage.EnvStorageResolver;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.company.codeinsight.modules.task.service.DecompileTaskService;
import com.company.codeinsight.modules.task.service.TaskExecutionLogger;
import com.company.codeinsight.modules.token.entity.TokenUsageAudit;
import com.company.codeinsight.modules.token.mapper.TokenUsageAuditMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务重跑清理覆盖测试（Plan A 实施方案）。
 *
 * <p>验证 {@link com.company.codeinsight.modules.task.service.impl.DecompileTaskServiceImpl#retryTask}
 * 在 FAILED/CANCELLED 任务上被调用后，所有历史副产物表 + 草稿子表 + 磁盘日志 + 任务字段
 * 都被彻底清零：</p>
 * <ul>
 *   <li>ci_task 字段：errorReason / durationMs / startedAt / endedAt / activeSegmentStartedAt /
 *       claimedBy/At/LeaseUntil → null；progress → 0；status → PENDING</li>
 *   <li>副产物表：incremental_impact / method_call / entrypoint / module_hierarchy_node /
 *       method_function_binding / code_file_snapshot / ai_call_record / token_usage_audit 全清</li>
 *   <li>草稿链：draft_workspace / knowledge_draft / draft_revision / draft_review_comment /
 *       draft_source_reference 全清</li>
 *   <li>磁盘：pipeline.log 不存在 / 磁盘 task_{id} 目录被重建（pipeline 启动后会再 mkdirs）</li>
 * </ul>
 *
 * <p>对应用户痛点：「任务重跑时『执行日志』卡片内容、详情页的开始时间/结束时间等都没有重置」。</p>
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@ActiveProfiles("test")
@Transactional
public class TaskRetryCleanupTest {

    @Autowired
    private DecompileTaskService decompileTaskService;

    @Autowired
    private DecompileTaskMapper taskMapper;

    @Autowired
    private AiCallRecordMapper aiCallRecordMapper;

    @Autowired
    private MethodCallMapper methodCallMapper;

    @Autowired
    private EntrypointMapper entrypointMapper;

    @Autowired
    private ModuleHierarchyNodeMapper moduleHierarchyNodeMapper;

    @Autowired
    private MethodFunctionBindingMapper methodFunctionBindingMapper;

    @Autowired
    private CodeFileSnapshotMapper codeFileSnapshotMapper;

    @Autowired
    private TokenUsageAuditMapper tokenUsageAuditMapper;

    @Autowired
    private DraftWorkspaceMapper draftWorkspaceMapper;

    @Autowired
    private KnowledgeDraftMapper knowledgeDraftMapper;

    @Autowired
    private DraftRevisionMapper draftRevisionMapper;

    @Autowired
    private DraftReviewCommentMapper draftReviewCommentMapper;

    @Autowired
    private DraftSourceReferenceMapper draftSourceReferenceMapper;

    @Autowired
    private TaskExecutionLogger execLog;

    @Autowired
    private EnvStorageResolver storageResolver;

    private static final long TASK_ID = 8801L;
    private static final long SYS_ID = 8801L;
    private static final long REPO_ID = 8801L;

    @BeforeEach
    public void setUp() {
        clearAllForTask();
        seedTaskFailed();
        seedAllArtifacts();
    }

    @AfterEach
    public void tearDown() {
        clearAllForTask();
    }

    private void clearAllForTask() {
        draftSourceReferenceMapper.delete(new LambdaQueryWrapper<>());
        draftReviewCommentMapper.delete(new LambdaQueryWrapper<>());
        draftRevisionMapper.delete(new LambdaQueryWrapper<>());
        knowledgeDraftMapper.delete(new LambdaQueryWrapper<>());
        draftWorkspaceMapper.delete(new LambdaQueryWrapper<>());
        tokenUsageAuditMapper.delete(new LambdaQueryWrapper<>());
        aiCallRecordMapper.delete(new LambdaQueryWrapper<>());
        methodFunctionBindingMapper.delete(new LambdaQueryWrapper<>());
        moduleHierarchyNodeMapper.delete(new LambdaQueryWrapper<>());
        entrypointMapper.delete(new LambdaQueryWrapper<>());
        methodCallMapper.delete(new LambdaQueryWrapper<>());
        codeFileSnapshotMapper.delete(new LambdaQueryWrapper<>());
        taskMapper.delete(new LambdaQueryWrapper<>());
    }

    /**
     * 落一条 FAILED 任务并把所有"老产物"塞进对应表 + pipeline.log
     */
    private void seedTaskFailed() {
        DecompileTask task = new DecompileTask();
        task.setId(TASK_ID);
        task.setSystemId(SYS_ID);
        task.setRepositoryId(REPO_ID);
        task.setStatus(TaskStatus.FAILED.name());
        task.setType("INITIAL");
        task.setProgress(80);  // 模拟跑到一半挂掉
        task.setErrorReason("模拟失败原因");
        task.setDurationMs(123_456L);
        task.setStartedAt(LocalDateTime.now().minusMinutes(10));
        task.setEndedAt(LocalDateTime.now().minusMinutes(5));
        task.setActiveSegmentStartedAt(LocalDateTime.now().minusMinutes(5));
        task.setClaimedBy("test-instance");
        task.setClaimedAt(LocalDateTime.now().minusMinutes(8));
        task.setLeaseUntil(LocalDateTime.now().minusMinutes(2));
        task.setPriority(50);
        task.setRequireHierarchyReview(Boolean.TRUE);
        task.setRequireEntrypointReview(Boolean.TRUE);
        task.setTriggerSource("MANUAL");
        taskMapper.insert(task);
    }

    private void seedAllArtifacts() {
        // 1. ci_code_file_snapshot
        CodeFileSnapshot file = new CodeFileSnapshot();
        file.setTaskId(TASK_ID);
        file.setFilePath("src/main/java/com/demo/Foo.java");
        file.setFileType("java");
        file.setLineCount(100);
        file.setFileHash("hash-foo");
        file.setContentUri("file:///tmp/Foo.java");
        file.setCreatedDate(LocalDateTime.now());
        codeFileSnapshotMapper.insert(file);

        // 2. ci_method_call
        MethodCall mc = new MethodCall();
        mc.setTaskId(TASK_ID);
        mc.setClassName("Foo");
        mc.setCallerMethod("doX");
        mc.setCallerSignature("Foo#doX()");
        mc.setDependencyName("svc:Bar");
        mc.setTargetMethod("doY");
        mc.setTargetSignature("doY");
        mc.setLineNumber(10);
        mc.setCreatedDate(LocalDateTime.now());
        methodCallMapper.insert(mc);

        // 3. ci_entrypoint
        EntrypointEntity ep = new EntrypointEntity();
        ep.setTaskId(TASK_ID);
        ep.setSystemId(SYS_ID);
        ep.setClassName("com.demo.Foo");
        ep.setEntryType("CONTROLLER");
        ep.setSortOrder(0);
        ep.setCreatedDate(LocalDateTime.now());
        ep.setUpdatedDate(LocalDateTime.now());
        entrypointMapper.insert(ep);

        // 4. ci_module_hierarchy_node
        ModuleHierarchyNode mhn = new ModuleHierarchyNode();
        mhn.setTaskId(TASK_ID);
        mhn.setSystemId(SYS_ID);
        mhn.setLevel("MODULE");
        mhn.setParentId(null);
        mhn.setNodeId("m00001");
        mhn.setName("测试模块");
        mhn.setKeywords("[\"k1\"]");
        mhn.setConfirmed(Boolean.FALSE);
        mhn.setCreatedDate(LocalDateTime.now());
        mhn.setUpdatedDate(LocalDateTime.now());
        moduleHierarchyNodeMapper.insert(mhn);

        // 5. ci_method_function_binding
        MethodFunctionBinding mfb = new MethodFunctionBinding();
        mfb.setTaskId(TASK_ID);
        mfb.setSystemId(SYS_ID);
        mfb.setModuleNodeId("m00001");
        mfb.setSubModuleNodeId("s00001");
        mfb.setFunctionNodeId("f00001");
        mfb.setClassName("com.demo.Foo");
        mfb.setMethodSignature("doX()");
        mfb.setSource("AI");
        mfb.setConfidence(java.math.BigDecimal.ONE);
        mfb.setCreatedDate(LocalDateTime.now());
        mfb.setUpdatedDate(LocalDateTime.now());
        methodFunctionBindingMapper.insert(mfb);

        // 6. ci_ai_call_record
        AiCallRecord ai = new AiCallRecord();
        ai.setTaskId(TASK_ID);
        ai.setCallStage("MODULE_HIERARCHY");
        ai.setModelName("minimax-M3");
        ai.setIsSuccess(1);
        ai.setInputToken(100);
        ai.setOutputToken(200);
        ai.setCreatedDate(LocalDateTime.now());
        aiCallRecordMapper.insert(ai);

        // 7. ci_token_usage_audit
        TokenUsageAudit tok = new TokenUsageAudit();
        tok.setTaskId(TASK_ID);
        tok.setSystemId(SYS_ID);
        tok.setModelName("minimax-M3");
        tok.setInputTokens(100);
        tok.setOutputTokens(200);
        tok.setCreatedDate(LocalDateTime.now());
        tokenUsageAuditMapper.insert(tok);

        // 8. 草稿工作区 + 草稿 + 草稿子表
        DraftWorkspace ws = new DraftWorkspace();
        ws.setTaskId(TASK_ID);
        ws.setSystemId(SYS_ID);
        ws.setRepositoryId(REPO_ID);
        ws.setStatus("ACTIVE");
        ws.setCreatedDate(LocalDateTime.now());
        ws.setUpdatedDate(LocalDateTime.now());
        draftWorkspaceMapper.insert(ws);

        KnowledgeDraft draft = new KnowledgeDraft();
        draft.setWorkspaceId(ws.getId());
        draft.setFilePath("foo.md");
        draft.setModuleName("Foo");
        draft.setContentUri("file:///tmp/foo.md");
        draft.setStatus(DraftStatus.DRAFT.name());
        draft.setHash("hash");
        draft.setCreatedDate(LocalDateTime.now());
        draft.setUpdatedDate(LocalDateTime.now());
        knowledgeDraftMapper.insert(draft);

        DraftRevision rev = new DraftRevision();
        rev.setDraftId(draft.getId());
        rev.setContentUri("file:///tmp/rev1.md");
        rev.setAuthor("tester");
        rev.setRemark("rev 1");
        rev.setCreatedDate(LocalDateTime.now());
        draftRevisionMapper.insert(rev);

        DraftReviewComment cmt = new DraftReviewComment();
        cmt.setDraftId(draft.getId());
        cmt.setComment("comment");
        cmt.setCreatedDate(LocalDateTime.now());
        draftReviewCommentMapper.insert(cmt);

        DraftSourceReference ref = new DraftSourceReference();
        ref.setDraftId(draft.getId());
        ref.setFilePath("src/main/java/com/demo/Foo.java");
        ref.setClassName("com.demo.Foo");
        ref.setStartLine(1);
        ref.setEndLine(10);
        ref.setCreatedDate(LocalDateTime.now());
        draftSourceReferenceMapper.insert(ref);

        // 9. pipeline.log 写一条历史日志
        execLog.log(TASK_ID, "[历史] 上一轮跑过的日志");
    }

    // =================== 测试用例 ===================

    /**
     * 关键回归：retry 调用后所有副产物表 + 草稿子表都被清零
     */
    @Test
    public void testRetryClearsAllArtifactTables() {
        // 验证种入成功（防 setUp 失败误判）
        Assertions.assertEquals(1, codeFileSnapshotMapper.selectCount(
                new LambdaQueryWrapper<CodeFileSnapshot>().eq(CodeFileSnapshot::getTaskId, TASK_ID)));
        Assertions.assertEquals(1, methodCallMapper.selectCount(
                new LambdaQueryWrapper<MethodCall>().eq(MethodCall::getTaskId, TASK_ID)));
        Assertions.assertEquals(1, entrypointMapper.selectCount(
                new LambdaQueryWrapper<EntrypointEntity>().eq(EntrypointEntity::getTaskId, TASK_ID)));
        Assertions.assertEquals(1, moduleHierarchyNodeMapper.selectCount(
                new LambdaQueryWrapper<ModuleHierarchyNode>().eq(ModuleHierarchyNode::getTaskId, TASK_ID)));
        Assertions.assertEquals(1, methodFunctionBindingMapper.selectCount(
                new LambdaQueryWrapper<MethodFunctionBinding>().eq(MethodFunctionBinding::getTaskId, TASK_ID)));
        Assertions.assertEquals(1, aiCallRecordMapper.selectCount(
                new LambdaQueryWrapper<AiCallRecord>().eq(AiCallRecord::getTaskId, TASK_ID)));
        Assertions.assertEquals(1, tokenUsageAuditMapper.selectCount(
                new LambdaQueryWrapper<TokenUsageAudit>().eq(TokenUsageAudit::getTaskId, TASK_ID)));
        Assertions.assertEquals(1, draftWorkspaceMapper.selectCount(
                new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getTaskId, TASK_ID)));
        Assertions.assertEquals(1, knowledgeDraftMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeDraft>().eq(KnowledgeDraft::getId, draftWorkspaceMapper.selectOne(
                        new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getTaskId, TASK_ID)
                ).getId())));
        Assertions.assertEquals(1, draftRevisionMapper.selectCount(new LambdaQueryWrapper<>()));
        Assertions.assertEquals(1, draftReviewCommentMapper.selectCount(new LambdaQueryWrapper<>()));
        Assertions.assertEquals(1, draftSourceReferenceMapper.selectCount(new LambdaQueryWrapper<>()));

        // 执行 retry
        decompileTaskService.retryTask(TASK_ID);

        // 验证：所有 ci_* 表的 task_id 对应行均被清空
        Assertions.assertEquals(0, codeFileSnapshotMapper.selectCount(
                new LambdaQueryWrapper<CodeFileSnapshot>().eq(CodeFileSnapshot::getTaskId, TASK_ID)));
        Assertions.assertEquals(0, methodCallMapper.selectCount(
                new LambdaQueryWrapper<MethodCall>().eq(MethodCall::getTaskId, TASK_ID)));
        Assertions.assertEquals(0, entrypointMapper.selectCount(
                new LambdaQueryWrapper<EntrypointEntity>().eq(EntrypointEntity::getTaskId, TASK_ID)));
        Assertions.assertEquals(0, moduleHierarchyNodeMapper.selectCount(
                new LambdaQueryWrapper<ModuleHierarchyNode>().eq(ModuleHierarchyNode::getTaskId, TASK_ID)));
        Assertions.assertEquals(0, methodFunctionBindingMapper.selectCount(
                new LambdaQueryWrapper<MethodFunctionBinding>().eq(MethodFunctionBinding::getTaskId, TASK_ID)));
        Assertions.assertEquals(0, aiCallRecordMapper.selectCount(
                new LambdaQueryWrapper<AiCallRecord>().eq(AiCallRecord::getTaskId, TASK_ID)));
        Assertions.assertEquals(0, tokenUsageAuditMapper.selectCount(
                new LambdaQueryWrapper<TokenUsageAudit>().eq(TokenUsageAudit::getTaskId, TASK_ID)));
        Assertions.assertEquals(0, draftWorkspaceMapper.selectCount(
                new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getTaskId, TASK_ID)));
        // 草稿及子表全清
        Assertions.assertEquals(0, knowledgeDraftMapper.selectCount(new LambdaQueryWrapper<>()));
        Assertions.assertEquals(0, draftRevisionMapper.selectCount(new LambdaQueryWrapper<>()));
        Assertions.assertEquals(0, draftReviewCommentMapper.selectCount(new LambdaQueryWrapper<>()));
        Assertions.assertEquals(0, draftSourceReferenceMapper.selectCount(new LambdaQueryWrapper<>()));
    }

    /**
     * 关键回归：retry 调用后任务核心字段全部重置为 null 并落库（开始/结束/耗时/进度/错误原因/集群租约）。
     *
     * <p>依赖 {@code DecompileTask} 实体上 timing / cluster 字段标注
     * {@link com.baomidou.mybatisplus.annotation.FieldStrategy#ALWAYS}，
     * 否则 MyBatis-Plus 默认 NOT_NULL 策略会让 {@code setEndedAt(null)} 这类清空操作在
     * {@code updateById} 时被静默忽略，DB 行仍保留上次的旧值。</p>
     */
    @Test
    public void testRetryResetsTaskCoreFields() {
        decompileTaskService.retryTask(TASK_ID);

        DecompileTask reloaded = taskMapper.selectById(TASK_ID);
        Assertions.assertNotNull(reloaded, "任务行不应被删除（retry 不删任务本身）");
        Assertions.assertEquals(TaskStatus.PENDING.name(), reloaded.getStatus());
        Assertions.assertEquals(0, reloaded.getProgress());
        Assertions.assertNull(reloaded.getErrorReason(), "error_reason 已有 ALWAYS，应被清空");
        // ↓ 以下 4 个字段以前因 FieldStrategy.NOT_NULL 默认值而未被清空；标 ALWAYS 后才落 null
        Assertions.assertNull(reloaded.getStartedAt(), "startedAt 必须被显式置 null 并落库");
        Assertions.assertNull(reloaded.getEndedAt(), "endedAt 必须被显式置 null 并落库（关键 bug 回归）");
        Assertions.assertNull(reloaded.getDurationMs(), "durationMs 必须被显式置 null 并落库（避免基于旧值累加）");
        Assertions.assertNull(reloaded.getActiveSegmentStartedAt(), "activeSegmentStartedAt 必须被显式置 null 并落库（避免下次段起算从旧值继续）");
        // ↓ 集群租约字段同样需要 ALWAYS
        Assertions.assertNull(reloaded.getClaimedBy());
        Assertions.assertNull(reloaded.getClaimedAt());
        Assertions.assertNull(reloaded.getLeaseUntil());
    }

    /**
     * pipeline.log 被清空：retry 后 taskDataDir（含 pipeline.log）整目录删除；
     * 下次 runPipeline 启动时 execLog.log() 会自动 mkdirs。
     */
    @Test
    public void testRetryClearsPipelineLog() {
        File logFile = storageResolver.taskDataDir(TASK_ID).resolve("pipeline.log").toFile();
        Assertions.assertTrue(logFile.exists() && logFile.length() > 0,
                "seed 后 pipeline.log 应存在且非空");

        decompileTaskService.retryTask(TASK_ID);

        Assertions.assertTrue(!logFile.exists() || logFile.length() == 0,
                "retry 后 pipeline.log 应被删除或清空");
        Assertions.assertFalse(storageResolver.taskDataDir(TASK_ID).toFile().exists(),
                "retry 后 taskDataDir 整目录应被删除");
    }

    /**
     * 防双发：仅 FAILED/CANCELLED 可重试，其他状态抛 BusinessException
     */
    @Test
    public void testRetryRejectsNonFailedStatus() {
        DecompileTask task = taskMapper.selectById(TASK_ID);
        task.setStatus(TaskStatus.DRAFT.name());
        taskMapper.updateById(task);

        Assertions.assertThrows(com.company.codeinsight.common.exception.BusinessException.class,
                () -> decompileTaskService.retryTask(TASK_ID));
    }
}