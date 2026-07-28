package com.company.codeinsight.modules.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.codeinsight.common.cluster.ClusterInstanceId;
import com.company.codeinsight.common.cluster.ClusterProperties;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.exception.ErrorCode;
import com.company.codeinsight.common.storage.EnvStorageResolver;
import com.company.codeinsight.common.storage.TaskWorkspacePaths;
import com.company.codeinsight.common.util.DirectoryCleanupUtil;
import com.company.codeinsight.modules.ai.entity.AiCallRecord;
import com.company.codeinsight.modules.ai.mapper.AiCallRecordMapper;
import com.company.codeinsight.modules.draft.entity.DraftRevision;
import com.company.codeinsight.modules.draft.entity.DraftReviewComment;
import com.company.codeinsight.modules.draft.entity.DraftSourceReference;
import com.company.codeinsight.modules.draft.entity.DraftWorkspace;
import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import com.company.codeinsight.modules.draft.mapper.DraftRevisionMapper;
import com.company.codeinsight.modules.draft.mapper.DraftReviewCommentMapper;
import com.company.codeinsight.modules.draft.mapper.DraftSourceReferenceMapper;
import com.company.codeinsight.modules.draft.mapper.DraftWorkspaceMapper;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.entrypoint.mapper.EntrypointMapper;
import com.company.codeinsight.modules.hierarchy.entity.ModuleHierarchyNode;
import com.company.codeinsight.modules.hierarchy.mapper.MethodFunctionBindingMapper;
import com.company.codeinsight.modules.hierarchy.mapper.ModuleHierarchyNodeMapper;
import com.company.codeinsight.modules.knowledge.entity.KnowledgeVersion;
import com.company.codeinsight.modules.knowledge.mapper.KnowledgeVersionMapper;
import com.company.codeinsight.modules.scanner.entity.CodeFileSnapshot;
import com.company.codeinsight.modules.scanner.mapper.CodeFileSnapshotMapper;
import com.company.codeinsight.modules.token.entity.TokenUsageAudit;
import com.company.codeinsight.modules.token.mapper.TokenUsageAuditMapper;
import com.company.codeinsight.modules.draft.enums.DraftStatus;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.service.CodeRepositoryService;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.service.SystemApplicationService;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.company.codeinsight.modules.task.service.DecompileTaskService;
import com.company.codeinsight.modules.task.service.TaskDiskCleanupService;
import com.company.codeinsight.modules.task.service.TaskStateMachineService;
import com.company.codeinsight.modules.task.support.TaskExecutionDuration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 反编译/静态扫描任务管理服务实现类
 * 负责任务的创建、启动、重跑、终止，以及协调代码拉取、静态解析、切片提取、大模型调用和 Markdown 草稿生成的完整流水线工作流。
 */
@Slf4j
@Service
public class DecompileTaskServiceImpl extends ServiceImpl<DecompileTaskMapper, DecompileTask> implements DecompileTaskService {

    // 运行态任务的共享内存快照映射，避免事务提交延迟导致高频轮询读不到内存状态的竞争情况
    public static final java.util.Map<Long, DecompileTask> taskCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 流水线断点恢复所需的轻量上下文（projectDir + IncrementalContext）。
     * <p>在 {@code runPipeline} 入口识别步骤完成后入缓存；在断点（ENTRYPOINT_REVIEW）resume 时读取使用，
     * 避免重新调用 {@code pullAndScan}（重复克隆仓库；扫描 commit 已写入任务 {@code source_commit}）。</p>
     * <p>在 {@code ENTRYPOINT_REVIEW} 暂停期间保留；由 resume / reject 或 pipeline 正常结束时清理。</p>
     */
    public record PipelineContext(
            java.io.File projectDir,
            com.company.codeinsight.modules.scanner.model.IncrementalContext ctx,
            String baselineCommitId,
            String headCommitId,
            String scanMode) {

        public static PipelineContext fromScan(java.io.File projectDir,
                                               com.company.codeinsight.modules.scanner.model.ScanResult scanResult) {
            return fromScan(projectDir, scanResult, scanResult.getIncrementalContext());
        }

        /**
         * @param enrichedCtx 流水线内已注入 {@code baselineTaskId} 的上下文（勿直接用 ScanResult 裸 ctx，
         *                    否则入口复核后续跑会丢掉基线 ID，MODULE_HIERARCHY 跳过继承）
         */
        public static PipelineContext fromScan(java.io.File projectDir,
                                               com.company.codeinsight.modules.scanner.model.ScanResult scanResult,
                                               com.company.codeinsight.modules.scanner.model.IncrementalContext enrichedCtx) {
            return new PipelineContext(
                    projectDir,
                    enrichedCtx != null ? enrichedCtx : scanResult.getIncrementalContext(),
                    scanResult.getBaselineCommitId(),
                    scanResult.getHeadCommitId(),
                    scanResult.getScanMode());
        }

        public static PipelineContext fullScan(java.io.File projectDir) {
            return new PipelineContext(projectDir,
                    com.company.codeinsight.modules.scanner.model.IncrementalContext.fullScan(),
                    null, null, "INITIAL");
        }
    }
    public static final java.util.Map<Long, PipelineContext> pipelineContextCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 增量影响分析结果缓存：在 continueAfterEntrypointReview 中计算后存入，
     * 供 resumeAfterHierarchyReview 恢复 GENERATING_DOC 阶段使用（避免重算反向 BFS）。
     */
    public static final java.util.Map<Long, com.company.codeinsight.modules.callchain.model.IncrementalImpact> impactCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    private TaskStateMachineService stateMachineService;

    @Autowired
    private com.company.codeinsight.modules.scanner.service.CodeScannerService codeScannerService;

    @Autowired
    private com.company.codeinsight.modules.ai.service.AiSummaryService aiSummaryService;

    @Autowired
    private com.company.codeinsight.modules.prompt.service.DecompilePromptService decompilePromptService;

    @Autowired
    private com.company.codeinsight.modules.model.mapper.AiModelMapper aiModelMapper;

    @Autowired
    private SystemApplicationService systemApplicationService;

    @Autowired
    private CodeRepositoryService codeRepositoryService;

    @Autowired
    private com.company.codeinsight.modules.callchain.service.MethodCallService methodCallService;

    @Autowired
    private com.company.codeinsight.modules.hierarchy.service.ModuleHierarchyService moduleHierarchyService;

    @Autowired
    private com.company.codeinsight.modules.entrypoint.service.EntrypointReviewService entrypointReviewService;

    @Autowired
    private com.company.codeinsight.modules.task.service.TaskConcurrencyLimiter taskConcurrencyLimiter;

    @Autowired
    private com.company.codeinsight.modules.task.service.TaskExecutionLogger execLog;

    @Autowired
    private com.company.codeinsight.modules.callchain.service.IncrementalImpactAnalyzer incrementalImpactAnalyzer;

    @Autowired
    private com.company.codeinsight.modules.callchain.service.IncrementalImpactPersistence incrementalImpactPersistence;

    @Autowired
    private com.company.codeinsight.modules.scanner.service.IncrementalScanPersistenceService incrementalScanPersistenceService;

    @Autowired
    private com.company.codeinsight.modules.scanner.service.BaselineInheritanceService baselineInheritanceService;

    @Autowired
    private com.company.codeinsight.modules.draft.service.DraftWorkspacePruneService draftWorkspacePruneService;

    @Autowired
    private AiCallRecordMapper aiCallRecordMapper;

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
    private EntrypointMapper entrypointMapper;

    @Autowired
    private ModuleHierarchyNodeMapper moduleHierarchyNodeMapper;

    @Autowired
    private MethodFunctionBindingMapper methodFunctionBindingMapper;

    @Autowired
    private CodeFileSnapshotMapper codeFileSnapshotMapper;

    @Autowired
    private KnowledgeVersionMapper knowledgeVersionMapper;

    @Autowired
    private TokenUsageAuditMapper tokenUsageAuditMapper;

    @Autowired
    private com.company.codeinsight.common.config.AiRetryProperties aiRetryProperties;

    @Autowired
    private ClusterProperties clusterProperties;

    @Autowired
    private ClusterInstanceId clusterInstanceId;

    @Autowired
    private com.company.codeinsight.modules.task.service.TaskQueueClaimService taskQueueClaimService;

    @Autowired
    private TaskWorkspacePaths taskWorkspacePaths;

    @Autowired
    private EnvStorageResolver storageResolver;

    @Autowired
    private TaskDiskCleanupService taskDiskCleanupService;

    @Autowired
    private com.company.codeinsight.modules.knowledge.service.KnowledgePublishFacade knowledgePublishFacade;

    /**
     * 分页获取任务列表
     */
    @Override
    public Page<DecompileTask> listTasksPage(int current, int size, Long systemId, String status, String type,
                                             List<String> statuses, String triggerSource,
                                             String keyword, String modelName,
                                             String createdDateStart, String createdDateEnd) {
        Page<DecompileTask> page = new Page<>(current, size);
        LambdaQueryWrapper<DecompileTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(systemId != null, DecompileTask::getSystemId, systemId)
                // status（单值）与 statuses（多值）互斥：单值用 eq，多值用 in
                .eq(StringUtils.hasText(status), DecompileTask::getStatus, status)
                .in(statuses != null && !statuses.isEmpty(), DecompileTask::getStatus, statuses)
                .eq(StringUtils.hasText(type), DecompileTask::getType, type)
                // 按 triggerSource 过滤（手动下发 / 定时触发视图）
                .eq(StringUtils.hasText(triggerSource), DecompileTask::getTriggerSource, triggerSource)
                // 精准搜索：模型名精确匹配
                .eq(StringUtils.hasText(modelName), DecompileTask::getModelName, modelName)
                // 精准搜索：创建时间区间（可单边）
                .ge(StringUtils.hasText(createdDateStart), DecompileTask::getCreatedDate, createdDateStart)
                .le(StringUtils.hasText(createdDateEnd), DecompileTask::getCreatedDate, createdDateEnd)
                .orderByDesc(DecompileTask::getCreatedDate);

        // 简单搜索 keyword：纯数字按 id 精确匹配，否则按 model_name LIKE '%keyword%'
        if (StringUtils.hasText(keyword)) {
            String trimmed = keyword.trim();
            if (trimmed.matches("\\d+")) {
                queryWrapper.eq(DecompileTask::getId, Long.parseLong(trimmed));
            } else {
                queryWrapper.and(w -> w.like(DecompileTask::getModelName, trimmed));
            }
        }

        Page<DecompileTask> result = this.page(page, queryWrapper);
        LocalDateTime now = LocalDateTime.now();
        for (DecompileTask task : result.getRecords()) {
            TaskExecutionDuration.enrichLiveDurationForRead(task, now);
        }
        return result;
    }

    @Override
    public DecompileTask getById(Serializable id) {
        DecompileTask task = super.getById(id);
        if (task != null) {
            TaskExecutionDuration.enrichLiveDurationForRead(task, LocalDateTime.now());
        }
        return task;
    }

    /**
     * 各状态分组的固定映射，便于一处维护。
     * key 是分组标识（API 返回给前端），value 是该分组下包含的所有状态枚举名。
     */
    private static final Map<String, List<String>> TASK_STATUS_GROUPS = Map.of(
            "RUNNING",         List.of("PENDING", "PULLING_CODE", "PARSING_CODE", "SPLITTING_TASK", "ENTRYPOINT_REVIEW", "AI_ANALYZING", "MODULE_HIERARCHY", "MODULE_HIERARCHY_REVIEW", "GENERATING_DOC", "PUSHING"),
            "PENDING_REVIEW",  List.of("PENDING_REVIEW", "REVIEWING"),
            "CONFIRMED",       List.of("CONFIRMED", "PUSHED"),
            "CLOSED",          List.of("FAILED", "CANCELLED", "ARCHIVED")
    );

    /** 允许物理删除的任务状态：草稿 / 排队未跑 / 已终止 */
    private static final Set<String> DELETABLE_STATUSES = Set.of(
            TaskStatus.DRAFT.name(),
            TaskStatus.PENDING.name(),
            TaskStatus.FAILED.name(),
            TaskStatus.CANCELLED.name(),
            TaskStatus.ARCHIVED.name()
    );

