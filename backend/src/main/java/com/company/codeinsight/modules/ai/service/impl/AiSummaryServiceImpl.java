package com.company.codeinsight.modules.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.company.codeinsight.common.config.AiDocBudgetProperties;
import com.company.codeinsight.common.config.AiRetryProperties;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.util.AiResponseJsonExtractor;
import com.company.codeinsight.modules.ai.entity.AiCallRecord;
import com.company.codeinsight.common.storage.EnvStorageResolver;
import com.company.codeinsight.common.storage.TaskWorkspacePaths;
import com.company.codeinsight.modules.ai.mapper.AiCallRecordMapper;
import com.company.codeinsight.modules.callchain.entity.MethodCall;
import com.company.codeinsight.modules.callchain.mapper.MethodCallMapper;
import com.company.codeinsight.modules.ai.model.FunctionSourceBundle;
import com.company.codeinsight.modules.ai.service.AiSummaryService;
import com.company.codeinsight.modules.ai.service.PipelineAiCaller;
import com.company.codeinsight.modules.ai.support.DocSourceBudgetShrinker;
import com.company.codeinsight.modules.draft.dto.RegenerateDraftResult;
import com.company.codeinsight.modules.draft.entity.DraftRevision;
import com.company.codeinsight.modules.draft.entity.DraftWorkspace;
import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import com.company.codeinsight.modules.draft.mapper.DraftRevisionMapper;
import com.company.codeinsight.modules.draft.mapper.DraftWorkspaceMapper;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.company.codeinsight.modules.token.service.TokenAuditService;
import com.company.codeinsight.modules.parser.service.JavaParserService;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo;
import com.company.codeinsight.modules.draft.entity.DraftSourceReference;
import com.company.codeinsight.modules.draft.mapper.DraftSourceReferenceMapper;
import com.company.codeinsight.modules.hierarchy.entity.MethodFunctionBinding;
import com.company.codeinsight.modules.hierarchy.mapper.MethodFunctionBindingMapper;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.scanner.model.ScanScope;
import com.company.codeinsight.modules.scanner.service.ScanScopeResolver;
import com.company.codeinsight.modules.task.service.TaskExecutionLogger;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class AiSummaryServiceImpl implements AiSummaryService {

    // AI 调用历史记录映射
    @Autowired
    private AiCallRecordMapper aiCallRecordMapper;

    // Token 消耗审计与流控服务
    @Autowired
    private TokenAuditService tokenAuditService;

    // 基础配置 - 流量管控：用户额度前置检查
    @Autowired
    private com.company.codeinsight.modules.quotacontrol.service.QuotaCheckService quotaCheckService;

    // 基础配置 - 流量管控：AI 调用并发信号量
    @Autowired
    private com.company.codeinsight.modules.quotacontrol.service.AiConcurrencyService aiConcurrencyService;

    // 草稿工作区映射
    @Autowired
    private DraftWorkspaceMapper draftWorkspaceMapper;

    // 知识草稿内容映射
    @Autowired
    private KnowledgeDraftMapper knowledgeDraftMapper;

    // 任务实体数据映射
    @Autowired
    private DecompileTaskMapper decompileTaskMapper;

    // Java 静态解析服务组件
    @Autowired
    private JavaParserService javaParserService;

    // 代码来源引用映射
    @Autowired
    private DraftSourceReferenceMapper draftSourceReferenceMapper;

    @Autowired
    private DraftRevisionMapper draftRevisionMapper;

    @Autowired
    private CodeRepositoryMapper repositoryMapper;

    @Autowired
    private com.company.codeinsight.modules.model.mapper.AiModelMapper aiModelMapper;

    @Autowired
    private com.company.codeinsight.modules.prompt.mapper.DecompilePromptMapper promptMapper;

    /**
     * 任务执行日志写入器：用于在"查看完整日志"中实时呈现 AI 阶段逐切片/逐模块进度。
     */
    @Autowired
    private TaskExecutionLogger execLog;

    @Autowired
    private PipelineAiCaller pipelineAiCaller;

    @Autowired
    private AiRetryProperties aiRetryProperties;

    @Autowired
    private AiDocBudgetProperties docBudgetProperties;

    /** 本机文档生成固定线程池；大小 = doc.parallelism，超额任务排队不新开线程 */
    private ExecutorService docAiExecutor;

    @PostConstruct
    public void initDocAiExecutor() {
        int n = Math.max(1, docBudgetProperties.getParallelism());
        docAiExecutor = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "doc-ai-");
            t.setDaemon(true);
            return t;
        });
        log.info("文档生成线程池已启动 parallelism={} acquireWaitSeconds={}",
                n, docBudgetProperties.getAcquireWaitSeconds());
    }

    @PreDestroy
    public void shutdownDocAiExecutor() {
        if (docAiExecutor == null) {
            return;
        }
        docAiExecutor.shutdown();
        try {
            if (!docAiExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                docAiExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            docAiExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 包级访问器：供 DecompileTaskServiceImpl 在 AI 阶段开头读取 Mock 状态写到 pipeline.log。
     */
    public boolean isAiMock() {
        return this.aiMock;
    }

    @Autowired
    private com.company.codeinsight.modules.prompt.service.DecompilePromptService decompilePromptService;

    @Autowired
    private com.company.codeinsight.modules.hierarchy.service.ModuleHierarchyService moduleHierarchyService;

    @Autowired
    private com.company.codeinsight.modules.entrypoint.service.EntryPointDiscoveryService entryPointDiscoveryService;

    @Autowired
    private com.company.codeinsight.modules.entrypoint.service.EntrypointReviewService entrypointReviewService;

    @Autowired
    private com.company.codeinsight.common.util.PromptTemplateLoader promptTemplateLoader;

    @Autowired
    private MethodCallMapper methodCallMapper;

    @Autowired
    private MethodFunctionBindingMapper methodFunctionBindingMapper;

    @Autowired
    private com.company.codeinsight.modules.callchain.service.MethodCallGraphService methodCallGraphService;

    @Autowired
    private ScanScopeResolver scanScopeResolver;

    @Autowired
    private TaskWorkspacePaths taskWorkspacePaths;

    @Autowired
    private EnvStorageResolver storageResolver;

    // 是否启用 AI 本地 Mock 仿真
    @Value("${code-insight.ai.mock:false}")
    private boolean aiMock;

    // 大模型服务访问密钥
    @Value("${code-insight.ai.api-key:}")
    private String apiKey;

    // 大模型服务接口基础地址 (默认为 MiniMax 服务端点)
    @Value("${code-insight.ai.api-url:https://api.minimax.io/v1}")
    private String apiUrl;

    // 默认选用的大模型版本名称
    @Value("${code-insight.ai.model-name:MiniMax-M3}")
    private String modelName;

    // 是否启用 Token 额度上限校验
    @Value("${code-insight.token.limit-enabled:true}")
    private boolean tokenLimitEnabled;

    // 单任务 Token 累积上限
    @Value("${code-insight.token.task-limit:100000}")
    private int taskTokenLimit;

    // 单系统月度 Token 累积上限
    @Value("${code-insight.token.system-monthly-limit:1000000}")
    private int systemMonthlyTokenLimit;

    /** 文档生成粒度：function=按功能(默认) / module=按模块 */
    @Value("${code-insight.doc-generation.granularity:function}")
    private String docGenerationGranularity;

    // JSON 数据映射工具
    private final ObjectMapper objectMapper = new ObjectMapper();
    // HTTP 调用客户端，连接超时设为 15 秒
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();


    /**
     * 大模型敏感数据脱敏过滤器
     * 运用正则表达式，在提交请求前对明文代码或提示词中的私钥、数据库密码、内网IP、认证Token等资产要素实施打码替换。
     *
     * @param input 原始未脱敏输入字符串
     * @return 脱敏完成的安全字符串
     */
    @Override
    public String filterSensitiveInfo(String input) {
        if (!StringUtils.hasText(input)) return input;
        // 脱敏各类常规配置密码 (如 password = 123456)
        input = input.replaceAll("(?i)(password|pwd|pass)\\s*[:=]\\s*['\"]?[a-zA-Z0-9_\\-\\$\\&\\*]+['\"]?", "$1=***");
        // 脱敏 Bearer Token 或 API Key 键值对
        input = input.replaceAll("(?i)(bearer\\s+|api[-_]?key\\s*[:=]\\s*)['\"]?[a-zA-Z0-9\\-\\._~+\\/]+=*['\"]?", "$1***");
        // 脱敏超过 16 位的强密钥或非对称私钥字段
        input = input.replaceAll("(?i)(secret|private_key)\\s*[:=]\\s*['\"]?[a-zA-Z0-9_\\-\\$\\&\\*]{16,}['\"]?", "$1=***");
        // 脱敏 JDBC 连接串中的密码明文部分
        input = input.replaceAll("jdbc:.*password=([^&;\\s]+)", "jdbc:***password=***");
        // 脱敏内网局域网 IP 地址链接 (如 192.168.x.x / 10.x.x.x)
        input = input.replaceAll("http://(192\\.168\\.\\d{1,3}\\.\\d{1,3}|10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})", "http://***");
        return input;
    }

    /**
     * 聚合模块/功能级分析结果并生成 Markdown 知识草稿。
     */
    @Override
    public void generateDraftDocument(Long taskId, String promptContent) {
        generateDraftDocument(taskId, promptContent, com.company.codeinsight.modules.scanner.model.IncrementalContext.fullScan());
    }

    @Override
    public void generateDraftDocument(Long taskId, String promptContent,
                                     com.company.codeinsight.modules.scanner.model.IncrementalContext ctx) {
        generateDraftDocument(taskId, promptContent, ctx, null);
    }

    @Override
    public void generateDraftDocument(Long taskId, String promptContent,
                                     com.company.codeinsight.modules.scanner.model.IncrementalContext ctx,
                                     com.company.codeinsight.modules.callchain.model.IncrementalImpact impact) {
        DecompileTask task = decompileTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("未找到关联的任务");
        }
        com.company.codeinsight.modules.scanner.model.IncrementalContext effective =
                ctx == null ? com.company.codeinsight.modules.scanner.model.IncrementalContext.fullScan() : ctx;

        com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy hierarchy =
                moduleHierarchyService.loadByTaskId(taskId);

        if (hierarchy.getModules().isEmpty()) {
            throw new BusinessException("模块层级为空，无法生成知识文档。请检查 MODULE_HIERARCHY 阶段是否成功。");
        }

        // 3. 查找或为当前任务创建工作区
        DraftWorkspace ws = draftWorkspaceMapper.selectOne(
                new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getTaskId, taskId)
        );
        if (ws == null) {
            ws = new DraftWorkspace();
            ws.setTaskId(taskId);
            ws.setSystemId(task.getSystemId());
            ws.setRepositoryId(task.getRepositoryId());
            ws.setStatus("ACTIVE");
            ws.setCreatedDate(LocalDateTime.now());
            ws.setUpdatedDate(LocalDateTime.now());
            draftWorkspaceMapper.insert(ws);
            if (ws.getId() == null) {
                ws = draftWorkspaceMapper.selectOne(
                        new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getTaskId, taskId));
            }
        }
        // v2: 纯 release 方案 — BASELINE_DOC_INHERIT 已将基线文档复制到本次 workspace，
        // workspace 自包含，不再设置 baselineWorkspaceId（避免 getWorkspaceTree 合并基线草稿）。

        // 4. projectDir 通过 taskId 反查（pipeline 启动时 pullAndScan 已写入该目录）
        File projectDir = taskWorkspacePaths.taskProjectDir(taskId);
        if (projectDir == null || !projectDir.isDirectory()) {
            log.error("generateDraftDocument: NAS/workspace 不可用 taskId={} projectDir={}",
                    taskId, projectDir == null ? null : projectDir.getAbsolutePath());
            execLog.log(taskId, "  [error] projectDir 不存在或不可读: "
                    + (projectDir == null ? "null" : projectDir.getAbsolutePath()));
        } else {
            log.info("generateDraftDocument: taskId={} projectDir={}", taskId, projectDir.getAbsolutePath());
        }

        // 5. 增量模式：算出「本次变更文件对应的 FQ 类名集合」，用于判定哪些模块需要重跑 AI
        java.util.Set<String> changedFqSet = null;
        if (effective.isIncremental() && !effective.getChangedPaths().isEmpty()) {
            changedFqSet = new java.util.HashSet<>();
            for (String p : effective.getChangedPaths()) {
                String fq = com.company.codeinsight.modules.hierarchy.service.impl.ModuleHierarchyServiceImpl.deriveFqcnFromPath(p);
                if (StringUtils.hasText(fq)) {
                    changedFqSet.add(fq);
                }
            }
            // Phase 3：把多态 ancestors 一起加入扩展集，避免 moduleTouchedByChange 漏命中
            changedFqSet = com.company.codeinsight.modules.callchain.support.IncrementalImpactSupport
                    .expandChangedFqSetWithPolymorphicAncestors(taskId, changedFqSet, methodCallMapper);
            log.info("增量草稿生成 — taskId={} 变更文件映射到 FQ 类名（含多态 ancestors） {} 个",
                    taskId, changedFqSet.size());
        }

        // 6. 根据 granularity 分发到整模块或按功能粒度
        if ("function".equalsIgnoreCase(docGenerationGranularity)) {
            // 构建反向 BFS 命中的入口类名集合（impact.hierarchyRetargetEntries）
            java.util.Set<String> bfsHitClassNames = null;
            if (impact != null && impact.isIncremental() && !impact.getHierarchyRetargetEntries().isEmpty()) {
                bfsHitClassNames = new java.util.HashSet<>();
                for (com.company.codeinsight.modules.entrypoint.model.EntryPoint ep : impact.getHierarchyRetargetEntries()) {
                    if (ep.getClassName() != null) {
                        bfsHitClassNames.add(ep.getClassName());
                    }
                }
            }
            // 构建入口 DIFF「内容变更」的类名集合（entrypoint bodyHash 变化的类）
            java.util.Set<String> entryModifiedClassNames = null;
            if (effective.isIncremental()) {
                try {
                    com.company.codeinsight.modules.entrypoint.dto.EntrypointDiffDto epDiff =
                            entrypointReviewService.getEntrypointDiff(taskId);
                    if (epDiff != null && epDiff.getModifiedRows() != null && !epDiff.getModifiedRows().isEmpty()) {
                        entryModifiedClassNames = new java.util.HashSet<>();
                        for (com.company.codeinsight.modules.entrypoint.model.EntrypointReviewView v : epDiff.getModifiedRows()) {
                            if (v.getClassName() != null) {
                                entryModifiedClassNames.add(v.getClassName());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("获取入口 DIFF 失败，跳过入口内容变更检测 — taskId={}", taskId, e);
                }
            }
            generateDraftDocumentByFunction(task, ws, hierarchy, projectDir, effective, changedFqSet,
                    bfsHitClassNames, entryModifiedClassNames);
        } else {
            // --- 按模块（默认，现有行为）---
            int moduleIndex = 0;
            int moduleTotal = hierarchy.getModules().size();
            int regenerated = 0;
            int skipped = 0;
            int failed = 0;
            java.util.Set<String> remediationModuleIds = parseRemediationModuleIds(task);
            for (com.company.codeinsight.modules.hierarchy.model.ModuleDto moduleDto : hierarchy.getModules().values()) {
                moduleIndex++;
                if (remediationModuleIds != null) {
                    if (!remediationModuleIds.contains(moduleDto.getId())) {
                        skipped++;
                        execLog.log(taskId, "  [module " + moduleIndex + "/" + moduleTotal + "] " + moduleDto.getModuleName() + " — 不在纠错范围，跳过");
                        continue;
                    }
                } else if (impact != null && impact.isIncremental()) {
                    if (!impact.getDocRetargetModuleIds().contains(moduleDto.getId())) {
                        skipped++;
                        execLog.log(taskId, "  [module " + moduleIndex + "/" + moduleTotal + "] " + moduleDto.getModuleName() + " — 未受本次变更影响，跳过");
                        continue;
                    }
                } else if (changedFqSet != null && !moduleTouchedByChange(moduleDto, changedFqSet)) {
                    skipped++;
                    execLog.log(taskId, "  [module " + moduleIndex + "/" + moduleTotal + "] " + moduleDto.getModuleName() + " — 未受本次变更影响，跳过");
                    continue;
                }
                execLog.log(taskId, "  [module " + moduleIndex + "/" + moduleTotal + "] " + moduleDto.getModuleName());
                try {
                    generateModuleDraft(task, ws, moduleDto, projectDir);
                    regenerated++;
                } catch (Exception e) {
                    failed++;
                    log.error("generateModuleDraft failed for module {}: {}",
                            moduleDto.getModuleName(), e.getMessage(), e);
                    execLog.logException(taskId, "文档生成失败 module=" + moduleDto.getModuleName(), e);
                }
            }
            execLog.log(taskId, String.format(
                    "  文档生成汇总 = 成功 %d / 共 %d（失败 %d，跳过 %d）",
                    regenerated, moduleTotal - skipped, failed, skipped));
            log.info("generateDraftDocument done (module). taskId={} modules={} regenerated={} skipped={} failed={}",
                    taskId, moduleTotal, regenerated, skipped, failed);
        }
    }

    /**
     * 增量模式辅助：判断模块下是否任一 function 的 classPath 引用了本次变更的类。
     * 命中即视为该模块需要被重新生成。
     */
    private boolean moduleTouchedByChange(com.company.codeinsight.modules.hierarchy.model.ModuleDto moduleDto,
                                          java.util.Set<String> changedFqSet) {
        if (moduleDto == null || changedFqSet == null || changedFqSet.isEmpty()) {
            return false;
        }
        for (com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm : moduleDto.getSubModules().values()) {
            for (com.company.codeinsight.modules.hierarchy.model.FunctionDto fn : sm.getFunctions().values()) {
                if (fn.getClassPaths() != null) {
                    for (String cp : fn.getClassPaths()) {
                        if (cp != null && changedFqSet.contains(cp)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * 增量模式辅助（功能粒度）：判断单个 function 是否需要重新生成。
     * <p>满足以下任一条件即需重跑：</p>
     * <ul>
     *   <li>① fn.classPaths ∩ changedFqSet ≠ ∅ — 直接 git diff 命中</li>
     *   <li>② fn.classPaths ∩ bfsHitClassNames ≠ ∅ — 反向 BFS 命中入口类（依赖变更追溯到入口）</li>
     *   <li>③ fn.classPaths ∩ entryModifiedClassNames ≠ ∅ — 入口内容变更（bodyHash 变化但功能不变也需重生成文档）</li>
     * </ul>
     */
    private boolean functionTouchedByIncremental(
            com.company.codeinsight.modules.hierarchy.model.FunctionDto fn,
            java.util.Set<String> changedFqSet,
            java.util.Set<String> bfsHitClassNames,
            java.util.Set<String> entryModifiedClassNames) {
        if (fn.getClassPaths() == null || fn.getClassPaths().isEmpty()) {
            return false;
        }
        for (String cp : fn.getClassPaths()) {
            if (cp == null) continue;
            if (changedFqSet != null && changedFqSet.contains(cp)) return true;
            if (bfsHitClassNames != null && bfsHitClassNames.contains(cp)) return true;
            if (entryModifiedClassNames != null && entryModifiedClassNames.contains(cp)) return true;
        }
        return false;
    }

    /**
     * 项 3 新增：整模块喂 AI 生成 md 的核心流程
     */
    /**
     * 按功能（Function）粒度生成文档：每个 FunctionDto 一次 AI 调用。
     * <p>草稿命名：{moduleName}/{subModuleName}/{functionName}.md</p>
     * <p>下线时删除本方法 + {@code docGenerationGranularity} 配置即可恢复单一 module 模式。</p>
     */
    private void generateDraftDocumentByFunction(DecompileTask task, DraftWorkspace ws,
                                                  com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy hierarchy,
                                                  File projectDir,
                                                  com.company.codeinsight.modules.scanner.model.IncrementalContext effective,
                                                  java.util.Set<String> changedFqSet,
                                                  java.util.Set<String> bfsHitClassNames,
                                                  java.util.Set<String> entryModifiedClassNames) {
        Long taskId = task.getId();
        int fnTotal = countFunctionsInHierarchy(hierarchy);

        record FnJob(int index, com.company.codeinsight.modules.hierarchy.model.ModuleDto m,
                     com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm,
                     com.company.codeinsight.modules.hierarchy.model.FunctionDto fn,
                     String label) {
        }

        List<FnJob> jobs = new ArrayList<>();
        int fnIndex = 0;
        int skipped = 0;
        for (com.company.codeinsight.modules.hierarchy.model.ModuleDto m : hierarchy.getModules().values()) {
            for (com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm : m.getSubModules().values()) {
                for (com.company.codeinsight.modules.hierarchy.model.FunctionDto fn : sm.getFunctions().values()) {
                    fnIndex++;
                    if (changedFqSet != null || bfsHitClassNames != null || entryModifiedClassNames != null) {
                        boolean touched = functionTouchedByIncremental(fn, changedFqSet, bfsHitClassNames, entryModifiedClassNames);
                        if (!touched) {
                            skipped++;
                            continue;
                        }
                    }
                    String label = m.getModuleName() + " / " + sm.getSubModuleName() + " / " + fn.getFunctionName();
                    jobs.add(new FnJob(fnIndex, m, sm, fn, label));
                }
            }
        }

        int parallelism = Math.max(1, docBudgetProperties.getParallelism());
        execLog.log(taskId, String.format(
                "  [DOC-PARALLEL] parallelism=%d pending=%d skipped=%d acquireWaitSeconds=%d",
                parallelism, jobs.size(), skipped, docBudgetProperties.getAcquireWaitSeconds()));

        AtomicInteger regenerated = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>(jobs.size());
        for (FnJob job : jobs) {
            futures.add(docAiExecutor.submit(() -> {
                execLog.log(taskId, "  [fn " + job.index() + "/" + fnTotal + "] " + job.label());
                try {
                    generateFunctionDraft(task, ws, job.m(), job.sm(), job.fn(), hierarchy, projectDir);
                    regenerated.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                    log.error("generateFunctionDraft failed for function {}: {}", job.label(), e.getMessage(), e);
                    execLog.logException(taskId, "文档生成失败 function=" + job.label(), e);
                }
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                failed.incrementAndGet();
                log.error("文档并行任务异常: {}", e.getMessage(), e);
            }
        }

        execLog.log(taskId, String.format(
                "  文档生成汇总 = 成功 %d / 共 %d（失败 %d，跳过 %d）",
                regenerated.get(), fnTotal, failed.get(), skipped));
        log.info("generateDraftDocument done (function). taskId={} total={} regenerated={} skipped={} failed={} parallelism={}",
                taskId, fnTotal, regenerated.get(), skipped, failed.get(), parallelism);
    }

    /**
     * 按功能粒度生成单条草稿。始终写出草稿（真 AI / 裁剪后 AI / TEMPLATE 降级），禁止软跳过留空洞。
     *
     * @return true 已写出草稿
     */
    private boolean generateFunctionDraft(DecompileTask task, DraftWorkspace ws,
                                        com.company.codeinsight.modules.hierarchy.model.ModuleDto moduleDto,
                                        com.company.codeinsight.modules.hierarchy.model.SubModuleDto subModuleDto,
                                        com.company.codeinsight.modules.hierarchy.model.FunctionDto functionDto,
                                        com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy hierarchy,
                                        File projectDir) {
        Long taskId = task.getId();
        String funcName = functionDto.getFunctionName();
        String label = moduleDto.getModuleName() + " / " + subModuleDto.getSubModuleName() + " / " + funcName;

        FunctionSourceBundle bundle = collectFunctionSourceBundle(taskId, functionDto, projectDir);
        if (!bundle.hasPromptText()) {
            log.warn("Function {} BFS 无可达源码，写 TEMPLATE 降级稿", funcName);
            execLog.log(taskId, String.format(
                    "[DOC-DEGRADE] stage=FUNCTION_DOC target=%s reason=source_unreachable tier=TEMPLATE",
                    label));
            upsertFunctionDraft(task, ws, moduleDto, subModuleDto, functionDto,
                    buildDegradedDoc(moduleDto, subModuleDto, functionDto, bundle, "source_unreachable"),
                    "PENDING_REVIEW", bundle, projectDir);
            return true;
        }

        String promptTemplate = decompilePromptService.requireTaskPromptContent(task,
                com.company.codeinsight.modules.prompt.entity.DecompilePrompt.TYPE_DOCUMENT_GENERATION);
        String scopedJson = buildScopedHierarchyJson(taskId, moduleDto, subModuleDto, functionDto, projectDir);

        final java.util.concurrent.atomic.AtomicReference<String> sourceRef =
                new java.util.concurrent.atomic.AtomicReference<>(bundle.getPromptText());
        // 首调不预裁：完整源码；仅上下文超限失败后再裁（见 mutateDocPromptOnRetry）
        String promptInput = promptTemplateLoader.renderModuleDoc(
                promptTemplate, label, scopedJson, sourceRef.get());
        if (promptTemplateLoader.hasUnresolvedModuleDocPlaceholders(promptInput)) {
            log.warn("Function {} prompt 有未替换占位符，回退 TEMPLATE 稿", funcName);
            execLog.log(taskId, String.format(
                    "[AI-FALLBACK] stage=FUNCTION_DOC target=%s reason=unresolved_placeholders tier=TEMPLATE",
                    label));
            upsertFunctionDraft(task, ws, moduleDto, subModuleDto, functionDto,
                    buildDegradedDoc(moduleDto, subModuleDto, functionDto, bundle, "unresolved_placeholders"),
                    "PENDING_REVIEW", bundle, projectDir);
            return true;
        }

        AiSummaryService.AiCallMeta callMeta = new AiSummaryService.AiCallMeta();
        callMeta.setCallStage("FUNCTION_DOC");
        callMeta.setClassPath(functionDto.getId());
        String aiMarkdown = pipelineAiCaller.callWithRetry(
                taskId,
                "FUNCTION_DOC",
                label,
                promptInput,
                task.getModelName(),
                callMeta,
                response -> {
                    if (!StringUtils.hasText(response) || "{}".equals(response.trim())) {
                        return PipelineAiCaller.ValidationResult.fail("empty response");
                    }
                    String validationMsg = validateModuleDocStructure(response);
                    if (validationMsg != null) {
                        return PipelineAiCaller.ValidationResult.fail("structure: " + validationMsg);
                    }
                    return PipelineAiCaller.ValidationResult.ok(response);
                },
                (original, current, failedAttempt, reason) ->
                        mutateDocPromptOnRetry(taskId, "FUNCTION_DOC", label,
                                promptTemplate, scopedJson, sourceRef, current, reason)
        );

        String finalMarkdown;
        String initialStatus;
        if (!StringUtils.hasText(aiMarkdown) || "{}".equals(aiMarkdown.trim())) {
            log.warn("Function {} AI 响应为空或全部重试失败，写 TEMPLATE 降级稿", funcName);
            execLog.log(taskId, String.format(
                    "[AI-FALLBACK] stage=FUNCTION_DOC target=%s reason=ai_failed tier=TEMPLATE",
                    label));
            finalMarkdown = buildDegradedDoc(moduleDto, subModuleDto, functionDto, bundle, "ai_failed");
            initialStatus = "PENDING_REVIEW";
        } else {
            finalMarkdown = aiMarkdown;
            initialStatus = "AI_GENERATED";
        }
        upsertFunctionDraft(task, ws, moduleDto, subModuleDto, functionDto, finalMarkdown, initialStatus, bundle, projectDir);
        return true;
    }

    /** 收集单个 Function 的可达源码（从 method_function_binding 反查根方法 → BFS） */
    private String collectFunctionSourceCode(Long taskId,
                                              com.company.codeinsight.modules.hierarchy.model.FunctionDto fn,
                                              File projectDir) {
        return collectFunctionSourceBundle(taskId, fn, projectDir).getPromptText();
    }

    private FunctionSourceBundle collectFunctionSourceBundle(Long taskId,
                                                             com.company.codeinsight.modules.hierarchy.model.FunctionDto fn,
                                                             File projectDir) {
        FunctionSourceBundle bundle = new FunctionSourceBundle();
        if (fn != null) {
            bundle.setFunctionNodeId(fn.getId());
        }
        String funcName = fn != null ? fn.getFunctionName() : "?";
        String fnId = fn != null ? fn.getId() : null;
        boolean projectOk = projectDir != null && projectDir.isDirectory();
        if (!projectOk) {
            log.warn("collectFunctionSourceBundle: projectDir 不可用 taskId={} function={} id={} path={}",
                    taskId, funcName, fnId, projectDir == null ? null : projectDir.getAbsolutePath());
        }

        Set<String> rootSignatures = loadFunctionRootSignatures(taskId, fn);
        bundle.setRootSignatures(new LinkedHashSet<>(rootSignatures));
        ScanScope scope = (projectOk && taskId != null)
                ? scanScopeResolver.resolveBestEffort(taskId, projectDir)
                : null;

        if (rootSignatures.isEmpty()) {
            int classPathCount = fn != null && fn.getClassPaths() != null ? fn.getClassPaths().size() : 0;
            log.warn("collectFunctionSourceBundle: 无 BFS 根签名，走 classPaths 整文件回退 taskId={} function={} id={} classPaths={}",
                    taskId, funcName, fnId, classPathCount);
            buildFallbackClassPathBundle(bundle, taskId, fn, projectDir, scope, funcName, fnId);
            return bundle;
        }

        Set<String> reachableMethods = methodCallGraphService.resolveReachableMethods(taskId, rootSignatures);
        List<FunctionSourceBundle.RefItem> refs = new ArrayList<>();
        int bfsOrder = 0;
        for (String callerSig : reachableMethods) {
            FunctionSourceBundle.RefItem refItem = buildRefItemFromCallerSignature(
                    taskId, callerSig, rootSignatures, scope, projectDir, bfsOrder++);
            if (refItem != null) {
                refs.add(refItem);
            }
        }
        // 同一物理方法可能因「仅类型」与「类型+参数名」两种签名各占一条；按文件行号去重
        refs = dedupeSourceRefs(refs);
        bundle.setRefs(refs);

        Map<String, Set<String>> classToMethodSigs = groupByClass(reachableMethods);
        log.info("collectFunctionSourceBundle: taskId={} function={} id={} roots={} reachable={} classes={} refs={} projectDir={}",
                taskId, funcName, fnId, rootSignatures.size(), reachableMethods.size(),
                classToMethodSigs.size(), refs.size(),
                projectDir == null ? null : projectDir.getAbsolutePath());
        if (log.isDebugEnabled()) {
            log.debug("collectFunctionSourceBundle roots sample={}", sampleForLog(rootSignatures, 5));
        }

        StringBuilder sb = new StringBuilder();
        int missLookup = 0;
        int missFile = 0;
        int missFilter = 0;
        for (Map.Entry<String, Set<String>> entry : classToMethodSigs.entrySet()) {
            String className = entry.getKey();
            Set<String> methodSigs = entry.getValue();
            String classFilePath = lookupClassFilePath(taskId, className);
            if (classFilePath == null) {
                missLookup++;
                log.warn("collectFunctionSourceBundle: 无 filePath 映射 class={} taskId={}", className, taskId);
                continue;
            }
            if (scope != null && !scope.accepts(classFilePath)) {
                log.warn("collectFunctionSourceBundle: 超出扫描范围，跳过 class={} path={}", className, classFilePath);
                continue;
            }
            File classFile = new File(projectDir, classFilePath);
            if (!classFile.exists()) {
                missFile++;
                log.warn("collectFunctionSourceBundle: 源文件不存在 class={} rel={} abs={}",
                        className, classFilePath, classFile.getAbsolutePath());
                continue;
            }
            ClassMethodSnippet snippet = filterClassToMethods(classFile, methodSigs);
            if (snippet == null || !StringUtils.hasText(snippet.methodsBody)) {
                missFilter++;
                log.warn("collectFunctionSourceBundle: 方法截取为空 class={} methods={}",
                        className, sampleForLog(methodSigs, 8));
                continue;
            }
            appendClassMethodSnippet(sb, taskId, className, projectDir, snippet);
        }
        if (!StringUtils.hasText(sb.toString())) {
            log.warn("collectFunctionSourceBundle: 组装结果为空 taskId={} function={} roots={} reachable={} missLookup={} missFile={} missFilter={}",
                    taskId, funcName, rootSignatures.size(), reachableMethods.size(),
                    missLookup, missFile, missFilter);
        }
        bundle.setPromptText(sb.toString());
        return bundle;
    }

    private void buildFallbackClassPathBundle(FunctionSourceBundle bundle, Long taskId,
                                              com.company.codeinsight.modules.hierarchy.model.FunctionDto fn,
                                              File projectDir, ScanScope scope,
                                              String funcName, String fnId) {
        if (fn == null || fn.getClassPaths() == null || fn.getClassPaths().isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        List<FunctionSourceBundle.RefItem> refs = new ArrayList<>();
        int missLookup = 0;
        int missFile = 0;
        int bfsOrder = 0;
        for (String cp : fn.getClassPaths()) {
            String classFilePath = lookupClassFilePath(taskId, cp);
            if (classFilePath == null) {
                missLookup++;
                continue;
            }
            if (scope != null && !scope.accepts(classFilePath)) {
                log.warn("collectFunctionSourceBundle fallback 超出扫描范围，跳过: class={} path={}",
                        cp, classFilePath);
                continue;
            }
            File f = new File(projectDir, classFilePath);
            if (!f.exists()) {
                missFile++;
                log.warn("collectFunctionSourceBundle fallback 文件不存在: class={} rel={} abs={}",
                        cp, classFilePath, f.getAbsolutePath());
                continue;
            }
            try {
                String content = Files.readString(f.toPath());
                String fq = resolveFqClassName(taskId, cp, projectDir, null);
                sb.append("// === Class: ").append(fq).append(" ===\n").append(content).append("\n\n");

                FunctionSourceBundle.RefItem refItem = new FunctionSourceBundle.RefItem();
                refItem.setFilePath(classFilePath);
                refItem.setClassName(cp);
                refItem.setMethodSignature(null);
                refItem.setStartLine(1);
                refItem.setEndLine(0);
                refItem.setRefKind(FunctionSourceBundle.REF_KIND_ROOT);
                refItem.setBfsOrder(bfsOrder++);
                refs.add(refItem);
            } catch (IOException e) {
                log.warn("collectFunctionSourceBundle fallback 读文件失败: {} {}", f.getAbsolutePath(), e.getMessage());
            }
        }
        if (!StringUtils.hasText(sb.toString())) {
            log.warn("collectFunctionSourceBundle fallback 仍为空 taskId={} function={} missLookup={} missFile={}",
                    taskId, funcName, missLookup, missFile);
        }
        bundle.setPromptText(sb.toString());
        bundle.setRefs(refs);
    }

    private FunctionSourceBundle.RefItem buildRefItemFromCallerSignature(Long taskId, String callerSig,
                                                                         Set<String> rootSignatures,
                                                                         ScanScope scope, File projectDir,
                                                                         int bfsOrder) {
        int hashIdx = callerSig.indexOf('#');
        if (hashIdx <= 0) {
            return null;
        }
        String className = callerSig.substring(0, hashIdx);
        String methodSig = callerSig.substring(hashIdx + 1);
        String classFilePath = lookupClassFilePath(taskId, className);
        if (classFilePath == null) {
            return null;
        }
        if (scope != null && !scope.accepts(classFilePath)) {
            return null;
        }
        File classFile = projectDir != null ? new File(projectDir, classFilePath) : null;
        if (classFile == null || !classFile.exists()) {
            return null;
        }
        int[] lines = resolveMethodLineRange(classFile, methodSig);
        FunctionSourceBundle.RefItem refItem = new FunctionSourceBundle.RefItem();
        refItem.setFilePath(classFilePath);
        refItem.setClassName(className);
        refItem.setMethodSignature(methodSig);
        if (lines != null) {
            refItem.setStartLine(lines[0]);
            refItem.setEndLine(lines[1]);
        } else {
            refItem.setStartLine(1);
            refItem.setEndLine(0);
        }
        refItem.setRefKind(isRootCallerSignature(callerSig, rootSignatures)
                ? FunctionSourceBundle.REF_KIND_ROOT
                : FunctionSourceBundle.REF_KIND_REACHABLE);
        refItem.setBfsOrder(bfsOrder);
        return refItem;
    }

    private static boolean isRootCallerSignature(String callerSig, Set<String> rootSignatures) {
        if (!StringUtils.hasText(callerSig) || rootSignatures == null || rootSignatures.isEmpty()) {
            return false;
        }
        String normalized = normalizeCallerSignature(callerSig);
        for (String root : rootSignatures) {
            if (callerSig.equals(root) || normalized.equals(normalizeCallerSignature(root))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeCallerSignature(String callerSig) {
        if (!StringUtils.hasText(callerSig)) {
            return callerSig;
        }
        int hashIdx = callerSig.indexOf('#');
        if (hashIdx <= 0) {
            return callerSig;
        }
        return stripPackage(callerSig.substring(0, hashIdx)) + callerSig.substring(hashIdx);
    }

    /**
     * 代码来源去重：同一文件同一行区间只保留一条；无有效行号时按「短类名#规范化方法签名」去重。
     * 冲突时优先保留 ROOT，其次保留带参数名的签名，并保留更早的 bfsOrder。
     */
    private static List<FunctionSourceBundle.RefItem> dedupeSourceRefs(List<FunctionSourceBundle.RefItem> refs) {
        if (refs == null || refs.isEmpty()) {
            return refs == null ? new ArrayList<>() : refs;
        }
        Map<String, FunctionSourceBundle.RefItem> byKey = new LinkedHashMap<>();
        for (FunctionSourceBundle.RefItem item : refs) {
            if (item == null || !StringUtils.hasText(item.getFilePath())) {
                continue;
            }
            String key = refDedupeKey(item);
            FunctionSourceBundle.RefItem existing = byKey.get(key);
            if (existing == null) {
                byKey.put(key, item);
            } else {
                byKey.put(key, preferSourceRef(existing, item));
            }
        }
        List<FunctionSourceBundle.RefItem> out = new ArrayList<>(byKey.values());
        for (int i = 0; i < out.size(); i++) {
            out.get(i).setBfsOrder(i);
        }
        return out;
    }

    private static String refDedupeKey(FunctionSourceBundle.RefItem item) {
        Integer start = item.getStartLine();
        Integer end = item.getEndLine();
        if (start != null && end != null && end > 0) {
            return item.getFilePath() + "|" + start + "|" + end;
        }
        String classPart = stripPackage(item.getClassName());
        String methodPart = normalizeMethodSignatureForDedupe(item.getMethodSignature());
        return item.getFilePath() + "|" + classPart + "#" + methodPart;
    }

    /**
     * {@code quote(Long productId, int quantity)} / {@code quote(Long, int)} → {@code quote(Long,int)}
     */
    private static String normalizeMethodSignatureForDedupe(String methodSig) {
        if (!StringUtils.hasText(methodSig)) {
            return "";
        }
        String s = methodSig.trim();
        int paren = s.indexOf('(');
        if (paren < 0) {
            return s;
        }
        String name = s.substring(0, paren).trim();
        int close = s.lastIndexOf(')');
        String inside = close > paren ? s.substring(paren + 1, close) : "";
        if (!StringUtils.hasText(inside)) {
            return name + "()";
        }
        StringBuilder types = new StringBuilder();
        for (String part : inside.split(",")) {
            String token = part.trim();
            if (!StringUtils.hasText(token)) {
                continue;
            }
            // 去掉参数名：取最后一个空格前的类型（含泛型简单场景则整段作为类型）
            int space = token.lastIndexOf(' ');
            String type = space > 0 ? token.substring(0, space).trim() : token;
            if (types.length() > 0) {
                types.append(',');
            }
            types.append(type);
        }
        return name + "(" + types + ")";
    }

    private static FunctionSourceBundle.RefItem preferSourceRef(
            FunctionSourceBundle.RefItem a, FunctionSourceBundle.RefItem b) {
        boolean aRoot = FunctionSourceBundle.REF_KIND_ROOT.equals(a.getRefKind());
        boolean bRoot = FunctionSourceBundle.REF_KIND_ROOT.equals(b.getRefKind());
        if (aRoot != bRoot) {
            return aRoot ? a : b;
        }
        int aNamed = methodSignatureParamNameScore(a.getMethodSignature());
        int bNamed = methodSignatureParamNameScore(b.getMethodSignature());
        if (aNamed != bNamed) {
            return aNamed > bNamed ? a : b;
        }
        int aOrder = a.getBfsOrder();
        int bOrder = b.getBfsOrder();
        return aOrder <= bOrder ? a : b;
    }

    /** 带参数名的签名得分更高（如 {@code Long productId}） */
    private static int methodSignatureParamNameScore(String methodSig) {
        if (!StringUtils.hasText(methodSig)) {
            return 0;
        }
        int paren = methodSig.indexOf('(');
        int close = methodSig.lastIndexOf(')');
        if (paren < 0 || close <= paren) {
            return 0;
        }
        String inside = methodSig.substring(paren + 1, close).trim();
        if (!StringUtils.hasText(inside)) {
            return 0;
        }
        int score = 0;
        for (String part : inside.split(",")) {
            if (part.trim().contains(" ")) {
                score++;
            }
        }
        return score;
    }

    /**
     * 反查某功能的 BFS 根方法集合（"短类名#methodSignature(ParamTypes)" 格式）。
     * <p>优先级：</p>
     * <ol>
     *   <li>主路径：从 {@code ci_method_function_binding} 反查该 function_node_id 下的全部方法绑定，
     *       每条 (class, sig) → "短类名#sig"。binding.class_name 可能是 AI 输出的 FQ，
     *       而 {@code ci_method_call.caller_signature} 落库为短类名，必须截短后才能命中 BFS。</li>
     *   <li>回退路径：当反向绑定表为空（AI 没输出 method_bindings、或老任务）时，回退到
     *       {@code fn.methodSignatures × fn.classPaths[0]} 的笛卡尔积（同样截短类名）</li>
     * </ol>
     */
    private Set<String> loadFunctionRootSignatures(Long taskId,
                                                   com.company.codeinsight.modules.hierarchy.model.FunctionDto fn) {
        Set<String> roots = new LinkedHashSet<>();
        int bindingRows = 0;
        if (taskId != null && fn != null
                && methodFunctionBindingMapper != null
                && StringUtils.hasText(fn.getId())) {
            List<MethodFunctionBinding> bindings =
                    methodFunctionBindingMapper.selectByTaskAndFunction(taskId, fn.getId());
            if (bindings != null) {
                bindingRows = bindings.size();
                for (MethodFunctionBinding b : bindings) {
                    if (!StringUtils.hasText(b.getClassName())
                            || !StringUtils.hasText(b.getMethodSignature())) {
                        continue;
                    }
                    String key = toCallerSignatureKey(b.getClassName(), b.getMethodSignature());
                    if (key != null) {
                        roots.add(key);
                    }
                }
            }
        }
        // 回退：旧 fn.methodSignatures × classPaths[0]（已被反向索引取代的旧路径）
        if (roots.isEmpty() && fn != null
                && fn.getMethodSignatures() != null && !fn.getMethodSignatures().isEmpty()
                && fn.getClassPaths() != null && !fn.getClassPaths().isEmpty()) {
            String classPath = fn.getClassPaths().stream().findFirst().orElse(null);
            if (StringUtils.hasText(classPath)) {
                for (String sig : fn.getMethodSignatures()) {
                    if (!StringUtils.hasText(sig)) continue;
                    String key = toCallerSignatureKey(classPath, sig);
                    if (key != null) {
                        roots.add(key);
                    }
                }
            }
            if (!roots.isEmpty()) {
                log.info("loadFunctionRootSignatures: binding 为空，回退 methodSignatures×classPaths taskId={} functionId={} roots={}",
                        taskId, fn.getId(), roots.size());
            }
        }
        if (roots.isEmpty()) {
            log.warn("loadFunctionRootSignatures: 根签名为空 taskId={} functionId={} bindingRows={} methodSigs={} classPaths={}",
                    taskId,
                    fn != null ? fn.getId() : null,
                    bindingRows,
                    fn != null && fn.getMethodSignatures() != null ? fn.getMethodSignatures().size() : 0,
                    fn != null && fn.getClassPaths() != null ? fn.getClassPaths().size() : 0);
        }
        return roots;
    }

    /**
     * 拼装与 {@code ci_method_call.caller_signature} 一致的键：短类名#method(args)。
     * binding / AI class_paths 常为 FQ，必须截短。
     */
    private static String toCallerSignatureKey(String classNameOrFq, String methodSignature) {
        if (!StringUtils.hasText(classNameOrFq) || !StringUtils.hasText(methodSignature)) {
            return null;
        }
        return stripPackage(classNameOrFq) + "#" + methodSignature.trim();
    }

    /** 去掉包前缀，已是短名则原样返回。 */
    private static String stripPackage(String fqOrShortClassName) {
        if (fqOrShortClassName == null) {
            return null;
        }
        int dotIdx = fqOrShortClassName.lastIndexOf('.');
        return dotIdx >= 0 ? fqOrShortClassName.substring(dotIdx + 1) : fqOrShortClassName;
    }

    private static String sampleForLog(java.util.Collection<String> items, int limit) {
        if (items == null || items.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (String s : items) {
            if (i > 0) sb.append(", ");
            sb.append(s);
            if (++i >= limit) {
                sb.append(", ...(+").append(items.size() - limit).append(")");
                break;
            }
        }
        return sb.append("]").toString();
    }

    /**
     * FUNCTION_DOC 专用 scoped JSON：在 analyze 视图之外额外注入权威 {@code class_paths} / {@code methods}，
     * 供文档「涉及类清单」原样引用，避免模型照抄提示词示例包名。
     */
    private String buildScopedHierarchyJson(
            Long taskId,
            com.company.codeinsight.modules.hierarchy.model.ModuleDto m,
            com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm,
            com.company.codeinsight.modules.hierarchy.model.FunctionDto fn,
            File projectDir) {
        try {
            Map<String, Object> scoped = new LinkedHashMap<>();
            Map<String, Object> modMap = new LinkedHashMap<>();
            modMap.put("id", m.getId());
            modMap.put("moduleName", m.getModuleName());
            modMap.put("keywords", m.getKeywords() != null ? m.getKeywords() : Collections.emptyList());
            Map<String, Object> subMap = new LinkedHashMap<>();
            subMap.put("id", sm.getId());
            subMap.put("subModuleName", sm.getSubModuleName());
            subMap.put("keywords", sm.getKeywords() != null ? sm.getKeywords() : Collections.emptyList());
            subMap.put("functions", Collections.singletonList(buildFunctionDocView(taskId, fn, projectDir)));
            modMap.put("subModules", Collections.singletonList(subMap));
            scoped.put("modules", Collections.singletonList(modMap));
            return objectMapper.writeValueAsString(scoped);
        } catch (Exception e) {
            log.warn("buildScopedHierarchyJson 失败: {}", e.getMessage());
            return "{}";
        }
    }

    /** 模块文档专用：仅序列化当前模块，并为各功能注入权威类路径 / 方法列表。 */
    private String buildModuleDocHierarchyJson(
            Long taskId,
            com.company.codeinsight.modules.hierarchy.model.ModuleDto m,
            File projectDir) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            Map<String, Object> modMap = new LinkedHashMap<>();
            modMap.put("id", m.getId());
            modMap.put("moduleName", m.getModuleName());
            modMap.put("keywords", m.getKeywords() != null ? m.getKeywords() : Collections.emptyList());
            List<Map<String, Object>> subList = new ArrayList<>();
            for (com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm : m.getSubModules().values()) {
                Map<String, Object> subMap = new LinkedHashMap<>();
                subMap.put("id", sm.getId());
                subMap.put("subModuleName", sm.getSubModuleName());
                subMap.put("keywords", sm.getKeywords() != null ? sm.getKeywords() : Collections.emptyList());
                List<Map<String, Object>> fnList = new ArrayList<>();
                for (com.company.codeinsight.modules.hierarchy.model.FunctionDto fn : sm.getFunctions().values()) {
                    fnList.add(buildFunctionDocView(taskId, fn, projectDir));
                }
                subMap.put("functions", fnList);
                subList.add(subMap);
            }
            modMap.put("subModules", subList);
            root.put("modules", Collections.singletonList(modMap));
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            log.warn("buildModuleDocHierarchyJson 失败: {}", e.getMessage());
            return "{}";
        }
    }

    private Map<String, Object> buildFunctionDocView(
            Long taskId,
            com.company.codeinsight.modules.hierarchy.model.FunctionDto fn,
            File projectDir) {
        Map<String, Object> fnMap = new LinkedHashMap<>();
        fnMap.put("id", fn.getId());
        fnMap.put("functionName", fn.getFunctionName());
        List<String> classPaths = resolveAuthoritativeClasses(taskId, fn, projectDir);
        fnMap.put("class_paths", classPaths);
        fnMap.put("methods", resolveAuthoritativeMethods(taskId, fn, projectDir));
        return fnMap;
    }

    /**
     * 文档生成权威类清单：binding.class_name ∪ fn.classPaths ∪ BFS 可达类（均升 FQ，去重保序）。
     */
    public List<String> resolveAuthoritativeClasses(
            Long taskId,
            com.company.codeinsight.modules.hierarchy.model.FunctionDto fn,
            File projectDir) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (fn == null) {
            return new ArrayList<>();
        }
        List<MethodFunctionBinding> bindings = loadBindingsForFunction(taskId, fn.getId());
        if (bindings != null) {
            for (MethodFunctionBinding b : bindings) {
                if (!StringUtils.hasText(b.getClassName())) {
                    continue;
                }
                out.add(resolveFqClassName(taskId, b.getClassName(), projectDir, null));
            }
        }
        if (fn.getClassPaths() != null) {
            for (String cp : fn.getClassPaths()) {
                if (!StringUtils.hasText(cp)) {
                    continue;
                }
                out.add(resolveFqClassName(taskId, cp, projectDir, null));
            }
        }
        Set<String> roots = loadFunctionRootSignatures(taskId, fn);
        if (!roots.isEmpty() && methodCallGraphService != null) {
            Set<String> reachable = methodCallGraphService.resolveReachableMethods(taskId, roots);
            if (reachable != null) {
                for (String shortOrFq : groupByClass(reachable).keySet()) {
                    out.add(resolveFqClassName(taskId, shortOrFq, projectDir, null));
                }
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * 权威方法列表：binding 优先；否则 classPaths[0] × methodSignatures。
     */
    public List<Map<String, String>> resolveAuthoritativeMethods(
            Long taskId,
            com.company.codeinsight.modules.hierarchy.model.FunctionDto fn,
            File projectDir) {
        List<Map<String, String>> out = new ArrayList<>();
        if (fn == null) {
            return out;
        }
        List<MethodFunctionBinding> bindings = loadBindingsForFunction(taskId, fn.getId());
        if (bindings != null && !bindings.isEmpty()) {
            for (MethodFunctionBinding b : bindings) {
                if (!StringUtils.hasText(b.getClassName()) || !StringUtils.hasText(b.getMethodSignature())) {
                    continue;
                }
                Map<String, String> row = new LinkedHashMap<>();
                row.put("classFq", resolveFqClassName(taskId, b.getClassName(), projectDir, null));
                row.put("methodSignature", b.getMethodSignature().trim());
                out.add(row);
            }
            return out;
        }
        if (fn.getMethodSignatures() != null && !fn.getMethodSignatures().isEmpty()
                && fn.getClassPaths() != null && !fn.getClassPaths().isEmpty()) {
            String classPath = fn.getClassPaths().stream().filter(StringUtils::hasText).findFirst().orElse(null);
            if (StringUtils.hasText(classPath)) {
                String fq = resolveFqClassName(taskId, classPath, projectDir, null);
                for (String sig : fn.getMethodSignatures()) {
                    if (!StringUtils.hasText(sig)) {
                        continue;
                    }
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("classFq", fq);
                    row.put("methodSignature", sig.trim());
                    out.add(row);
                }
            }
        }
        return out;
    }

    private List<MethodFunctionBinding> loadBindingsForFunction(Long taskId, String functionNodeId) {
        if (taskId == null || !StringUtils.hasText(functionNodeId) || methodFunctionBindingMapper == null) {
            return Collections.emptyList();
        }
        List<MethodFunctionBinding> bindings =
                methodFunctionBindingMapper.selectByTaskAndFunction(taskId, functionNodeId);
        return bindings != null ? bindings : Collections.emptyList();
    }

    /**
     * 将短类名/FQ 解析为全限定名。失败时保留原值并打 warn，不编造包名。
     */
    public String resolveFqClassName(Long taskId, String nameHint, File projectDir, ParsedClassInfo parsed) {
        if (!StringUtils.hasText(nameHint)) {
            return nameHint;
        }
        String trimmed = nameHint.trim();
        if (trimmed.contains(".")) {
            return trimmed;
        }
        if (parsed != null && StringUtils.hasText(parsed.getPackageName())
                && StringUtils.hasText(parsed.getClassName())) {
            return parsed.getPackageName() + "." + parsed.getClassName();
        }
        String filePath = lookupClassFilePath(taskId, trimmed);
        if (StringUtils.hasText(filePath) && projectDir != null) {
            File classFile = new File(projectDir, filePath);
            if (classFile.exists()) {
                try {
                    ParsedClassInfo info = javaParserService.parseFile(classFile);
                    if (info != null && StringUtils.hasText(info.getPackageName())
                            && StringUtils.hasText(info.getClassName())) {
                        return info.getPackageName() + "." + info.getClassName();
                    }
                } catch (Exception e) {
                    log.debug("resolveFqClassName parse failed class={} err={}", trimmed, e.getMessage());
                }
            }
            String fromPath = fqFromSourceRelativePath(filePath);
            if (StringUtils.hasText(fromPath)) {
                return fromPath;
            }
        }
        log.warn("resolveFqClassName: 无法升 FQ，保留短名 class={} taskId={}", trimmed, taskId);
        return trimmed;
    }

    /** 从 {@code .../src/main/java/com/foo/Bar.java} 反推 {@code com.foo.Bar}。 */
    public static String fqFromSourceRelativePath(String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            return null;
        }
        String norm = relativePath.replace('\\', '/');
        String marker = "/src/main/java/";
        int idx = norm.indexOf(marker);
        if (idx < 0) {
            marker = "/src/test/java/";
            idx = norm.indexOf(marker);
        }
        String rel;
        if (idx >= 0) {
            rel = norm.substring(idx + marker.length());
        } else if (norm.startsWith("src/main/java/")) {
            rel = norm.substring("src/main/java/".length());
        } else if (norm.startsWith("src/test/java/")) {
            rel = norm.substring("src/test/java/".length());
        } else {
            return null;
        }
        if (!rel.endsWith(".java")) {
            return null;
        }
        String withoutExt = rel.substring(0, rel.length() - ".java".length());
        if (!StringUtils.hasText(withoutExt) || withoutExt.contains("..")) {
            return null;
        }
        return withoutExt.replace('/', '.');
    }

    private void appendClassMethodSnippet(StringBuilder sb, Long taskId, String classNameHint,
                                          File projectDir, ClassMethodSnippet snippet) {
        String fq = resolveFqClassName(taskId, classNameHint, projectDir, snippet.parsed);
        sb.append("// === Class: ").append(fq).append(" ===\n");
        String pkg = snippet.parsed != null ? snippet.parsed.getPackageName() : null;
        if (!StringUtils.hasText(pkg) && fq != null && fq.contains(".")) {
            pkg = fq.substring(0, fq.lastIndexOf('.'));
        }
        if (StringUtils.hasText(pkg)) {
            sb.append("package ").append(pkg).append(";\n");
        }
        sb.append(snippet.methodsBody).append("\n\n");
    }

    /** 方法截取结果：保留 ParsedClassInfo 以便写 package / FQ 头。 */
    public static final class ClassMethodSnippet {
        final ParsedClassInfo parsed;
        final String methodsBody;

        public ClassMethodSnippet(ParsedClassInfo parsed, String methodsBody) {
            this.parsed = parsed;
            this.methodsBody = methodsBody;
        }
    }

    /** 按功能粒度写草稿：路径 = {moduleName}/{subModuleName}/{functionName}.md */
    private void upsertFunctionDraft(DecompileTask task, DraftWorkspace ws,
                                      com.company.codeinsight.modules.hierarchy.model.ModuleDto m,
                                      com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm,
                                      com.company.codeinsight.modules.hierarchy.model.FunctionDto fn,
                                      String markdown, String initialStatus,
                                      FunctionSourceBundle bundle, File projectDir) {
        Long taskId = task.getId();
        String safeModule = m.getModuleName().replaceAll("[\\s/\\(\\)]", "_");
        String safeSub = sm.getSubModuleName().replaceAll("[\\s/\\(\\)]", "_");
        String safeFn = fn.getFunctionName().replaceAll("[\\s/\\(\\)]", "_");
        String relativeDocPath = "task_" + taskId + "/" + safeModule + "/" + safeSub + "/" + safeFn + ".md";
        Path storePath = storageResolver.draftsRoot().resolve(relativeDocPath);
        try {
            Files.createDirectories(storePath.getParent());
            Files.writeString(storePath, markdown);
        } catch (IOException e) {
            log.error("保存 Function 知识草稿文件失败", e);
        }
        String hash = DigestUtils.md5DigestAsHex(markdown.getBytes());
        String fullModuleName = m.getModuleName() + " / " + sm.getSubModuleName() + " / " + fn.getFunctionName();
        // 先按 filePath 查（正常路径：之前 AI 生成过、filePath 已是嵌套格式）
        KnowledgeDraft draft = knowledgeDraftMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDraft>()
                        .eq(KnowledgeDraft::getWorkspaceId, ws.getId())
                        .eq(KnowledgeDraft::getFilePath, relativeDocPath));
        // filePath 未找到时按 moduleName 查（覆盖增量场景：inheritDrafts 用扁平 filePath 复制了基线草稿，
        // AI 重生成时 filePath 不匹配，但 moduleName 一致 → 找到继承草稿并覆盖）
        if (draft == null) {
            draft = knowledgeDraftMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeDraft>()
                            .eq(KnowledgeDraft::getWorkspaceId, ws.getId())
                            .eq(KnowledgeDraft::getModuleName, fullModuleName));
        }
        if (draft == null) {
            draft = new KnowledgeDraft();
            draft.setWorkspaceId(ws.getId());
            draft.setFilePath(relativeDocPath);
            draft.setModuleName(fullModuleName);
            String contentUri = com.company.codeinsight.common.util.DraftFileUtil.buildDraftUri(
                    task.getSystemId(), task.getRepositoryId(), taskId, relativeDocPath);
            draft.setContentUri(contentUri);
            draft.setStatus(initialStatus);
            draft.setHash(hash);
            draft.setFunctionNodeId(fn.getId());
            draft.setCreatedDate(LocalDateTime.now());
            draft.setUpdatedDate(LocalDateTime.now());
            knowledgeDraftMapper.insert(draft);
        } else {
            String contentUri = com.company.codeinsight.common.util.DraftFileUtil.buildDraftUri(
                    task.getSystemId(), task.getRepositoryId(), taskId, relativeDocPath);
            draft.setFilePath(relativeDocPath);     // 确保 filePath 更新为 AI 嵌套格式（可能从继承的扁平路径迁移）
            draft.setContentUri(contentUri);
            draft.setHash(hash);
            draft.setStatus(initialStatus);          // AI 重生成 → 需重新复核
            draft.setFunctionNodeId(fn.getId());
            draft.setBaselineTaskId(null);           // AI 重生成 → 不再是基线继承，标记为 modified（需 FieldStrategy.ALWAYS）
            draft.setUpdatedDate(LocalDateTime.now());
            knowledgeDraftMapper.updateById(draft);
        }
        if (bundle != null && bundle.getRefs() != null && !bundle.getRefs().isEmpty()) {
            replaceDraftSourceReferencesFromBundle(draft.getId(), bundle);
        } else {
            replaceDraftSourceReferencesForFunction(draft.getId(), taskId, fn, projectDir);
        }
        log.info("Function draft: {} → {}", relativeDocPath, initialStatus);
    }

    /** 统计 hierarchy 中所有 FunctionDto 总数 */
    private int countFunctionsInHierarchy(com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy hierarchy) {
        int n = 0;
        if (hierarchy == null || hierarchy.getModules() == null) return 0;
        for (com.company.codeinsight.modules.hierarchy.model.ModuleDto m : hierarchy.getModules().values()) {
            n += countFunctions(m);
        }
        return n;
    }

    private void generateModuleDraft(DecompileTask task, DraftWorkspace ws,
                                    com.company.codeinsight.modules.hierarchy.model.ModuleDto moduleDto,
                                    File projectDir) {
        String moduleName = moduleDto.getModuleName();
        Long taskId = task.getId();

        // 1. 收集该模块涉及的所有源码（BFS 入口可达）
        String moduleSource = collectModuleSourceCode(taskId, moduleDto, projectDir);
        if (!StringUtils.hasText(moduleSource)) {
            log.warn("模块 {} BFS 无可达源码，写 TEMPLATE 降级稿（DTO 有 {} 个 Function）",
                    moduleName, countFunctions(moduleDto));
            execLog.log(taskId, String.format(
                    "[DOC-DEGRADE] stage=MODULE_DOC target=%s reason=source_unreachable tier=TEMPLATE",
                    moduleName));
            upsertModuleDraft(task, ws, moduleDto,
                    buildDegradedDoc(moduleDto, null, null, null, "source_unreachable"),
                    "PENDING_REVIEW", projectDir);
            return;
        }

        // 2. 渲染 prompt（必须来自任务快照的提示词绑定）
        String promptTemplate = decompilePromptService.requireTaskPromptContent(task,
                com.company.codeinsight.modules.prompt.entity.DecompilePrompt.TYPE_DOCUMENT_GENERATION);

        // 把当前模块（含权威 class_paths / methods）序列化成 JSON 给 AI
        String moduleHierarchyJson = buildModuleDocHierarchyJson(taskId, moduleDto, projectDir);

        final java.util.concurrent.atomic.AtomicReference<String> sourceRef =
                new java.util.concurrent.atomic.AtomicReference<>(moduleSource);
        // 首调不预裁：完整源码；仅上下文超限失败后再裁
        String promptInput = promptTemplateLoader.renderModuleDoc(
                promptTemplate, moduleName, moduleHierarchyJson, sourceRef.get());
        if (promptTemplateLoader.hasUnresolvedModuleDocPlaceholders(promptInput)) {
            log.warn("模块 {} prompt 仍有未替换占位符，回退 TEMPLATE 稿", moduleName);
            execLog.log(taskId, String.format(
                    "[AI-FALLBACK] stage=MODULE_DOC target=%s reason=unresolved_placeholders tier=TEMPLATE",
                    moduleName));
            upsertModuleDraft(task, ws, moduleDto,
                    buildDegradedDoc(moduleDto, null, null, null, "unresolved_placeholders"),
                    "PENDING_REVIEW", projectDir);
            return;
        }

        // 3. 调 AI（可配置重试 + pipeline.log）
        AiSummaryService.AiCallMeta callMeta = new AiSummaryService.AiCallMeta();
        callMeta.setCallStage("MODULE_DOC");
        callMeta.setClassPath(moduleDto.getId());

        String aiMarkdown = pipelineAiCaller.callWithRetry(
                taskId,
                "MODULE_DOC",
                moduleName,
                promptInput,
                task.getModelName(),
                callMeta,
                response -> {
                    if (!StringUtils.hasText(response) || "{}".equals(response.trim())) {
                        return PipelineAiCaller.ValidationResult.fail("empty response");
                    }
                    String validationMsg = validateModuleDocStructure(response);
                    if (validationMsg != null) {
                        return PipelineAiCaller.ValidationResult.fail("structure: " + validationMsg);
                    }
                    return PipelineAiCaller.ValidationResult.ok(response);
                },
                (original, current, failedAttempt, reason) ->
                        mutateDocPromptOnRetry(taskId, "MODULE_DOC", moduleName,
                                promptTemplate, moduleHierarchyJson, sourceRef, current, reason)
        );

        String finalMarkdown;
        String initialStatus;
        if (!StringUtils.hasText(aiMarkdown) || "{}".equals(aiMarkdown.trim())) {
            log.warn("模块 {} AI 响应为空或全部重试失败，写 PENDING_REVIEW TEMPLATE 稿", moduleName);
            execLog.log(taskId, String.format(
                    "[AI-FALLBACK] stage=MODULE_DOC target=%s reason=ai_failed tier=TEMPLATE",
                    moduleName));
            finalMarkdown = buildDegradedDoc(moduleDto, null, null, null, "ai_failed");
            initialStatus = "PENDING_REVIEW";
        } else {
            finalMarkdown = aiMarkdown;
            initialStatus = "AI_GENERATED";
        }

        // 4. 落库（写文件 + 写 KnowledgeDraft + 写 source references）
        upsertModuleDraft(task, ws, moduleDto, finalMarkdown, initialStatus, projectDir);
    }

    /**
     * 校验模块文档的 Markdown 结构完整性
     * 检查是否包含 prompt 模板要求的所有中文数字章节（一～六）。
     * 不阻塞流水线，仅用于标记状态：结构不完整时降级为 PENDING_REVIEW 供人工补充。
     *
     * @param markdown AI 生成的 Markdown 正文
     * @return 结构问题时返回描述信息，结构完整返回 null
     */
    private String validateModuleDocStructure(String markdown) {
        if (!StringUtils.hasText(markdown)) return "内容为空";

        // 必需的中文数字章节标题（module_doc_prompt.md 规范）
        String[] requiredSections = {"一、", "二、", "三、", "四、", "五、", "六、"};
        int found = 0;
        java.util.List<String> missing = new java.util.ArrayList<>();

        for (String section : requiredSections) {
            if (markdown.contains(section)) {
                found++;
            } else {
                missing.add(section);
            }
        }

        if (found < requiredSections.length) {
            return "缺少章节: " + String.join(", ", missing) + "（发现 " + found + "/" + requiredSections.length + "）";
        }
        return null;
    }

    /**
     * 收集模块所有入口方法（BFS 入口），并按 ci_method_call 取可达源码。
     * <p>模块层根方法直接从 {@code ci_method_function_binding WHERE module_node_id} 一次查出，
     * 不再按"function × classPaths[0]"笛卡尔积（该路径已被反向索引取代，避免大杂烩 BFS）。</p>
     */
    private String collectModuleSourceCode(Long taskId,
                                           com.company.codeinsight.modules.hierarchy.model.ModuleDto moduleDto,
                                           File projectDir) {
        Set<String> rootSignatures = new LinkedHashSet<>();
        if (taskId != null && moduleDto != null
                && methodFunctionBindingMapper != null
                && StringUtils.hasText(moduleDto.getId())) {
            List<MethodFunctionBinding> bindings =
                    methodFunctionBindingMapper.selectByTaskAndModule(taskId, moduleDto.getId());
            if (bindings != null) {
                for (MethodFunctionBinding b : bindings) {
                    if (StringUtils.hasText(b.getClassName())
                            && StringUtils.hasText(b.getMethodSignature())) {
                        String key = toCallerSignatureKey(b.getClassName(), b.getMethodSignature());
                        if (key != null) {
                            rootSignatures.add(key);
                        }
                    }
                }
            }
        }
        // 回退：老数据（无 method_function_binding 行时）按 fn.methodSignatures × classPaths[0] 走
        if (rootSignatures.isEmpty()) {
            for (com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm : moduleDto.getSubModules().values()) {
                for (com.company.codeinsight.modules.hierarchy.model.FunctionDto fn : sm.getFunctions().values()) {
                    Set<String> fnRoots = loadFunctionRootSignatures(taskId, fn);
                    rootSignatures.addAll(fnRoots);
                }
            }
            if (rootSignatures.isEmpty()) {
                return collectModuleSourceCodeByClass(taskId, moduleDto, projectDir);
            }
        }

        // BFS 调用链反查
        Set<String> reachableMethods = methodCallGraphService.resolveReachableMethods(taskId, rootSignatures);
        log.info("阶段 2 文档生成 taskId={} module={} roots={} reachable={} projectDir={}",
                taskId, moduleDto.getModuleName(), rootSignatures.size(), reachableMethods.size(),
                projectDir == null ? null : projectDir.getAbsolutePath());

        Map<String, Set<String>> classToMethodSigs = groupByClass(reachableMethods);

        ScanScope scope = (projectDir != null && projectDir.isDirectory() && taskId != null)
                ? scanScopeResolver.resolveBestEffort(taskId, projectDir)
                : null;

        StringBuilder sb = new StringBuilder();
        int missFile = 0;
        for (Map.Entry<String, Set<String>> entry : classToMethodSigs.entrySet()) {
            String className = entry.getKey();
            Set<String> methodSigs = entry.getValue();
            String classFilePath = lookupClassFilePath(taskId, className);
            if (classFilePath == null) continue;
            if (scope != null && !scope.accepts(classFilePath)) {
                log.warn("模块源码收集：超出扫描范围，跳过 class={} path={}", className, classFilePath);
                continue;
            }
            File classFile = new File(projectDir, classFilePath);
            if (!classFile.exists()) {
                missFile++;
                log.warn("模块源码收集：文件不存在 class={} rel={} abs={}",
                        className, classFilePath, classFile.getAbsolutePath());
                continue;
            }
            ClassMethodSnippet snippet = filterClassToMethods(classFile, methodSigs);
            if (snippet == null || !StringUtils.hasText(snippet.methodsBody)) continue;
            appendClassMethodSnippet(sb, taskId, className, projectDir, snippet);
        }
        if (!StringUtils.hasText(sb.toString()) && missFile > 0) {
            log.warn("模块 {} 源码组装为空（missFile={}），请检查 NAS workspace 与 filePath",
                    moduleDto.getModuleName(), missFile);
        }
        return sb.toString();
    }

    /**
     * fallback：methodSignatures 为空时按 classPaths 走类粒度 BFS
     * 兼容阶段 1 之前未配置 method_signatures 的旧任务
     */
    private String collectModuleSourceCodeByClass(Long taskId,
                                                  com.company.codeinsight.modules.hierarchy.model.ModuleDto moduleDto,
                                                  File projectDir) {
        Set<String> entryClassPaths = new LinkedHashSet<>();
        for (com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm : moduleDto.getSubModules().values()) {
            for (com.company.codeinsight.modules.hierarchy.model.FunctionDto fn : sm.getFunctions().values()) {
                entryClassPaths.addAll(fn.getClassPaths());
            }
        }
        if (entryClassPaths.isEmpty()) {
            return "";
        }
        DecompileTask task = decompileTaskMapper.selectById(taskId);
        com.company.codeinsight.modules.entrypoint.model.EntryPointConfig config =
                com.company.codeinsight.modules.entrypoint.model.EntryPointConfigCodec.decode(task.getEntryScanConfig());
        StringBuilder sb = new StringBuilder();
        for (String entryClass : entryClassPaths) {
            String src = entryPointDiscoveryService.collectReachableSource(
                    taskId, entryClass, projectDir, config);
            if (!StringUtils.hasText(src)) continue;
            sb.append("// ===== Entry: ").append(entryClass).append(" =====\n");
            sb.append(src).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 拼装完整方法签名：短类名#methodName(ParamType1, ParamType2)
     * classPath 取 function.classPaths 的第一个元素（FQ 会截短，对齐 ci_method_call）
     */
    private String buildFullMethodSignature(com.company.codeinsight.modules.hierarchy.model.FunctionDto fn,
                                           String methodSig) {
        if (fn == null || !StringUtils.hasText(methodSig)) return null;
        String classPath = fn.getClassPaths().stream().findFirst().orElse(null);
        if (!StringUtils.hasText(classPath)) return null;
        return toCallerSignatureKey(classPath, methodSig);
    }

    /**
     * 把 methodSignatures 集合按 className 聚合
     * 输入：["com.demo.A#listUsers()", "com.demo.B#findById()", "com.demo.A#createUser()"]
     * 输出：{ "com.demo.A" → {"listUsers()", "createUser()"}, "com.demo.B" → {"findById()"} }
     */
    /**
     * 按 methodSignatures 的遍历序聚合到类（须为 LinkedHashSet/BFS 发现序），
     * 使用 LinkedHashMap 保证 prompt 中类块顺序 = 入口 → 下游调用链顺序。
     */
    private Map<String, Set<String>> groupByClass(Set<String> methodSignatures) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String sig : methodSignatures) {
            int hashIdx = sig.indexOf('#');
            if (hashIdx < 0) continue;
            String className = sig.substring(0, hashIdx);
            String methodOnly = sig.substring(hashIdx + 1);
            result.computeIfAbsent(className, k -> new LinkedHashSet<>()).add(methodOnly);
        }
        return result;
    }

    /**
     * 按 methodName 集合截取类文件源码（用 startLine/endLine 范围）。
     * 方法输出顺序跟随 {@code targetMethodSigs} 的插入序（BFS 发现序），而非源文件声明序。
     * 只输出目标方法，不输出 import / 字段 / 其他方法；调用方负责拼 FQ / package 头。
     */
    private ClassMethodSnippet filterClassToMethods(File classFile, Set<String> targetMethodSigs) {
        try {
            ParsedClassInfo info = javaParserService.parseFile(classFile);
            if (info == null) return null;
            Map<String, ParsedClassInfo.MethodInfo> byName = new LinkedHashMap<>();
            for (ParsedClassInfo.MethodInfo mi : info.getMethods()) {
                if (mi.getName() != null && !byName.containsKey(mi.getName())) {
                    byName.put(mi.getName(), mi);
                }
            }
            // 按 BFS 发现序去重方法名
            LinkedHashSet<String> orderedNames = new LinkedHashSet<>();
            for (String sig : targetMethodSigs) {
                int parenIdx = sig.indexOf('(');
                String methodName = parenIdx >= 0 ? sig.substring(0, parenIdx) : sig;
                if (StringUtils.hasText(methodName)) {
                    orderedNames.add(methodName.trim());
                }
            }
            java.util.List<String> lines = Files.readAllLines(classFile.toPath());
            StringBuilder sb = new StringBuilder();
            for (String methodName : orderedNames) {
                ParsedClassInfo.MethodInfo mi = byName.get(methodName);
                if (mi == null || mi.getStartLine() == null || mi.getEndLine() == null) {
                    continue;
                }
                int start = mi.getStartLine() - 1;
                int end = Math.min(mi.getEndLine(), lines.size());
                for (int i = start; i < end; i++) {
                    sb.append(lines.get(i)).append("\n");
                }
                sb.append("\n");
            }
            String body = sb.toString();
            if (!StringUtils.hasText(body)) {
                return null;
            }
            return new ClassMethodSnippet(info, body);
        } catch (Exception e) {
            log.warn("filterClassToMethods failed for {}: {}", classFile, e.getMessage());
            return null;
        }
    }

    /**
     * 从 ci_method_call 反查类的物理文件路径。
     * <p>{@code ci_method_call.class_name} 为短类名；入参可能是 FQ（binding / hierarchy classPaths），
     * 需同时尝试短名，否则会落到错误的 {@code src/main/java/...} 推断路径（多模块 NAS 下几乎必挂）。</p>
     */
    private String lookupClassFilePath(Long taskId, String className) {
        if (!StringUtils.hasText(className)) {
            return null;
        }
        String fromDb = lookupClassFilePathExact(taskId, className);
        if (fromDb != null) {
            return fromDb;
        }
        String shortName = stripPackage(className);
        if (StringUtils.hasText(shortName) && !shortName.equals(className)) {
            fromDb = lookupClassFilePathExact(taskId, shortName);
            if (fromDb != null) {
                return fromDb;
            }
        }
        // 兜底：用包路径推断（单模块约定；多模块应以 DB filePath 为准）
        if (className.contains(".")) {
            String pkgPath = className.substring(0, className.lastIndexOf('.')).replace('.', '/');
            String simple = className.substring(className.lastIndexOf('.') + 1);
            return "src/main/java/" + pkgPath + "/" + simple + ".java";
        }
        return "src/main/java/" + className + ".java";
    }

    private String lookupClassFilePathExact(Long taskId, String className) {
        try {
            List<com.company.codeinsight.modules.callchain.entity.MethodCall> calls = methodCallMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.company.codeinsight.modules.callchain.entity.MethodCall>()
                            .eq(com.company.codeinsight.modules.callchain.entity.MethodCall::getTaskId, taskId)
                            .eq(com.company.codeinsight.modules.callchain.entity.MethodCall::getClassName, className)
                            .last("LIMIT 1")
            );
            if (!calls.isEmpty() && StringUtils.hasText(calls.get(0).getFilePath())) {
                return calls.get(0).getFilePath();
            }
        } catch (Exception e) {
            log.warn("lookupClassFilePath failed for {}: {}", className, e.getMessage());
        }
        return null;
    }

    /**
     * AI 失败 / 源码不可达时的结构化降级文档（方案 A G2）。
     */
    private String buildDegradedDoc(com.company.codeinsight.modules.hierarchy.model.ModuleDto moduleDto,
                                    com.company.codeinsight.modules.hierarchy.model.SubModuleDto subModuleDto,
                                    com.company.codeinsight.modules.hierarchy.model.FunctionDto functionDto,
                                    FunctionSourceBundle bundle,
                                    String reason) {
        String title = moduleDto != null ? moduleDto.getModuleName() : "模块";
        if (functionDto != null && StringUtils.hasText(functionDto.getFunctionName())) {
            title = functionDto.getFunctionName();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        sb.append("> 生成档位：TEMPLATE；原因：").append(reason != null ? reason : "unknown")
                .append("。可在知识复核窗口「重跑此篇」升档。\n\n");

        sb.append("## 一、概述\n\n暂无相关信息（降级稿，待 AI/人工补充）\n\n");
        sb.append("## 二、涉及类清单\n\n");
        if (functionDto != null && functionDto.getClassPaths() != null && !functionDto.getClassPaths().isEmpty()) {
            sb.append("| 序号 | 类路径 |\n| --- | --- |\n");
            int i = 1;
            for (String cp : functionDto.getClassPaths()) {
                sb.append("| ").append(i++).append(" | ").append(cp).append(" |\n");
            }
            sb.append("\n");
        } else if (moduleDto != null) {
            sb.append("### 子模块清单\n");
            for (com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm : moduleDto.getSubModules().values()) {
                sb.append("- **").append(sm.getSubModuleName()).append("**\n");
                for (com.company.codeinsight.modules.hierarchy.model.FunctionDto fn : sm.getFunctions().values()) {
                    sb.append("  - ").append(fn.getFunctionName())
                            .append("（入口: ").append(String.join(", ", fn.getClassPaths())).append("）\n");
                }
            }
            sb.append("\n");
        } else {
            sb.append("暂无相关信息\n\n");
        }

        sb.append("## 三、输入输出\n\n暂无相关信息\n\n");
        sb.append("## 四、核心业务流程图\n\n暂无相关信息\n\n");
        sb.append("## 五、核心业务逻辑\n\n暂无相关信息\n\n");
        sb.append("## 六、调用链路说明\n\n");
        if (bundle != null && bundle.getRootSignatures() != null && !bundle.getRootSignatures().isEmpty()) {
            sb.append("- ROOT 签名：").append(String.join(", ", bundle.getRootSignatures())).append("\n");
        }
        if (bundle != null && bundle.getRefs() != null && !bundle.getRefs().isEmpty()) {
            sb.append("- 来源 refs：").append(bundle.getRefs().size()).append(" 条（按 bfs_order）\n");
            int shown = 0;
            for (FunctionSourceBundle.RefItem ref : bundle.getRefs()) {
                if (shown >= 20) {
                    sb.append("- …\n");
                    break;
                }
                sb.append("- [").append(ref.getRefKind()).append("] ")
                        .append(ref.getClassName() != null ? ref.getClassName() : "?");
                if (StringUtils.hasText(ref.getMethodSignature())) {
                    sb.append("#").append(ref.getMethodSignature());
                }
                sb.append("\n");
                shown++;
            }
            sb.append("\n");
        } else {
            sb.append("暂无相关信息\n\n");
        }

        if (bundle != null && bundle.hasPromptText()) {
            String excerpt = bundle.getPromptText();
            int cap = 4000;
            if (excerpt.length() > cap) {
                excerpt = excerpt.substring(0, cap) + "\n// ... excerpt truncated ...\n";
            }
            sb.append("## 附：源码摘录\n\n```java\n").append(excerpt).append("\n```\n");
        } else if (subModuleDto != null) {
            sb.append("## 附：层级定位\n\n- 子模块：").append(subModuleDto.getSubModuleName()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 文档 AI 重试 mutator：仅上下文超限 → 裁剪源码；结构不合规 → 追加系统提示；其它（含超时）原样重试。
     */
    private String mutateDocPromptOnRetry(Long taskId, String stage, String target,
                                          String promptTemplate, String hierarchyJson,
                                          java.util.concurrent.atomic.AtomicReference<String> sourceRef,
                                          String currentPrompt, String reason) {
        if (PipelineAiCaller.isContextLengthFailure(reason)) {
            // 首次超限：压到配置的 max-prompt-chars；再次超限：再按 shrinkFactor 压
            int maxPrompt = Math.max(10_000, docBudgetProperties.getMaxPromptChars());
            int maxBlock = Math.max(500, docBudgetProperties.getMaxMethodBodyChars());
            String source = sourceRef.get() != null ? sourceRef.get() : "";
            String rendered = promptTemplateLoader.renderModuleDoc(promptTemplate, target, hierarchyJson, source);
            DocSourceBudgetShrinker.ShrinkResult sr;
            if (rendered.length() > maxPrompt) {
                int overhead = Math.max(0, rendered.length() - source.length());
                int sourceBudget = Math.max(2_000, maxPrompt - overhead - 200);
                sr = DocSourceBudgetShrinker.shrink(source, sourceBudget, maxBlock);
            } else {
                sr = DocSourceBudgetShrinker.shrinkFurther(
                        source, maxBlock, docBudgetProperties.getShrinkFactor());
            }
            if (sr.changed() && StringUtils.hasText(sr.text())) {
                sourceRef.set(sr.text());
                execLog.log(taskId, String.format(
                        "[AI-SHRINK] stage=%s target=%s %s reason=%s",
                        stage, target, sr.summary(),
                        reason != null && reason.length() > 80 ? reason.substring(0, 80) + "..." : reason));
                return promptTemplateLoader.renderModuleDoc(promptTemplate, target, hierarchyJson, sr.text());
            }
        }
        if (reason != null && reason.startsWith("structure:")) {
            return currentPrompt
                    + "\n\n[系统提示] 上轮输出不符合要求：" + reason
                    + "\n请补全全部六个章节（一、～六、），输出完整 Markdown。";
        }
        return currentPrompt;
    }

    /**
     * upsert 落库：写文件 → 写 KnowledgeDraft → 写 source references
     */
    private void upsertModuleDraft(DecompileTask task, DraftWorkspace ws,
                                   com.company.codeinsight.modules.hierarchy.model.ModuleDto moduleDto,
                                   String markdown, String initialStatus,
                                   File projectDir) {
        Long taskId = task.getId();
        String moduleName = moduleDto.getModuleName();
        String safeModuleName = moduleName.replaceAll("[\\s/\\(\\)]", "_");
        String relativeDocPath = "task_" + taskId + "/" + safeModuleName + ".md";
        Path storePath = storageResolver.draftsRoot().resolve(relativeDocPath);

        // 写文件
        try {
            Files.createDirectories(storePath.getParent());
            Files.writeString(storePath, markdown);
        } catch (IOException e) {
            log.error("保存 Markdown 知识草稿文件失败", e);
        }

        // 本地仓库副本
        try {
            CodeRepository repo = repositoryMapper.selectById(task.getRepositoryId());
            if (repo != null && StringUtils.hasText(repo.getGitUrl())) {
                File localRepoDir = new File(repo.getGitUrl());
                if (localRepoDir.exists() && localRepoDir.isDirectory()) {
                    File targetDraftDir = new File(localRepoDir, "docs/code-insight/drafts");
                    if (!targetDraftDir.exists()) {
                        targetDraftDir.mkdirs();
                    }
                    File targetDraftFile = new File(targetDraftDir, safeModuleName + ".md");
                    Files.writeString(targetDraftFile.toPath(), markdown);
                    log.info("本地模式：成功备份草稿文档至指定目录：{}", targetDraftFile.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            log.error("备份草稿文档至本地代码库指定目录失败", e);
        }

        // upsert KnowledgeDraft
        String hash = DigestUtils.md5DigestAsHex(markdown.getBytes());
        KnowledgeDraft draft = knowledgeDraftMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDraft>()
                        .eq(KnowledgeDraft::getWorkspaceId, ws.getId())
                        .eq(KnowledgeDraft::getFilePath, relativeDocPath)
        );
        if (draft == null) {
            draft = new KnowledgeDraft();
            draft.setWorkspaceId(ws.getId());
            draft.setFilePath(relativeDocPath);
            draft.setModuleName(moduleName);
            draft.setContentUri(storePath.toAbsolutePath().toUri().toString());
            draft.setStatus(initialStatus);
            draft.setHash(hash);
            draft.setCreatedDate(LocalDateTime.now());
            draft.setUpdatedDate(LocalDateTime.now());
            knowledgeDraftMapper.insert(draft);
        } else {
            draft.setHash(hash);
            draft.setStatus(initialStatus);
            draft.setModuleName(moduleName);
            draft.setContentUri(storePath.toAbsolutePath().toUri().toString());
            draft.setBaselineTaskId(null); // 覆盖继承草稿时清标记（需 FieldStrategy.ALWAYS）
            draft.setUpdatedDate(LocalDateTime.now());
            knowledgeDraftMapper.updateById(draft);
        }

        // 清旧 source references，按功能节点写入类 + 方法签名引用
        draftSourceReferenceMapper.delete(
                new LambdaQueryWrapper<DraftSourceReference>().eq(DraftSourceReference::getDraftId, draft.getId())
        );
        for (com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm : moduleDto.getSubModules().values()) {
            for (com.company.codeinsight.modules.hierarchy.model.FunctionDto fn : sm.getFunctions().values()) {
                insertDraftSourceReferences(draft.getId(), taskId, fn, projectDir);
            }
        }
    }

    private void replaceDraftSourceReferencesForFunction(Long draftId, Long taskId,
                                                       com.company.codeinsight.modules.hierarchy.model.FunctionDto fn,
                                                       File projectDir) {
        draftSourceReferenceMapper.delete(
                new LambdaQueryWrapper<DraftSourceReference>().eq(DraftSourceReference::getDraftId, draftId)
        );
        insertDraftSourceReferences(draftId, taskId, fn, projectDir);
    }

    private void replaceDraftSourceReferencesFromBundle(Long draftId, FunctionSourceBundle bundle) {
        draftSourceReferenceMapper.delete(
                new LambdaQueryWrapper<DraftSourceReference>().eq(DraftSourceReference::getDraftId, draftId)
        );
        if (bundle == null || bundle.getRefs() == null || bundle.getRefs().isEmpty()) {
            return;
        }
        for (FunctionSourceBundle.RefItem item : bundle.getRefs()) {
            DraftSourceReference ref = new DraftSourceReference();
            ref.setDraftId(draftId);
            ref.setFilePath(item.getFilePath());
            ref.setClassName(item.getClassName());
            if (StringUtils.hasText(item.getMethodSignature())) {
                ref.setMethodSignature(item.getMethodSignature().trim());
            }
            ref.setStartLine(item.getStartLine());
            ref.setEndLine(item.getEndLine());
            ref.setRefKind(item.getRefKind());
            ref.setBfsOrder(item.getBfsOrder());
            ref.setCreatedDate(LocalDateTime.now());
            draftSourceReferenceMapper.insert(ref);
        }
    }

    @Override
    public RegenerateDraftResult regenerateFunctionDocument(Long draftId) {
        if (draftId == null) {
            throw new BusinessException("草稿 ID 不能为空");
        }
        KnowledgeDraft draft = knowledgeDraftMapper.selectById(draftId);
        if (draft == null) {
            throw new BusinessException("草稿不存在: " + draftId);
        }
        DraftWorkspace ws = draftWorkspaceMapper.selectById(draft.getWorkspaceId());
        if (ws == null) {
            throw new BusinessException("草稿工作区不存在");
        }
        DecompileTask task = decompileTaskMapper.selectById(ws.getTaskId());
        if (task == null) {
            throw new BusinessException("关联任务不存在");
        }

        String taskStatus = task.getStatus();
        if (!TaskStatus.PENDING_REVIEW.name().equals(taskStatus)
                && !TaskStatus.REVIEWING.name().equals(taskStatus)) {
            throw new BusinessException("任务状态为 " + taskStatus + "，仅待复核/复核中可重跑单篇功能文档");
        }

        Long taskId = task.getId();
        File projectDir = taskWorkspacePaths.taskProjectDir(taskId);
        if (projectDir == null || !projectDir.isDirectory()) {
            throw new BusinessException("源码工作区已回收或不可用，无法重跑（知识确认后源码已清理）");
        }

        com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy hierarchy =
                moduleHierarchyService.loadByTaskId(taskId);
        if (hierarchy == null || hierarchy.getModules() == null || hierarchy.getModules().isEmpty()) {
            throw new BusinessException("模块层级为空，无法重跑功能文档");
        }

        FunctionHierarchyContext ctx = resolveFunctionHierarchyContext(draft, hierarchy);
        generateFunctionDraft(task, ws, ctx.module(), ctx.subModule(), ctx.function(), hierarchy, projectDir);

        KnowledgeDraft updated = knowledgeDraftMapper.selectById(draftId);
        DraftRevision revision = new DraftRevision();
        revision.setDraftId(draftId);
        revision.setContentUri(updated.getContentUri());
        revision.setAuthor("system");
        revision.setRemark("AI 重跑");
        revision.setCreatedDate(LocalDateTime.now());
        draftRevisionMapper.insert(revision);

        Long refCount = draftSourceReferenceMapper.selectCount(
                new LambdaQueryWrapper<DraftSourceReference>().eq(DraftSourceReference::getDraftId, draftId));

        RegenerateDraftResult result = new RegenerateDraftResult();
        result.setDraftId(draftId);
        result.setStatus(updated.getStatus());
        result.setAccepted(false);
        result.setContentUri(updated.getContentUri());
        result.setFunctionNodeId(updated.getFunctionNodeId());
        result.setReferenceCount(refCount != null ? refCount.intValue() : 0);
        return result;
    }

    private record FunctionHierarchyContext(
            com.company.codeinsight.modules.hierarchy.model.ModuleDto module,
            com.company.codeinsight.modules.hierarchy.model.SubModuleDto subModule,
            com.company.codeinsight.modules.hierarchy.model.FunctionDto function) {
    }

    private FunctionHierarchyContext resolveFunctionHierarchyContext(
            KnowledgeDraft draft,
            com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy hierarchy) {
        String functionNodeId = draft.getFunctionNodeId();
        if (StringUtils.hasText(functionNodeId)) {
            for (com.company.codeinsight.modules.hierarchy.model.ModuleDto m : hierarchy.getModules().values()) {
                for (com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm : m.getSubModules().values()) {
                    for (com.company.codeinsight.modules.hierarchy.model.FunctionDto fn : sm.getFunctions().values()) {
                        if (functionNodeId.equals(fn.getId())) {
                            return new FunctionHierarchyContext(m, sm, fn);
                        }
                    }
                }
            }
        }

        String fullName = draft.getModuleName();
        if (StringUtils.hasText(fullName)) {
            String[] parts = fullName.split("\\s*/\\s*", 3);
            if (parts.length == 3) {
                String modName = parts[0].trim();
                String subName = parts[1].trim();
                String fnName = parts[2].trim();
                for (com.company.codeinsight.modules.hierarchy.model.ModuleDto m : hierarchy.getModules().values()) {
                    if (!modName.equals(m.getModuleName())) {
                        continue;
                    }
                    for (com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm : m.getSubModules().values()) {
                        if (!subName.equals(sm.getSubModuleName())) {
                            continue;
                        }
                        for (com.company.codeinsight.modules.hierarchy.model.FunctionDto fn : sm.getFunctions().values()) {
                            if (fnName.equals(fn.getFunctionName())) {
                                if (!StringUtils.hasText(functionNodeId) && StringUtils.hasText(fn.getId())) {
                                    draft.setFunctionNodeId(fn.getId());
                                    draft.setUpdatedDate(LocalDateTime.now());
                                    knowledgeDraftMapper.updateById(draft);
                                }
                                return new FunctionHierarchyContext(m, sm, fn);
                            }
                        }
                    }
                }
            }
        }

        throw new BusinessException("无法定位功能节点，请确认草稿 moduleName 或 functionNodeId");
    }

    private void insertDraftSourceReferences(Long draftId, Long taskId,
                                           com.company.codeinsight.modules.hierarchy.model.FunctionDto fn,
                                           File projectDir) {
        if (fn == null || draftId == null) return;
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();

        // 主路径：直接按 method_function_binding 行写入每条方法引用，避免 classPaths × methodSignatures 笛卡尔积
        if (methodFunctionBindingMapper != null && taskId != null
                && StringUtils.hasText(fn.getId())) {
            List<MethodFunctionBinding> bindings =
                    methodFunctionBindingMapper.selectByTaskAndFunction(taskId, fn.getId());
            if (bindings != null && !bindings.isEmpty()) {
                for (MethodFunctionBinding b : bindings) {
                    if (!StringUtils.hasText(b.getClassName())
                            || !StringUtils.hasText(b.getMethodSignature())) {
                        continue;
                    }
                    String classPath = b.getClassName();
                    String filePath = lookupClassFilePath(taskId, classPath);
                    if (!StringUtils.hasText(filePath)) {
                        filePath = entryClassToFilePath(taskId, classPath);
                    }
                    if (!StringUtils.hasText(filePath)) continue;
                    String dedupeKey = filePath + "|" + classPath + "|" + b.getMethodSignature();
                    if (!seen.add(dedupeKey)) continue;
                    File classFile = projectDir != null ? new File(projectDir, filePath) : null;
                    int[] lines = resolveMethodLineRange(classFile, b.getMethodSignature());
                    DraftSourceReference ref = new DraftSourceReference();
                    ref.setDraftId(draftId);
                    ref.setFilePath(filePath);
                    ref.setClassName(classPath);
                    ref.setMethodSignature(b.getMethodSignature().trim());
                    if (lines != null) {
                        ref.setStartLine(lines[0]);
                        ref.setEndLine(lines[1]);
                    } else {
                        ref.setStartLine(1);
                        ref.setEndLine(0);
                    }
                    ref.setCreatedDate(LocalDateTime.now());
                    draftSourceReferenceMapper.insert(ref);
                }
                return; // 主路径优先：找到任何绑定就完成引用落库，不再走旧路径
            }
        }

        // 回退（旧数据 / 无绑定表的退化任务）：仍按 fn.methodSignatures × fn.classPaths 笛卡尔积写入
        if (fn.getMethodSignatures() != null && !fn.getMethodSignatures().isEmpty()
                && fn.getClassPaths() != null && !fn.getClassPaths().isEmpty()) {
            for (String classPath : fn.getClassPaths()) {
                String filePath = lookupClassFilePath(taskId, classPath);
                if (!StringUtils.hasText(filePath)) {
                    filePath = entryClassToFilePath(taskId, classPath);
                }
                if (!StringUtils.hasText(filePath)) continue;
                File classFile = projectDir != null ? new File(projectDir, filePath) : null;
                for (String methodSig : fn.getMethodSignatures()) {
                    if (!StringUtils.hasText(methodSig)) continue;
                    String dedupeKey = filePath + "|" + classPath + "|" + methodSig;
                    if (!seen.add(dedupeKey)) continue;
                    int[] lines = resolveMethodLineRange(classFile, methodSig);
                    DraftSourceReference ref = new DraftSourceReference();
                    ref.setDraftId(draftId);
                    ref.setFilePath(filePath);
                    ref.setClassName(classPath);
                    ref.setMethodSignature(methodSig.trim());
                    if (lines != null) {
                        ref.setStartLine(lines[0]);
                        ref.setEndLine(lines[1]);
                    } else {
                        ref.setStartLine(1);
                        ref.setEndLine(0);
                    }
                    ref.setCreatedDate(LocalDateTime.now());
                    draftSourceReferenceMapper.insert(ref);
                }
            }
            return;
        }
        if (fn.getClassPaths() == null) return;
        for (String classPath : fn.getClassPaths()) {
            if (!StringUtils.hasText(classPath)) continue;
            String filePath = entryClassToFilePath(taskId, classPath);
            if (!StringUtils.hasText(filePath)) continue;
            String dedupeKey = filePath + "|" + classPath;
            if (!seen.add(dedupeKey)) continue;
            DraftSourceReference ref = new DraftSourceReference();
            ref.setDraftId(draftId);
            ref.setFilePath(filePath);
            ref.setClassName(classPath);
            ref.setStartLine(1);
            ref.setEndLine(0);
            ref.setCreatedDate(LocalDateTime.now());
            draftSourceReferenceMapper.insert(ref);
        }
    }

    private int[] resolveMethodLineRange(File classFile, String methodSig) {
        if (classFile == null || !classFile.exists() || !StringUtils.hasText(methodSig)) {
            return null;
        }
        try {
            com.company.codeinsight.modules.parser.model.ParsedClassInfo info = javaParserService.parseFile(classFile);
            if (info == null || info.getMethods() == null) return null;
            int parenIdx = methodSig.indexOf('(');
            String methodName = (parenIdx >= 0 ? methodSig.substring(0, parenIdx) : methodSig).trim();
            for (com.company.codeinsight.modules.parser.model.ParsedClassInfo.MethodInfo mi : info.getMethods()) {
                if (methodName.equals(mi.getName()) && mi.getStartLine() != null && mi.getEndLine() != null) {
                    return new int[]{mi.getStartLine(), mi.getEndLine()};
                }
            }
        } catch (Exception e) {
            log.warn("resolveMethodLineRange failed for {}: {}", classFile, e.getMessage());
        }
        return null;
    }

    /**
     * 从 methodCall 表反查入口类的物理文件路径；兜底用包路径推断
     */
    private String entryClassToFilePath(Long taskId, String fqClassName) {
        if (!StringUtils.hasText(fqClassName)) return null;
        String shortName = fqClassName.contains(".")
                ? fqClassName.substring(fqClassName.lastIndexOf('.') + 1)
                : fqClassName;
        try {
            List<MethodCall> calls = methodCallMapper.selectList(
                    new LambdaQueryWrapper<MethodCall>().eq(MethodCall::getTaskId, taskId)
            );
            for (MethodCall mc : calls) {
                if (shortName.equals(mc.getClassName()) && StringUtils.hasText(mc.getFilePath())) {
                    return mc.getFilePath();
                }
            }
        } catch (Exception e) {
            log.warn("entryClassToFilePath 反查调用链失败: {}", e.getMessage());
        }
        // 兜底：包路径推断
        if (fqClassName.contains(".")) {
            String pkgPath = fqClassName.substring(0, fqClassName.lastIndexOf('.')).replace('.', '/');
            String simple = fqClassName.substring(fqClassName.lastIndexOf('.') + 1);
            return "src/main/java/" + pkgPath + "/" + simple + ".java";
        }
        return fqClassName + ".java";
    }

    private int countFunctions(com.company.codeinsight.modules.hierarchy.model.ModuleDto moduleDto) {
        int c = 0;
        for (com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm : moduleDto.getSubModules().values()) {
            c += sm.getFunctions().size();
        }
        return c;
    }

    /**
     * 将整个 ModuleHierarchy DTO 序列化为 JSON 字符串（注入 {module_hierarchy.json} 占位符）
     * <p>
     * 提示词专用：使用 PromptViewDtos 剥离 class_paths，仅暴露 id / name / keywords。
     * 内部仍持有完整 class_paths 用于 collectModuleSourceCode / routing。
     */
    private String serializeHierarchyToJson(com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy hierarchy) {
        if (hierarchy == null) return "{}";
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    com.company.codeinsight.modules.hierarchy.model.PromptViewDtos.from(hierarchy));
        } catch (Exception e) {
            log.warn("serializeHierarchyToJson 失败，回退到空对象", e);
            return "{}";
        }
    }

    /**
     * 保存 AI 原始响应记录并提交至 Token 审计表
     */
    private void saveCallRecordAndAudit(Long systemId, Long taskId, Long promptId, Integer promptVersion,
                                        Long chunkId, String model, int inTokens, int outTokens,
                                        String response, boolean isSuccess, String errorMsg, long duration,
                                        String callStage) {
        // 保存 AI 调用记录
        AiCallRecord record = new AiCallRecord();
        record.setTaskId(taskId);
        record.setChunkId(chunkId);
        record.setPromptId(promptId);
        record.setPromptVersion(promptVersion);
        record.setModelName(model);
        record.setInputToken(inTokens);
        record.setOutputToken(outTokens);
        record.setIsSuccess(isSuccess ? 1 : 0);
        record.setErrorReason(com.company.codeinsight.common.util.DbStringLimits.truncate(
                errorMsg, com.company.codeinsight.common.util.DbStringLimits.ERROR_REASON));
        record.setDurationMs(duration);
        record.setCreatedDate(LocalDateTime.now());
        record.setCallStage(callStage);

        // 模拟请求和响应存储
        String stageTag = callStage == null ? "call" : callStage.toLowerCase();
        String fileBase = stageTag + "_" + (chunkId == null ? "0" : chunkId) + "_" + System.currentTimeMillis();
        Path reqPath = storageResolver.aiLogDir(taskId).resolve(fileBase + "_req.json");
        Path respPath = storageResolver.aiLogDir(taskId).resolve(fileBase + "_resp.txt");
        try {
            Files.createDirectories(reqPath.getParent());
            Files.writeString(reqPath, "{\"chunkId\":" + chunkId + ",\"model\":\"" + model + "\",\"stage\":\"" + callStage + "\"}");
            Files.writeString(respPath, response != null ? response : "");
            record.setRequestUri(reqPath.toAbsolutePath().toUri().toString());
            record.setResponseUri(respPath.toAbsolutePath().toUri().toString());
        } catch (IOException e) {
            log.error("写入 AI 请求日志文件失败", e);
        }

        aiCallRecordMapper.insert(record);

        // 写入 Token 审计（callStage 作为 type 维度；userId 来自当前操作人）
        tokenAuditService.logTokenUsage(systemId, taskId, com.company.codeinsight.common.auth.OperatorContext.getUserId(), model, inTokens, outTokens, callStage == null ? "INITIAL" : callStage, isSuccess);
    }

    @Override
    public String summarizeWithPrompt(Long taskId, String promptInput, String modelName, AiCallMeta callMeta) {
        if (taskId == null || promptInput == null) {
            return "{}";
        }

        DecompileTask task = decompileTaskMapper.selectById(taskId);
        Long systemId = task != null ? task.getSystemId() : 0L;

        String modelToUse = StringUtils.hasText(modelName) ? modelName : this.modelName;

        // 模型热插拔：按 identifier 取 apiKey/baseUrl
        String activeApiKey = this.apiKey;
        String activeApiUrl = this.apiUrl;
        if (StringUtils.hasText(modelToUse)) {
            com.company.codeinsight.modules.model.entity.AiModel dbModel = aiModelMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.company.codeinsight.modules.model.entity.AiModel>()
                            .eq(com.company.codeinsight.modules.model.entity.AiModel::getIdentifier, modelToUse)
                            .last("LIMIT 1")
            );
            if (dbModel != null) {
                if (StringUtils.hasText(dbModel.getApiKey())) activeApiKey = dbModel.getApiKey();
                if (StringUtils.hasText(dbModel.getBaseUrl())) activeApiUrl = dbModel.getBaseUrl();
            }
        }

        String processedPrompt = filterSensitiveInfo(promptInput);

        // Token 流控校验
        int taskUsed = tokenAuditService.getTaskCumulativeTokens(taskId);
        int systemUsed = tokenAuditService.getSystemMonthlyTokens(systemId);
        int currentEstimate = processedPrompt.length() / 3;
        if (isTaskTokenExceeded(taskUsed, currentEstimate)) {
            log.warn("Token 额度阻断：taskUsed={}, currentEstimate={}, taskLimit={}", taskUsed, currentEstimate, taskTokenLimit);
            logAiBlock(taskId, callMeta, "task token limit exceeded");
            throw new BusinessException("Token 消耗额度超限: task token limit exceeded");
        }
        if (isSystemTokenExceeded(systemUsed, currentEstimate)) {
            log.warn("Token 额度阻断：systemUsed={}, currentEstimate={}, systemLimit={}", systemUsed, currentEstimate, systemMonthlyTokenLimit);
            logAiBlock(taskId, callMeta, "system token limit exceeded");
            throw new BusinessException("Token 消耗额度超限: system monthly token limit exceeded");
        }

        // 基础配置 - 流量管控：用户额度 + AI 调用并发
        try {
            quotaCheckService.checkUserQuota(currentEstimate);
        } catch (BusinessException e) {
            log.warn("用户额度阻断（summarizeWithPrompt）: {}", e.getMessage());
            logAiBlock(taskId, callMeta, e.getMessage());
            throw e;
        }

        String callStage = callMeta != null && StringUtils.hasText(callMeta.getCallStage())
                ? callMeta.getCallStage() : "PROMPT";
        // 文档 / 模块层级：应用层排队等 AI 槽（不改 AiConcurrencyService）；其它阶段 fail-fast
        if (shouldWaitForAiPermit(callStage)) {
            acquireAiPermitWithWait(taskId, callStage);
        } else {
            aiConcurrencyService.tryAcquire();
        }
        try {

        // Mock 降级
        boolean shouldMock = this.aiMock
                || !StringUtils.hasText(activeApiKey)
                || activeApiKey.startsWith("test-key")
                || "mock".equalsIgnoreCase(activeApiKey);

        if (shouldMock) {
            log.info("Mock 模式已开启 (aiMock={}, apiKey={})，对 task {} / stage {} 跳过真实 AI 调用",
                    this.aiMock, maskKey(activeApiKey), taskId, callStage);
            saveCallRecordAndAudit(systemId, taskId, resolvePromptId(task, callStage), null,
                    null, modelToUse, currentEstimate, 2, "{}", true, "mock-mode", 100, callStage);
            return "{}";
        }

        log.info("真实 AI 调用开始: task={}, stage={}, model={}, promptEst={}tokens",
                taskId, callStage, modelToUse, currentEstimate);

        long start = System.currentTimeMillis();
        try {
            Map<String, Object> reqBody = new HashMap<>();
            reqBody.put("model", modelToUse);
            reqBody.put("stream", false);
            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", processedPrompt);
            messages.add(userMsg);
            reqBody.put("messages", messages);

            String jsonPayload = objectMapper.writeValueAsString(reqBody);

            String requestUrl = activeApiUrl;
            if (!requestUrl.endsWith("/chat/completions")) {
                requestUrl = requestUrl.replaceAll("/+$", "") + "/chat/completions";
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .header("Authorization", "Bearer " + activeApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .timeout(Duration.ofSeconds(Math.max(30, docBudgetProperties.getHttpTimeoutSeconds())))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long duration = System.currentTimeMillis() - start;

            if (response.statusCode() == 200) {
                String rawBody = response.body();
                JsonNode root = objectMapper.readTree(rawBody);
                JsonNode choices = root.path("choices");
                String rawContent = choices.isArray() && choices.size() > 0
                        ? choices.get(0).path("message").path("content").asText("")
                        : "";
                String aiText = normalizeModelContent(rawContent);
                int inTokens = root.path("usage").path("prompt_tokens").asInt(currentEstimate);
                int outTokens = root.path("usage").path("completion_tokens").asInt(aiText.length() / 3);
                saveCallRecordAndAudit(systemId, taskId, null, null,
                        null, modelToUse, inTokens, outTokens, aiText, true, null, duration, callStage);
                return aiText;
            } else {
                String errMsg = "HTTP " + response.statusCode() + ": " + truncateAiLogReason(response.body());
                log.error("真实 AI 调用失败 task={} stage={} model={}: {}", taskId, callStage, modelToUse, errMsg);
                saveCallRecordAndAudit(systemId, taskId, null, null,
                        null, modelToUse, currentEstimate, 0, "{}", false, errMsg, duration, callStage);
                throw new BusinessException(errMsg);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            String errMsg = "AI 调用异常: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            log.error("真实 AI 调用异常 task={} stage={} model={}: {}", taskId, callStage, modelToUse, errMsg);
            saveCallRecordAndAudit(systemId, taskId, resolvePromptId(task, callStage), null,
                    null, modelToUse, currentEstimate, 0, "{}", false, errMsg, duration, callStage);
            throw new BusinessException(errMsg);
        }
        } finally {
            // 释放并发信号量
            aiConcurrencyService.release();
        }
    }

    private boolean isTaskTokenExceeded(int taskUsed, int currentEstimate) {
        return tokenLimitEnabled && taskUsed + currentEstimate > taskTokenLimit;
    }

    private boolean isSystemTokenExceeded(int systemUsed, int currentEstimate) {
        return tokenLimitEnabled && systemUsed + currentEstimate > systemMonthlyTokenLimit;
    }

    /**
     * 从 task 中按 callStage 解析当前使用的 promptId
     * MODULE_HIERARCHY → modularizePromptId
     * MODULE_DOC → documentPromptId
     */
    private Long resolvePromptId(DecompileTask task, String callStage) {
        if (task == null || callStage == null) return null;
        if ("MODULE_DOC".equals(callStage)) return task.getDocumentPromptId();
        return task.getModularizePromptId();
    }

    /**
     * 对 API Key 进行脱敏显示（仅保留前4后4，中间用 *** 替代）
     */
    private String maskKey(String key) {
        if (!StringUtils.hasText(key)) return "(空)";
        if (key.length() <= 12) return key.substring(0, Math.min(2, key.length())) + "***";
        return key.substring(0, 4) + "***" + key.substring(key.length() - 4);
    }

    private static String truncateAiLogReason(String text) {
        if (!StringUtils.hasText(text)) {
            return "unknown";
        }
        String t = text.replace('\n', ' ').trim();
        return t.length() <= 200 ? t : t.substring(0, 200) + "...";
    }

    private void logAiBlock(Long taskId, AiCallMeta callMeta, String reason) {
        if (taskId == null) {
            return;
        }
        String stage = callMeta != null && StringUtils.hasText(callMeta.getCallStage())
                ? callMeta.getCallStage() : "AI";
        String target = callMeta != null && StringUtils.hasText(callMeta.getClassPath())
                ? callMeta.getClassPath() : "-";
        execLog.log(taskId, String.format("[AI-BLOCK] stage=%s target=%s reason=%s",
                stage, target, truncateAiLogReason(reason)));
    }

    /**
     * 文档与模块层级：慢可以，优先等槽拿到许可再调模型；其它 stage 仍 fail-fast。
     */
    private static boolean shouldWaitForAiPermit(String callStage) {
        if (!StringUtils.hasText(callStage)) {
            return false;
        }
        String s = callStage.trim().toUpperCase();
        if ("MODULE_HIERARCHY".equals(s)) {
            return true;
        }
        return s.contains("DOC") || "FUNCTION_DOC".equals(s) || "MODULE_DOC".equals(s);
    }

    /**
     * 对现有 {@code tryAcquire()} 做应用层排队等待，不改动 AiConcurrencyService。
     * <p>超时取 max(httpTimeout×2, acquireWaitSeconds)。层级超时后由 PipelineAiCaller
     * 标记 non-retryable → 当前入口放弃，阶段继续；文档超时走既有失败/降级路径。</p>
     */
    private void acquireAiPermitWithWait(Long taskId, String callStage) {
        int waitSec = Math.max(docBudgetProperties.getHttpTimeoutSeconds() * 2,
                Math.max(60, docBudgetProperties.getAcquireWaitSeconds()));
        long deadlineNs = System.nanoTime() + Duration.ofSeconds(waitSec).toNanos();
        boolean loggedWait = false;
        while (true) {
            try {
                aiConcurrencyService.tryAcquire();
                return;
            } catch (BusinessException e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (!msg.contains("并发已达上限")) {
                    throw e;
                }
                long remainingNs = deadlineNs - System.nanoTime();
                if (remainingNs <= 0) {
                    throw new BusinessException("AI 调用并发等待超时（" + waitSec + "s），请稍后重试");
                }
                if (!loggedWait) {
                    loggedWait = true;
                    log.info("AI 并发已满，应用层排队等待 stage={} timeout={}s", callStage, waitSec);
                    if (taskId != null) {
                        execLog.log(taskId, String.format(
                                "[AI-WAIT] stage=%s waiting for ai.concurrency permit timeout=%ds",
                                callStage, waitSec));
                    }
                }
                try {
                    long pollMs = Math.max(1_000L, docBudgetProperties.getAcquirePollIntervalMs());
                    long sleepMs = Math.min(pollMs, Math.max(50L, TimeUnit.NANOSECONDS.toMillis(remainingNs)));
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException("AI 调用并发等待被中断");
                }
            }
        }
    }

    private java.util.Set<String> parseRemediationModuleIds(DecompileTask task) {
        if (task == null
                || !com.company.codeinsight.modules.knowledge.remediation.KnowledgeRemediationConstants.TRIGGER_SOURCE
                .equals(task.getTriggerSource())
                || !org.springframework.util.StringUtils.hasText(task.getRemediationScopeJson())) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(task.getRemediationScopeJson());
            com.fasterxml.jackson.databind.JsonNode arr = root.get("moduleIds");
            if (arr == null || !arr.isArray()) {
                return null;
            }
            java.util.Set<String> ids = new java.util.LinkedHashSet<>();
            arr.forEach(node -> {
                if (node != null && org.springframework.util.StringUtils.hasText(node.asText())) {
                    ids.add(node.asText().trim());
                }
            });
            return ids.isEmpty() ? null : ids;
        } catch (Exception e) {
            log.warn("parseRemediationModuleIds failed taskId={}: {}", task.getId(), e.getMessage());
            return null;
        }
    }

    private String normalizeModelContent(String aiText) {
        return AiResponseJsonExtractor.stripModelArtifacts(aiText);
    }
}
