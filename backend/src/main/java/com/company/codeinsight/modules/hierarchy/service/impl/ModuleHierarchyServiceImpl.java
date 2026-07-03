package com.company.codeinsight.modules.hierarchy.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.storage.TaskWorkspacePaths;
import com.company.codeinsight.common.util.AiResponseJsonExtractor;
import com.company.codeinsight.common.util.Base62Generator;
import com.company.codeinsight.common.util.PromptTemplateLoader;
import com.company.codeinsight.modules.ai.service.AiSummaryService;
import com.company.codeinsight.modules.ai.service.PipelineAiCaller;
import com.company.codeinsight.modules.businessknowledge.service.BusinessKnowledgeService;
import com.company.codeinsight.modules.entrypoint.model.EntryPoint;
import com.company.codeinsight.modules.entrypoint.model.EntryPointConfig;
import com.company.codeinsight.modules.entrypoint.model.EntrypointMethodView;
import com.company.codeinsight.modules.entrypoint.service.EntrypointReviewService;
import com.company.codeinsight.modules.entrypoint.service.EntryPointDiscoveryService;
import com.company.codeinsight.modules.hierarchy.entity.ModuleHierarchyNode;
import com.company.codeinsight.modules.hierarchy.entity.MethodFunctionBinding;
import com.company.codeinsight.modules.hierarchy.mapper.MethodFunctionBindingMapper;
import com.company.codeinsight.modules.hierarchy.mapper.ModuleHierarchyNodeMapper;
import com.company.codeinsight.modules.hierarchy.model.FunctionDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy;
import com.company.codeinsight.modules.hierarchy.model.PromptViewDtos;
import com.company.codeinsight.modules.hierarchy.model.SubModuleDto;
import com.company.codeinsight.modules.hierarchy.service.ModuleHierarchyService;
import com.company.codeinsight.modules.scanner.model.IncrementalContext;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.company.codeinsight.modules.task.service.TaskExecutionLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 模块层级服务实现
 * - 加载已有节点 → DTO 重建
 * - 入口逐个调 AI → 解析 JSON → 复用/新增 ID → 注入 classPaths
 * - 全量重写表（delete + batch insert）保证幂等
 */
@Slf4j
@Service
public class ModuleHierarchyServiceImpl implements ModuleHierarchyService {

    /**
     * 模块提取提示词的 classpath 兜底文件路径
     * <p>仅当 DB 中所有 MODULARIZE 提示词都不可用（如未执行 seed）时使用，保证旧任务也能跑通。</p>
     */
    private static final String MODULARIZE_PROMPT_FALLBACK_PATH = "analyze_prompt.md";

    private static final String LEVEL_MODULE = "MODULE";
    private static final String LEVEL_SUB_MODULE = "SUB_MODULE";
    private static final String LEVEL_FUNCTION = "FUNCTION";

    @Autowired
    private ModuleHierarchyNodeMapper nodeMapper;

    @Autowired
    private MethodFunctionBindingMapper methodFunctionBindingMapper;

    @Autowired
    private com.company.codeinsight.modules.callchain.mapper.MethodCallMapper methodCallMapper;

    @Autowired
    private DecompileTaskMapper taskMapper;

    @Autowired
    private EntryPointDiscoveryService entryPointDiscoveryService;

    @Autowired
    private EntrypointReviewService entrypointReviewService;

    @Autowired
    @Lazy
    private AiSummaryService aiSummaryService;

    @Autowired
    private PipelineAiCaller pipelineAiCaller;

    @Autowired
    private TaskExecutionLogger execLog;

    @Autowired
    private PromptTemplateLoader promptTemplateLoader;

    @Autowired
    private Base62Generator base62Generator;

    @Autowired
    private com.company.codeinsight.modules.prompt.service.DecompilePromptService decompilePromptService;

    @Autowired
    private BusinessKnowledgeService businessKnowledgeService;

    @Autowired
    private TaskWorkspacePaths taskWorkspacePaths;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** AI 调用专用线程池，并发度由 {@code code-insight.ai.hierarchy-parallelism} 控制，避免打爆 LLM API */
    @Value("${code-insight.ai.hierarchy-parallelism:4}")
    private int hierarchyParallelism;

    private ExecutorService aiExecutor;