    @Override
    public Map<String, Long> countByStatusGroup(Long systemId) {
        Map<String, Long> result = new HashMap<>();
        // 初始化所有分组为 0
        for (String key : TASK_STATUS_GROUPS.keySet()) {
            result.put(key, 0L);
        }

        // 一次性 GROUP BY status 拉所有状态的数量（按 systemId 过滤）
        LambdaQueryWrapper<DecompileTask> qw = new LambdaQueryWrapper<>();
        if (systemId != null) {
            qw.eq(DecompileTask::getSystemId, systemId);
        }
        qw.select(DecompileTask::getStatus);
        List<DecompileTask> all = this.list(qw);

        long total = 0L;
        // 累加 ALL 与各分组
        for (DecompileTask t : all) {
            total += 1;
            for (Map.Entry<String, List<String>> entry : TASK_STATUS_GROUPS.entrySet()) {
                if (entry.getValue().contains(t.getStatus())) {
                    result.merge(entry.getKey(), 1L, Long::sum);
                }
            }
        }
        result.put("ALL", total);
        return result;
    }

    /**
     * 创建全量代码分析任务
     *
     * @param systemId            所属业务系统 ID
     * @param repositoryId        所选代码仓库 ID
     * @param modularizePromptId  模块提取提示词 ID（ci_prompt 主键），可空走默认
     * @param documentPromptId    文档生成提示词 ID（ci_prompt 主键），可空走默认
     * @param modelName           大模型名称
     * @return 新增的任务记录对象
     */
    @Override
    @Transactional
    public DecompileTask createInitialTask(Long systemId, Long repositoryId,
                                           Long modularizePromptId, Long documentPromptId,
                                           String modelName,
                                           com.company.codeinsight.modules.entrypoint.model.EntryPointConfig entryScanConfig,
                                           Boolean requireHierarchyReview) {
        return createInitialTask(systemId, repositoryId, modularizePromptId, documentPromptId, modelName,
                entryScanConfig, requireHierarchyReview, Boolean.TRUE);
    }

    /**
     * 创建全量任务（含 ENTRYPOINT_REVIEW 断点开关）
     */
    @Override
    @Transactional
    public DecompileTask createInitialTask(Long systemId, Long repositoryId,
                                           Long modularizePromptId, Long documentPromptId,
                                           String modelName,
                                           com.company.codeinsight.modules.entrypoint.model.EntryPointConfig entryScanConfig,
                                           Boolean requireHierarchyReview,
                                           Boolean requireEntrypointReview) {
        // 验证系统和仓库的从属合法性
        validateTaskSource(systemId, repositoryId);
        validateNoPendingReviewTasks(systemId, repositoryId);
        decompilePromptService.validateRepositoryPromptBinding(repositoryId);
        DecompileTask task = new DecompileTask();
        task.setSystemId(systemId);
        task.setRepositoryId(repositoryId);
        applyPromptIds(task, modularizePromptId, documentPromptId);
        decompilePromptService.validateTaskPromptBinding(task.getModularizePromptId(), task.getDocumentPromptId());
        task.setStatus(TaskStatus.DRAFT.name());
        task.setType("INITIAL");
        task.setProgress(0);

        // 若没有手动指定大模型，则拉取系统默认配置的模型
        if (!org.springframework.util.StringUtils.hasText(modelName)) {
            com.company.codeinsight.modules.model.entity.AiModel defaultModel = aiModelMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.company.codeinsight.modules.model.entity.AiModel>()
                            .eq(com.company.codeinsight.modules.model.entity.AiModel::getIsDefault, "true")
                            .last("LIMIT 1")
            );
            if (defaultModel != null) {
                modelName = defaultModel.getIdentifier();
            }
        }
        task.setModelName(modelName);
        task.setEntryScanConfig(buildTaskEntryScanSnapshot(repositoryId, entryScanConfig));
        // 默认开启模块层级调试断点，调用方显式传 false 才跳过
        task.setRequireHierarchyReview(requireHierarchyReview == null ? Boolean.TRUE : requireHierarchyReview);
        // 默认开启知识入口复核断点，调用方显式传 false 才跳过
        task.setRequireEntrypointReview(requireEntrypointReview == null ? Boolean.TRUE : requireEntrypointReview);
        // 知识复核断点：null → TRUE（与库默认一致）；下发页显式传 false 跳过
        task.setRequireKnowledgeReview(Boolean.TRUE);

