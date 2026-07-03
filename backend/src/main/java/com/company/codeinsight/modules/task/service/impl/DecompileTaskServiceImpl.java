package com.company.codeinsight.modules.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.codeinsight.common.cluster.ClusterInstanceId;
import com.company.codeinsight.common.cluster.ClusterProperties;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.storage.TaskWorkspacePaths;
import com.company.codeinsight.modules.ai.mapper.AiCallRecordMapper;
import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import com.company.codeinsight.modules.draft.enums.DraftStatus;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.service.CodeRepositoryService;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.service.SystemApplicationService;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.company.codeinsight.modules.task.service.DecompileTaskService;
import com.company.codeinsight.modules.task.service.TaskStateMachineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.company.codeinsight.modules.chunk.entity.CodeChunk;
import java.util.concurrent.CompletableFuture;

/**
 * 反编译/静态扫描任务管理服务实现类
 * 负责任务的创建、启动、重跑、终止，以及协调代码拉取、静态解析、切片提取、大模型调用和 Markdown 草稿生成的完整流水线工作流。
 */
@Slf4j
@Service
public class DecompileTaskServiceImpl extends ServiceImpl<DecompileTaskMapper, DecompileTask> implements DecompileTaskService {

    @org.springframework.beans.factory.annotation.Value("${code-insight.storage.local-path:./storage}")
    private String storageBase;

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
            return new PipelineContext(
                    projectDir,
                    scanResult.getIncrementalContext(),
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

    @Autowired
    private TaskStateMachineService stateMachineService;

    @Autowired
    private com.company.codeinsight.modules.scanner.service.CodeScannerService codeScannerService;

    @Autowired
    private com.company.codeinsight.modules.chunk.service.CodeChunkService codeChunkService;

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
    private AiCallRecordMapper aiCallRecordMapper;

    @Autowired
    private com.company.codeinsight.common.config.AiRetryProperties aiRetryProperties;

    /**
     * 草稿主表映射：用于任务创建前置条件校验，
     * 扫描 ci_knowledge_draft 中仍处于非终态的草稿。
     */
    @Autowired
    private KnowledgeDraftMapper draftMapper;

    @Autowired
    private com.company.codeinsight.modules.draft.mapper.DraftWorkspaceMapper workspaceMapper;

    @Autowired
    private ClusterProperties clusterProperties;

    @Autowired
    private ClusterInstanceId clusterInstanceId;

    @Autowired
    private TaskWorkspacePaths taskWorkspacePaths;

    /**
     * 分页获取任务列表
     */
    @Override
    public Page<DecompileTask> listTasksPage(int current, int size, Long systemId, String status, String type,
                                             List<String> statuses, String triggerSource,
                                             String keyword, String modelName,
                                             String createdAtStart, String createdAtEnd) {
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
                .ge(StringUtils.hasText(createdAtStart), DecompileTask::getCreatedAt, createdAtStart)
                .le(StringUtils.hasText(createdAtEnd), DecompileTask::getCreatedAt, createdAtEnd)
                .orderByDesc(DecompileTask::getCreatedAt);

        // 简单搜索 keyword：纯数字按 id 精确匹配，否则按 model_name LIKE '%keyword%'
        if (StringUtils.hasText(keyword)) {
            String trimmed = keyword.trim();
            if (trimmed.matches("\\d+")) {
                queryWrapper.eq(DecompileTask::getId, Long.parseLong(trimmed));
            } else {
                queryWrapper.and(w -> w.like(DecompileTask::getModelName, trimmed));
            }
        }

        return this.page(page, queryWrapper);
    }

    /**
     * 各状态分组的固定映射，便于一处维护。
     * key 是分组标识（API 返回给前端），value 是该分组下包含的所有状态枚举名。
     */
    private static final Map<String, List<String>> TASK_STATUS_GROUPS = Map.of(
            "RUNNING",         List.of("PENDING", "PULLING_CODE", "PARSING_CODE", "SPLITTING_TASK", "ENTRYPOINT_REVIEW", "AI_ANALYZING", "MODULE_HIERARCHY", "MODULE_HIERARCHY_REVIEW", "GENERATING_DOC", "PUSHING"),
            "PENDING_REVIEW",  List.of("PENDING_REVIEW", "REVIEWING"),
            "CONFIRMED",       List.of("CONFIRMED", "PUSHED"),
            "CLOSED",          List.of("FAILED", "CANCELLED", "ARCHIVED", "DRAFT")
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
        validateNoUnconfirmedDrafts(systemId, repositoryId);
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
        validateNoUnconfirmedDrafts(systemId, repositoryId);
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
     * 业务前置条件：基于当前系统+仓库校验未确认草稿，禁止新建任务。
     *
     * <p>非终态白名单与 {@link com.company.codeinsight.modules.draft.service.impl.DraftServiceImpl#findGlobalReadiness()}
     * 保持一致：DRAFT / EDITING 视为未完成。CONFIRMED / PUSHED / ARCHIVED 算"已消化"。
     *
     * <p>通过 DraftWorkspace 桥接 KnowledgeDraft，只检查属于当前 systemId + repositoryId 组合的草稿，
     * 避免 A 系统的未确认草稿阻塞 B 系统创建任务。</p>
     */
    private void validateNoUnconfirmedDrafts(Long systemId, Long repositoryId) {
        // 1. 查询该系统+仓库下的工作区
        List<com.company.codeinsight.modules.draft.entity.DraftWorkspace> workspaces = workspaceMapper.selectList(
                new LambdaQueryWrapper<com.company.codeinsight.modules.draft.entity.DraftWorkspace>()
                        .eq(com.company.codeinsight.modules.draft.entity.DraftWorkspace::getSystemId, systemId)
                        .eq(com.company.codeinsight.modules.draft.entity.DraftWorkspace::getRepositoryId, repositoryId)
        );
        if (workspaces.isEmpty()) {
            return; // 无工作区 = 该组合尚无草稿，直接放行
        }
        List<Long> workspaceIds = workspaces.stream()
                .map(com.company.codeinsight.modules.draft.entity.DraftWorkspace::getId)
                .collect(java.util.stream.Collectors.toList());

        // 2. 在这些工作区中查找未确认草稿
        long blocking = draftMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeDraft>()
                        .in(KnowledgeDraft::getWorkspaceId, workspaceIds)
                        .notIn(KnowledgeDraft::getStatus, java.util.List.of(
                                DraftStatus.CONFIRMED.name(),
                                DraftStatus.PUSHED.name(),
                                DraftStatus.ARCHIVED.name()
                        ))
        );
        if (blocking > 0) {
            throw new BusinessException("当前系统和代码库下仍有 " + blocking +
                    " 个草稿未确认，无法新建任务。请前往「复核工作区」完成确认。");
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
     */
    private void prepareTaskRerun(Long taskId, DecompileTask task) {
        task.setErrorReason(null);
        task.setEndedAt(null);
        task.setDurationMs(null);
        task.setStartedAt(null);
        task.setClaimedBy(null);
        task.setClaimedAt(null);
        task.setLeaseUntil(null);
        pipelineContextCache.remove(taskId);
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
     * 重跑/重试任务
     */
    @Override
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
        cleanupTaskWorkspace(id); // 重试前清理旧 workspace
        prepareTaskRerun(id, task);
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
                String promptContent = decompilePromptService.requireTaskPromptContent(task,
                        com.company.codeinsight.modules.prompt.entity.DecompilePrompt.TYPE_DOCUMENT_GENERATION);
                List<CodeChunk> chunks = codeChunkService.getChunksByTaskId(id);

                // 进入草稿汇总归档阶段 (GENERATING_DOC)
                stateMachineService.transitTo(id, TaskStatus.GENERATING_DOC, null);
                aiSummaryService.generateDraftDocument(id, chunks, promptContent);

                // 流转为终态：等待人工复核 (PENDING_REVIEW)
                stateMachineService.transitTo(id, TaskStatus.PENDING_REVIEW, null);
            } catch (Exception e) {
                log.error("Resume after hierarchy review failed for task " + id, e);
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
                com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy hierarchy =
                        moduleHierarchyService.buildAndPersist(id, projectDir);
                int modCount = hierarchy.getModules() != null ? hierarchy.getModules().size() : 0;
                execLog.log(id, "  模块数         = " + modCount);
                execLog.log(id, "<<< 模块层级重新提炼完成");
            } catch (Exception e) {
                log.error("Rebuild module hierarchy failed for task {}", id, e);
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
                    execLog.log(id, "  pipelineContext 已从 temp_repos 重建（内存缓存失效，如后端重启）");
                }
                continueAfterEntrypointReview(id, taskRef, pctx.projectDir(), pctx.ctx(), pctx);
            } catch (Exception e) {
                log.error("Resume after entrypoint review failed for task " + id, e);
                try {
                    stateMachineService.transitTo(id, TaskStatus.FAILED, e.getMessage());
                } catch (Exception ex) {
                    log.error("Failed to transit failed status", ex);
                }
            } finally {
                taskCache.remove(id);
                pipelineContextCache.remove(id);
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
        taskCache.remove(id);
    }

    /**
     * 从磁盘上的任务工作目录重建断点恢复上下文（后端重启导致内存缓存丢失时的兜底）。
     */
    private PipelineContext rebuildPipelineContext(DecompileTask task) {
        File projectDir = taskWorkspacePaths.taskProjectDir(task.getId());
        if (!projectDir.isDirectory()) {
            return null;
        }
        return PipelineContext.fullScan(projectDir);
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
        stateMachineService.transitTo(taskId, TaskStatus.MODULE_HIERARCHY, null);
        execLog.log(taskId, ">>> MODULE_HIERARCHY — AI 提炼模块层级");
        long t1 = System.currentTimeMillis();
        com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy hierarchy =
                moduleHierarchyService.buildAndPersist(taskId, projectDir, incrementalCtx, impact);
        int modCount = hierarchy.getModules() != null ? hierarchy.getModules().size() : 0;
        execLog.log(taskId, "  模块数         = " + modCount);
        execLog.log(taskId, "  耗时 " + (System.currentTimeMillis() - t1) + "ms");

        List<CodeChunk> chunks = codeChunkService.getChunksByTaskId(taskId);

        // 5. 调试断点 / GENERATING_DOC
        if (!Boolean.TRUE.equals(task.getRequireHierarchyReview())) {
            execLog.log(taskId, ">>> GENERATING_DOC — 生成文档");
            t1 = System.currentTimeMillis();
            stateMachineService.transitTo(taskId, TaskStatus.GENERATING_DOC, null);
            aiSummaryService.generateDraftDocument(taskId, chunks,
                    decompilePromptService.requireTaskPromptContent(task,
                            com.company.codeinsight.modules.prompt.entity.DecompilePrompt.TYPE_DOCUMENT_GENERATION),
                    incrementalCtx, impact);
            stateMachineService.transitTo(taskId, TaskStatus.PENDING_REVIEW, null);
            execLog.log(taskId, "  耗时 " + (System.currentTimeMillis() - t1) + "ms");
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
            execLog.log(taskId, "<<< 流水线完成 → PENDING_REVIEW");
        } else {
            stateMachineService.transitTo(taskId, TaskStatus.MODULE_HIERARCHY_REVIEW, null);
            execLog.log(taskId, "<<< 暂停 — 等待人工复核模块层级");
        }
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
                execLog.log(taskId, "!!! 纠错流水线异常: " + e.getMessage());
                try {
                    stateMachineService.transitTo(taskId, TaskStatus.FAILED, e.getMessage());
                } catch (Exception ex) {
                    log.error("Failed to transit failed status", ex);
                }
            } finally {
                taskCache.remove(taskId);
                pipelineContextCache.remove(taskId);
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
        List<CodeChunk> chunks = codeChunkService.getChunksByTaskId(taskId);
        aiSummaryService.generateDraftDocument(taskId, chunks,
                decompilePromptService.requireTaskPromptContent(task,
                        com.company.codeinsight.modules.prompt.entity.DecompilePrompt.TYPE_DOCUMENT_GENERATION),
                incrementalCtx);
        stateMachineService.transitTo(taskId, TaskStatus.PENDING_REVIEW, null);
        execLog.log(taskId, "<<< 纠错流水线完成 → PENDING_REVIEW");
    }

    /**
     * 异步流水线核心控制器
     * 将代码扫描、切片、AI分析和归纳归整串联在一起的无阻塞后台流水线。
     */
    @Override
    public void runPipeline(Long taskId) {
        // 缓存 systemId 用于 finally 释放并发许可
        DecompileTask task4SysId = taskCache.get(taskId);
        Long systemId = (task4SysId != null) ? task4SysId.getSystemId()
                : (this.getById(taskId) != null ? this.getById(taskId).getSystemId() : null);
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

                // 3. SPLITTING_TASK
                stateMachineService.transitTo(taskId, TaskStatus.SPLITTING_TASK, null);
                execLog.log(taskId, ">>> SPLITTING_TASK — 代码切片");
                t1 = System.currentTimeMillis();
                codeChunkService.chunkAndEstimate(taskId, snapshots, incrementalCtx);

                List<CodeChunk> allChunks = codeChunkService.getChunksByTaskId(taskId);
                long fileCount = allChunks.stream().filter(c -> "FILE".equals(c.getChunkType())).count();
                long clsCount = allChunks.stream().filter(c -> "CLASS".equals(c.getChunkType())).count();
                long mtdCount = allChunks.stream().filter(c -> "METHOD".equals(c.getChunkType())).count();
                execLog.log(taskId, "  切片总数       = " + allChunks.size() + " (FILE=" + fileCount + " CLASS=" + clsCount + " METHOD=" + mtdCount + ")");
                execLog.log(taskId, "  耗时 " + (System.currentTimeMillis() - t1) + "ms");

                // 3.5 ENTRYPOINT_REVIEW — 入口识别 + 落表（无论是否启用断点都先落表，保证 AI 阶段数据一致）
                execLog.log(taskId, ">>> ENTRYPOINT_DISCOVERY — 知识入口识别与落表");
                t1 = System.currentTimeMillis();
                com.company.codeinsight.modules.entrypoint.model.EntryPointConfig entryPointConfig =
                        entrypointReviewService.resolveConfig(task);
                java.util.List<com.company.codeinsight.modules.entrypoint.model.DiscoveredEntrypoint> discovered =
                        entrypointReviewService.discoverAndPersist(taskId, projectDir, entryPointConfig);
                int epCount = discovered == null ? 0 : discovered.size();
                int mtdTotal = discovered == null ? 0
                        : discovered.stream().mapToInt(d -> d.getMethods() == null ? 0 : d.getMethods().size()).sum();
                execLog.log(taskId, "  入口数         = " + epCount + " (含方法总数 = " + mtdTotal + ")");
                execLog.log(taskId, "  耗时 " + (System.currentTimeMillis() - t1) + "ms");

                // 流水线上下文入缓存，供 resumeAfterEntrypointReview 使用
                pipelineContextCache.put(taskId, PipelineContext.fromScan(projectDir, scanResult));

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
                execLog.log(taskId, "!!! 流水线异常: " + e.getClass().getSimpleName() + " — " + e.getMessage());
                for (StackTraceElement ste : e.getStackTrace()) {
                    if (ste.getClassName().contains("codeinsight")) {
                        execLog.log(taskId, "    at " + ste.getClassName() + "." + ste.getMethodName() + "(" + ste.getFileName() + ":" + ste.getLineNumber() + ")");
                    }
                }
                try {
                    stateMachineService.transitTo(taskId, TaskStatus.FAILED, e.getMessage());
                } catch (Exception ex) {
                    log.error("Failed to transit failed status", ex);
                }
            } finally {
                taskCache.remove(taskId);
                if (!pausedForEntrypointReview) {
                    pipelineContextCache.remove(taskId);
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

    /** 清理任务临时工作区（terminate/retry/cancel 后调用） */
    private void cleanupTaskWorkspace(Long taskId) {
        try {
            File ws = taskWorkspacePaths.taskProjectDir(taskId);
            if (ws.exists()) {
                deleteRecursively(ws);
                log.info("cleanupTaskWorkspace: taskId={} path={}", taskId, ws.getAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("cleanupTaskWorkspace failed taskId={}: {}", taskId, e.getMessage());
        }
    }

    private static void deleteRecursively(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File f : children) deleteRecursively(f);
        }
        dir.delete();
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
          .orderByAsc(DecompileTask::getCreatedAt);
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
                if (t.getCreatedAt() != null) {
                    long wait = (now - t.getCreatedAt()
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
}