    @PostConstruct
    public void initAiExecutor() {
        aiExecutor = Executors.newFixedThreadPool(hierarchyParallelism, r -> {
            Thread t = new Thread(r, "hierarchy-ai-");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModuleHierarchy buildAndPersist(Long taskId, File projectDir) {
        return buildAndPersist(taskId, projectDir, IncrementalContext.fullScan());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModuleHierarchy buildAndPersist(Long taskId, File projectDir, IncrementalContext ctx) {
        return buildAndPersist(taskId, projectDir, ctx, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModuleHierarchy buildAndPersist(Long taskId, File projectDir, IncrementalContext ctx,
                                           com.company.codeinsight.modules.callchain.model.IncrementalImpact impact) {
        if (taskId == null) {
            throw new BusinessException("taskId 不能为空");
        }
        DecompileTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在: " + taskId);
        }
        IncrementalContext effective = ctx == null ? IncrementalContext.fullScan() : ctx;

        // 1. 加载已有节点 → 重建 DTO
        ModuleHierarchy hierarchy = loadByTaskId(taskId);
        hierarchy.setTaskId(taskId);
        hierarchy.setSystemId(task.getSystemId());

        // 2. 读取已落表的入口（用户在 ENTRYPOINT_REVIEW 阶段确认后的快照）；
        //    这里不再做入口识别与 EntryPointConfig 解析——由 ENTRYPOINT_REVIEW 阶段统一负责并落表。
        List<EntryPoint> entries = entrypointReviewService.loadEnabledEntries(taskId);
        Map<String, List<EntrypointMethodView>> methodsByClass = entrypointReviewService.loadMethodsByClassName(taskId);
        log.info("ModuleHierarchyService.buildAndPersist taskId={} entries={} ctx={}", taskId, entries.size(), effective);

        // 3. 加载 prompt 模板（仅一次，必须来自任务快照的提示词绑定）
        String promptTemplate = decompilePromptService.requireTaskPromptContent(task,
                com.company.codeinsight.modules.prompt.entity.DecompilePrompt.TYPE_MODULARIZE);

        // 4. 收集需处理的入口（增量模式下跳过未变更入口）
        int skippedByIncremental = 0;
        List<EntryPoint> toProcess = new ArrayList<>();
        for (EntryPoint entry : entries) {
            if (effective.isIncremental() && impact != null && impact.isIncremental()) {
                if (!impact.isHierarchyRetarget(entry)) {
                    skippedByIncremental++;
                } else {
                    toProcess.add(entry);
                }
            } else if (effective.isIncremental() && !effective.isPathChanged(entry.getFilePath())) {
                skippedByIncremental++;
            } else {
                toProcess.add(entry);
            }
        }

        int processedByAi = 0;
        if (!toProcess.isEmpty()) {
            // 4a. 并行调用 AI（每个入口独立请求，I/O 密集型，线程池控制并发度为 4）
            final String finalPrompt = promptTemplate;
            final com.company.codeinsight.modules.entrypoint.model.EntryPointConfig finalConfig =
                    entrypointReviewService.resolveConfig(task);
            final DecompileTask finalTask = task;
            final File finalProjectDir = projectDir;
            List<CompletableFuture<JsonNode>> futures = toProcess.stream()
                    .map(entry -> CompletableFuture.supplyAsync(
                            () -> callAiForEntry(finalTask, entry, finalPrompt, finalProjectDir, finalConfig),
                            aiExecutor))
                    .toList();

            // 4b. 顺序合并结果到 DTO（共享 hierarchy 需要单线程写入）
            for (int i = 0; i < futures.size(); i++) {
                JsonNode inc = futures.get(i).join();
                if (inc != null) {
                    mergeEntryResult(hierarchy, toProcess.get(i), inc, methodsByClass);
                    persistMethodBindingsFromIncrement(taskId, task.getSystemId(),
                            toProcess.get(i), inc, methodsByClass);
                    processedByAi++;
                }
            }
        }

        backfillMethodSignaturesFromEntrypoints(hierarchy, methodsByClass);

        // 5. 增量模式：清理被删除文件对应的 classPath 引用
        if (effective.isIncremental() && !effective.getDeletedPaths().isEmpty()) {
            int removedRefs = purgeDeletedClassPaths(hierarchy, effective.getDeletedPaths());
            log.info("增量清理 — 任务 {} 删除 {} 个文件，从层级中移除 {} 个 classPath 引用",
                    taskId, effective.getDeletedPaths().size(), removedRefs);
        }

        // 6. 全量重写（保证幂等；增量模式下未变节点仍会被原样重写）
        persistAll(taskId, task.getSystemId(), hierarchy);

        log.info("ModuleHierarchyService.buildAndPersist done. taskId={} modules={} functions={} aiCalls={} skipped={}",
                taskId, hierarchy.getModules().size(), countFunctions(hierarchy), processedByAi, skippedByIncremental);
        return hierarchy;
    }

    /**
     * 增量模式辅助：把被删除文件的 FQ 类名从所有 function.classPaths 中移除。
     * 不会删除 function 节点本身（其他入口可能仍引用同一 function）；
     * 若某 function 移除后 classPaths 为空，自动清空集合让前端能感知到「无入口归属」。
     *
     * @return 实际移除的 classPath 引用数
     */
    private int purgeDeletedClassPaths(ModuleHierarchy hierarchy, Set<String> deletedPaths) {
        if (hierarchy == null || deletedPaths == null || deletedPaths.isEmpty()) {
            return 0;
        }
        Set<String> deletedFqSet = new HashSet<>();
        for (String p : deletedPaths) {
            String fq = deriveFqcnFromPath(p);
            if (StringUtils.hasText(fq)) {
                deletedFqSet.add(fq);
            }
        }
        if (deletedFqSet.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (ModuleDto m : hierarchy.getModules().values()) {
            for (SubModuleDto sm : m.getSubModules().values()) {
                for (FunctionDto fn : sm.getFunctions().values()) {
                    if (fn.getClassPaths() == null || fn.getClassPaths().isEmpty()) {
                        continue;
                    }
                    int before = fn.getClassPaths().size();
                    fn.getClassPaths().removeAll(deletedFqSet);
                    removed += before - fn.getClassPaths().size();
                }
            }
        }
        return removed;
    }

    /**
     * 从 Maven/Gradle 约定的源码相对路径推导出 FQ 类名。
     * 例如 {@code src/main/java/com/demo/UserService.java} → {@code com.demo.UserService}。
     * 无法识别（无 src/main/java 前缀、非 .java 文件）时返回 null。
     * <p>public 以便 AI 草稿阶段等跨包调用方复用同一份推导规则。
     */
    public static String deriveFqcnFromPath(String relativePath) {
        if (!StringUtils.hasText(relativePath) || !relativePath.endsWith(".java")) {
            return null;
        }
        String normalized = relativePath.replace('\\', '/');
        String[] prefixes = {"src/main/java/", "src/test/java/", "src/"};
        for (String pfx : prefixes) {
            if (normalized.startsWith(pfx)) {
                normalized = normalized.substring(pfx.length());
                break;
            }
        }
        normalized = normalized.substring(0, normalized.length() - ".java".length());
        return normalized.replace('/', '.');
    }

    @Override
    public ModuleHierarchy loadByTaskId(Long taskId) {
        ModuleHierarchy hierarchy = new ModuleHierarchy();
        hierarchy.setTaskId(taskId);

        List<ModuleHierarchyNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<ModuleHierarchyNode>()
                        .eq(ModuleHierarchyNode::getTaskId, taskId)
                        .orderByAsc(ModuleHierarchyNode::getId)
        );
        if (nodes.isEmpty()) {
            return hierarchy;
        }
        hierarchy.setSystemId(nodes.get(0).getSystemId());

        // 用 id 索引，方便后续按 parent_id 关联
        Map<Long, ModuleHierarchyNode> idToNode = new HashMap<>();
        for (ModuleHierarchyNode n : nodes) {
            idToNode.put(n.getId(), n);
        }

        for (ModuleHierarchyNode n : nodes) {
            if (LEVEL_MODULE.equals(n.getLevel())) {
                ModuleDto m = new ModuleDto();
                m.setId(n.getNodeId());
                m.setModuleName(n.getName());
                m.setKeywords(parseJsonArray(n.getKeywords()));
                m.setConfirmed(n.getConfirmed());
                hierarchy.getModules().put(m.getId(), m);
            } else if (LEVEL_SUB_MODULE.equals(n.getLevel()) && n.getParentId() != null) {
                ModuleHierarchyNode parent = idToNode.get(n.getParentId());
                if (parent != null && LEVEL_MODULE.equals(parent.getLevel())) {
                    ModuleDto m = hierarchy.getModules().get(parent.getNodeId());
                    if (m == null) {
                        // 父级缺失（异常数据），跳过
                        continue;
                    }
                    SubModuleDto sm = new SubModuleDto();
                    sm.setId(n.getNodeId());
                    sm.setSubModuleName(n.getName());
                    sm.setKeywords(parseJsonArray(n.getKeywords()));
                    sm.setConfirmed(n.getConfirmed());
                    m.getSubModules().put(sm.getId(), sm);
                }
            } else if (LEVEL_FUNCTION.equals(n.getLevel()) && n.getParentId() != null) {
                ModuleHierarchyNode parent = idToNode.get(n.getParentId());
                if (parent != null && LEVEL_SUB_MODULE.equals(parent.getLevel())) {
                    ModuleHierarchyNode grand = idToNode.get(parent.getParentId());
                    if (grand == null || !LEVEL_MODULE.equals(grand.getLevel())) {
                        continue;
                    }
                    ModuleDto m = hierarchy.getModules().get(grand.getNodeId());
                    SubModuleDto sm = m == null ? null : m.getSubModules().get(parent.getNodeId());
                    if (m == null || sm == null) {
                        continue;
                    }
                    FunctionDto fn = new FunctionDto();
                    fn.setId(n.getNodeId());
                    fn.setFunctionName(n.getName());
                    fn.setClassPaths(new LinkedHashSet<>(parseJsonArray(n.getClassPaths())));
                    fn.setMethodSignatures(new LinkedHashSet<>(parseJsonArray(n.getMethodSignatures())));
                    fn.setConfirmed(n.getConfirmed());
                    sm.getFunctions().put(fn.getId(), fn);
                }
            }
        }
        return hierarchy;
    }

    // ============================ private helpers ============================

    /**
     * 并行阶段：对单个入口渲染 prompt 并调 AI（含可配置重试），返回解析后的 JSON 节点。
     * 失败时写入 pipeline.log 并返回 null。
     */
    private JsonNode callAiForEntry(DecompileTask task, EntryPoint entry,
                                    String promptTemplate, File projectDir,
                                    EntryPointConfig entryPointConfig) {
        Long taskId = task.getId();
        String entryLabel = entry.getClassName();
        try {
            String javaCode = entryPointDiscoveryService.readEntrySource(projectDir, entry, entryPointConfig);
            if (!StringUtils.hasText(javaCode)) {
                log.warn("入口 {} 无可读源文件或命中排除规则，跳过", entryLabel);
                execLog.log(taskId, "[AI-SKIP] stage=MODULE_HIERARCHY target=" + entryLabel + " reason=no readable source");
                return null;
            }

            String businessKnowledge = businessKnowledgeService.getContentBySystemId(task.getSystemId());
            String promptInput = promptTemplateLoader.render(promptTemplate, javaCode, businessKnowledge, "{}");
            if (promptTemplateLoader.hasUnresolvedPlaceholders(promptInput)) {
                log.warn("Prompt 仍有未替换占位符，跳过入口 {}", entryLabel);
                execLog.log(taskId, "[AI-SKIP] stage=MODULE_HIERARCHY target=" + entryLabel + " reason=unresolved prompt placeholders");
                return null;
            }

            AiSummaryService.AiCallMeta meta = new AiSummaryService.AiCallMeta();
            meta.setCallStage("MODULE_HIERARCHY");
            meta.setClassPath(entryLabel);

            String aiPayload = pipelineAiCaller.callWithRetry(
                    taskId,
                    "MODULE_HIERARCHY",
                    entryLabel,
                    promptInput,
                    task.getModelName(),
                    meta,
                    response -> {
                        if (!StringUtils.hasText(response) || "{}".equals(response.trim())) {
                            return PipelineAiCaller.ValidationResult.fail("empty response");
                        }
                        try {
                            String cleaned = AiResponseJsonExtractor.extractJsonPayload(response);
                            objectMapper.readTree(cleaned);
                            return PipelineAiCaller.ValidationResult.ok(cleaned);
                        } catch (Exception e) {
                            return PipelineAiCaller.ValidationResult.fail("JSON parse: " + e.getMessage());
                        }
                    },
                    (original, current, failedAttempt, reason) -> original
                            + "\n\n[系统提示] 上轮输出 JSON 解析失败：" + reason
                            + "\n请确保输出是合法的 JSON 格式（用 ```json ... ``` 包裹），字段约束见上方模板。"
            );

            if (!StringUtils.hasText(aiPayload) || "{}".equals(aiPayload.trim())) {
                return null;
            }
            return objectMapper.readTree(aiPayload);
        } catch (Exception e) {
            log.error("callAiForEntry failed for {}: {}", entryLabel, e.getMessage(), e);
            execLog.log(taskId, "[AI-FAIL] stage=MODULE_HIERARCHY target=" + entryLabel
                    + " reason=unexpected: " + e.getMessage());
        }
        return null;
    }

    private void mergeEntryResult(ModuleHierarchy hierarchy, EntryPoint entry, JsonNode inc,
                                  Map<String, List<EntrypointMethodView>> methodsByClass) {
        Set<String> newlyCreatedFunctionIds = new LinkedHashSet<>();
        mergeIncrementIntoHierarchy(hierarchy, inc, newlyCreatedFunctionIds);

        for (String fnId : newlyCreatedFunctionIds) {
            FunctionDto fn = findFunctionById(hierarchy, fnId);
            if (fn != null) {
                fn.getClassPaths().add(entry.getClassName());
                backfillFunctionMethodSignatures(fn, methodsByClass);
            }
        }
    }

    /**
     * 对 method_signatures 为空的功能节点，从 ci_entrypoint.methods_json 按 class_paths 兜底注入。
     * <p>2026-07 反向索引迁移：仅当 {@code fn.classPaths} <b>也</b>为空时（AI 完全没给 class 提示），
     * 才用入口的全集方法填充，否则直接跳过——避免 AI 给了 class_paths 但漏 method_signatures 时被全集污染。
     * <ul>
     *   <li>AI 输出 classPaths = ["UserController"] + methodSignatures = [...] → 不进 backfill，
     *       binding 表才是权威（笛卡尔积合法元组被交叉校验保留）</li>
     *   <li>AI 输出 classPaths = []（罕见，可能 deprecated schema/漏填）→ 仍走老回退，避免完全没数据</li>
     * </ul>
     */
    private void backfillMethodSignaturesFromEntrypoints(ModuleHierarchy hierarchy,
                                                         Map<String, List<EntrypointMethodView>> methodsByClass) {
        if (hierarchy == null || methodsByClass == null || methodsByClass.isEmpty()) return;
        for (ModuleDto m : hierarchy.getModules().values()) {
            for (SubModuleDto sm : m.getSubModules().values()) {
                for (FunctionDto fn : sm.getFunctions().values()) {
                    if (fn.getMethodSignatures() == null || fn.getMethodSignatures().isEmpty()) {
                        if (fn.getClassPaths() == null || fn.getClassPaths().isEmpty()) {
                            backfillFunctionMethodSignatures(fn, methodsByClass);
                        }
                        // classPaths 非空 + methodSignatures 空 → 不再污染回填，靠 binding 表驱动 BFS
                    }
                }
            }
        }
    }

    /**
     * 解析 AI 增量 JSON 中已合并入 DTO 树（{@code modules[].sub_modules[].functions[]}）的
     * 功能节点，并把每个 function 的 {@code class_paths × method_signatures} 笛卡尔积成
     * 反向绑定行写入 {@code ci_method_function_binding}。
     *
     * <p>关键设计（2026-07 反向索引方案，与 analyze_prompt.md 输出结构解耦）：</p>
     * <ul>
     *   <li>AI 输出 schema 仍然是 {@code modules[].sub_modules[].functions[].class_paths[] + method_signatures[]}，
     *       我们不再改 prompt 的输出契约——这是用户的"根基"约束</li>
     *   <li>对每个 function，把 {@code class_paths[i] × method_signatures[j]} 笛卡尔积成 N 条 (class, sig) 元组</li>
     *   <li>把每个 (class, sig) 元组与 {@code ci_method_call.caller_signature} 做存在性交叉校验——
     *       数据库里没有这条 caller 边的鬼魂元组会被丢弃并 warn</li>
     *   <li>校验通过的元组按 (module, sub_module, function) 三段 Base62 ID 落表；
     *       ID 是从 {@code mergeIncrementIntoHierarchy} 后的 {@code ModuleHierarchy} DTO 里取，
     *       而不是从 AI JSON 里读，因此不可能出现 ID 不全的丢弃路径</li>
     * </ul>
     *
     * <p>净效果：BFS 沿 binding 表走，源头不再是 {@code ci_module_hierarchy.method_signatures}
     * 的"全集污染"；AI 输出契约保持不变。</p>
     */
    private void persistMethodBindingsFromIncrement(Long taskId, Long systemId,
                                                    EntryPoint entry,
                                                    JsonNode increment,
                                                    Map<String, List<EntrypointMethodView>> methodsByClass) {
        if (taskId == null || entry == null || increment == null || methodFunctionBindingMapper == null) {
            return;
        }
        // 入口类本身的限定（本入口外的 class 不做白名单限制——并行阶段其他入口已落库）
        String entryClassName = entry.getClassName();

        JsonNode modulesNode = increment.path("modules");
        if (!modulesNode.isArray()) {
            return;
        }
        // 一次 SQL 收集 task 内所有现存 caller_signature 的全集，作为白名单存在性校验
        Set<String> candidateCrossCheck = collectAllKnownCallerSignaturesFromCallGraph(taskId);

        List<MethodFunctionBinding> rows = new ArrayList<>();
        int skippedEmptyCartesian = 0;
        int skippedNotExisting = 0;
        for (JsonNode modNode : modulesNode) {
            JsonNode subs = modNode.path("sub_modules");
            if (!subs.isArray()) continue;
            for (JsonNode subNode : subs) {
                JsonNode fns = subNode.path("functions");
                if (!fns.isArray()) continue;
                for (JsonNode fnNode : fns) {
                    String moduleId = modNode.path("id").asText("").trim();
                    String subModuleId = subNode.path("id").asText("").trim();
                    String functionId = fnNode.path("id").asText("").trim();
                    if (!StringUtils.hasText(moduleId)
                            || !StringUtils.hasText(subModuleId)
                            || !StringUtils.hasText(functionId)) {
                        log.warn("modules 跳过 ID 不全 (entry={}): moduleId/subModuleId/functionId 至少一段缺失",
                                entryClassName);
                        continue;
                    }
                    Set<String> classPaths = collectAsTextSet(fnNode.path("class_paths"));
                    Set<String> methodSigs = collectAsTextSet(fnNode.path("method_signatures"));
                    if (classPaths.isEmpty() || methodSigs.isEmpty()) {
                        skippedEmptyCartesian++;
                        continue;
                    }
                    // 笛卡尔积 → (class, sig) 元组
                    int before = rows.size();
                    for (String cp : classPaths) {
                        for (String sig : methodSigs) {
                            String full = cp + "#" + sig;
                            // 与 ci_method_call 交叉校验：仅保留 DB 真有 caller 边的元组
                            if (!candidateCrossCheck.isEmpty() && !candidateCrossCheck.contains(full)) {
                                skippedNotExisting++;
                                log.warn("modules 元组不存在于 ci_method_call, 跳过 (entry={}, tuple={})",
                                        entryClassName, full);
                                continue;
                            }
                            MethodFunctionBinding row = new MethodFunctionBinding();
                            row.setTaskId(taskId);
                            row.setSystemId(systemId);
                            row.setModuleNodeId(moduleId);
                            row.setSubModuleNodeId(subModuleId);
                            row.setFunctionNodeId(functionId);
                            row.setClassName(cp);
                            row.setMethodSignature(sig);
                            row.setSource("AI");
                            rows.add(row);
                        }
                    }
                    if (rows.size() == before) {
                        log.info("entry={} 的 function={} 笛卡尔后所有元组都被交叉校验剔除",
                                entryClassName, functionId);
                    }
                }
            }
        }
        if (rows.isEmpty()) {
            if (skippedNotExisting > 0 || skippedEmptyCartesian > 0) {
                log.info("入口 {} binding 入库为空（笛卡尔空 {} 条, call-graph 不存在 {} 条）",
                        entryClassName, skippedEmptyCartesian, skippedNotExisting);
            }
            return;
        }
        int upserted = methodFunctionBindingMapper.batchUpsertBindings(rows);
        log.info("入口 {} binding 入库: 笛卡尔+交叉校验后 {} 行, upsert {} 行 (call-graph 不存在 {} 条)",
                entryClassName, rows.size(), upserted, skippedNotExisting);
    }

    /** 收集 JSON 数组节点的文本值到 Set（trim 后非空） */
    private Set<String> collectAsTextSet(JsonNode arrayNode) {
        Set<String> out = new LinkedHashSet<>();
        if (arrayNode == null || !arrayNode.isArray()) return out;
        for (JsonNode n : arrayNode) {
            String v = n.asText("");
            if (StringUtils.hasText(v)) out.add(v.trim());
        }
        return out;
    }

    /**
     * 取该任务下 {@code ci_method_call} 的全部 caller_signature，作为反向绑定交叉校验的白名单。
     * <p>实现方式：通过 {@link MethodCallMapper} 的 {@code selectExistingCallerSignatures}
     * 一次查询汇总。表为空（新任务还未持久化 AST）或查询失败时返回空集合——空集合视为"跳过交叉校验"
     * 而不是"全部丢弃"，避免回退路径被锁死。</p>
     */
    private Set<String> collectAllKnownCallerSignaturesFromCallGraph(Long taskId) {
        Set<String> known = new HashSet<>();
        try {
            // 一次查询拿全部：把 task 内所有 caller_signature 选中。我们约定一次性查 ≤ 5k 行；
            // 超出时分批，避免内存爆。本 MVP 阶段简单取首 5000 行。
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.company.codeinsight.modules.callchain.entity.MethodCall> all =
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
            all.eq(com.company.codeinsight.modules.callchain.entity.MethodCall::getTaskId, taskId)
               .isNotNull(com.company.codeinsight.modules.callchain.entity.MethodCall::getCallerSignature)
               .select(com.company.codeinsight.modules.callchain.entity.MethodCall::getCallerSignature)
               .last("LIMIT 5000");
            List<com.company.codeinsight.modules.callchain.entity.MethodCall> rows = methodCallMapper.selectList(all);
            for (com.company.codeinsight.modules.callchain.entity.MethodCall row : rows) {
                if (row.getCallerSignature() != null) {
                    known.add(row.getCallerSignature());
                }
            }
        } catch (Exception e) {
            log.warn("collectAllKnownCallerSignaturesFromCallGraph 失败, 回退为跳过交叉校验: {}", e.getMessage());
        }
        return known;
    }

    private void backfillFunctionMethodSignatures(FunctionDto fn,
                                                  Map<String, List<EntrypointMethodView>> methodsByClass) {
        if (fn == null || methodsByClass == null) return;
        if (fn.getMethodSignatures() == null) {
            fn.setMethodSignatures(new LinkedHashSet<>());
        }
        if (!fn.getMethodSignatures().isEmpty()) return;
        if (fn.getClassPaths() == null || fn.getClassPaths().isEmpty()) return;
        for (String classPath : fn.getClassPaths()) {
            List<EntrypointMethodView> methods = methodsByClass.get(classPath);
            if (methods == null) continue;
            for (EntrypointMethodView mv : methods) {
                String sig = toFunctionMethodSignature(mv, classPath);
                if (StringUtils.hasText(sig)) {
                    fn.getMethodSignatures().add(sig.trim());
                }
            }
        }
    }

    /** 将入口复核 methods_json 中的签名转为 FunctionDto 格式：methodName(ParamTypes) */
    private String toFunctionMethodSignature(EntrypointMethodView mv, String classPath) {
        if (mv == null) return null;
        if (StringUtils.hasText(mv.getMethodSignature())) {
            String raw = mv.getMethodSignature().trim();
            int hash = raw.indexOf('#');
            if (hash >= 0 && hash < raw.length() - 1) {
                return raw.substring(hash + 1);
            }
            return raw;
        }
        if (StringUtils.hasText(mv.getMethodName())) {
            return mv.getMethodName().trim();
        }
        return null;
    }

    /**
     * 把 AI 返回的增量 JSON 合并进 DTO（复用已有 ID / 不存在则生成）
     * @param newlyCreatedFunctionIds 输出本次新增的 function id 列表
     */
    private void mergeIncrementIntoHierarchy(ModuleHierarchy hierarchy, JsonNode increment,
                                             Set<String> newlyCreatedFunctionIds) {
        JsonNode modulesNode = increment.path("modules");
        if (!modulesNode.isArray()) {
            return;
        }
        Set<String> existingModuleIds = new HashSet<>(hierarchy.getModules().keySet());
        Set<String> existingSubModuleIds = new HashSet<>();
        Set<String> existingFunctionIds = new HashSet<>();
        for (ModuleDto m : hierarchy.getModules().values()) {
            existingSubModuleIds.addAll(m.getSubModules().keySet());
            for (SubModuleDto sm : m.getSubModules().values()) {
                existingFunctionIds.addAll(sm.getFunctions().keySet());
            }
        }

        for (JsonNode modNode : modulesNode) {
            String modId = modNode.path("id").asText("");
            if (!StringUtils.hasText(modId)) continue;
            // 归一化 AI 输出的 ID：后端约定 prefix + 4 位 Base62 = 5 位总长，
            // 但 AI 可能按 "5 位 Base62" 字面理解为 prefix + 5 位 = 6 位，
            // 此处统一截断为 5 位后再做冲突检测与复用。
            String rawModId = modId;
            modId = normalizeAiNodeId(modId, 'm', hierarchy.getModules().keySet());
            ModuleDto module = hierarchy.getModules().get(modId);
            if (module == null) {
                // 归一化后未匹配到模块：检查原始截断前的 ID（AI 输出的原值）是否存在于 map 中。
                // 场景：DB 中有历史遗留的 6 位 ID（如 mA1b2C），AI 本次输出同样的 6 位值，
                // 归一化截断为 5 位 (mA1b2) 后与 map 中的 key (mA1b2C) 不一致。
                // 此时应将遗留模块重命名到归一化后的 5 位 ID，避免 persistAll 保存重复。
                if (!modId.equals(rawModId)) {
                    module = hierarchy.getModules().get(rawModId);
                    if (module != null) {
                        log.info("将历史遗留 6 位模块 ID {} 重命名为 5 位 ID {}", rawModId, modId);
                        hierarchy.getModules().remove(rawModId);
                    }
                }
                if (module == null) {
                    module = new ModuleDto();
                }
                module.setId(modId);
                hierarchy.getModules().put(modId, module);
            }
            if (StringUtils.hasText(modNode.path("module_name").asText(""))) {
                module.setModuleName(modNode.path("module_name").asText());
            }
            mergeKeywords(module.getKeywords(), modNode.path("keywords"));

            JsonNode subsNode = modNode.path("sub_modules");
            if (!subsNode.isArray()) continue;
            for (JsonNode subNode : subsNode) {
                String subId = subNode.path("id").asText("");
                if (!StringUtils.hasText(subId)) continue;
                String rawSubId = subId;
                subId = normalizeAiNodeId(subId, 's', existingSubModuleIds);
                if (existingSubModuleIds.contains(subId) && !module.getSubModules().containsKey(subId)) {
                    log.warn("AI 输出子模块 ID {} 与同任务其他模块冲突，重新生成", subId);
                    subId = base62Generator.generateUnique('s', existingSubModuleIds);
                }
                SubModuleDto sub = module.getSubModules().get(subId);
                if (sub == null) {
                    // 兼容历史遗留 6 位 ID：查原始截断前的 key
                    if (!subId.equals(rawSubId)) {
                        sub = module.getSubModules().get(rawSubId);
                        if (sub != null) {
                            log.info("将历史遗留 6 位子模块 ID {} 重命名为 5 位 ID {}", rawSubId, subId);
                            module.getSubModules().remove(rawSubId);
                        }
                    }
                    if (sub == null) {
                        sub = new SubModuleDto();
                    }
                    sub.setId(subId);
                    module.getSubModules().put(subId, sub);
                    existingSubModuleIds.add(subId);
                }
                if (StringUtils.hasText(subNode.path("sub_module_name").asText(""))) {
                    sub.setSubModuleName(subNode.path("sub_module_name").asText());
                }
                mergeKeywords(sub.getKeywords(), subNode.path("keywords"));

                JsonNode fnsNode = subNode.path("functions");
                if (!fnsNode.isArray()) continue;
                for (JsonNode fnNode : fnsNode) {
                    String fnId = fnNode.path("id").asText("");
                    if (!StringUtils.hasText(fnId)) continue;
                    String rawFnId = fnId;
                    fnId = normalizeAiNodeId(fnId, 'f', existingFunctionIds);
                    if (existingFunctionIds.contains(fnId) && !sub.getFunctions().containsKey(fnId)) {
                        log.warn("AI 输出功能 ID {} 与同任务其他子模块冲突，重新生成", fnId);
                        fnId = base62Generator.generateUnique('f', existingFunctionIds);
                    }
                    FunctionDto fn = sub.getFunctions().get(fnId);
                    if (fn == null) {
                        // 兼容历史遗留 6 位 ID：查原始截断前的 key
                        if (!fnId.equals(rawFnId)) {
                            fn = sub.getFunctions().get(rawFnId);
                            if (fn != null) {
                                log.info("将历史遗留 6 位功能 ID {} 重命名为 5 位 ID {}", rawFnId, fnId);
                                sub.getFunctions().remove(rawFnId);
                            }
                        }
                        if (fn == null) {
                            fn = new FunctionDto();
                        }
                        fn.setId(fnId);
                        sub.getFunctions().put(fnId, fn);
                        existingFunctionIds.add(fnId);
                        newlyCreatedFunctionIds.add(fnId);
                    }
                    if (StringUtils.hasText(fnNode.path("function_name").asText(""))) {
                        fn.setFunctionName(fnNode.path("function_name").asText());
                    }
                    // 解析 AI 输出的 class_paths（不再由程序兜底，但保留 processEntry 的兜底注入逻辑兼容旧数据）
                    mergeClassPaths(fn, fnNode.path("class_paths"));
                    // 解析 AI 输出的 method_signatures
                    mergeMethodSignatures(fn, fnNode.path("method_signatures"));
                }
            }
        }

        // 兼容：AI 输出的 modules[].functions 直接挂在 module 下时（罕见），归到默认 sub_module
        // 当前需求不覆盖此情况，保持简单
    }

    private void mergeKeywords(List<String> existing, JsonNode keywordsNode) {
        if (existing == null || !keywordsNode.isArray()) return;
        for (JsonNode kw : keywordsNode) {
            String v = kw.asText();
            if (StringUtils.hasText(v) && !existing.contains(v)) {
                existing.add(v);
            }
        }
    }

    /**
     * 合并 AI 输出的 class_paths 到 FunctionDto.classPaths
     * null/空/非数组都安全忽略；trim 去除多余空白
     */
    private void mergeClassPaths(FunctionDto fn, JsonNode classPathsNode) {
        if (fn == null || classPathsNode == null || !classPathsNode.isArray()) return;
        for (JsonNode cp : classPathsNode) {
            String v = cp.asText();
            if (StringUtils.hasText(v)) {
                fn.getClassPaths().add(v.trim());
            }
        }
    }

    /**
     * 合并 AI 输出的 method_signatures 到 FunctionDto.methodSignatures
     * 格式约束：methodName(ParamType1, ParamType2)，不含返回类型
     * null/空/非数组都安全忽略；trim 去除多余空白
     */
    private void mergeMethodSignatures(FunctionDto fn, JsonNode methodSignaturesNode) {
        if (fn == null || methodSignaturesNode == null || !methodSignaturesNode.isArray()) return;
        for (JsonNode sig : methodSignaturesNode) {
            String v = sig.asText();
            if (StringUtils.hasText(v)) {
                fn.getMethodSignatures().add(v.trim());
            }
        }
    }

    private FunctionDto findFunctionById(ModuleHierarchy hierarchy, String functionId) {
        for (ModuleDto m : hierarchy.getModules().values()) {
            for (SubModuleDto sm : m.getSubModules().values()) {
                FunctionDto fn = sm.getFunctions().get(functionId);
                if (fn != null) return fn;
            }
        }
        return null;
    }

    /**
     * 全量重写：先 deleteByTaskId，再把 DTO 树展开为 3 行结构批量 insert
     */
    private void persistAll(Long taskId, Long systemId, ModuleHierarchy hierarchy) {
        nodeMapper.delete(new LambdaQueryWrapper<ModuleHierarchyNode>().eq(ModuleHierarchyNode::getTaskId, taskId));
        if (hierarchy.getModules().isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<ModuleHierarchyNode> rows = new ArrayList<>();
        for (ModuleDto m : hierarchy.getModules().values()) {
            ModuleHierarchyNode modRow = new ModuleHierarchyNode();
            modRow.setTaskId(taskId);
            modRow.setSystemId(systemId);
            modRow.setLevel(LEVEL_MODULE);
            modRow.setParentId(null);
            modRow.setNodeId(m.getId());
            modRow.setName(m.getModuleName());
            modRow.setKeywords(serializeJsonArray(m.getKeywords()));
            modRow.setClassPaths(null);
            modRow.setConfirmed(Boolean.TRUE.equals(m.getConfirmed()));
            modRow.setCreatedAt(now);
            modRow.setUpdatedAt(now);
            rows.add(modRow);
        }
        nodeMapper.batchInsert(rows);
        // 拿到 module 行 ID 后才能填 sub_module.parentId
        Map<String, Long> moduleRowIdByNodeId = new HashMap<>();
        // 重新查一遍（按 nodeId 索引）
        List<ModuleHierarchyNode> insertedModules = nodeMapper.selectList(
                new LambdaQueryWrapper<ModuleHierarchyNode>()
                        .eq(ModuleHierarchyNode::getTaskId, taskId)
                        .eq(ModuleHierarchyNode::getLevel, LEVEL_MODULE)
        );
        for (ModuleHierarchyNode n : insertedModules) {
            moduleRowIdByNodeId.put(n.getNodeId(), n.getId());
        }

        List<ModuleHierarchyNode> subRows = new ArrayList<>();
        Set<String> seenSubNodeIds = new HashSet<>();
        for (ModuleDto m : hierarchy.getModules().values()) {
            Long parentRowId = moduleRowIdByNodeId.get(m.getId());
            if (parentRowId == null) continue;
            for (SubModuleDto sm : m.getSubModules().values()) {
                if (!seenSubNodeIds.add(sm.getId())) {
                    log.warn("persistAll 跳过重复子模块 node_id={}, taskId={}", sm.getId(), taskId);
                    continue;
                }
                ModuleHierarchyNode subRow = new ModuleHierarchyNode();
                subRow.setTaskId(taskId);
                subRow.setSystemId(systemId);
                subRow.setLevel(LEVEL_SUB_MODULE);
                subRow.setParentId(parentRowId);
                subRow.setNodeId(sm.getId());
                subRow.setName(sm.getSubModuleName());
                subRow.setKeywords(serializeJsonArray(sm.getKeywords()));
                subRow.setClassPaths(null);
                subRow.setConfirmed(Boolean.TRUE.equals(sm.getConfirmed()));
                subRow.setCreatedAt(now);
                subRow.setUpdatedAt(now);
                subRows.add(subRow);
            }
        }
        if (!subRows.isEmpty()) {
            nodeMapper.batchInsert(subRows);
        }

        // 重新查 sub_module 行 ID
        Map<String, Long> subModuleRowIdByNodeId = new HashMap<>();
        List<ModuleHierarchyNode> insertedSubs = nodeMapper.selectList(
                new LambdaQueryWrapper<ModuleHierarchyNode>()
                        .eq(ModuleHierarchyNode::getTaskId, taskId)
                        .eq(ModuleHierarchyNode::getLevel, LEVEL_SUB_MODULE)
        );
        for (ModuleHierarchyNode n : insertedSubs) {
            subModuleRowIdByNodeId.put(n.getNodeId(), n.getId());
        }

        List<ModuleHierarchyNode> fnRows = new ArrayList<>();
        Set<String> seenFunctionNodeIds = new HashSet<>();
        for (ModuleDto m : hierarchy.getModules().values()) {
            for (SubModuleDto sm : m.getSubModules().values()) {
                Long parentRowId = subModuleRowIdByNodeId.get(sm.getId());
                if (parentRowId == null) continue;
                for (FunctionDto fn : sm.getFunctions().values()) {
                    if (!seenFunctionNodeIds.add(fn.getId())) {
                        log.warn("persistAll 跳过重复功能 node_id={}, taskId={}", fn.getId(), taskId);
                        continue;
                    }
                    ModuleHierarchyNode fnRow = new ModuleHierarchyNode();
                    fnRow.setTaskId(taskId);
                    fnRow.setSystemId(systemId);
                    fnRow.setLevel(LEVEL_FUNCTION);
                    fnRow.setParentId(parentRowId);
                    fnRow.setNodeId(fn.getId());
                    fnRow.setName(fn.getFunctionName());
                    fnRow.setKeywords(null);
                    fnRow.setClassPaths(serializeJsonArray(new ArrayList<>(fn.getClassPaths())));
                    fnRow.setMethodSignatures(serializeJsonArray(new ArrayList<>(fn.getMethodSignatures())));
                    fnRow.setConfirmed(Boolean.TRUE.equals(fn.getConfirmed()));
                    fnRow.setCreatedAt(now);
                    fnRow.setUpdatedAt(now);
                    fnRows.add(fnRow);
                }
            }
        }
        if (!fnRows.isEmpty()) {
            nodeMapper.batchInsert(fnRows);
        }

        log.info("persistAll done. taskId={} modules={} subModules={} functions={}",
                taskId, rows.size(), subRows.size(), fnRows.size());
    }

    private List<String> parseJsonArray(String json) {
        if (!StringUtils.hasText(json)) return new ArrayList<>();
        try {
            JsonNode arr = objectMapper.readTree(json);
            List<String> out = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    String v = n.asText();
                    if (StringUtils.hasText(v)) out.add(v);
                }
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String serializeJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }

    private String serializeHierarchyToJson(ModuleHierarchy hierarchy) {
        // 仅供提示词渲染：剥离 class_paths，避免敏感类路径泄漏到大模型调用 payload
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(PromptViewDtos.from(hierarchy));
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 归一化 AI 输出的节点 ID：后端约定 prefix + 4 位 Base62 = 5 位总长，
     * 但 AI 可能按 prompt 字面理解为 prefix + 5 位 = 6 位（如 mA1b2C）。
     * 此处将 6 位 ID 截断为 5 位；如截断后与已有 ID 冲突则重新生成。
     */
    private String normalizeAiNodeId(String aiId, char prefix, Set<String> existingIds) {
        if (aiId == null || aiId.isEmpty()) return aiId;
        // 已经是 5 位且前缀正确，直接返回
        if (aiId.length() == 5 && aiId.charAt(0) == prefix) return aiId;
        // 超过 5 位（通常是 AI 多生成了 1 位），截断为 5 位
        if (aiId.length() > 5) {
            String truncated = aiId.substring(0, 5);
            if (truncated.charAt(0) == prefix && !existingIds.contains(truncated)) {
                return truncated;
            }
        }
        // 格式不对或截断后冲突 → 重新生成
        return base62Generator.generateUnique(prefix, existingIds);
    }

    /**
     * 校验前端手工编辑后的层级树：
     * - 节点 ID 必须符合 Base62Generator 约定（m/s/f 前缀 + 5 位）
     * - 名称非空（模块 / 子模块 / 功能）
     * - 子模块挂在模块下，功能挂在子模块下，结构不能错位
     * - 同一 task 内 ID 不能重复
     * - classPaths 中允许为空，但元素必须是字符串
     */
    private void validateReplacement(Long taskId, ModuleHierarchy hierarchy) {
        if (hierarchy == null) {
            throw new BusinessException("模块层级为空");
        }
        Set<String> seenModuleIds = new HashSet<>();
        for (ModuleDto m : hierarchy.getModules().values()) {
            if (!StringUtils.hasText(m.getId()) || !m.getId().startsWith("m") || m.getId().length() != 5) {
                throw new BusinessException("模块 ID 非法: " + m.getId());
            }
            if (!seenModuleIds.add(m.getId())) {
                throw new BusinessException("模块 ID 重复: " + m.getId());
            }
            if (!StringUtils.hasText(m.getModuleName())) {
                throw new BusinessException("模块名称不能为空, id=" + m.getId());
            }
            Set<String> seenSubIds = new HashSet<>();
            for (SubModuleDto sm : m.getSubModules().values()) {
                if (!StringUtils.hasText(sm.getId()) || !sm.getId().startsWith("s") || sm.getId().length() != 5) {
                    throw new BusinessException("子模块 ID 非法: " + sm.getId());
                }
                if (!seenSubIds.add(sm.getId())) {
                    throw new BusinessException("子模块 ID 重复: " + sm.getId());
                }
                if (!StringUtils.hasText(sm.getSubModuleName())) {
                    throw new BusinessException("子模块名称不能为空, id=" + sm.getId());
                }
                Set<String> seenFnIds = new HashSet<>();
                for (FunctionDto fn : sm.getFunctions().values()) {
                    if (!StringUtils.hasText(fn.getId()) || !fn.getId().startsWith("f") || fn.getId().length() != 5) {
                        throw new BusinessException("功能 ID 非法: " + fn.getId());
                    }
                    if (!seenFnIds.add(fn.getId())) {
                        throw new BusinessException("功能 ID 重复: " + fn.getId());
                    }
                    if (!StringUtils.hasText(fn.getFunctionName())) {
                        throw new BusinessException("功能名称不能为空, id=" + fn.getId());
                    }
                    if (fn.getClassPaths() == null) {
                        fn.setClassPaths(new LinkedHashSet<>());
                    }
                    if (fn.getMethodSignatures() == null) {
                        fn.setMethodSignatures(new LinkedHashSet<>());
                    }
                    for (String cp : fn.getClassPaths()) {
                        if (!StringUtils.hasText(cp)) {
                            throw new BusinessException("功能 classPaths 含空元素, id=" + fn.getId());
                        }
                    }
                }
            }
        }
    }

    @Override
    public ModuleHierarchy replaceHierarchy(Long taskId, ModuleHierarchy replacement) {
        if (taskId == null) {
            throw new BusinessException("taskId 不能为空");
        }
        DecompileTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在: " + taskId);
        }
        validateReplacement(taskId, replacement);

        // 设置上下文后落表（persistAll 依赖 taskId / systemId）
        replacement.setTaskId(taskId);
        replacement.setSystemId(task.getSystemId());
        persistAll(taskId, task.getSystemId(), replacement);
        log.info("replaceHierarchy done. taskId={} modules={} functions={}",
                taskId, replacement.getModules().size(), countFunctions(replacement));
        return replacement;
    }

    private String readOptionalFile(java.nio.file.Path path) {
        if (path == null || !Files.exists(path)) return "";
        try {
            return Files.readString(path);
        } catch (Exception e) {
            return "";
        }
    }

    private int countFunctions(ModuleHierarchy hierarchy) {
        int c = 0;
        for (ModuleDto m : hierarchy.getModules().values()) {
            for (SubModuleDto sm : m.getSubModules().values()) {
                c += sm.getFunctions().size();
            }
        }
        return c;
    }
}