        this.save(task);
        return task;
    }

    /**
     * 创建增量代码分析任务
     */
    @Override
    @Transactional
    public DecompileTask createIncrementalTask(Long systemId, Long repositoryId,
                                               Long modularizePromptId, Long documentPromptId,
                                               String modelName,
                                               com.company.codeinsight.modules.entrypoint.model.EntryPointConfig entryScanConfig,
                                               Boolean requireHierarchyReview) {
        return createIncrementalTask(systemId, repositoryId, modularizePromptId, documentPromptId, modelName,
                entryScanConfig, requireHierarchyReview, Boolean.TRUE);
    }

    /**
     * 创建增量任务（含 ENTRYPOINT_REVIEW 断点开关）
     */
    @Override
    @Transactional
    public DecompileTask createIncrementalTask(Long systemId, Long repositoryId,
                                               Long modularizePromptId, Long documentPromptId,
                                               String modelName,
                                               com.company.codeinsight.modules.entrypoint.model.EntryPointConfig entryScanConfig,
                                               Boolean requireHierarchyReview,
                                               Boolean requireEntrypointReview) {
        validateTaskSource(systemId, repositoryId);
        validateNoPendingReviewTasks(systemId, repositoryId);
        validateIncrementalBaselineGate(systemId, repositoryId);
        decompilePromptService.validateRepositoryPromptBinding(repositoryId);
        DecompileTask task = new DecompileTask();
        task.setSystemId(systemId);
        task.setRepositoryId(repositoryId);
        applyPromptIds(task, modularizePromptId, documentPromptId);
        decompilePromptService.validateTaskPromptBinding(task.getModularizePromptId(), task.getDocumentPromptId());
        task.setStatus(TaskStatus.DRAFT.name());
        task.setType("INCREMENTAL");
        task.setProgress(0);

        if (!org.springframework.util.StringUtils.hasText(modelName)) {
            com.company.codeinsight.modules.model.entity.AiModel defaultModel = aiModelMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.company.codeinsight.modules.model.entity.AiModel>()
                            .eq(com.company.codeinsight.modules.model.entity.AiModel::getIsDefault, "true")
                            .last("LIMIT 1")
            );
            if (defaultModel != null) {
                modelName = defaultModel.getIdentifier();
            }
        }
        task.setModelName(modelName);
        task.setEntryScanConfig(buildTaskEntryScanSnapshot(repositoryId, entryScanConfig));
        task.setRequireHierarchyReview(requireHierarchyReview == null ? Boolean.TRUE : requireHierarchyReview);
        task.setRequireEntrypointReview(requireEntrypointReview == null ? Boolean.TRUE : requireEntrypointReview);
        task.setRequireKnowledgeReview(Boolean.TRUE);

        this.save(task);
        return task;
    }

    /**
     * 创建全量任务，并打上触发来源标签。
     * <p>复用 {@link #createInitialTask(Long, Long, Long, Long, String, com.company.codeinsight.modules.entrypoint.model.EntryPointConfig, Boolean, Boolean)}，
     * 保存后回填 triggerSource / scheduleId（避免在原方法签名里追加与现有调用方无关的参数）。</p>
     */
    @Override
    @Transactional
    public DecompileTask createInitialTask(Long systemId, Long repositoryId,
                                           Long modularizePromptId, Long documentPromptId,
                                           String modelName,
                                           com.company.codeinsight.modules.entrypoint.model.EntryPointConfig entryScanConfig,
                                           Boolean requireHierarchyReview,
                                           String triggerSource) {
        return createInitialTask(systemId, repositoryId, modularizePromptId, documentPromptId, modelName,
                entryScanConfig, requireHierarchyReview, Boolean.TRUE, triggerSource);
    }

    /**
     * 创建全量任务（含 ENTRYPOINT_REVIEW 断点开关 + 触发来源标签）
     */
    @Override
    @Transactional
    public DecompileTask createInitialTask(Long systemId, Long repositoryId,
                                           Long modularizePromptId, Long documentPromptId,
                                           String modelName,
                                           com.company.codeinsight.modules.entrypoint.model.EntryPointConfig entryScanConfig,
                                           Boolean requireHierarchyReview,
                                           Boolean requireEntrypointReview,
                                           String triggerSource) {
        DecompileTask task = createInitialTask(systemId, repositoryId,
                modularizePromptId, documentPromptId, modelName, entryScanConfig,
                requireHierarchyReview, requireEntrypointReview);
        applyTriggerSource(task.getId(), triggerSource);
        return task;
    }

    /**
     * 创建增量任务，并打上触发来源标签。
     */
    @Override
    @Transactional
    public DecompileTask createIncrementalTask(Long systemId, Long repositoryId,
                                               Long modularizePromptId, Long documentPromptId,
                                               String modelName,
                                               com.company.codeinsight.modules.entrypoint.model.EntryPointConfig entryScanConfig,
                                               Boolean requireHierarchyReview,
                                               String triggerSource) {
        return createIncrementalTask(systemId, repositoryId, modularizePromptId, documentPromptId, modelName,
                entryScanConfig, requireHierarchyReview, Boolean.TRUE, triggerSource);
    }

    /**
     * 创建增量任务（含 ENTRYPOINT_REVIEW 断点开关 + 触发来源标签）
     */
    @Override
    @Transactional
    public DecompileTask createIncrementalTask(Long systemId, Long repositoryId,
                                               Long modularizePromptId, Long documentPromptId,
                                               String modelName,
                                               com.company.codeinsight.modules.entrypoint.model.EntryPointConfig entryScanConfig,
                                               Boolean requireHierarchyReview,
                                               Boolean requireEntrypointReview,
                                               String triggerSource) {
        DecompileTask task = createIncrementalTask(systemId, repositoryId,
                modularizePromptId, documentPromptId, modelName, entryScanConfig,
                requireHierarchyReview, requireEntrypointReview);
        applyTriggerSource(task.getId(), triggerSource);
        return task;
    }

    /** 把 trigger_source 写回刚保存的 task 行 */
    private void applyTriggerSource(Long taskId, String triggerSource) {
        this.lambdaUpdate()
                .eq(DecompileTask::getId, taskId)
                .set(DecompileTask::getTriggerSource, normalizeTriggerSource(triggerSource))
                .update();
    }

    private static String normalizeTriggerSource(String triggerSource) {
        return "SCHEDULED".equalsIgnoreCase(triggerSource) ? "SCHEDULED" : "MANUAL";
    }

    /**
     * 把传入的两类提示词 id 写入 task。
     * 解析顺序：①调用方显式传入 → ②仓库级绑定（ci_repository.modularize_prompt_id / document_prompt_id）。
     * 不做全局默认提示词兜底。
     */
    private void applyPromptIds(DecompileTask task, Long modularizePromptId, Long documentPromptId) {
        Long repoModularize = null;
        Long repoDocument = null;
        if (modularizePromptId == null || documentPromptId == null) {
            CodeRepository repo = codeRepositoryService.getById(task.getRepositoryId());
            if (repo != null) {
                if (modularizePromptId == null) {
                    repoModularize = repo.getModularizePromptId();
                }
                if (documentPromptId == null) {
                    repoDocument = repo.getDocumentPromptId();
                }
            }
        }
        task.setModularizePromptId(modularizePromptId != null ? modularizePromptId : repoModularize);
        task.setDocumentPromptId(documentPromptId != null ? documentPromptId : repoDocument);
    }

    /**
     * 数据源合规性拦截校验：系统与代码库必须存在且归属关系正确。
     * <p>系统启停状态机已删除，不再校验 ACTIVE。</p>
     */
    private void validateTaskSource(Long systemId, Long repositoryId) {
        if (systemId == null || repositoryId == null) {
            throw new BusinessException("请选择系统和代码库");
        }

        SystemApplication system = systemApplicationService.getById(systemId);
        if (system == null) {
            throw new BusinessException("所选系统不存在");
        }

        CodeRepository repository = codeRepositoryService.getById(repositoryId);
        if (repository == null) {
            throw new BusinessException("所选代码库不存在");
        }
        if (!Objects.equals(repository.getSystemId(), systemId)) {
            throw new BusinessException("所选代码库不属于当前系统");
        }
    }

    /**
     * 业务前置条件：基于当前系统+仓库校验是否存在「待知识复核」任务，禁止新建任务。
     *
     * <p>「知识复核」以任务级状态机为唯一权威源（{@link TaskStatus}），不再依赖草稿状态判断。
     * 仅命中以下 2 个文档级人工复核状态之一即视为仍在等人工介入，应引导复核人先去
     * 「任务概览 / 复核」页处置：</p>
     * <ul>
     *   <li>{@link TaskStatus#PENDING_REVIEW} — 待人工复核评审（文档已生成）</li>
     *   <li>{@link TaskStatus#REVIEWING} — 复核人工编辑中</li>
     * </ul>
     *
     * <p>明确不阻塞的流水线中间断点：</p>
     * <ul>
     *   <li>{@link TaskStatus#ENTRYPOINT_REVIEW} — 入口类清单确认（默认开启，可关闭）</li>
     *   <li>{@link TaskStatus#MODULE_HIERARCHY_REVIEW} — 模块层级人工编辑（默认开启，可关闭）</li>
     * </ul>
     * 这两个属于流水线必经步骤，新建任务会进入独立 workspace、不冲突，不应阻塞并行发起。
     *
     * <p>改用任务级判定后，避免了草稿被复核人在任务 CONFIRMED/PUSHED 后再次编辑回流到
     * {@link DraftStatus#EDITING} 所带来的「伪阻塞」：草稿 EDITING ≠ 任务待知识复核，
     * 任务状态才是「我能不能开新流水线」的真正信号。</p>
     */
    private void validateNoPendingReviewTasks(Long systemId, Long repositoryId) {
        long blocking = this.baseMapper.selectCount(
                new LambdaQueryWrapper<DecompileTask>()
                        .eq(DecompileTask::getSystemId, systemId)
                        .eq(DecompileTask::getRepositoryId, repositoryId)
                        .in(DecompileTask::getStatus, java.util.List.of(
                                TaskStatus.PENDING_REVIEW.name(),
                                TaskStatus.REVIEWING.name()
                        ))
        );
        if (blocking > 0) {
            throw new BusinessException("当前系统下仍有 " + blocking +
                    " 个任务的知识文档待复核，无法新建任务。请前往「任务概览 / 复核」页完成处置。");
        }
    }

    /**
     * INCREMENTAL 任务硬性门禁：
     * <ul>
     *   <li>门禁 1：仓库必须有过 PUSHED（lastPublishedVersionId 非空 且 lastCommitId 非空）</li>
     *   <li>门禁 2：禁止使用本地路径模式（gitUrl 指向本地目录）</li>
     * </ul>
     * 不满足任一条件直接抛 {@link BusinessException}，前端通过 message.error 展示具体原因。
     */
    private void validateIncrementalBaselineGate(Long systemId, Long repositoryId) {
        CodeRepository repository = codeRepositoryService.getById(repositoryId);
        if (repository == null) {
            throw new BusinessException("所选代码库不存在");
        }
        if (!Objects.equals(repository.getSystemId(), systemId)) {
            throw new BusinessException("所选代码库不属于当前系统");
        }
        // 门禁 1：必须 PUSHED 过（lastPublishedVersionId 是「最近一次发布」指针；lastCommitId 是发布时的源代码基线 commit）
        if (repository.getLastPublishedVersionId() == null
                || !org.springframework.util.StringUtils.hasText(repository.getLastCommitId())) {
            throw new BusinessException(ErrorCode.INCREMENTAL_NO_BASELINE);
        }
        // 门禁 2：本地路径模式一律禁止增量
        File probe = new File(repository.getGitUrl());
        if (probe.exists() && probe.isDirectory()) {
            throw new BusinessException(ErrorCode.INCREMENTAL_LOCAL_PATH_NOT_SUPPORTED);
        }
    }

    /**
     * 启动分析任务
     * 将任务置入缓存并扭转状态机为 PENDING，随后启动异步线程池执行拉取、解析、分片与分析。
     *
     * @param id 任务 ID
     */
    @Override
    public void startTask(Long id) {
        DecompileTask task = this.getById(id);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        // 防双发：仅 DRAFT 可入队
        if (!TaskStatus.DRAFT.name().equals(task.getStatus())) {
            throw new BusinessException("仅 DRAFT 状态可启动；当前状态: " + task.getStatus());
        }
        decompilePromptService.validateTaskPromptBinding(task.getModularizePromptId(), task.getDocumentPromptId());
        // 缺省优先级：SCHEDULED=60, MANUAL=50
        if (task.getPriority() == null) {
            task.setPriority(defaultPriorityFor(task.getTriggerSource()));
        }
        prepareTaskRerun(id, task);
        taskCache.put(task.getId(), task);
        // DRAFT → PENDING（TaskQueueDispatcher 在下个 tick 拉起）
        stateMachineService.transitTo(task, TaskStatus.PENDING, null);
    }

    /**
     * 构建任务入口扫描快照：默认全量复制仓库配置（含 excludeTargets），请求体字段整段覆写。
     */
    private String buildTaskEntryScanSnapshot(Long repositoryId,
            com.company.codeinsight.modules.entrypoint.model.EntryPointConfig taskOverride) {
        com.company.codeinsight.modules.repository.entity.CodeRepository repo =
                codeRepositoryService.getById(repositoryId);
        com.company.codeinsight.modules.entrypoint.model.EntryPointConfig repoCfg =
                com.company.codeinsight.modules.entrypoint.model.EntryPointConfigCodec.decode(
                        repo != null ? repo.getEntryScanConfig() : null);
        com.company.codeinsight.modules.entrypoint.model.EntryPointConfig snapshot =
                com.company.codeinsight.modules.entrypoint.model.EntryPointConfig.buildTaskSnapshot(
                        repoCfg, taskOverride);
        return com.company.codeinsight.modules.entrypoint.model.EntryPointConfigCodec.encode(snapshot);
    }

    /**
     * 重跑前清理上次失败/取消留下的错误信息、日志与内存上下文。
     *
     * @deprecated 自 v0.5 起重跑场景已升级为复用 {@link #purgeTaskArtifacts}，确保所有
     *             ci_* 副产物表 + 草稿子表都被清零。本方法保留仅为兜底，无外部调用方。
     */
    @Deprecated
    private void prepareTaskRerun(Long taskId, DecompileTask task) {
        task.setErrorReason(null);
        TaskExecutionDuration.resetTiming(task);
        task.setClaimedBy(null);
        task.setClaimedAt(null);
        task.setLeaseUntil(null);
        pipelineContextCache.remove(taskId);
        impactCache.remove(taskId);
        execLog.truncate(taskId);
        incrementalImpactPersistence.delete(taskId);
        this.updateById(task);
    }

    private int defaultPriorityFor(String triggerSource) {
        return "SCHEDULED".equalsIgnoreCase(triggerSource) ? 60 : 50;
    }

    /**
     * 强制终止分析任务
     */
    @Override
    @Transactional
    public void terminateTask(Long id) {
        DecompileTask task = this.getById(id);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        stateMachineService.transitTo(task, TaskStatus.CANCELLED, "用户终止了任务");
        cleanupTaskWorkspace(id);
    }

    /**
     * 重跑/重试任务。
     *
     * <p>清理范围（彻底归零，确保详情页 / 执行日志卡片 / Token 审计计数全部刷新）：</p>
     * <ol>
     *   <li>{@code ci_task} 字段：errorReason / durationMs / startedAt / endedAt /
     *       activeSegmentStartedAt / claimedBy/At/LeaseUntil / progress → null/0；status 保持
     *       CANCELLED/FAILED，由后续 transitTo(PENDING) 翻转到 PENDING。</li>
     *   <li>所有副产物表：incremental_impact / method_call / entrypoint /
     *       module_hierarchy_node / method_function_binding / code_file_snapshot /
     *       ai_call_record / token_usage_audit</li>
     *   <li>草稿工作区链：draft_workspace / knowledge_draft / draft_revision /
     *       draft_review_comment / draft_source_reference</li>
     *   <li>磁盘：workspaces/task_{id}、task_{id}/（pipeline.log）、drafts/task_{id}/、ai_logs/task_{id}/</li>
     *   <li>内存上下文：pipelineContextCache / taskCache</li>
     * </ol>
     *
     * <p>整体走一个事务，保证清理与状态转换的原子性。</p>
     */
    @Override
    @Transactional
    public void retryTask(Long id) {
        DecompileTask task = this.getById(id);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        // 防双发：仅 FAILED/CANCELLED 可重试
        String cur = task.getStatus();
        if (!"FAILED".equals(cur) && !"CANCELLED".equals(cur)) {
            throw new BusinessException("仅 FAILED/CANCELLED 状态可重试；当前状态: " + cur);
        }
        if (task.getPriority() == null) {
            task.setPriority(defaultPriorityFor(task.getTriggerSource()));
        }

        // 1) 字段重置：先在内存清零 + 一次 updateById 落库，确保重试途中查 DB 也看到新值
        task.setErrorReason(null);
        TaskExecutionDuration.resetTiming(task);
        task.setClaimedBy(null);
        task.setClaimedAt(null);
        task.setLeaseUntil(null);
        task.setProgress(0);
        this.updateById(task);

        // 2) 副产物清理：覆盖所有 ci_* 表 + 磁盘 workspace + pipeline.log + 草稿子表
        purgeTaskArtifacts(id);

        // 3) 内存上下文清理
        pipelineContextCache.remove(id);
        impactCache.remove(id);

        // 4) 重新入队
        taskCache.put(task.getId(), task);
        // FAILED/CANCELLED → PENDING（dispatcher 在下个 tick 拉起）
        stateMachineService.transitTo(task, TaskStatus.PENDING, null);
    }

    /**
     * 在模块层级人工复核断点 (MODULE_HIERARCHY_REVIEW) 处恢复流水线
     * <p>
     * 仅当任务处于 MODULE_HIERARCHY_REVIEW 时可调用；流转到 GENERATING_DOC → 生成草稿 → PENDING_REVIEW。
     * 异步执行，与 runPipeline 中对应阶段保持一致逻辑。
     */
    @Override
    public void resumeAfterHierarchyReview(Long id) {
        DecompileTask task = this.getById(id);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        TaskStatus current = TaskStatus.valueOf(task.getStatus());
        if (current != TaskStatus.MODULE_HIERARCHY_REVIEW) {
            throw new BusinessException("仅在模块层级复核状态下可恢复，当前状态: " + current);
        }
        assertTaskAffinity(task);

        taskCache.put(task.getId(), task);
        CompletableFuture.runAsync(() -> {
            try {
                execLog.log(id, ">>> 复核通过后继续 — BASELINE_DOC_INHERIT → GENERATING_DOC");

                // 恢复 PipelineContext（优先缓存，丢失时从 DB 重建）
                PipelineContext pctx = pipelineContextCache.get(id);
                if (pctx == null) {
                    pctx = rebuildPipelineContext(task);
                    if (pctx != null) {
                        pipelineContextCache.put(id, pctx);
                    }
                }
                com.company.codeinsight.modules.scanner.model.IncrementalContext incrementalCtx =
                        pctx != null ? pctx.ctx() : null;

                // 恢复 impact（优先缓存，丢失时重算）
                com.company.codeinsight.modules.callchain.model.IncrementalImpact impact = impactCache.get(id);

                boolean isIncremental = "INCREMENTAL".equals(task.getType());
                if (isIncremental) {
                    if (impact == null) {
                        execLog.log(id, "  impact 缓存丢失，重新计算增量影响分析");
                        impact = analyzeIncrementalImpact(id, pctx.projectDir(), incrementalCtx, pctx);
                        impactCache.put(id, impact);
                    }
                    runBaselineDocInheritAndGenerateDoc(id, task, incrementalCtx, impact);
                } else {
                    // 全量任务：直接 GENERATING_DOC
                    String promptContent = decompilePromptService.requireTaskPromptContent(task,
                            com.company.codeinsight.modules.prompt.entity.DecompilePrompt.TYPE_DOCUMENT_GENERATION);
                    execLog.log(id, ">>> GENERATING_DOC — 生成文档");
                    stateMachineService.transitTo(id, TaskStatus.GENERATING_DOC, null);
                    aiSummaryService.generateDraftDocument(id, promptContent, incrementalCtx, impact);
                    finishAfterDocGenerated(id);
                }

                execLog.log(id, "<<< 模块层级复核后续跑完成");
            } catch (Exception e) {
                log.error("Resume after hierarchy review failed for task " + id, e);
                execLog.logException(id, "模块层级复核后续跑异常", e);
                try {
                    stateMachineService.transitTo(id, TaskStatus.FAILED, e.getMessage());
                } catch (Exception ex) {
                    log.error("Failed to transit failed status", ex);
                }
            } finally {
                taskCache.remove(id);
            }
        });
    }

    /**
     * 「重新继承基线文档」重试入口：仅在 BASELINE_DOC_INHERIT 失败（FAILED）时可用，
     * 从该节点重新执行基线文档复制 → AI 文档生成。
     * <p>与普通「重试」区分：普通重试从 PENDING 从头跑；此入口仅重跑 BASELINE_DOC_INHERIT 及其后继。</p>
     */
    @Override
    public void retryBaselineInherit(Long id) {
        DecompileTask task = this.getById(id);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        TaskStatus current = TaskStatus.valueOf(task.getStatus());
        if (current != TaskStatus.FAILED) {
            throw new BusinessException("仅任务失败时可重新继承基线文档，当前状态: " + current);
        }
        if (!"INCREMENTAL".equals(task.getType())) {
            throw new BusinessException("仅增量任务支持重新继承基线文档");
        }
        assertTaskAffinity(task);

        taskCache.put(task.getId(), task);
        CompletableFuture.runAsync(() -> {
            try {
                execLog.truncate(id);
                execLog.log(id, ">>> 重新继承基线文档 — BASELINE_DOC_INHERIT → GENERATING_DOC");

                // 流转到 BASELINE_DOC_INHERIT（FAILED → BASELINE_DOC_INHERIT 已在状态机中放行）
                stateMachineService.transitTo(id, TaskStatus.BASELINE_DOC_INHERIT, null);

                PipelineContext pctx = pipelineContextCache.get(id);
                if (pctx == null) {
                    pctx = rebuildPipelineContext(task);
                    if (pctx != null) {
                        pipelineContextCache.put(id, pctx);
                    }
                }
                com.company.codeinsight.modules.scanner.model.IncrementalContext incrementalCtx =
                        pctx != null ? pctx.ctx() : null;

                com.company.codeinsight.modules.callchain.model.IncrementalImpact impact = impactCache.get(id);
                if (impact == null) {
                    execLog.log(id, "  impact 缓存丢失，重新计算增量影响分析");
                    impact = analyzeIncrementalImpact(id, pctx.projectDir(), incrementalCtx, pctx);
                    impactCache.put(id, impact);
                }

                // 复用 runBaselineDocInheritAndGenerateDoc 中 GENERATING_DOC 部分（workspace 已在 BASELINE_DOC_INHERIT 创建）
                com.company.codeinsight.modules.draft.entity.DraftWorkspace ws = ensureWorkspaceWithBaseline(
                        id, task, null);
                int inherited = baselineInheritanceService.inheritDrafts(id, ws.getId(), task.getRepositoryId());
                execLog.log(id, "  继承基线草稿 " + inherited + " 份");
                execLog.log(id, "<<< BASELINE_DOC_INHERIT 完成");

                execLog.log(id, ">>> GENERATING_DOC — 生成文档");
                stateMachineService.transitTo(id, TaskStatus.GENERATING_DOC, null);
                aiSummaryService.generateDraftDocument(id,
                        decompilePromptService.requireTaskPromptContent(task,
                                com.company.codeinsight.modules.prompt.entity.DecompilePrompt.TYPE_DOCUMENT_GENERATION),
                        incrementalCtx, impact);
                int pruned = draftWorkspacePruneService.pruneStaleDrafts(id, ws.getId());
                execLog.log(id, "  裁剪失效草稿 " + pruned + " 份");
                finishAfterDocGenerated(id);
                execLog.log(id, "<<< 重新继承基线文档完成");
            } catch (Exception e) {
                log.error("Retry baseline inherit failed for task " + id, e);
                execLog.logException(id, "重新继承基线文档异常", e);
                try {
                    stateMachineService.transitTo(id, TaskStatus.FAILED, e.getMessage());
                } catch (Exception ex) {
                    log.error("Failed to transit failed status", ex);
                }
            } finally {
                taskCache.remove(id);
            }
        });
    }

    /**
     * 在模块层级复核断点重新执行 AI 提炼（用于解析失败导致空树等场景）。
     * <p>INCREMENTAL 任务必须带基线上下文（继承 + persistIncremental），禁止走 fullScan+persistAll 冲掉基线树。</p>
     */
    @Override
    public void rebuildModuleHierarchy(Long id) {
        DecompileTask task = this.getById(id);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        TaskStatus current = TaskStatus.valueOf(task.getStatus());
        if (current != TaskStatus.MODULE_HIERARCHY_REVIEW) {
            throw new BusinessException("仅在模块层级复核状态下可重新提炼，当前状态: " + current);
        }
        assertTaskAffinity(task);
        File projectDir = taskWorkspacePaths.taskProjectDir(id);
        if (!projectDir.isDirectory()) {
            throw new BusinessException("任务工作目录不存在，无法重新提炼");
        }
        taskCache.put(task.getId(), task);
        CompletableFuture.runAsync(() -> {
            try {
                execLog.log(id, ">>> MODULE_HIERARCHY — 重新提炼模块层级");
                com.company.codeinsight.modules.scanner.model.IncrementalContext rebuildCtx =
                        resolveContextForHierarchyRebuild(task);
                execLog.log(id, "  rebuildCtx     = " + rebuildCtx);
                com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy hierarchy =
                        moduleHierarchyService.buildAndPersist(id, projectDir, rebuildCtx, null);
                int modCount = hierarchy.getModules() != null ? hierarchy.getModules().size() : 0;
                execLog.log(id, "  模块数         = " + modCount);
                execLog.log(id, "<<< 模块层级重新提炼完成");
            } catch (Exception e) {
                log.error("Rebuild module hierarchy failed for task {}", id, e);
                execLog.logException(id, "重新提炼模块层级异常", e);
                try {
                    stateMachineService.transitTo(id, TaskStatus.FAILED, e.getMessage());
                } catch (Exception ex) {
                    log.error("Failed to transit failed status", ex);
                }
            } finally {
                taskCache.remove(id);
            }
        });
    }

    /**
     * 模块层级「重新提炼」用的增量上下文：INCREMENTAL 必带 baselineTaskId，避免 fullScan 整表冲库。
     */
    private com.company.codeinsight.modules.scanner.model.IncrementalContext resolveContextForHierarchyRebuild(
            DecompileTask task) {
        if (!"INCREMENTAL".equals(task.getType())) {
            return com.company.codeinsight.modules.scanner.model.IncrementalContext.fullScan();
        }
        Long baselineTaskId = null;
        java.util.Set<String> changed = new java.util.LinkedHashSet<>();
        java.util.Set<String> deleted = new java.util.LinkedHashSet<>();
        com.company.codeinsight.modules.scanner.entity.IncrementalScanRecord scan =
                incrementalScanPersistenceService.findByTaskId(task.getId());
        if (scan != null) {
            baselineTaskId = scan.getBaselineTaskId();
            changed.addAll(incrementalScanPersistenceService.deserializePaths(scan.getChangedPaths()));
            deleted.addAll(incrementalScanPersistenceService.deserializePaths(scan.getDeletedPaths()));
        }
        if (baselineTaskId == null && task.getRepositoryId() != null) {
            CodeRepository repo = codeRepositoryService.getById(task.getRepositoryId());
            baselineTaskId = repo == null ? null : repo.getLastPublishedTaskId();
        }
        if (baselineTaskId == null) {
            throw new BusinessException("增量任务缺少基线任务 ID，无法重新提炼模块层级（禁止 fullScan 冲库）");
        }
        // 无 changed 清单时：对全部已启用入口重跑 AI（仍先 inherit）
        if (changed.isEmpty()) {
            for (com.company.codeinsight.modules.entrypoint.model.EntryPoint ep :
                    entrypointReviewService.loadEnabledEntries(task.getId())) {
                if (ep.getFilePath() != null) {
                    changed.add(ep.getFilePath());
                }
            }
        }
        return com.company.codeinsight.modules.scanner.model.IncrementalContext.incremental(
                changed, deleted, baselineTaskId);
    }

    /**
     * 在知识入口人工复核断点 (ENTRYPOINT_REVIEW) 处恢复流水线。
     * <p>仅当任务处于 ENTRYPOINT_REVIEW 时可调用；流转到 AI_ANALYZING → MODULE_HIERARCHY →（按 requireHierarchyReview
     * 选择 GENERATING_DOC 或 MODULE_HIERARCHY_REVIEW）。</p>
     * <p>异步执行；projectDir / IncrementalContext 从 {@link #pipelineContextCache} 读取，避免重新调用
     * {@code pullAndScan}（INCREMENTAL 任务会重复克隆仓库）。</p>
     */
    @Override
    public void resumeAfterEntrypointReview(Long id) {
        resumeAfterEntrypointReview(id, null);
    }

    @Override
    public void resumeAfterEntrypointReview(Long id,
            java.util.List<com.company.codeinsight.modules.entrypoint.model.ExcludeTarget> additionalExcludes) {
        DecompileTask task = this.getById(id);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        TaskStatus current = TaskStatus.valueOf(task.getStatus());
        if (current != TaskStatus.ENTRYPOINT_REVIEW) {
            throw new BusinessException("仅在知识入口复核状态下可恢复，当前状态: " + current);
        }
        if (additionalExcludes != null && !additionalExcludes.isEmpty()) {
            entrypointReviewService.applyReviewExcludes(id, additionalExcludes);
            task = this.getById(id);
        }
        assertTaskAffinity(task);

        taskCache.put(task.getId(), task);
        final DecompileTask taskRef = task;
        CompletableFuture.runAsync(() -> {
            try {
                PipelineContext pctx = pipelineContextCache.get(id);
                if (pctx == null) {
                    pctx = rebuildPipelineContext(taskRef);
                    if (pctx == null) {
                        throw new BusinessException("流水线上下文丢失（projectDir / IncrementalContext），请重试任务");
                    }
                    execLog.log(id, "  pipelineContext 已从 temp_repos + ci_incremental_scan 重建"
                            + "（内存缓存失效；INCREMENTAL 已恢复 baselineTaskId="
                            + (pctx.ctx() != null ? pctx.ctx().getBaselineTaskId() : null) + "）");
                }
                continueAfterEntrypointReview(id, taskRef, pctx.projectDir(), pctx.ctx(), pctx);
            } catch (Exception e) {
                log.error("Resume after entrypoint review failed for task " + id, e);
                execLog.logException(id, "知识入口复核后续跑异常", e);
                try {
                    stateMachineService.transitTo(id, TaskStatus.FAILED, e.getMessage());
                } catch (Exception ex) {
                    log.error("Failed to transit failed status", ex);
                }
            } finally {
                taskCache.remove(id);
                pipelineContextCache.remove(id);
        impactCache.remove(id);
            }
        });
    }

    /**
     * 在知识入口人工复核断点驳回任务。
     * <p>仅当任务处于 ENTRYPOINT_REVIEW 时可调用；流转到 CANCELLED（不入任何知识资产）。</p>
     *
     * @param id     任务 ID
     * @param reason 驳回理由（写进 task.error_reason 并落审计日志；可空）
     */
    @Override
    @Transactional
    public void rejectEntrypointReview(Long id, String reason) {
        DecompileTask task = this.getById(id);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        TaskStatus current = TaskStatus.valueOf(task.getStatus());
        if (current != TaskStatus.ENTRYPOINT_REVIEW) {
            throw new BusinessException("仅在知识入口复核状态下可驳回，当前状态: " + current);
        }
        String err = (reason == null || reason.isBlank()) ? "用户在知识入口复核驳回" : reason;
        stateMachineService.transitTo(task, TaskStatus.CANCELLED, err);
        pipelineContextCache.remove(id);
        impactCache.remove(id);
        taskCache.remove(id);
    }

    /**
     * 从磁盘工作区 + {@code ci_incremental_scan} 重建断点恢复上下文（后端重启导致内存缓存丢失时的兜底）。
     * <p>INCREMENTAL 必须带 {@code baselineTaskId}，禁止退回 {@link PipelineContext#fullScan}——
     * 否则 MODULE_HIERARCHY 会跳过基线继承，把空树喂给 AI。</p>
     */
    private PipelineContext rebuildPipelineContext(DecompileTask task) {
        File projectDir = taskWorkspacePaths.taskProjectDir(task.getId());
        if (!projectDir.isDirectory()) {
            return null;
        }
        com.company.codeinsight.modules.scanner.entity.IncrementalScanRecord scan =
                incrementalScanPersistenceService.findByTaskId(task.getId());
        boolean incrementalTask = "INCREMENTAL".equals(task.getType())
                || (scan != null && "INCREMENTAL".equals(scan.getScanMode()));
        if (!incrementalTask) {
            if (scan != null) {
                return new PipelineContext(
                        projectDir,
                        com.company.codeinsight.modules.scanner.model.IncrementalContext.fullScan(),
                        scan.getBaselineCommitId(),
                        scan.getHeadCommitId(),
                        scan.getScanMode() != null ? scan.getScanMode() : "INITIAL");
            }
            return PipelineContext.fullScan(projectDir);
        }
        Long baselineTaskId = scan != null ? scan.getBaselineTaskId() : null;
        java.util.Set<String> changed = new java.util.LinkedHashSet<>();
        java.util.Set<String> deleted = new java.util.LinkedHashSet<>();
        if (scan != null) {
            changed.addAll(incrementalScanPersistenceService.deserializePaths(scan.getChangedPaths()));
            deleted.addAll(incrementalScanPersistenceService.deserializePaths(scan.getDeletedPaths()));
        }
        if (baselineTaskId == null && task.getRepositoryId() != null) {
            CodeRepository repo = codeRepositoryService.getById(task.getRepositoryId());
            baselineTaskId = repo == null ? null : repo.getLastPublishedTaskId();
        }
        if (baselineTaskId == null) {
            throw new BusinessException(
                    "增量任务缺少基线任务 ID，无法从断点恢复流水线（禁止 fullScan 跳过基线继承）");
        }
        if (scan == null) {
            throw new BusinessException(
                    "增量任务缺少 ci_incremental_scan 记录，无法从断点恢复（禁止 fullScan 跳过基线继承）");
        }
        return new PipelineContext(
                projectDir,
                com.company.codeinsight.modules.scanner.model.IncrementalContext.incremental(
                        changed, deleted, baselineTaskId),
                scan.getBaselineCommitId(),
                scan.getHeadCommitId(),
                "INCREMENTAL");
    }

    private com.company.codeinsight.modules.callchain.model.IncrementalImpact analyzeIncrementalImpact(
            Long taskId,
            File projectDir,
            com.company.codeinsight.modules.scanner.model.IncrementalContext incrementalCtx,
            PipelineContext pctx) {
        com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy hierarchy =
                moduleHierarchyService.loadByTaskId(taskId);
        java.util.List<com.company.codeinsight.modules.entrypoint.model.EntryPoint> entries =
                entrypointReviewService.loadEnabledEntries(taskId);
        String baseline = pctx != null ? pctx.baselineCommitId() : null;
        String head = pctx != null ? pctx.headCommitId() : null;
        String scanMode = pctx != null ? pctx.scanMode() : "INITIAL";
        long t0 = System.currentTimeMillis();
        com.company.codeinsight.modules.callchain.model.IncrementalImpact impact =
                incrementalImpactAnalyzer.analyze(taskId, projectDir, incrementalCtx, hierarchy, entries,
                        baseline, head, scanMode);
        execLog.log(taskId, ">>> INCREMENTAL_IMPACT — 增量影响分析");
        if (impact.isIncremental()) {
            execLog.log(taskId, "  changed classes = " + incrementalCtx.getChangedPaths().stream()
                    .filter(p -> p.endsWith(".java")).count());
            execLog.log(taskId, "  entry retarget    = " + impact.getHierarchyRetargetEntries().size());
            execLog.log(taskId, "  doc modules       = " + impact.getDocRetargetModuleIds().size());
            execLog.log(taskId, "  reverse BFS hits  = " + impact.reverseBfsHitCount());
            execLog.log(taskId, "  degraded modules  = " + impact.getDegradedModuleCount());
            for (com.company.codeinsight.modules.callchain.model.ImpactTrace trace : impact.getTraces()) {
                if (trace.kind() == com.company.codeinsight.modules.callchain.model.ImpactTraceKind.REVERSE_BFS) {
                    execLog.log(taskId, "  trace: " + trace.changedFqcn() + " → " + trace.path()
                            + " → 模块「" + trace.moduleName() + "」");
                }
            }
        }
        execLog.log(taskId, "  耗时 " + (System.currentTimeMillis() - t0) + "ms");
        incrementalImpactPersistence.persist(taskId, impact, incrementalCtx);
        return impact;
    }

    /**
     * 集群断点恢复：若任务由其他节点执行且本地无工作目录，则拒绝恢复（需共享卷或原节点）。
     */
    private void assertTaskAffinity(DecompileTask task) {
        if (!clusterProperties.isEnabled()) {
            return;
        }
        File projectDir = taskWorkspacePaths.taskProjectDir(task.getId());
        if (!projectDir.isDirectory()) {
            String owner = task.getClaimedBy();
            throw new BusinessException("任务工作目录不在本节点"
                    + (owner != null ? "（认领节点: " + owner + "）" : "")
                    + "。请确认 temp_repos 已挂载为共享存储，或在原节点继续操作。");
        }
        if (StringUtils.hasText(task.getClaimedBy())
                && !task.getClaimedBy().equals(clusterInstanceId.get())) {
            log.warn("任务 #{} 由节点 {} 执行，当前节点 {} 因共享卷存在继续恢复",
                    task.getId(), task.getClaimedBy(), clusterInstanceId.get());
        }
    }

    /**
     * ENTRYPOINT_REVIEW 之后的共享流水线尾段
     * <p>从 {@code runPipeline}（断点跳过时）与 {@code resumeAfterEntrypointReview}（用户确认后）复用同一份代码。</p>
     */
    private void continueAfterEntrypointReview(Long taskId, DecompileTask task,
                                               File projectDir,
                                               com.company.codeinsight.modules.scanner.model.IncrementalContext incrementalCtx) {
        continueAfterEntrypointReview(taskId, task, projectDir, incrementalCtx, null);
    }

    private void continueAfterEntrypointReview(Long taskId, DecompileTask task,
                                               File projectDir,
                                               com.company.codeinsight.modules.scanner.model.IncrementalContext incrementalCtx,
                                               PipelineContext pctx) {
        // 4. AI_ANALYZING → MODULE_HIERARCHY
        if (!TaskStatus.AI_ANALYZING.name().equals(task.getStatus())) {
            stateMachineService.transitTo(taskId, TaskStatus.AI_ANALYZING, null);
        }
        execLog.log(taskId, ">>> AI_ANALYZING — AI 归纳");
        execLog.log(taskId, "  aiMock=" + aiSummaryService.isAiMock() + " | model="
                + (task.getModelName() != null ? task.getModelName() : "(default)"));
        execLog.log(taskId, "  aiRetry       = maxAttempts=" + aiRetryProperties.getMaxAttempts()
                + " backoffMs=" + aiRetryProperties.getBackoffMs());
        long aiT0 = System.currentTimeMillis();
        com.company.codeinsight.modules.callchain.model.IncrementalImpact impact = analyzeIncrementalImpact(
                taskId, projectDir, incrementalCtx, pctx);
        // 缓存 impact 供 resumeAfterHierarchyReview 恢复 GENERATING_DOC 时使用
        if (impact != null) {
            impactCache.put(taskId, impact);
        }
        stateMachineService.transitTo(taskId, TaskStatus.MODULE_HIERARCHY, null);
        execLog.log(taskId, ">>> MODULE_HIERARCHY — AI 提炼模块层级");
        long t1 = System.currentTimeMillis();
        com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy hierarchy =
                moduleHierarchyService.buildAndPersist(taskId, projectDir, incrementalCtx, impact);
        int modCount = hierarchy.getModules() != null ? hierarchy.getModules().size() : 0;
        execLog.log(taskId, "  模块数         = " + modCount);
        execLog.log(taskId, "  耗时 " + (System.currentTimeMillis() - t1) + "ms");

        // 5. 调试断点 / BASELINE_DOC_INHERIT / GENERATING_DOC
        if (!Boolean.TRUE.equals(task.getRequireHierarchyReview())) {
            // 跳过层级复核：INCREMENTAL 先走基线文档继承，再生成文档
            if ("INCREMENTAL".equals(task.getType())) {
                runBaselineDocInheritAndGenerateDoc(taskId, task, incrementalCtx, impact);
            } else {
                execLog.log(taskId, ">>> GENERATING_DOC — 生成文档");
                t1 = System.currentTimeMillis();
                stateMachineService.transitTo(taskId, TaskStatus.GENERATING_DOC, null);
                aiSummaryService.generateDraftDocument(taskId,
                        decompilePromptService.requireTaskPromptContent(task,
                                com.company.codeinsight.modules.prompt.entity.DecompilePrompt.TYPE_DOCUMENT_GENERATION),
                        incrementalCtx, impact);
                finishAfterDocGenerated(taskId);
                execLog.log(taskId, "  耗时 " + (System.currentTimeMillis() - t1) + "ms");
            }
            // AI 阶段终态汇总：从 ci_ai_call_record 统计本次任务的成功/失败次数
            long aiOk = aiCallRecordMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.company.codeinsight.modules.ai.entity.AiCallRecord>()
                            .eq(com.company.codeinsight.modules.ai.entity.AiCallRecord::getTaskId, taskId)
                            .eq(com.company.codeinsight.modules.ai.entity.AiCallRecord::getIsSuccess, 1));
            long aiFail = aiCallRecordMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.company.codeinsight.modules.ai.entity.AiCallRecord>()
                            .eq(com.company.codeinsight.modules.ai.entity.AiCallRecord::getTaskId, taskId)
                            .eq(com.company.codeinsight.modules.ai.entity.AiCallRecord::getIsSuccess, 0));
            execLog.log(taskId, "<<< AI_ANALYZING 完成 (成功 " + aiOk + " 失败 " + aiFail + ", 耗时 " + (System.currentTimeMillis() - aiT0) + "ms)");
            execLog.log(taskId, "<<< 流水线文档阶段完成");
        } else {
            stateMachineService.transitTo(taskId, TaskStatus.MODULE_HIERARCHY_REVIEW, null);
            execLog.log(taskId, "<<< 暂停 — 等待人工复核模块层级");
        }
    }

    /**
     * INCREMENTAL 任务的基线文档继承 + 文档生成两步流：
     * BASELINE_DOC_INHERIT（复制基线草稿）→ GENERATING_DOC（AI 重生成变更功能）。
     * <p>workspace 在 BASELINE_DOC_INHERIT 阶段创建并复制基线文档，
     * GENERATING_DOC 阶段只做 AI 重生成（upsert 覆盖继承文档中需重跑的）。</p>
     */
    private void runBaselineDocInheritAndGenerateDoc(Long taskId, DecompileTask task,
            com.company.codeinsight.modules.scanner.model.IncrementalContext incrementalCtx,
            com.company.codeinsight.modules.callchain.model.IncrementalImpact impact) {
        // ① BASELINE_DOC_INHERIT：创建 workspace + 复制基线文档
        execLog.log(taskId, ">>> BASELINE_DOC_INHERIT — 继承基线知识文档");
        stateMachineService.transitTo(taskId, TaskStatus.BASELINE_DOC_INHERIT, null);
        com.company.codeinsight.modules.draft.entity.DraftWorkspace ws = ensureWorkspaceWithBaseline(taskId, task, null);
        int inherited = baselineInheritanceService.inheritDrafts(taskId, ws.getId(), task.getRepositoryId());
        execLog.log(taskId, "  继承基线草稿 " + inherited + " 份");
        execLog.log(taskId, "<<< BASELINE_DOC_INHERIT 完成");

        // ② GENERATING_DOC：AI 重生成变更功能文档
        execLog.log(taskId, ">>> GENERATING_DOC — 生成文档");
        long t1 = System.currentTimeMillis();
        stateMachineService.transitTo(taskId, TaskStatus.GENERATING_DOC, null);
        aiSummaryService.generateDraftDocument(taskId,
                decompilePromptService.requireTaskPromptContent(task,
                        com.company.codeinsight.modules.prompt.entity.DecompilePrompt.TYPE_DOCUMENT_GENERATION),
                incrementalCtx, impact);
        // ③ 按当前 hierarchy 裁掉失效草稿（含已删功能的继承文档），使 workspace = 最终发布集
        int pruned = draftWorkspacePruneService.pruneStaleDrafts(taskId, ws.getId());
        execLog.log(taskId, "  裁剪失效草稿 " + pruned + " 份");
        finishAfterDocGenerated(taskId);
        execLog.log(taskId, "  耗时 " + (System.currentTimeMillis() - t1) + "ms");
    }

    /**
     * 文档生成结束后：按 requireKnowledgeReview 进入人工复核，或跳过并自动建版推送。
     */
    private void finishAfterDocGenerated(Long taskId) {
        DecompileTask task = this.getById(taskId);
        if (task == null) {
            task = taskCache.get(taskId);
        }
        if (task != null && Boolean.FALSE.equals(task.getRequireKnowledgeReview())) {
            execLog.log(taskId, "<<< 跳过知识复核 → 自动确认并 NAS 发布");
            knowledgePublishFacade.autoConfirmAndPublish(taskId);
            return;
        }
        stateMachineService.transitTo(taskId, TaskStatus.PENDING_REVIEW, null);
        execLog.log(taskId, "<<< 流水线完成 → PENDING_REVIEW（等待知识复核）");
    }

    /**
     * 创建或获取本次任务的 workspace，并设置 baselineWorkspaceId。
     */
    private com.company.codeinsight.modules.draft.entity.DraftWorkspace ensureWorkspaceWithBaseline(
            Long taskId, DecompileTask task, Long baselineTaskId) {
        com.company.codeinsight.modules.draft.entity.DraftWorkspace ws = draftWorkspaceMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.company.codeinsight.modules.draft.entity.DraftWorkspace>()
                        .eq(com.company.codeinsight.modules.draft.entity.DraftWorkspace::getTaskId, taskId));
        if (ws == null) {
            ws = new com.company.codeinsight.modules.draft.entity.DraftWorkspace();
            ws.setTaskId(taskId);
            ws.setSystemId(task.getSystemId());
            ws.setRepositoryId(task.getRepositoryId());
            ws.setStatus("ACTIVE");
            ws.setCreatedDate(LocalDateTime.now());
            ws.setUpdatedDate(LocalDateTime.now());
            draftWorkspaceMapper.insert(ws);
            if (ws.getId() == null) {
                ws = draftWorkspaceMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.company.codeinsight.modules.draft.entity.DraftWorkspace>()
                                .eq(com.company.codeinsight.modules.draft.entity.DraftWorkspace::getTaskId, taskId));
            }
        }
        if (baselineTaskId != null && ws.getBaselineWorkspaceId() == null) {
            Long baselineWsId = baselineInheritanceService.lookupBaselineWorkspaceId(baselineTaskId);
            if (baselineWsId != null) {
                ws.setBaselineWorkspaceId(baselineWsId);
                ws.setUpdatedDate(LocalDateTime.now());
                draftWorkspaceMapper.updateById(ws);
            }
        }
        return ws;
    }

    /**
     * 知识纠错任务：跳过拉取/AST/入口识别等前置阶段，从 resume_from 续跑。
     */
    public void runRemediationPipeline(Long taskId) {
        DecompileTask task4SysId = taskCache.get(taskId);
        Long systemId = (task4SysId != null) ? task4SysId.getSystemId()
                : (this.getById(taskId) != null ? this.getById(taskId).getSystemId() : null);
        CompletableFuture.runAsync(() -> {
            try {
                execLog.truncate(taskId);
        incrementalImpactPersistence.delete(taskId);
                execLog.log(taskId, "══════ 知识纠错流水线 taskId=" + taskId + " ══════");
                DecompileTask task = this.getById(taskId);
                if (task == null) {
                    throw new BusinessException("任务不存在");
                }
                assertTaskAffinity(task);
                PipelineContext pctx = rebuildPipelineContext(task);
                if (pctx == null) {
                    throw new BusinessException("纠错任务工作区不可用，请确认来源任务目录仍存在");
                }
                pipelineContextCache.put(taskId, pctx);
                String resume = task.getResumeFrom();
                if (com.company.codeinsight.modules.knowledge.remediation.KnowledgeRemediationConstants.RESUME_AI_ANALYZING
                        .equals(resume)) {
                    execLog.log(taskId, ">>> 纠错续跑 — 从 AI_ANALYZING / MODULE_HIERARCHY 开始（复用已克隆入口与 AST）");
                    continueAfterEntrypointReview(taskId, task, pctx.projectDir(), pctx.ctx());
                } else if (com.company.codeinsight.modules.knowledge.remediation.KnowledgeRemediationConstants.RESUME_GENERATING_DOC
                        .equals(resume)) {
                    execLog.log(taskId, ">>> 纠错续跑 — 从 GENERATING_DOC 开始");
                    continueGeneratingDocRemediation(taskId, task, pctx.projectDir(), pctx.ctx());
                } else {
                    throw new BusinessException("未知纠错续跑起点: " + resume);
                }
            } catch (Exception e) {
                execLog.logException(taskId, "纠错流水线异常", e);
                try {
                    stateMachineService.transitTo(taskId, TaskStatus.FAILED, e.getMessage());
                } catch (Exception ex) {
                    log.error("Failed to transit failed status", ex);
                }
            } finally {
                taskCache.remove(taskId);
                pipelineContextCache.remove(taskId);
        impactCache.remove(taskId);
                if (systemId != null) {
                    taskConcurrencyLimiter.release(systemId, taskId);
                }
            }
        });
    }

    private void continueGeneratingDocRemediation(Long taskId, DecompileTask task, File projectDir,
            com.company.codeinsight.modules.scanner.model.IncrementalContext incrementalCtx) {
        // 调度器已将纠错任务置为 GENERATING_DOC；幂等跳过重复流转
        if (!TaskStatus.GENERATING_DOC.name().equals(task.getStatus())) {
            stateMachineService.transitTo(taskId, TaskStatus.GENERATING_DOC, null);
        }
        aiSummaryService.generateDraftDocument(taskId,
                decompilePromptService.requireTaskPromptContent(task,
                        com.company.codeinsight.modules.prompt.entity.DecompilePrompt.TYPE_DOCUMENT_GENERATION),
                incrementalCtx);
        if ("INCREMENTAL".equals(task.getType())) {
            DraftWorkspace ws = draftWorkspaceMapper.selectOne(
                    new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getTaskId, taskId));
            if (ws != null) {
                int pruned = draftWorkspacePruneService.pruneStaleDrafts(taskId, ws.getId());
                execLog.log(taskId, "  裁剪失效草稿 " + pruned + " 份");
            }
        }
        finishAfterDocGenerated(taskId);
        execLog.log(taskId, "<<< 纠错流水线文档阶段完成");
    }

    /**
     * 异步流水线核心控制器
     * 将代码扫描、切片、AI分析和归纳归整串联在一起的无阻塞后台流水线。
     */
    @Override
    public boolean isPipelineThreadActive(Long taskId) {
        return taskId != null && taskCache.containsKey(taskId);
    }

    /**
     * 孤儿任务阶段重入。调用前须已 CAS 接管 claimed_by。
     * <ul>
     *   <li>PULLING_CODE / PARSING_CODE → FAILED（清产物）→ PENDING，由调度器重跑整条流水线</li>
     *   <li>AI_ANALYZING / MODULE_HIERARCHY → 重建上下文后 continueAfterEntrypointReview</li>
     *   <li>BASELINE_DOC_INHERIT / GENERATING_DOC → 重建上下文后走继承+生成或仅生成</li>
     *   <li>PUSHING → FAILED，提示手工重推</li>
     * </ul>
     */
    @Override
    public void reclaimOrphanAndResume(Long taskId) {
        DecompileTask task = this.getById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        TaskStatus status;
        try {
            status = TaskStatus.valueOf(task.getStatus());
        } catch (Exception e) {
            throw new BusinessException("任务状态无法识别: " + task.getStatus());
        }
        execLog.log(taskId, ">>> 孤儿接管 — 当前状态=" + status.name()
                + " claimedBy=" + task.getClaimedBy());

        switch (status) {
            case PULLING_CODE, PARSING_CODE -> reclaimEarlyStageToPending(task);
            case AI_ANALYZING, MODULE_HIERARCHY -> reclaimFromAiAnalyzing(task);
            case BASELINE_DOC_INHERIT, GENERATING_DOC -> reclaimFromDocStage(task);
            case PUSHING -> {
                stateMachineService.transitTo(taskId, TaskStatus.FAILED,
                        "推送过程中节点中断，请在推送页对 DRAFT/FAILED 版本重新入队");
                taskQueueClaimService.clearReservation(taskId);
            }
            default -> throw new BusinessException("状态 " + status + " 不支持孤儿接管");
        }
    }

    private void reclaimEarlyStageToPending(DecompileTask task) {
        Long id = task.getId();
        stateMachineService.transitTo(id, TaskStatus.FAILED, "节点丢失，自动接管后重新排队");
        DecompileTask fresh = this.getById(id);
        if (fresh == null) {
            return;
        }
        fresh.setErrorReason(null);
        TaskExecutionDuration.resetTiming(fresh);
        fresh.setClaimedBy(null);
        fresh.setClaimedAt(null);
        fresh.setLeaseUntil(null);
        fresh.setProgress(0);
        this.updateById(fresh);
        purgeTaskArtifacts(id);
        pipelineContextCache.remove(id);
        impactCache.remove(id);
        taskCache.remove(id);
        stateMachineService.transitTo(fresh, TaskStatus.PENDING, null);
        execLog.log(id, "<<< 孤儿接管 — 已重新入队 PENDING，等待调度器拉起");
    }

    private void reclaimFromAiAnalyzing(DecompileTask task) {
        Long id = task.getId();
        File projectDir = taskWorkspacePaths.taskProjectDir(id);
        if (projectDir == null || !projectDir.isDirectory()) {
            stateMachineService.transitTo(id, TaskStatus.FAILED,
                    "孤儿接管失败：工作目录不存在，无法从 AI 阶段续跑");
            taskQueueClaimService.clearReservation(id);
            return;
        }
        Long systemId = task.getSystemId();
        if (!taskConcurrencyLimiter.tryAcquire(systemId, id)) {
            // 许可暂满：清认领回到可再次扫描，避免永久占着 claimed_by
            taskQueueClaimService.clearReservation(id);
            execLog.log(id, "孤儿接管暂缓 — 任务并发许可不足，稍后重试");
            return;
        }
        taskCache.put(id, task);
        taskQueueClaimService.renewLease(id);
        CompletableFuture.runAsync(() -> {
            try {
                PipelineContext pctx = pipelineContextCache.get(id);
                if (pctx == null) {
                    pctx = rebuildPipelineContext(task);
                    if (pctx != null) {
                        pipelineContextCache.put(id, pctx);
                    }
                }
                if (pctx == null) {
                    throw new BusinessException("无法重建流水线上下文");
                }
                execLog.log(id, ">>> 孤儿接管 — 从 AI_ANALYZING / MODULE_HIERARCHY 重入");
                DecompileTask latest = this.getById(id);
                if (latest == null) {
                    latest = task;
                }
                // 拉回 AI_ANALYZING 以便 continueAfterEntrypointReview 从头走归纳
                if (!TaskStatus.AI_ANALYZING.name().equals(latest.getStatus())) {
                    // MODULE_HIERARCHY → 不允许直接回 AI；先 FAILED 再不行。
                    // 状态机：MODULE_HIERARCHY → GENERATING_DOC 或 REVIEW，不能回 AI。
                    // 故对 MODULE_HIERARCHY 直接从层级重跑：先 transit 保持，continue 内部会再 AI→MODULE。
                    // continueAfterEntrypointReview 若当前不是 AI_ANALYZING 会 transitTo AI_ANALYZING。
                    // 但从 MODULE_HIERARCHY → AI_ANALYZING 状态机禁止！
                    // 解决：FAILED 再不行… 用强制 update 状态？不优雅。
                    // 更好：MODULE_HIERARCHY 卡住时从 MODULE_HIERARCHY 重跑 buildAndPersist，再进后续。
                    if (TaskStatus.MODULE_HIERARCHY.name().equals(latest.getStatus())) {
                        reclaimModuleHierarchyOnly(id, latest, pctx);
                        return;
                    }
                }
                continueAfterEntrypointReview(id, latest, pctx.projectDir(), pctx.ctx(), pctx);
            } catch (Exception e) {
                execLog.logException(id, "孤儿接管(AI阶段)异常", e);
                try {
                    stateMachineService.transitTo(id, TaskStatus.FAILED, e.getMessage());
                } catch (Exception ex) {
                    log.error("孤儿接管失败状态流转异常", ex);
                }
            } finally {
                taskCache.remove(id);
                pipelineContextCache.remove(id);
                impactCache.remove(id);
                taskConcurrencyLimiter.release(systemId, id);
            }
        });
    }

    private void reclaimModuleHierarchyOnly(Long id, DecompileTask task, PipelineContext pctx) {
        execLog.log(id, ">>> 孤儿接管 — 从 MODULE_HIERARCHY 重入");
        com.company.codeinsight.modules.callchain.model.IncrementalImpact impact = impactCache.get(id);
        if (impact == null) {
            impact = analyzeIncrementalImpact(id, pctx.projectDir(), pctx.ctx(), pctx);
            if (impact != null) {
                impactCache.put(id, impact);
            }
        }
        moduleHierarchyService.buildAndPersist(id, pctx.projectDir(), pctx.ctx(), impact);
        if (!Boolean.TRUE.equals(task.getRequireHierarchyReview())) {
            if ("INCREMENTAL".equals(task.getType())) {
                runBaselineDocInheritAndGenerateDoc(id, task, pctx.ctx(), impact);
            } else {
                stateMachineService.transitTo(id, TaskStatus.GENERATING_DOC, null);
                aiSummaryService.generateDraftDocument(id,
                        decompilePromptService.requireTaskPromptContent(task,
                                com.company.codeinsight.modules.prompt.entity.DecompilePrompt.TYPE_DOCUMENT_GENERATION),
                        pctx.ctx(), impact);
                finishAfterDocGenerated(id);
            }
        } else {
            stateMachineService.transitTo(id, TaskStatus.MODULE_HIERARCHY_REVIEW, null);
            execLog.log(id, "<<< 暂停 — 等待人工复核模块层级");
        }
    }

    private void reclaimFromDocStage(DecompileTask task) {
        Long id = task.getId();
        File projectDir = taskWorkspacePaths.taskProjectDir(id);
        if (projectDir == null || !projectDir.isDirectory()) {
            // INCREMENTAL 文档阶段有时仍可用（基线继承不依赖本地源码？）但 generate 需要源码
            stateMachineService.transitTo(id, TaskStatus.FAILED,
                    "孤儿接管失败：工作目录不存在，无法从文档阶段续跑");
            taskQueueClaimService.clearReservation(id);
            return;
        }
        Long systemId = task.getSystemId();
        if (!taskConcurrencyLimiter.tryAcquire(systemId, id)) {
            taskQueueClaimService.clearReservation(id);
            execLog.log(id, "孤儿接管暂缓 — 任务并发许可不足，稍后重试");
            return;
        }
        taskCache.put(id, task);
        taskQueueClaimService.renewLease(id);
        final String stage = task.getStatus();
        CompletableFuture.runAsync(() -> {
            try {
                PipelineContext pctx = pipelineContextCache.get(id);
                if (pctx == null) {
                    pctx = rebuildPipelineContext(task);
                    if (pctx != null) {
                        pipelineContextCache.put(id, pctx);
                    }
                }
                if (pctx == null) {
                    throw new BusinessException("无法重建流水线上下文");
                }
                com.company.codeinsight.modules.callchain.model.IncrementalImpact impact = impactCache.get(id);
                if (impact == null && "INCREMENTAL".equals(task.getType())) {
                    impact = analyzeIncrementalImpact(id, pctx.projectDir(), pctx.ctx(), pctx);
                    if (impact != null) {
                        impactCache.put(id, impact);
                    }
                }
                DecompileTask latest = this.getById(id);
                if (latest == null) {
                    latest = task;
                }
                if (TaskStatus.BASELINE_DOC_INHERIT.name().equals(stage)
                        || ("INCREMENTAL".equals(latest.getType())
                        && TaskStatus.GENERATING_DOC.name().equals(stage))) {
                    execLog.log(id, ">>> 孤儿接管 — 从 BASELINE_DOC_INHERIT / GENERATING_DOC 重入");
                    runBaselineDocInheritAndGenerateDoc(id, latest, pctx.ctx(), impact);
                } else {
                    execLog.log(id, ">>> 孤儿接管 — 从 GENERATING_DOC 重入");
                    if (!TaskStatus.GENERATING_DOC.name().equals(latest.getStatus())) {
                        stateMachineService.transitTo(id, TaskStatus.GENERATING_DOC, null);
                    }
                    aiSummaryService.generateDraftDocument(id,
                            decompilePromptService.requireTaskPromptContent(latest,
                                    com.company.codeinsight.modules.prompt.entity.DecompilePrompt.TYPE_DOCUMENT_GENERATION),
                            pctx.ctx(), impact);
                    finishAfterDocGenerated(id);
                }
            } catch (Exception e) {
                execLog.logException(id, "孤儿接管(文档阶段)异常", e);
                try {
                    stateMachineService.transitTo(id, TaskStatus.FAILED, e.getMessage());
                } catch (Exception ex) {
                    log.error("孤儿接管失败状态流转异常", ex);
                }
            } finally {
                taskCache.remove(id);
                pipelineContextCache.remove(id);
                impactCache.remove(id);
                taskConcurrencyLimiter.release(systemId, id);
            }
        });
    }

    @Override
    public void runPipeline(Long taskId) {
        // 缓存 systemId 用于 finally 释放并发许可
        DecompileTask task4SysId = taskCache.get(taskId);
        Long systemId = (task4SysId != null) ? task4SysId.getSystemId()
                : (this.getById(taskId) != null ? this.getById(taskId).getSystemId() : null);
        // 确保认领/租约落在本节点（单机路径此前可能未写 claimed_by）
        try {
            taskQueueClaimService.renewLease(taskId);
        } catch (Exception e) {
            log.warn("runPipeline 初始续租失败 taskId={}: {}", taskId, e.getMessage());
        }
        CompletableFuture.runAsync(() -> {
            long t0 = System.currentTimeMillis();
            boolean pausedForEntrypointReview = false;
            try {
                DecompileTask taskEarly = this.getById(taskId);
                if (taskEarly != null
                        && com.company.codeinsight.modules.knowledge.remediation.KnowledgeRemediationConstants.TRIGGER_SOURCE
                        .equals(taskEarly.getTriggerSource())) {
                    runRemediationPipeline(taskId);
                    return;
                }

                execLog.truncate(taskId);
        incrementalImpactPersistence.delete(taskId);
                execLog.log(taskId, "══════ 流水线启动 taskId=" + taskId + " ══════");

                // 1. PULLING_CODE
                stateMachineService.transitTo(taskId, TaskStatus.PULLING_CODE, null);
                DecompileTask task = this.getById(taskId);
                if (task == null) task = taskCache.get(taskId);
                if (task == null) throw new BusinessException("任务不存在, ID: " + taskId);

                decompilePromptService.validateTaskPromptBinding(
                        task.getModularizePromptId(), task.getDocumentPromptId());

                execLog.log(taskId, ">>> PULLING_CODE — 拉取代码");
                execLog.log(taskId, "  model          = " + (task.getModelName() != null ? task.getModelName() : "(default)"));
                execLog.log(taskId, "  hierarchyReview= " + task.getRequireHierarchyReview());
                execLog.log(taskId, "  type           = " + task.getType());
                execLog.log(taskId, "  repositoryId   = " + task.getRepositoryId());

                long t1 = System.currentTimeMillis();
                com.company.codeinsight.modules.scanner.model.ScanResult scanResult =
                        codeScannerService.pullAndScan(taskId, task.getRepositoryId(), task.getType());
                File projectDir = scanResult.getProjectDir();
                com.company.codeinsight.modules.scanner.model.IncrementalContext incrementalCtx = scanResult.getIncrementalContext();

                // v1: 用仓库最近 PUSHED 任务的 ID 作为 baselineTaskId，注入到 IncrementalContext
                //    （仅 INCREMENTAL 任务有意义；INITIAL 任务该字段为 null，走全量路径）
                Long baselineTaskId = null;
                if (incrementalCtx.isIncremental()) {
                    com.company.codeinsight.modules.repository.entity.CodeRepository repoRef =
                            codeRepositoryService.getById(task.getRepositoryId());
                    baselineTaskId = repoRef == null ? null : repoRef.getLastPublishedTaskId();
                    incrementalCtx = com.company.codeinsight.modules.scanner.model.IncrementalContext.incremental(
                            incrementalCtx.getChangedPaths(),
                            incrementalCtx.getDeletedPaths(),
                            baselineTaskId);
                }

                // v1: 落盘扫描结果到 ci_incremental_scan；并在 INCREMENTAL 任务下做基线继承
                java.util.Set<String> changedForInherit = incrementalCtx.getChangedPaths();
                java.util.Set<String> deletedForInherit = incrementalCtx.getDeletedPaths();
                java.util.Set<String> excludedForInherit = new java.util.HashSet<>(
                        changedForInherit.size() + deletedForInherit.size());
                excludedForInherit.addAll(changedForInherit);
                excludedForInherit.addAll(deletedForInherit);
                if (incrementalCtx.isIncremental() && baselineTaskId != null) {
                    int inheritedEntrypoints = baselineInheritanceService.inheritEntrypoints(
                            taskId, baselineTaskId, excludedForInherit);
                    int inheritedMethodCalls = baselineInheritanceService.inheritMethodCalls(
                            taskId, baselineTaskId, excludedForInherit);
                    incrementalScanPersistenceService.updateInheritedCount(taskId,
                            inheritedEntrypoints + inheritedMethodCalls);
                    execLog.log(taskId, "  基线继承 (baselineTaskId=" + baselineTaskId + ")");
                    execLog.log(taskId, "    entrypoints inherited = " + inheritedEntrypoints);
                    execLog.log(taskId, "    method_calls inherited = " + inheritedMethodCalls);
                }
                // 落盘（INITIAL 任务也会写一条，baselineTaskId/baselineCommitId 为 NULL）
                incrementalScanPersistenceService.persist(taskId, task.getSystemId(), task.getRepositoryId(),
                        baselineTaskId, scanResult.getBaselineCommitId(), scanResult.getHeadCommitId(),
                        changedForInherit, deletedForInherit);

                execLog.log(taskId, "  projectDir     = " + projectDir.getAbsolutePath());
                execLog.log(taskId, "  scanMode       = " + (incrementalCtx.isIncremental() ? "INCREMENTAL" : "INITIAL"));
                if (StringUtils.hasText(scanResult.getHeadCommitId())) {
                    execLog.log(taskId, "  sourceCommit   = " + scanResult.getHeadCommitId());
                }
                if (StringUtils.hasText(scanResult.getBaselineCommitId())) {
                    execLog.log(taskId, "  publishedBase  = " + scanResult.getBaselineCommitId());
                }
                if (incrementalCtx.isIncremental()) {
                    execLog.log(taskId, "  changed files  = " + incrementalCtx.getChangedPaths().size()
                            + ", deleted files = " + incrementalCtx.getDeletedPaths().size());
                }
                execLog.log(taskId, "  耗时 " + (System.currentTimeMillis() - t1) + "ms");

                // 2. PARSING_CODE
                stateMachineService.transitTo(taskId, TaskStatus.PARSING_CODE, null);
                execLog.log(taskId, ">>> PARSING_CODE — AST 静态解析 + 调用链落表");
                t1 = System.currentTimeMillis();

                List<com.company.codeinsight.modules.scanner.entity.CodeFileSnapshot> snapshots = codeScannerService.getSnapshotsByTaskId(taskId);
                execLog.log(taskId, "  文件快照数    = " + snapshots.size());

                int callCount = methodCallService.persistAstForTask(taskId, projectDir, incrementalCtx);
                execLog.log(taskId, "  方法调用链数   = " + callCount);
                execLog.log(taskId, "  耗时 " + (System.currentTimeMillis() - t1) + "ms");

                // 3. ENTRYPOINT_DISCOVERY — 入口识别 + 落表（无论是否启用断点都先落表，保证 AI 阶段数据一致）
                execLog.log(taskId, ">>> ENTRYPOINT_DISCOVERY — 知识入口识别与落表");
                t1 = System.currentTimeMillis();
                com.company.codeinsight.modules.entrypoint.model.EntryPointConfig entryPointConfig =
                        entrypointReviewService.resolveConfig(task);
                // v1: 传入 IncrementalContext，INCREMENTAL 任务走基线 + 增量分支
                java.util.List<com.company.codeinsight.modules.entrypoint.model.DiscoveredEntrypoint> discovered =
                        entrypointReviewService.discoverAndPersist(taskId, projectDir, entryPointConfig, incrementalCtx);
                int epCount = discovered == null ? 0 : discovered.size();
                int mtdTotal = discovered == null ? 0
                        : discovered.stream().mapToInt(d -> d.getMethods() == null ? 0 : d.getMethods().size()).sum();
                execLog.log(taskId, "  入口数         = " + epCount + " (含方法总数 = " + mtdTotal + ")");
                execLog.log(taskId, "  耗时 " + (System.currentTimeMillis() - t1) + "ms");

                // 流水线上下文入缓存，供 resumeAfterEntrypointReview 使用。
                // 必须用已注入 baselineTaskId 的 incrementalCtx，不能用 ScanResult 裸 ctx
                // （scanner 产出的 IncrementalContext 不带 baselineTaskId）。
                pipelineContextCache.put(taskId, PipelineContext.fromScan(projectDir, scanResult, incrementalCtx));

                if (Boolean.TRUE.equals(task.getRequireEntrypointReview())) {
                    execLog.log(taskId, ">>> ENTRYPOINT_REVIEW — 入口复核");
                    stateMachineService.transitTo(taskId, TaskStatus.ENTRYPOINT_REVIEW, null);
                    execLog.log(taskId, "<<< 暂停 — 等待人工复核知识入口 (已耗时 " + (System.currentTimeMillis() - t0) + "ms)");
                    pausedForEntrypointReview = true;
                    return;
                }
                // 跳过断点：直接进入 AI 阶段（continueAfterEntrypointReview 复用）
                PipelineContext runCtx = pipelineContextCache.get(taskId);
                continueAfterEntrypointReview(taskId, task, projectDir, incrementalCtx, runCtx);
                return;
            } catch (Exception e) {
                execLog.logException(taskId, "流水线异常", e);
                try {
                    stateMachineService.transitTo(taskId, TaskStatus.FAILED, e.getMessage());
                } catch (Exception ex) {
                    log.error("Failed to transit failed status", ex);
                }
            } finally {
                taskCache.remove(taskId);
                if (!pausedForEntrypointReview) {
                    pipelineContextCache.remove(taskId);
        impactCache.remove(taskId);
                }
                // 释放任务并发许可（TaskQueueDispatcher / startTask 获取的全局 + 系统 Semaphore）
                if (systemId != null) {
                    taskConcurrencyLimiter.release(systemId, taskId);
                }
            }
        });
    }

    // ========== 队列管控方法 ==========

    @Override
    public void cancelQueuedTask(Long id) {
        DecompileTask task = this.getById(id);
        if (task == null) throw new BusinessException("任务不存在");
        if (!TaskStatus.PENDING.name().equals(task.getStatus())) {
            throw new BusinessException("仅 PENDING 状态可取消（in-flight 请用终止）");
        }
        stateMachineService.transitTo(task, TaskStatus.CANCELLED, "用户在队列中取消");
        cleanupTaskWorkspace(id);
    }

    /**
     * 清理任务在 NAS/runtimeRoot 上的全部磁盘产物（terminate/retry/cancel/delete 后调用）。
     * <p>覆盖：workspaces、taskDataDir、drafts、ai_logs。删除失败打 warn，
     * 真正 clone 前 {@code CodeScannerServiceImpl} 会再强制清一次并硬失败。</p>
     */
    private void cleanupTaskWorkspace(Long taskId) {
        taskDiskCleanupService.cleanupAllRuntimeArtifacts(taskId);
    }

    @Override
    public void adjustPriority(Long id, Integer newPriority) {
        if (newPriority == null || newPriority < 0 || newPriority > 100) {
            throw new BusinessException("priority 必须在 [0, 100] 范围");
        }
        DecompileTask task = this.getById(id);
        if (task == null) throw new BusinessException("任务不存在");
        if (!TaskStatus.PENDING.name().equals(task.getStatus())) {
            throw new BusinessException("仅 PENDING 状态可调整优先级");
        }
        task.setPriority(newPriority);
        this.updateById(task);
    }

    @Override
    public Page<DecompileTask> listQueuedTasks(int current, int size, Long systemId) {
        Page<DecompileTask> page = new Page<>(current, size);
        LambdaQueryWrapper<DecompileTask> qw = new LambdaQueryWrapper<>();
        qw.eq(DecompileTask::getStatus, TaskStatus.PENDING.name())
          .eq(systemId != null, DecompileTask::getSystemId, systemId)
          .orderByDesc(DecompileTask::getPriority)
          .orderByAsc(DecompileTask::getCreatedDate);
        return this.page(page, qw);
    }

    @Override
    public Map<String, Object> getQueueSummary() {
        List<DecompileTask> pending = this.list(new LambdaQueryWrapper<DecompileTask>()
                .eq(DecompileTask::getStatus, TaskStatus.PENDING.name()));
        long total = pending.size();
        long avgWaitSeconds = 0;
        if (!pending.isEmpty()) {
            long now = System.currentTimeMillis();
            long sum = 0;
            for (DecompileTask t : pending) {
                if (t.getCreatedDate() != null) {
                    long wait = (now - t.getCreatedDate()
                            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()) / 1000;
                    if (wait > 0) sum += wait;
                }
            }
            avgWaitSeconds = sum / total;
        }
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("total", total);
        result.put("avgWaitSeconds", avgWaitSeconds);
        return result;
    }

    @Override
    @Transactional
    public void deleteTask(Long id) {
        DecompileTask task = this.getById(id);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        // v1 基线 + 增量：PUSHED 状态永远不能删除。
        // PUSHED 是知识确认的硬边界：
        //   1) 它是仓库 last_published_task_id 指向的"基线任务"；
        //   2) 它的 entrypoint / module_hierarchy / knowledge_draft 是后续 INCREMENTAL 任务的基线数据复制源。
        // 删除 PUSHED 任务会断开基线链，破坏增量继承。绕过 DELETABLE_STATUSES 也必须拦截。
        if (TaskStatus.PUSHED.name().equals(task.getStatus())) {
            throw new BusinessException("PUSHED 状态任务不能删除；PUSHED 是知识确认的硬边界，" +
                    "其数据是后续 INCREMENTAL 任务的基线复制源。如确需清理，请联系管理员手动处理。");
        }
        if (!DELETABLE_STATUSES.contains(task.getStatus())) {
            throw new BusinessException("当前状态不允许删除；仅草稿、排队、失败、取消或归档任务可删除");
        }
        if (taskCache.containsKey(id)) {
            throw new BusinessException("任务正在本节点执行中，请先终止后再删除");
        }
        assertDeletableArtifacts(id, task);
        purgeTaskArtifacts(id);
        this.removeById(id);
        taskCache.remove(id);
        pipelineContextCache.remove(id);
        impactCache.remove(id);
        log.info("deleteTask: removed taskId={} status={}", id, task.getStatus());
    }

    private void assertDeletableArtifacts(Long taskId, DecompileTask task) {
        Long versionCount = knowledgeVersionMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeVersion>().eq(KnowledgeVersion::getTaskId, taskId));
        if (versionCount != null && versionCount > 0) {
            throw new BusinessException("任务已关联知识发布版本，无法删除");
        }
        if (task.getRepositoryId() != null) {
            CodeRepository repo = codeRepositoryService.getById(task.getRepositoryId());
            if (repo != null && Objects.equals(repo.getLastPublishedTaskId(), taskId)) {
                throw new BusinessException("该任务是仓库当前生效发布来源，无法删除");
            }
        }
    }

    private void purgeTaskArtifacts(Long taskId) {
        cleanupTaskWorkspace(taskId);
        incrementalImpactPersistence.delete(taskId);
        methodCallService.deleteByTaskId(taskId);
        entrypointMapper.deleteByTaskId(taskId);
        moduleHierarchyNodeMapper.deleteByTaskId(taskId);
        methodFunctionBindingMapper.deleteByTaskId(taskId);
        codeFileSnapshotMapper.delete(
                new LambdaQueryWrapper<CodeFileSnapshot>().eq(CodeFileSnapshot::getTaskId, taskId));
        aiCallRecordMapper.delete(
                new LambdaQueryWrapper<AiCallRecord>().eq(AiCallRecord::getTaskId, taskId));
        tokenUsageAuditMapper.delete(
                new LambdaQueryWrapper<TokenUsageAudit>().eq(TokenUsageAudit::getTaskId, taskId));
        purgeDraftArtifacts(taskId);
    }

    private void purgeDraftArtifacts(Long taskId) {
        DraftWorkspace workspace = draftWorkspaceMapper.selectOne(
                new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getTaskId, taskId));
        if (workspace == null) {
            return;
        }
        List<KnowledgeDraft> drafts = knowledgeDraftMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDraft>().eq(KnowledgeDraft::getWorkspaceId, workspace.getId()));
        for (KnowledgeDraft draft : drafts) {
            Long draftId = draft.getId();
            draftRevisionMapper.delete(
                    new LambdaQueryWrapper<DraftRevision>().eq(DraftRevision::getDraftId, draftId));
            draftReviewCommentMapper.delete(
                    new LambdaQueryWrapper<DraftReviewComment>().eq(DraftReviewComment::getDraftId, draftId));
            draftSourceReferenceMapper.delete(
                    new LambdaQueryWrapper<DraftSourceReference>().eq(DraftSourceReference::getDraftId, draftId));
        }
        knowledgeDraftMapper.delete(
                new LambdaQueryWrapper<KnowledgeDraft>().eq(KnowledgeDraft::getWorkspaceId, workspace.getId()));
        draftWorkspaceMapper.deleteById(workspace.getId());
    }
}

