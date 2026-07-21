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
import java.util.LinkedHashMap;
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
    private com.company.codeinsight.modules.entrypoint.mapper.EntrypointMapper entrypointMapper;

    @Autowired
    private com.company.codeinsight.modules.repository.service.CodeRepositoryService codeRepositoryService;

    @Autowired
    private com.company.codeinsight.modules.scanner.service.BaselineInheritanceService baselineInheritanceService;

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

        // v1: INCREMENTAL 任务下，先从基线任务继承整树
        //    （基线节点全部入库；后续 AI 重提炼 retarget 入口时，会先把 retarget 入口的旧节点 delete 再重做）
        if (effective.isIncremental() && effective.getBaselineTaskId() != null) {
            int inherited = baselineInheritanceService.inheritModuleHierarchy(taskId, effective.getBaselineTaskId());
            log.info("ModuleHierarchy.buildAndPersist — INCREMENTAL 任务从基线继承整树 taskId={} baselineTaskId={} rows={}",
                    taskId, effective.getBaselineTaskId(), inherited);
        }

        // 1. 加载已有节点 → 重建 DTO（INCREMENTAL 任务下这里会包含基线继承的节点）
        ModuleHierarchy hierarchy = loadByTaskId(taskId);
        hierarchy.setTaskId(taskId);
        hierarchy.setSystemId(task.getSystemId());

        // 1.5 【v1 重构】预处理：基于入口 diff 剔除被删入口对应的整模块
        //    同步逻辑删 DB + reserved node_id，禁止 AI 占用已删模块 id（见方案 §5.2.1）
        Set<String> reservedDeletedNodeIds = new HashSet<>();
        if (effective.isIncremental() && effective.getBaselineTaskId() != null) {
            Set<String> currentNames = new HashSet<>();
            for (EntryPoint ep : entrypointReviewService.loadEnabledEntries(taskId)) {
                if (ep.getClassName() != null) currentNames.add(ep.getClassName());
            }
            Set<String> deletedNames = new HashSet<>();
            for (com.company.codeinsight.modules.entrypoint.entity.EntrypointEntity be :
                    entrypointMapper.selectByTaskId(effective.getBaselineTaskId())) {
                if (be.getClassName() != null && !currentNames.contains(be.getClassName())) {
                    deletedNames.add(be.getClassName());
                }
            }
            List<ModuleDto> preprocessedDeleted = preprocessHierarchy(hierarchy, currentNames, deletedNames);
            for (ModuleDto deletedMod : preprocessedDeleted) {
                collectModuleTreeNodeIds(deletedMod, reservedDeletedNodeIds);
            }
            if (!reservedDeletedNodeIds.isEmpty()) {
                int softDeleted = nodeMapper.deleteByTaskIdAndNodeIds(taskId, reservedDeletedNodeIds);
                log.info("预处理逻辑删已删入口模块树 — taskId={} reservedNodeIds={} dbRows={}",
                        taskId, reservedDeletedNodeIds.size(), softDeleted);
            }
            log.info("ModuleHierarchy.buildAndPersist 预处理 — taskId={} baselineTaskId={} deletedEntries={} preprocessedDeletedModules={}",
                    taskId, effective.getBaselineTaskId(), deletedNames.size(), preprocessedDeleted.size());
        }

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
            // 方案 B：INITIAL 全量重写 binding 前先逻辑删腾出 uk_mfb_*_active
            if (!effective.isIncremental() && methodFunctionBindingMapper != null) {
                methodFunctionBindingMapper.deleteByTaskId(taskId);
            }
            // INCREMENTAL：不再按 sourceEntryClass 整入口清空 FUNCTION（否则 AI 会重划未变方法的挂载）。
            // 删除签名的清理在 merge 后 purgeDeletedMethodSignatures 中按入口 DIFF 处理。
            final String finalPrompt = promptTemplate;
            final com.company.codeinsight.modules.entrypoint.model.EntryPointConfig finalConfig =
                    entrypointReviewService.resolveConfig(task);
            final DecompileTask finalTask = task;
            final File finalProjectDir = projectDir;
            Long baselineTaskIdForDiff = effective.isIncremental() ? effective.getBaselineTaskId() : null;
            for (EntryPoint entry : toProcess) {
                Map<String, String> methodDiffBySig = baselineTaskIdForDiff == null
                        ? java.util.Collections.emptyMap()
                        : buildEntrypointMethodDiffStatus(taskId, baselineTaskIdForDiff, entry.getClassName());
                JsonNode inc = callAiForEntry(finalTask, entry, finalPrompt, finalProjectDir, finalConfig,
                        hierarchy, methodDiffBySig);
                if (inc != null) {
                    mergeEntryResult(hierarchy, entry, inc, methodDiffBySig, reservedDeletedNodeIds);
                    purgeDeletedMethodSignatures(hierarchy, methodDiffBySig);
                    persistMethodBindingsFromIncrement(taskId, task.getSystemId(),
                            entry, inc, methodsByClass);
                    processedByAi++;
                    log.info("MODULE_HIERARCHY 串行处理进度 — taskId={} {}/{} entry={} modules={}",
                            taskId, processedByAi, toProcess.size(), entry.getClassName(),
                            hierarchy.getModules().size());
                }
            }
        }

        // 1.7 合并后按基线同名强制改回旧 ID（AI 发明新 id 时的兜底，如 mK7qP→mQ8nR）
        if (effective.isIncremental() && effective.getBaselineTaskId() != null) {
            ModuleHierarchy baselineForReconcile = loadByTaskId(effective.getBaselineTaskId());
            reconcileModuleIdsWithBaseline(hierarchy, baselineForReconcile);
        }

        // 1.8 【v1 重构】反向检索：按 methodSignature 配对基线 vs 当前，给每个 FUNCTION 标 diffStatus
        if (effective.isIncremental() && effective.getBaselineTaskId() != null) {
            ModuleHierarchy baseline = loadByTaskId(effective.getBaselineTaskId());
            reverseEngineerDiff(hierarchy, baseline);
        }

        backfillMethodSignaturesFromEntrypoints(hierarchy, methodsByClass);

        // 5. 增量模式：清理被删除文件对应的 classPath 引用
        if (effective.isIncremental() && !effective.getDeletedPaths().isEmpty()) {
            int removedRefs = purgeDeletedClassPaths(hierarchy, effective.getDeletedPaths());
            log.info("增量清理 — 任务 {} 删除 {} 个文件，从层级中移除 {} 个 classPath 引用",
                    taskId, effective.getDeletedPaths().size(), removedRefs);
        }

        // 6. 落表：
        //   - INITIAL 模式：走原 persistAll（deleteByTaskId + 3 步 batchInsert）
        //   - INCREMENTAL 模式：retarget 入口的旧节点已在前置步骤删除；其余节点来自基线继承不动；
        //     只对 retarget 入口的新 DTO 子树做 batchInsert 3 步（生成新 DB ID 和 parent_id）
        if (effective.isIncremental()) {
            persistIncremental(taskId, task.getSystemId(), hierarchy, toProcess);
        } else {
            persistAll(taskId, task.getSystemId(), hierarchy);
        }

        int failedByAi = toProcess.size() - processedByAi;
        execLog.log(taskId, String.format(
                "  入口提炼汇总 = 成功 %d / 共 %d（失败 %d，增量跳过 %d）",
                processedByAi, toProcess.size(), failedByAi, skippedByIncremental));

        log.info("ModuleHierarchyService.buildAndPersist done. taskId={} modules={} functions={} aiCalls={} skipped={}",
                taskId, hierarchy.getModules().size(), countFunctions(hierarchy), processedByAi, skippedByIncremental);
        return hierarchy;
    }

    /**
     * v1: 增量模式落表。
     * <p>语义：
     * <ul>
     *   <li>retarget 入口的旧节点已在前置 {@code deleteByTaskIdAndSourceEntryClass} 删除</li>
     *   <li>基线继承的节点不动</li>
     *   <li>AI 重提炼产生的新 DTO 子树用 3 步 batchInsert（MODULE → SUB_MODULE → FUNCTION）落表</li>
     * </ul>
     * </p>
     * <p>实现细节：toProcess 列表里的入口对应的"新生成的 DTO 节点"靠 DTO 的判定
     * （AI 重提炼会通过 {@link #mergeEntryResult} 把节点挂到 hierarchy 上），
     * 落表时按"DB 中已存在的 nodeId 跳过"避免重复插入。</p>
     */
    /**
     * 增量落表：基线节点不动；仅 insert 内存树中 DB 尚不存在的 node_id。
     * <p>支持「复用已有 MODULE/SUB，只追加新 FUNCTION」——同名强制复用 ID 后必须能把新功能写进已有模块下。</p>
     */
    private void persistIncremental(Long taskId, Long systemId, ModuleHierarchy hierarchy,
                                     List<EntryPoint> toProcess) {
        if (hierarchy.getModules().isEmpty()) {
            return;
        }
        assertHierarchyNamesPresent(hierarchy);
        Set<String> existingNodeIds = new HashSet<>();
        List<ModuleHierarchyNode> existingRows = nodeMapper.selectList(
                new LambdaQueryWrapper<ModuleHierarchyNode>().eq(ModuleHierarchyNode::getTaskId, taskId));
        for (ModuleHierarchyNode r : existingRows) {
            if (StringUtils.hasText(r.getNodeId())) {
                existingNodeIds.add(r.getNodeId());
            }
        }

        LocalDateTime now = LocalDateTime.now();
        String sourceEntry = firstToProcessClassName(toProcess);

        // MODULE：只插新 id
        List<ModuleHierarchyNode> modRows = new ArrayList<>();
        for (ModuleDto m : hierarchy.getModules().values()) {
            if (existingNodeIds.contains(m.getId())) {
                continue;
            }
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
            modRow.setSourceEntryClass(sourceEntry);
            modRow.setCreatedDate(now);
            modRow.setUpdatedDate(now);
            modRows.add(modRow);
        }
        if (!modRows.isEmpty()) {
            nodeMapper.batchInsert(modRows);
        }
        Map<String, Long> moduleRowIdByNodeId = loadTaskNodeIdToPk(taskId, LEVEL_MODULE);

        // SUB_MODULE：父模块可以是基线已有；只插新 sub id
        List<ModuleHierarchyNode> subRows = new ArrayList<>();
        Set<String> seenSubNodeIds = new HashSet<>();
        for (ModuleDto m : hierarchy.getModules().values()) {
            Long parentRowId = moduleRowIdByNodeId.get(m.getId());
            if (parentRowId == null) {
                continue;
            }
            for (SubModuleDto sm : m.getSubModules().values()) {
                if (!seenSubNodeIds.add(sm.getId())) {
                    continue;
                }
                if (existingNodeIds.contains(sm.getId())) {
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
                subRow.setSourceEntryClass(sourceEntry);
                subRow.setCreatedDate(now);
                subRow.setUpdatedDate(now);
                subRows.add(subRow);
            }
        }
        if (!subRows.isEmpty()) {
            nodeMapper.batchInsert(subRows);
        }
        Map<String, Long> subModuleRowIdByNodeId = loadTaskNodeIdToPk(taskId, LEVEL_SUB_MODULE);

        // FUNCTION：父 sub 可以是基线已有；只插新 function id
        List<ModuleHierarchyNode> fnRows = new ArrayList<>();
        Set<String> seenFunctionNodeIds = new HashSet<>();
        for (ModuleDto m : hierarchy.getModules().values()) {
            for (SubModuleDto sm : m.getSubModules().values()) {
                Long parentRowId = subModuleRowIdByNodeId.get(sm.getId());
                if (parentRowId == null) {
                    continue;
                }
                for (FunctionDto fn : sm.getFunctions().values()) {
                    if (!seenFunctionNodeIds.add(fn.getId())) {
                        continue;
                    }
                    if (existingNodeIds.contains(fn.getId())) {
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
                    fnRow.setSourceEntryClass(sourceEntry);
                    fnRow.setCreatedDate(now);
                    fnRow.setUpdatedDate(now);
                    fnRows.add(fnRow);
                }
            }
        }
        if (!fnRows.isEmpty()) {
            nodeMapper.batchInsert(fnRows);
        }
        log.info("persistIncremental done. taskId={} newModules={} newSubModules={} newFunctions={}",
                taskId, modRows.size(), subRows.size(), fnRows.size());
    }

    private Map<String, Long> loadTaskNodeIdToPk(Long taskId, String level) {
        Map<String, Long> map = new HashMap<>();
        List<ModuleHierarchyNode> rows = nodeMapper.selectList(
                new LambdaQueryWrapper<ModuleHierarchyNode>()
                        .eq(ModuleHierarchyNode::getTaskId, taskId)
                        .eq(ModuleHierarchyNode::getLevel, level));
        for (ModuleHierarchyNode n : rows) {
            if (StringUtils.hasText(n.getNodeId())) {
                map.put(n.getNodeId(), n.getId());
            }
        }
        return map;
    }

    /**
     * 辅助：取 retarget 入口列表中第一个的 className 作为 sourceEntryClass 标记
     * （当 toProcess 只有一个入口时，这就是该入口；多个入口时仍能追溯到任一处理方）
     */
    private String firstToProcessClassName(List<EntryPoint> toProcess) {
        if (toProcess == null || toProcess.isEmpty()) return null;
        EntryPoint first = toProcess.get(0);
        return first == null ? null : first.getClassName();
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
                m.setSourceEntryClass(n.getSourceEntryClass());
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
                    fn.setSourceEntryClass(n.getSourceEntryClass());
                    sm.getFunctions().put(fn.getId(), fn);
                }
            }
        }
        return hierarchy;
    }

    @Override
    public com.company.codeinsight.modules.hierarchy.dto.ModuleHierarchyDiffDto getHierarchyDiff(Long taskId) {
        com.company.codeinsight.modules.hierarchy.dto.ModuleHierarchyDiffDto result =
                new com.company.codeinsight.modules.hierarchy.dto.ModuleHierarchyDiffDto();
        DecompileTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return result;
        }
        if (!"INCREMENTAL".equals(task.getType()) || task.getRepositoryId() == null) {
            return result;
        }
        com.company.codeinsight.modules.repository.entity.CodeRepository repo =
                codeRepositoryService.getById(task.getRepositoryId());
        Long baselineTaskId = repo == null ? null : repo.getLastPublishedTaskId();
        if (baselineTaskId == null) {
            return result;
        }

        // 每次请求现场加载并重算——不依赖 build 阶段未落库的 diffStatus / 单例缓存
        ModuleHierarchy current = loadByTaskId(taskId);
        ModuleHierarchy baseline = loadByTaskId(baselineTaskId);
        reverseEngineerDiff(current, baseline);

        ModuleHierarchy newHier = newEmptyHierarchy(taskId, task.getSystemId());
        ModuleHierarchy modifiedHier = newEmptyHierarchy(taskId, task.getSystemId());
        ModuleHierarchy inheritedHier = newEmptyHierarchy(taskId, task.getSystemId());
        ModuleHierarchy deletedHier = newEmptyHierarchy(baselineTaskId, task.getSystemId());

        Map<String, ModuleDto> currentMods = current != null && current.getModules() != null
                ? current.getModules() : java.util.Collections.emptyMap();
        Map<String, ModuleDto> baselineMods = baseline != null && baseline.getModules() != null
                ? baseline.getModules() : java.util.Collections.emptyMap();

        // 先 ID 精确配对，再同名 1:1 兜底（避免「商品管理」成对新增+删除）
        Map<String, String> currentToBaselineId = pairModulesForDiff(currentMods, baselineMods);
        Set<String> matchedBaselineIds = new HashSet<>(currentToBaselineId.values());

        for (ModuleDto m : currentMods.values()) {
            String pairedBaselineId = currentToBaselineId.get(m.getId());
            ModuleDto bm = pairedBaselineId == null ? null : baselineMods.get(pairedBaselineId);
            ModuleDto cp = copyModuleTree(m);
            if (bm == null) {
                // 真新增：清空跨基线签名误标的 ~改，统一按 new 展示
                markAllFunctionsNew(cp);
                cp.setDiffStatus("new");
                newHier.getModules().put(m.getId(), cp);
            } else if (isModuleContentModified(m, bm)) {
                // classPaths / 方法签名 / 功能名 任一相对基线有变 → 本次变更（含仅功能级 +/-/~）
                cp.setDiffStatus("modified");
                modifiedHier.getModules().put(m.getId(), cp);
            } else {
                cp.setDiffStatus("unchanged");
                inheritedHier.getModules().put(m.getId(), cp);
            }
        }
        for (ModuleDto bm : baselineMods.values()) {
            if (!matchedBaselineIds.contains(bm.getId())) {
                ModuleDto cp = copyModuleTree(bm);
                cp.setDiffStatus("deleted");
                deletedHier.getModules().put(bm.getId(), cp);
            }
        }

        result.setNewHierarchy(newHier);
        result.setModifiedHierarchy(modifiedHier);
        result.setInheritedHierarchy(inheritedHier);
        result.setDeletedHierarchy(deletedHier);
        log.info("ModuleHierarchyDiff — taskId={} baselineTaskId={} newMod={} modifiedMod={} inheritedMod={} deletedMod={}",
                taskId, baselineTaskId,
                newHier.getModules().size(), modifiedHier.getModules().size(),
                inheritedHier.getModules().size(), deletedHier.getModules().size());
        return result;
    }

    /**
     * 模块配对：先 moduleId，再 normalize(moduleName) 1:1；同名多模块时 classPaths Jaccard 优先。
     * @return currentModuleId → baselineModuleId
     */
    private Map<String, String> pairModulesForDiff(Map<String, ModuleDto> currentMods,
                                                   Map<String, ModuleDto> baselineMods) {
        Map<String, String> currentToBaseline = new HashMap<>();
        Set<String> matchedBaseline = new HashSet<>();

        for (ModuleDto m : currentMods.values()) {
            if (baselineMods.containsKey(m.getId())) {
                currentToBaseline.put(m.getId(), m.getId());
                matchedBaseline.add(m.getId());
            }
        }

        Map<String, List<ModuleDto>> unmatchedBaselineByName = new HashMap<>();
        for (ModuleDto bm : baselineMods.values()) {
            if (matchedBaseline.contains(bm.getId())) {
                continue;
            }
            String key = normalizeModuleNameKey(bm.getModuleName());
            if (key == null) {
                continue;
            }
            unmatchedBaselineByName.computeIfAbsent(key, k -> new ArrayList<>()).add(bm);
        }

        List<ModuleDto> unmatchedCurrent = new ArrayList<>();
        for (ModuleDto m : currentMods.values()) {
            if (!currentToBaseline.containsKey(m.getId())) {
                unmatchedCurrent.add(m);
            }
        }
        // 稳定顺序，便于平局时字典序
        unmatchedCurrent.sort((a, b) -> String.valueOf(a.getId()).compareTo(String.valueOf(b.getId())));

        for (ModuleDto m : unmatchedCurrent) {
            String key = normalizeModuleNameKey(m.getModuleName());
            if (key == null) {
                continue;
            }
            List<ModuleDto> candidates = unmatchedBaselineByName.get(key);
            if (candidates == null || candidates.isEmpty()) {
                continue;
            }
            ModuleDto best = pickBestNameMatch(m, candidates);
            if (best == null) {
                continue;
            }
            candidates.remove(best);
            currentToBaseline.put(m.getId(), best.getId());
            matchedBaseline.add(best.getId());
            log.info("DIFF_NAME_PAIR current={} baseline={} name={}", m.getId(), best.getId(), m.getModuleName());
        }
        return currentToBaseline;
    }

    private String normalizeModuleNameKey(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        return name.trim();
    }

    /** 同名候选中选 classPaths Jaccard 最大者；平局取 id 字典序最小 */
    private ModuleDto pickBestNameMatch(ModuleDto current, List<ModuleDto> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        Set<String> curPaths = collectFunctionClassPaths(current);
        ModuleDto best = null;
        double bestScore = -1;
        for (ModuleDto c : candidates) {
            double score = jaccard(curPaths, collectFunctionClassPaths(c));
            if (best == null
                    || score > bestScore
                    || (score == bestScore && String.valueOf(c.getId()).compareTo(String.valueOf(best.getId())) < 0)) {
                best = c;
                bestScore = score;
            }
        }
        return best;
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        int inter = 0;
        for (String x : a) {
            if (b.contains(x)) {
                inter++;
            }
        }
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }

    /** 真新增模块：功能统一标 new，避免跨基线签名误出 ~改 */
    private void markAllFunctionsNew(ModuleDto m) {
        if (m == null || m.getSubModules() == null) {
            return;
        }
        for (SubModuleDto sm : m.getSubModules().values()) {
            if (sm.getFunctions() == null) {
                continue;
            }
            for (FunctionDto fn : sm.getFunctions().values()) {
                fn.setDiffStatus("new");
            }
        }
    }

    private ModuleHierarchy newEmptyHierarchy(Long taskId, Long systemId) {
        ModuleHierarchy h = new ModuleHierarchy();
        h.setTaskId(taskId);
        h.setSystemId(systemId);
        return h;
    }

    /** 复制模块整棵子树（含 function，供 DIFF 分组用，避免共享可变引用） */
    private ModuleDto copyModuleTree(ModuleDto src) {
        ModuleDto cp = copyModule(src);
        cp.setDiffStatus(src.getDiffStatus());
        if (src.getSubModules() == null) {
            return cp;
        }
        for (SubModuleDto sm : src.getSubModules().values()) {
            SubModuleDto smCp = copySubModule(sm);
            if (sm.getFunctions() != null) {
                for (FunctionDto fn : sm.getFunctions().values()) {
                    smCp.getFunctions().put(fn.getId(), fn);
                }
            }
            cp.getSubModules().put(smCp.getId(), smCp);
        }
        return cp;
    }

    /**
     * 预处理：inheritModuleHierarchy 之后剔除「被删入口」对应的整模块，避免 AI 复用已删模块 ID。
     * <p>返回值供调用方收集 reserved node_id 并逻辑删 DB；DIFF 分类在 {@link #getHierarchyDiff} 内按基线/当前 ID 重算。</p>
     */
    private List<ModuleDto> preprocessHierarchy(
            ModuleHierarchy hierarchy,
            Set<String> currentEntryClassNames,
            Set<String> deletedEntryClassNames) {
        List<ModuleDto> deletedModules = new ArrayList<>();
        Iterator<Map.Entry<String, ModuleDto>> it = hierarchy.getModules().entrySet().iterator();
        while (it.hasNext()) {
            ModuleDto m = it.next().getValue();
            Set<String> moduleClassPaths = collectFunctionClassPaths(m);
            if (moduleClassPaths.isEmpty()) continue;
            // 整模块入口被全删（基线有入口但本次都没有）→ 整模块剔除
            boolean hasAnyDeleted = moduleClassPaths.stream()
                .anyMatch(deletedEntryClassNames::contains);
            if (hasAnyDeleted && moduleClassPaths.stream()
                    .noneMatch(currentEntryClassNames::contains)) {
                m.setDiffStatus("deleted");
                deletedModules.add(m);
                it.remove();
                continue;
            }
            // 部分入口被删 → 从 FUNCTION.classPaths 中剔除已删除的入口类（消除歧义）
            for (SubModuleDto sm : m.getSubModules().values()) {
                if (sm.getFunctions() == null) continue;
                for (FunctionDto fn : sm.getFunctions().values()) {
                    if (fn.getClassPaths() == null) continue;
                    boolean hasDeleted = fn.getClassPaths().stream()
                        .anyMatch(deletedEntryClassNames::contains);
                    if (hasDeleted) {
                        fn.getClassPaths().removeAll(deletedEntryClassNames);
                    }
                }
            }
        }
        return deletedModules;
    }

    /** 收集模块树全部 node_id（module + sub + function），供 reserved / 逻辑删。 */
    private void collectModuleTreeNodeIds(ModuleDto module, Set<String> out) {
        if (module == null || out == null) {
            return;
        }
        if (StringUtils.hasText(module.getId())) {
            out.add(module.getId());
        }
        if (module.getSubModules() == null) {
            return;
        }
        for (SubModuleDto sm : module.getSubModules().values()) {
            if (sm == null) {
                continue;
            }
            if (StringUtils.hasText(sm.getId())) {
                out.add(sm.getId());
            }
            if (sm.getFunctions() == null) {
                continue;
            }
            for (FunctionDto fn : sm.getFunctions().values()) {
                if (fn != null && StringUtils.hasText(fn.getId())) {
                    out.add(fn.getId());
                }
            }
        }
    }

    /**
     * 按 methodSignature 配对基线 vs 当前，标记 FUNCTION 的 diffStatus（仅内存，供 UI 着色；不落库）。
     * <p>除签名增删/改名外：同签名但模块/子模块路径不同 → modified（结构/归属变更）。</p>
     */
    private void reverseEngineerDiff(ModuleHierarchy currentHier, ModuleHierarchy baselineHier) {
        Map<String, FunctionDto> baselineBySig = new HashMap<>();
        Map<String, String> baselinePathBySig = new HashMap<>();
        if (baselineHier != null && baselineHier.getModules() != null) {
            for (ModuleDto bm : baselineHier.getModules().values()) {
                if (bm.getSubModules() == null) continue;
                for (SubModuleDto bs : bm.getSubModules().values()) {
                    if (bs.getFunctions() == null) continue;
                    String path = hierarchyPath(bm.getModuleName(), bs.getSubModuleName());
                    for (FunctionDto bf : bs.getFunctions().values()) {
                        if (bf.getMethodSignatures() == null) continue;
                        for (String sig : bf.getMethodSignatures()) {
                            baselineBySig.put(sig, bf);
                            baselinePathBySig.put(sig, path);
                        }
                    }
                }
            }
        }
        if (currentHier == null || currentHier.getModules() == null) {
            return;
        }
        for (ModuleDto m : currentHier.getModules().values()) {
            if (m.getSubModules() == null) continue;
            for (SubModuleDto sm : m.getSubModules().values()) {
                if (sm.getFunctions() == null) continue;
                String curPath = hierarchyPath(m.getModuleName(), sm.getSubModuleName());
                for (FunctionDto fn : sm.getFunctions().values()) {
                    if (fn.getMethodSignatures() == null || fn.getMethodSignatures().isEmpty()) {
                        continue;
                    }
                    boolean anyNew = false;
                    boolean anyMod = false;
                    boolean anyUnchanged = false;
                    for (String sig : fn.getMethodSignatures()) {
                        FunctionDto bf = baselineBySig.get(sig);
                        if (bf == null) {
                            anyNew = true;
                        } else if (fn.getFunctionName() == null
                                || !fn.getFunctionName().equals(bf.getFunctionName())) {
                            anyMod = true;
                        } else {
                            String basePath = baselinePathBySig.get(sig);
                            if (basePath != null && !basePath.equals(curPath)) {
                                anyMod = true; // 同签名换了模块/子模块挂载
                            } else {
                                anyUnchanged = true;
                            }
                        }
                    }
                    if (anyNew) {
                        fn.setDiffStatus("new");
                    } else if (anyMod) {
                        fn.setDiffStatus("modified");
                    } else if (anyUnchanged) {
                        fn.setDiffStatus("unchanged");
                    }
                }
            }
        }
    }

    private static String hierarchyPath(String moduleName, String subModuleName) {
        return String.valueOf(moduleName) + "/" + String.valueOf(subModuleName);
    }

    /** v1: 收集一个模块下所有 FUNCTION 节点的 classPaths 并集（用于判断 modified vs unchanged） */
    private java.util.Set<String> collectFunctionClassPaths(ModuleDto m) {
        java.util.Set<String> fp = new java.util.HashSet<>();
        if (m.getSubModules() == null) return fp;
        for (SubModuleDto sm : m.getSubModules().values()) {
            if (sm.getFunctions() == null) continue;
            for (FunctionDto fn : sm.getFunctions().values()) {
                if (fn.getClassPaths() != null) fp.addAll(fn.getClassPaths());
            }
        }
        return fp;
    }

    /**
     * 配对成功的模块是否相对基线有内容变更。
     * <p>不仅看 classPaths：方法签名增删、功能名变更（reverseEngineerDiff 标 new/modified）
     * 均应进「本次变更」，避免「基线继承」里仍出现 +/~ 功能却 ~0 变更。</p>
     */
    private boolean isModuleContentModified(ModuleDto current, ModuleDto baseline) {
        if (!collectFunctionClassPaths(current).equals(collectFunctionClassPaths(baseline))) {
            return true;
        }
        if (!collectFunctionMethodSignatures(current).equals(collectFunctionMethodSignatures(baseline))) {
            return true;
        }
        return hasFunctionStatus(current, "new", "modified", "deleted");
    }

    private java.util.Set<String> collectFunctionMethodSignatures(ModuleDto m) {
        java.util.Set<String> sigs = new java.util.HashSet<>();
        if (m == null || m.getSubModules() == null) {
            return sigs;
        }
        for (SubModuleDto sm : m.getSubModules().values()) {
            if (sm.getFunctions() == null) {
                continue;
            }
            for (FunctionDto fn : sm.getFunctions().values()) {
                if (fn.getMethodSignatures() != null) {
                    sigs.addAll(fn.getMethodSignatures());
                }
            }
        }
        return sigs;
    }

    private boolean hasFunctionStatus(ModuleDto m, String... statuses) {
        if (m == null || m.getSubModules() == null || statuses == null || statuses.length == 0) {
            return false;
        }
        Set<String> want = new HashSet<>(java.util.Arrays.asList(statuses));
        for (SubModuleDto sm : m.getSubModules().values()) {
            if (sm.getFunctions() == null) {
                continue;
            }
            for (FunctionDto fn : sm.getFunctions().values()) {
                if (fn.getDiffStatus() != null && want.contains(fn.getDiffStatus())) {
                    return true;
                }
            }
        }
        return false;
    }

    // 辅助：复制 ModuleDto（避免共享引用）
    private ModuleDto copyModule(ModuleDto src) {
        ModuleDto m = new ModuleDto();
        m.setId(src.getId());
        m.setModuleName(src.getModuleName());
        m.setKeywords(new java.util.ArrayList<>(src.getKeywords()));
        m.setConfirmed(src.getConfirmed());
        m.setSourceEntryClass(src.getSourceEntryClass());
        return m;
    }

    // 辅助：复制 SubModuleDto（避免共享引用）
    private SubModuleDto copySubModule(SubModuleDto src) {
        SubModuleDto sm = new SubModuleDto();
        sm.setId(src.getId());
        sm.setSubModuleName(src.getSubModuleName());
        sm.setKeywords(new java.util.ArrayList<>(src.getKeywords()));
        sm.setConfirmed(src.getConfirmed());
        return sm;
    }

    // ============================ private helpers ============================

    /**
     * 序列化 hierarchy 为 AI prompt 用的精简 JSON。
     * <p>保留：id / module_name / sub_module_name / function_name / keywords / class_paths<br>
     * 去掉：method_signatures / url / 时间戳（避免 token 浪费）</p>
     * <p>保留 class_paths：与提示词「类路径命中 → 复用已有节点」对齐，避免 AI 重新发明 ID。</p>
     */
    private String serializeHierarchyForPrompt(ModuleHierarchy hierarchy) {
        if (hierarchy == null || hierarchy.getModules() == null || hierarchy.getModules().isEmpty()) {
            return "{\"modules\":[]}";
        }
        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        java.util.List<java.util.Map<String, Object>> modulesOut = new java.util.ArrayList<>();
        for (ModuleDto m : hierarchy.getModules().values()) {
            java.util.Map<String, Object> modMap = new java.util.LinkedHashMap<>();
            modMap.put("id", m.getId());
            modMap.put("module_name", m.getModuleName());
            modMap.put("keywords", m.getKeywords());
            java.util.List<java.util.Map<String, Object>> subsOut = new java.util.ArrayList<>();
            for (SubModuleDto sm : m.getSubModules().values()) {
                java.util.Map<String, Object> subMap = new java.util.LinkedHashMap<>();
                subMap.put("id", sm.getId());
                subMap.put("sub_module_name", sm.getSubModuleName());
                subMap.put("keywords", sm.getKeywords());
                java.util.List<java.util.Map<String, Object>> fnsOut = new java.util.ArrayList<>();
                for (FunctionDto fn : sm.getFunctions().values()) {
                    java.util.Map<String, Object> fnMap = new java.util.LinkedHashMap<>();
                    fnMap.put("id", fn.getId());
                    fnMap.put("function_name", fn.getFunctionName());
                    if (fn.getClassPaths() != null && !fn.getClassPaths().isEmpty()) {
                        fnMap.put("class_paths", new ArrayList<>(fn.getClassPaths()));
                    }
                    fnsOut.add(fnMap);
                }
                subMap.put("functions", fnsOut);
                subsOut.add(subMap);
            }
            modMap.put("sub_modules", subsOut);
            modulesOut.add(modMap);
        }
        root.put("modules", modulesOut);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("serializeHierarchyForPrompt 失败，返回空对象：{}", e.getMessage());
            return "{\"modules\":[]}";
        }
    }

    /**
     * 并行阶段：对单个入口渲染 prompt 并调 AI（含可配置重试），返回解析后的 JSON 节点。
     * 失败时写入 pipeline.log 并返回 null。
     *
     * <p>v1 单次调用 + 共享上下文：传入 {@code existingHierarchy}（当前任务的最新模块树），
     * 序列化为精简 JSON 注入 prompt 的 {@code {module_hierarchy.json}} 占位符；
     * AI 据此判断复用已有 ID 还是新建。</p>
     * <p>若 {@code methodDiffBySig} 含 new/modified/deleted：追加方法变更提示，并拒绝空 {@code modules}。</p>
     */
    private JsonNode callAiForEntry(DecompileTask task, EntryPoint entry,
                                    String promptTemplate, File projectDir,
                                    EntryPointConfig entryPointConfig,
                                    ModuleHierarchy existingHierarchy,
                                    Map<String, String> methodDiffBySig) {
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
            String hierarchyJson = serializeHierarchyForPrompt(existingHierarchy);
            String promptInput = promptTemplateLoader.render(promptTemplate, javaCode, businessKnowledge, hierarchyJson);
            if (promptTemplateLoader.hasUnresolvedPlaceholders(promptInput)) {
                log.warn("Prompt 仍有未替换占位符，跳过入口 {}", entryLabel);
                execLog.log(taskId, "[AI-SKIP] stage=MODULE_HIERARCHY target=" + entryLabel + " reason=unresolved prompt placeholders");
                return null;
            }

            boolean hasMethodDiffHint = hasEntrypointMethodChanges(methodDiffBySig);
            boolean rejectEmptyModules = hasNewOrModifiedMethodDiff(methodDiffBySig);
            if (hasMethodDiffHint) {
                String diffHint = buildMethodDiffPromptHint(methodDiffBySig);
                promptInput = promptInput + "\n\n" + diffHint;
                log.info("MODULE_HIERARCHY 入口有方法 DIFF — entry={} rejectEmptyModules={}",
                        entryLabel, rejectEmptyModules);
                execLog.log(taskId, "[AI-HINT] stage=MODULE_HIERARCHY target=" + entryLabel
                        + " rejectEmptyModules=" + rejectEmptyModules + " methodDiffHint appended");
            }

            AiSummaryService.AiCallMeta meta = new AiSummaryService.AiCallMeta();
            meta.setCallStage("MODULE_HIERARCHY");
            meta.setClassPath(entryLabel);

            final boolean requireNonEmptyModules = rejectEmptyModules;
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
                            JsonNode tree = objectMapper.readTree(cleaned);
                            if (requireNonEmptyModules && isEmptyModulesPayload(tree)) {
                                return PipelineAiCaller.ValidationResult.fail(
                                        "empty modules while entrypoint has new/modified methods");
                            }
                            return PipelineAiCaller.ValidationResult.ok(cleaned);
                        } catch (Exception e) {
                            return PipelineAiCaller.ValidationResult.fail("JSON parse: " + e.getMessage());
                        }
                    },
                    (original, current, failedAttempt, reason) -> {
                        String retry = original
                                + "\n\n[系统提示] 上轮输出未通过校验：" + reason
                                + "\n请确保输出是合法的 JSON（可用 ```json ... ``` 包裹）。";
                        if (requireNonEmptyModules) {
                            retry += "\n本入口存在方法 new/modified：即使 class_paths 已命中已有模块，"
                                    + "也必须在已有模块/子模块 id 下输出功能增量；禁止输出 { \"modules\": [] }。";
                        }
                        return retry;
                    }
            );

            if (!StringUtils.hasText(aiPayload) || "{}".equals(aiPayload.trim())) {
                return null;
            }
            JsonNode result = objectMapper.readTree(aiPayload);
            if (requireNonEmptyModules && isEmptyModulesPayload(result)) {
                log.warn("callAiForEntry 重试后仍为空 modules，放弃合并增量 — entry={}", entryLabel);
                execLog.log(taskId, "[AI-FAIL] stage=MODULE_HIERARCHY target=" + entryLabel
                        + " reason=empty modules after retries with new/modified methods");
                return null;
            }
            return result;
        } catch (Exception e) {
            log.error("callAiForEntry failed for {}: {}", entryLabel, e.getMessage(), e);
            execLog.logException(taskId, "[AI-FAIL] stage=MODULE_HIERARCHY target=" + entryLabel, e);
        }
        return null;
    }

    /** 入口相对基线是否存在方法级 new/modified/deleted。 */
    private boolean hasEntrypointMethodChanges(Map<String, String> methodDiffBySig) {
        if (methodDiffBySig == null || methodDiffBySig.isEmpty()) {
            return false;
        }
        for (String st : methodDiffBySig.values()) {
            if ("new".equals(st) || "modified".equals(st) || "deleted".equals(st)) {
                return true;
            }
        }
        return false;
    }

    /** 仅 new/modified 时强制非空 modules（纯 deleted 由 purge 处理，允许空增量）。 */
    private boolean hasNewOrModifiedMethodDiff(Map<String, String> methodDiffBySig) {
        if (methodDiffBySig == null || methodDiffBySig.isEmpty()) {
            return false;
        }
        for (String st : methodDiffBySig.values()) {
            if ("new".equals(st) || "modified".equals(st)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEmptyModulesPayload(JsonNode tree) {
        if (tree == null || tree.isNull() || !tree.isObject()) {
            return true;
        }
        JsonNode modules = tree.get("modules");
        return modules == null || !modules.isArray() || modules.isEmpty();
    }

    /**
     * 追加到 prompt：列出本入口方法 DIFF，避免「类路径已命中就输出空 modules」。
     */
    private String buildMethodDiffPromptHint(Map<String, String> methodDiffBySig) {
        java.util.LinkedHashSet<String> news = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> mods = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> dels = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, String> e : methodDiffBySig.entrySet()) {
            String sig = e.getKey();
            if (!StringUtils.hasText(sig) || sig.contains("#")) {
                // 优先展示短签名；Class#method 与短签名成对写入时跳过带 # 的，避免重复
                continue;
            }
            String st = e.getValue();
            if ("new".equals(st)) {
                news.add(sig);
            } else if ("modified".equals(st)) {
                mods.add(sig);
            } else if ("deleted".equals(st)) {
                dels.add(sig);
            }
        }
        // 若短签名全被跳过（异常），回退用全部 key
        if (news.isEmpty() && mods.isEmpty() && dels.isEmpty()) {
            for (Map.Entry<String, String> e : methodDiffBySig.entrySet()) {
                String st = e.getValue();
                if ("new".equals(st)) {
                    news.add(e.getKey());
                } else if ("modified".equals(st)) {
                    mods.add(e.getKey());
                } else if ("deleted".equals(st)) {
                    dels.add(e.getKey());
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[系统增量提示] 本入口相对基线存在方法变更。即使 module_hierarchy.json 的 class_paths 已命中，")
                .append("也必须输出非空 modules 增量：在已有模块/子模块 id 下给出 new/modified 对应的功能节点；")
                .append("禁止输出 { \"modules\": [] }。\n");
        if (!news.isEmpty()) {
            sb.append("- new: ").append(String.join(", ", news)).append('\n');
        }
        if (!mods.isEmpty()) {
            sb.append("- modified: ").append(String.join(", ", mods)).append('\n');
        }
        if (!dels.isEmpty()) {
            sb.append("- deleted（程序会 purge，无需编造删除节点）: ").append(String.join(", ", dels)).append('\n');
        }
        return sb.toString().trim();
    }

    private void mergeEntryResult(ModuleHierarchy hierarchy, EntryPoint entry, JsonNode inc,
                                  Map<String, String> methodDiffBySig,
                                  Set<String> reservedDeletedNodeIds) {
        Set<String> newlyCreatedFunctionIds = new LinkedHashSet<>();
        mergeIncrementIntoHierarchy(hierarchy, inc, newlyCreatedFunctionIds, methodDiffBySig,
                reservedDeletedNodeIds);

        // 只注入入口类路径；不把 methods_json 全集灌进新建功能（避免整类污染 method_signatures）
        for (String fnId : newlyCreatedFunctionIds) {
            FunctionDto fn = findFunctionById(hierarchy, fnId);
            if (fn != null && StringUtils.hasText(entry.getClassName())) {
                fn.getClassPaths().add(entry.getClassName());
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
        // 一次 SQL 收集 task 内调用图白名单：signatures（Tier 1 严格匹配）+ classNames（Tier 2 兜底）。
        // Tier 1 用 caller 端完整签名白名单校验 (cp, sig) 元组；
        // Tier 2 用 caller + callee 端类名白名单兜底，避免 Service/Repository 这类 leaf callee
        // 因不出现在 caller_signature 而被误剔除。
        CallGraphWhitelist whitelist = collectCallGraphWhitelist(taskId);
        Set<String> signatureSet = whitelist.signatures();
        Set<String> classSet = whitelist.classNames();

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
                        // AI 输出 class_paths 是 FQ（如 com.demo.UserController）；
                        // 白名单里的 className 是短名，caller_signature 也是短类名拼接。
                        // 因此把 cp 截短为短类名用于 Tier 1 匹配。
                        String cpShort = stripPackage(cp);
                        boolean cpClassHit = !classSet.isEmpty()
                                && (classSet.contains(cp) || classSet.contains(cpShort));
                        for (String sig : methodSigs) {
                            // Tier 1：caller_signature 严格命中（短类名#完整签名）
                            String fullShort = cpShort + "#" + sig;
                            boolean sigHit = !signatureSet.isEmpty() && signatureSet.contains(fullShort);
                            // Tier 2 兜底：class 出现在调用图里（caller 或 callee 端均可）
                            boolean classHit = cpClassHit;
                            if (!sigHit && !classHit) {
                                skippedNotExisting++;
                                log.warn("modules 元组既不在 caller_signature 也不在调用图 class 白名单, 跳过 (entry={}, tuple={})",
                                        entryClassName, fullShort);
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
        // 方案 B：plain INSERT 前逻辑删 — 同 function 旧行 + 即将写入的 (class,sig) 活行键
        java.util.Set<String> functionIds = new java.util.LinkedHashSet<>();
        for (MethodFunctionBinding r : rows) {
            if (StringUtils.hasText(r.getFunctionNodeId())) {
                functionIds.add(r.getFunctionNodeId());
            }
        }
        if (!functionIds.isEmpty()) {
            methodFunctionBindingMapper.deleteByTaskIdAndFunctionNodeIds(taskId, functionIds);
        }
        methodFunctionBindingMapper.deleteByTaskIdAndClassMethodKeys(taskId, rows);
        int inserted = methodFunctionBindingMapper.batchInsertBindings(rows);
        log.info("入口 {} binding 入库: 笛卡尔+交叉校验后 {} 行, insert {} 行 (call-graph 不存在 {} 条)",
                entryClassName, rows.size(), inserted, skippedNotExisting);
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
     * 调用图交叉校验白名单容器。
     *
     * <p>两套集合按用途区分：</p>
     * <ul>
     *   <li>{@code signatures} — Tier 1：caller 端方法签名白名单（"短类名#methodName(ParamTypes)"）。
     *       用于精确校验 AI 输出的 (class, sig) 元组是否在调用图里有 caller 边。</li>
     *   <li>{@code classNames} — Tier 2：caller 端 + callee 端类名白名单（短类名 + FQ 名）。
     *       当 Tier 1 未命中时兜底：只要 AI 输出的 class 在调用图里作为 caller 或 callee
     *       出现过，就接受其元组（容忍 AI 输出的方法签名格式差异与 Service/Repository
     *       这类 leaf callee 的方法不出现在 caller_signature 的情况）。</li>
     * </ul>
     */
    private record CallGraphWhitelist(Set<String> signatures, Set<String> classNames) {
        static final CallGraphWhitelist EMPTY = new CallGraphWhitelist(Set.of(), Set.of());
    }

    /**
     * 取该任务下 {@code ci_method_call} 的 caller_signature + 所有出现过的类名，
     * 作为反向绑定交叉校验的两套白名单。
     *
     * <p>实现方式：一次查询汇总 task 内所有 method_call 行，挑选：
     * <ul>
     *   <li>{@code caller_signature} → signatures 集合（Tier 1 严格匹配）</li>
     *   <li>{@code className}（caller 端短类名）+ {@code dependencyName} 解析出的类名（callee 端）→ classNames 集合（Tier 2 兜底）</li>
     * </ul>
     * 表为空（新任务还未持久化 AST）或查询失败时返回空白名单——空集合视为"跳过交叉校验"
     * 而不是"全部丢弃"，避免回退路径被锁死。</p>
     *
     * <p>格式不匹配的处理（关键）：AI 输出 class_paths 是 FQ（如 {@code com.demo.UserController}），
     * 而 {@code ci_method_call.caller_signature} 落库时是短类名（如 {@code UserController#listUsers(...)}）。
     * 因此 Tier 1 校验时需要把 AI 的 FQ 截短为短类名再拼签名；Tier 2 同时存 FQ 与短名以兼容。</p>
     */
    private CallGraphWhitelist collectCallGraphWhitelist(Long taskId) {
        Set<String> signatures = new HashSet<>();
        Set<String> classNames = new HashSet<>();
        try {
            // 一次查询拿全部：把 task 内所有 caller_signature + className + dependencyName 选中。
            // 约定一次性查 ≤ 5k 行；超出时分批，本 MVP 阶段简单取首 5000 行。
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.company.codeinsight.modules.callchain.entity.MethodCall> all =
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
            all.eq(com.company.codeinsight.modules.callchain.entity.MethodCall::getTaskId, taskId)
               .select(
                       com.company.codeinsight.modules.callchain.entity.MethodCall::getCallerSignature,
                       com.company.codeinsight.modules.callchain.entity.MethodCall::getClassName,
                       com.company.codeinsight.modules.callchain.entity.MethodCall::getDependencyName
               )
               .last("LIMIT 5000");
            List<com.company.codeinsight.modules.callchain.entity.MethodCall> rows = methodCallMapper.selectList(all);
            for (com.company.codeinsight.modules.callchain.entity.MethodCall row : rows) {
                if (row.getCallerSignature() != null) {
                    signatures.add(row.getCallerSignature());
                }
                if (row.getClassName() != null) {
                    classNames.add(row.getClassName());           // caller 端短类名
                    // 兜底补一个去掉包前缀后的纯短名（虽然 parser 通常已是短名，防御一下）
                    String shortName = stripPackage(row.getClassName());
                    if (shortName != null && !shortName.equals(row.getClassName())) {
                        classNames.add(shortName);
                    }
                }
                if (row.getDependencyName() != null) {
                    // dependencyName 格式："variableName:TypeFQ"，如 "userService:com.demo.UserService"
                    String depType = stripVariableFromDependencyName(row.getDependencyName());
                    if (depType != null) {
                        classNames.add(depType);                  // callee 端（可能 FQ）
                        String depShort = stripPackage(depType);
                        if (depShort != null && !depShort.equals(depType)) {
                            classNames.add(depShort);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("collectCallGraphWhitelist 失败, 回退为跳过交叉校验: {}", e.getMessage());
        }
        return new CallGraphWhitelist(signatures, classNames);
    }

    /**
     * 去掉 FQ 类名的包前缀，返回短类名。已是短名则原样返回。
     */
    private static String stripPackage(String fqOrShortClassName) {
        if (fqOrShortClassName == null) return null;
        int dotIdx = fqOrShortClassName.lastIndexOf('.');
        return dotIdx >= 0 ? fqOrShortClassName.substring(dotIdx + 1) : fqOrShortClassName;
    }

    /**
     * 解析 dependencyName（"variable:Type"）中的类型部分。
     */
    private static String stripVariableFromDependencyName(String dependencyName) {
        if (dependencyName == null) return null;
        int colonIdx = dependencyName.indexOf(':');
        return colonIdx >= 0 ? dependencyName.substring(colonIdx + 1) : dependencyName;
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
     * 把 AI 返回的增量 JSON 合并进 DTO。
     * <p>合并顺序（防 ID 劫持）：同名优先 → ID 命中且名称一致才复用 → ID 命中但改名则拆新节点 → 否则新建。</p>
     * <p>INCREMENTAL：若提供 {@code methodDiffBySig}，则跳过「仅含入口 unchanged 签名」的功能节点，防止 AI 把未变方法搬家。</p>
     * @param newlyCreatedFunctionIds 输出本次新增的 function id 列表
     */
    private void mergeIncrementIntoHierarchy(ModuleHierarchy hierarchy, JsonNode increment,
                                             Set<String> newlyCreatedFunctionIds,
                                             Map<String, String> methodDiffBySig,
                                             Set<String> reservedDeletedNodeIds) {
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
        // 已删入口模块的 node_id 仍占用，禁止 AI 新建时复用（即使已不在内存树）
        if (reservedDeletedNodeIds != null && !reservedDeletedNodeIds.isEmpty()) {
            existingModuleIds.addAll(reservedDeletedNodeIds);
            existingSubModuleIds.addAll(reservedDeletedNodeIds);
            existingFunctionIds.addAll(reservedDeletedNodeIds);
        }
        // 快照：用于区分「本次新建功能」vs「复用已有」（供入口 classPath 注入）
        Set<String> knownFunctionIdsAtStart = Set.copyOf(existingFunctionIds);
        boolean filterByEntrypointDiff = methodDiffBySig != null && !methodDiffBySig.isEmpty();

        for (JsonNode modNode : modulesNode) {
            String rawModId = modNode.path("id").asText("").trim();
            if (!StringUtils.hasText(rawModId)) continue;
            String modName = readAiName(modNode, "module_name", "moduleName", "name");
            if (!StringUtils.hasText(modName)) {
                log.warn("SKIP_MODULE_EMPTY_NAME aiId={} keywords={}",
                        rawModId, modNode.path("keywords"));
                continue;
            }
            String candidateId = normalizeAiNodeId(rawModId, 'm', existingModuleIds);

            ModuleDto module = resolveModuleForMerge(
                    hierarchy, existingModuleIds, candidateId, rawModId, modName, reservedDeletedNodeIds);
            module.setModuleName(modName);
            mergeKeywords(module.getKeywords(), modNode.path("keywords"));

            JsonNode subsNode = modNode.path("sub_modules");
            if (!subsNode.isArray()) {
                // 兼容 AI 偶发 camelCase
                subsNode = modNode.path("subModules");
            }
            if (!subsNode.isArray()) continue;
            for (JsonNode subNode : subsNode) {
                String rawSubId = subNode.path("id").asText("").trim();
                if (!StringUtils.hasText(rawSubId)) continue;
                String subName = readAiName(subNode, "sub_module_name", "subModuleName", "name");
                if (!StringUtils.hasText(subName)) {
                    log.warn("SKIP_SUB_MODULE_EMPTY_NAME aiId={} moduleId={}", rawSubId, module.getId());
                    continue;
                }
                String candidateSubId = normalizeAiNodeId(rawSubId, 's', existingSubModuleIds);

                SubModuleDto sub = resolveSubModuleForMerge(
                        module, existingSubModuleIds, candidateSubId, rawSubId, subName, reservedDeletedNodeIds);
                sub.setSubModuleName(subName);
                mergeKeywords(sub.getKeywords(), subNode.path("keywords"));

                JsonNode fnsNode = subNode.path("functions");
                if (!fnsNode.isArray()) continue;
                for (JsonNode fnNode : fnsNode) {
                    String rawFnId = fnNode.path("id").asText("").trim();
                    if (!StringUtils.hasText(rawFnId)) continue;
                    if (filterByEntrypointDiff && shouldSkipAiFunctionForUnchangedOnly(fnNode, methodDiffBySig)) {
                        log.info("SKIP_AI_FUNCTION_UNCHANGED sigs-only-unchanged fnId={} name={}",
                                rawFnId, readAiName(fnNode, "function_name", "functionName", "name"));
                        continue;
                    }
                    String fnName = readAiName(fnNode, "function_name", "functionName", "name");
                    if (!StringUtils.hasText(fnName)) {
                        log.warn("SKIP_FUNCTION_EMPTY_NAME aiId={} subModuleId={}", rawFnId, sub.getId());
                        continue;
                    }
                    String candidateFnId = normalizeAiNodeId(rawFnId, 'f', existingFunctionIds);

                    FunctionDto fn = resolveFunctionForMerge(
                            sub, existingFunctionIds, candidateFnId, rawFnId, fnName, reservedDeletedNodeIds);
                    // 合并前已知名单；resolve 可能往 existingFunctionIds 追加新建 id
                    if (!knownFunctionIdsAtStart.contains(fn.getId())) {
                        newlyCreatedFunctionIds.add(fn.getId());
                    }
                    fn.setFunctionName(fnName);
                    mergeClassPaths(fn, fnNode.path("class_paths"));
                    if (fnNode.path("class_paths").isMissingNode() || fnNode.path("class_paths").isNull()) {
                        mergeClassPaths(fn, fnNode.path("classPaths"));
                    }
                    mergeMethodSignatures(fn, fnNode.path("method_signatures"));
                    if (fnNode.path("method_signatures").isMissingNode() || fnNode.path("method_signatures").isNull()) {
                        mergeMethodSignatures(fn, fnNode.path("methodSignatures"));
                    }
                }
            }
        }
    }

    /**
     * 从 AI JSON 节点读取名称：按候选字段顺序取第一个非空文本（兼容 snake / camel / 简写 name）。
     */
    public static String readAiName(JsonNode node, String... fieldNames) {
        if (node == null || fieldNames == null) {
            return "";
        }
        for (String field : fieldNames) {
            if (!StringUtils.hasText(field)) {
                continue;
            }
            JsonNode v = node.get(field);
            if (v == null || v.isNull() || !v.isValueNode()) {
                continue;
            }
            String text = v.asText("").trim();
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return "";
    }

    /**
     * 落库前硬校验：任一模块/子模块/功能名为空则抛业务异常，避免撞 PG NOT NULL。
     */
    public void assertHierarchyNamesPresent(ModuleHierarchy hierarchy) {
        if (hierarchy == null || hierarchy.getModules() == null) {
            return;
        }
        for (ModuleDto m : hierarchy.getModules().values()) {
            if (!StringUtils.hasText(m.getModuleName())) {
                throw new BusinessException("模块名称不能为空, id=" + m.getId());
            }
            if (m.getSubModules() == null) {
                continue;
            }
            for (SubModuleDto sm : m.getSubModules().values()) {
                if (!StringUtils.hasText(sm.getSubModuleName())) {
                    throw new BusinessException("子模块名称不能为空, id=" + sm.getId());
                }
                if (sm.getFunctions() == null) {
                    continue;
                }
                for (FunctionDto fn : sm.getFunctions().values()) {
                    if (!StringUtils.hasText(fn.getFunctionName())) {
                        throw new BusinessException("功能名称不能为空, id=" + fn.getId());
                    }
                }
            }
        }
    }

    /**
     * 入口方法 DIFF 状态：key 同时放入「短签名 method(args)」与「Class#method(args)」便于与 AI 输出对齐。
     */
    private Map<String, String> buildEntrypointMethodDiffStatus(Long taskId, Long baselineTaskId, String className) {
        Map<String, String> out = new HashMap<>();
        if (taskId == null || baselineTaskId == null || !StringUtils.hasText(className)) {
            return out;
        }
        List<com.company.codeinsight.modules.entrypoint.entity.EntrypointEntity> currentRows =
                entrypointMapper.selectByTaskId(taskId);
        List<com.company.codeinsight.modules.entrypoint.entity.EntrypointEntity> baselineRows =
                entrypointMapper.selectByTaskId(baselineTaskId);
        com.company.codeinsight.modules.entrypoint.entity.EntrypointEntity cur = null;
        com.company.codeinsight.modules.entrypoint.entity.EntrypointEntity base = null;
        for (com.company.codeinsight.modules.entrypoint.entity.EntrypointEntity r : currentRows) {
            if (className.equals(r.getClassName())) {
                cur = r;
                break;
            }
        }
        for (com.company.codeinsight.modules.entrypoint.entity.EntrypointEntity r : baselineRows) {
            if (className.equals(r.getClassName())) {
                base = r;
                break;
            }
        }
        List<EntrypointMethodView> currentMethods = cur == null
                ? java.util.Collections.emptyList()
                : deserializeEntrypointMethods(cur.getMethodsJson());
        List<EntrypointMethodView> baselineMethods = base == null
                ? java.util.Collections.emptyList()
                : deserializeEntrypointMethods(base.getMethodsJson());
        Map<String, String> baselineBodyByShort = new HashMap<>();
        Set<String> baselineShort = new HashSet<>();
        for (EntrypointMethodView m : baselineMethods) {
            String shortSig = shortMethodSignature(m.getMethodSignature());
            if (!StringUtils.hasText(shortSig)) {
                continue;
            }
            baselineShort.add(shortSig);
            if (StringUtils.hasText(m.getBodyHash())) {
                baselineBodyByShort.put(shortSig, m.getBodyHash());
            }
        }
        Set<String> currentShort = new HashSet<>();
        for (EntrypointMethodView m : currentMethods) {
            String shortSig = shortMethodSignature(m.getMethodSignature());
            if (!StringUtils.hasText(shortSig)) {
                continue;
            }
            currentShort.add(shortSig);
            String status;
            if (!baselineShort.contains(shortSig)) {
                status = "new";
            } else {
                String bh = baselineBodyByShort.get(shortSig);
                if (StringUtils.hasText(bh) && StringUtils.hasText(m.getBodyHash()) && !bh.equals(m.getBodyHash())) {
                    status = "modified";
                } else {
                    status = "unchanged";
                }
            }
            putMethodDiffStatus(out, m.getMethodSignature(), shortSig, status);
        }
        for (EntrypointMethodView m : baselineMethods) {
            String shortSig = shortMethodSignature(m.getMethodSignature());
            if (StringUtils.hasText(shortSig) && !currentShort.contains(shortSig)) {
                putMethodDiffStatus(out, m.getMethodSignature(), shortSig, "deleted");
            }
        }
        return out;
    }

    private void putMethodDiffStatus(Map<String, String> out, String fullSig, String shortSig, String status) {
        if (StringUtils.hasText(shortSig)) {
            out.put(shortSig, status);
        }
        if (StringUtils.hasText(fullSig)) {
            out.put(fullSig.trim(), status);
        }
    }

    private static String shortMethodSignature(String sig) {
        if (!StringUtils.hasText(sig)) {
            return null;
        }
        String raw = sig.trim();
        int hash = raw.indexOf('#');
        return hash >= 0 && hash < raw.length() - 1 ? raw.substring(hash + 1) : raw;
    }

    private List<EntrypointMethodView> deserializeEntrypointMethods(String methodsJson) {
        if (!StringUtils.hasText(methodsJson)) {
            return java.util.Collections.emptyList();
        }
        try {
            return objectMapper.readValue(methodsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, EntrypointMethodView.class));
        } catch (Exception e) {
            log.warn("deserializeEntrypointMethods failed: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /** AI 功能节点的 method_signatures 全部为入口 unchanged → 跳过，避免搬家 */
    private boolean shouldSkipAiFunctionForUnchangedOnly(JsonNode fnNode, Map<String, String> methodDiffBySig) {
        JsonNode sigs = fnNode.path("method_signatures");
        if (!sigs.isArray() || sigs.isEmpty()) {
            return false; // 无签名时不拦（兼容旧输出）
        }
        boolean sawAny = false;
        for (JsonNode s : sigs) {
            String sig = s.asText("").trim();
            if (!StringUtils.hasText(sig)) {
                continue;
            }
            sawAny = true;
            String st = lookupMethodDiffStatus(methodDiffBySig, sig);
            if (!"unchanged".equals(st)) {
                return false;
            }
        }
        return sawAny;
    }

    private String lookupMethodDiffStatus(Map<String, String> methodDiffBySig, String sig) {
        if (methodDiffBySig == null || !StringUtils.hasText(sig)) {
            return null;
        }
        String st = methodDiffBySig.get(sig);
        if (st != null) {
            return st;
        }
        return methodDiffBySig.get(shortMethodSignature(sig));
    }

    /** 从层级树移除入口 DIFF 标记为 deleted 的方法签名；功能无签名则删节点 */
    private void purgeDeletedMethodSignatures(ModuleHierarchy hierarchy, Map<String, String> methodDiffBySig) {
        if (hierarchy == null || hierarchy.getModules() == null
                || methodDiffBySig == null || methodDiffBySig.isEmpty()) {
            return;
        }
        Set<String> deletedShort = new HashSet<>();
        for (Map.Entry<String, String> e : methodDiffBySig.entrySet()) {
            if ("deleted".equals(e.getValue())) {
                String shortSig = shortMethodSignature(e.getKey());
                if (StringUtils.hasText(shortSig)) {
                    deletedShort.add(shortSig);
                }
            }
        }
        if (deletedShort.isEmpty()) {
            return;
        }
        for (ModuleDto m : hierarchy.getModules().values()) {
            if (m.getSubModules() == null) continue;
            for (SubModuleDto sm : m.getSubModules().values()) {
                if (sm.getFunctions() == null) continue;
                Iterator<Map.Entry<String, FunctionDto>> it = sm.getFunctions().entrySet().iterator();
                while (it.hasNext()) {
                    FunctionDto fn = it.next().getValue();
                    if (fn.getMethodSignatures() == null || fn.getMethodSignatures().isEmpty()) {
                        continue;
                    }
                    fn.getMethodSignatures().removeIf(sig -> deletedShort.contains(shortMethodSignature(sig)));
                    if (fn.getMethodSignatures().isEmpty()) {
                        it.remove();
                        log.info("PURGE_DELETED_FUNCTION id={} name={}", fn.getId(), fn.getFunctionName());
                    }
                }
            }
        }
    }

    /**
     * 模块解析：1) 同名优先 2) ID 命中且名称不冲突 3) ID 劫持则新生成 4) 否则新建。
     */
    private ModuleDto resolveModuleForMerge(ModuleHierarchy hierarchy,
                                            Set<String> existingModuleIds,
                                            String candidateId,
                                            String rawModId,
                                            String modName,
                                            Set<String> reservedDeletedNodeIds) {
        // 1) 同名优先
        if (StringUtils.hasText(modName)) {
            ModuleDto byName = findModuleByName(hierarchy, modName);
            if (byName != null) {
                if (!byName.getId().equals(candidateId) && !byName.getId().equals(rawModId)) {
                    log.info("NAME_REMAP module aiId={} name={} → reuse={}", candidateId, modName, byName.getId());
                }
                return byName;
            }
        }
        // 2) ID 命中
        ModuleDto byId = hierarchy.getModules().get(candidateId);
        if (byId == null && !candidateId.equals(rawModId)) {
            byId = hierarchy.getModules().get(rawModId);
            if (byId != null && !namesConflict(byId.getModuleName(), modName)) {
                // 历史 6 位 ID 归一化为 5 位
                log.info("将历史遗留 6 位模块 ID {} 重命名为 5 位 ID {}", rawModId, candidateId);
                hierarchy.getModules().remove(rawModId);
                byId.setId(candidateId);
                hierarchy.getModules().put(candidateId, byId);
                existingModuleIds.add(candidateId);
                return byId;
            }
        }
        if (byId != null) {
            if (namesConflict(byId.getModuleName(), modName)) {
                String newId = base62Generator.generateUnique('m', existingModuleIds);
                log.warn("ID_HIJACK_BLOCKED module aiId={} aiName={} existingName={} → newId={}",
                        candidateId, modName, byId.getModuleName(), newId);
                ModuleDto created = new ModuleDto();
                created.setId(newId);
                hierarchy.getModules().put(newId, created);
                existingModuleIds.add(newId);
                return created;
            }
            return byId;
        }
        // 3) 新建（candidate 若在 reserved/已占用集合中则换新 id）
        String newId = candidateId;
        if (existingModuleIds.contains(candidateId)) {
            newId = base62Generator.generateUnique('m', existingModuleIds);
            boolean reserved = reservedDeletedNodeIds != null
                    && (reservedDeletedNodeIds.contains(candidateId) || reservedDeletedNodeIds.contains(rawModId));
            log.warn("{} module aiId={} aiName={} → newId={}",
                    reserved ? "ID_RESERVED_FROM_DELETED" : "ID_COLLISION",
                    candidateId, modName, newId);
        }
        ModuleDto created = new ModuleDto();
        created.setId(newId);
        hierarchy.getModules().put(newId, created);
        existingModuleIds.add(newId);
        return created;
    }

    private SubModuleDto resolveSubModuleForMerge(ModuleDto module,
                                                  Set<String> existingSubModuleIds,
                                                  String candidateId,
                                                  String rawSubId,
                                                  String subName,
                                                  Set<String> reservedDeletedNodeIds) {
        // 1) 同名优先（本模块内）
        if (StringUtils.hasText(subName)) {
            SubModuleDto byName = findSubModuleByName(module, subName);
            if (byName != null) {
                if (!byName.getId().equals(candidateId) && !byName.getId().equals(rawSubId)) {
                    log.info("NAME_REMAP subModule aiId={} name={} module={} → reuse={}",
                            candidateId, subName, module.getId(), byName.getId());
                }
                return byName;
            }
        }
        // 2) 本模块内 ID 命中
        SubModuleDto byId = module.getSubModules().get(candidateId);
        if (byId == null && !candidateId.equals(rawSubId)) {
            byId = module.getSubModules().get(rawSubId);
            if (byId != null && !namesConflict(byId.getSubModuleName(), subName)) {
                log.info("将历史遗留 6 位子模块 ID {} 重命名为 5 位 ID {}", rawSubId, candidateId);
                module.getSubModules().remove(rawSubId);
                byId.setId(candidateId);
                module.getSubModules().put(candidateId, byId);
                existingSubModuleIds.add(candidateId);
                return byId;
            }
        }
        if (byId != null) {
            if (namesConflict(byId.getSubModuleName(), subName)) {
                String newId = base62Generator.generateUnique('s', existingSubModuleIds);
                log.warn("ID_HIJACK_BLOCKED subModule aiId={} aiName={} existingName={} module={} → newId={}",
                        candidateId, subName, byId.getSubModuleName(), module.getId(), newId);
                SubModuleDto created = new SubModuleDto();
                created.setId(newId);
                module.getSubModules().put(newId, created);
                existingSubModuleIds.add(newId);
                return created;
            }
            return byId;
        }
        // 3) 跨模块 id 冲突 / reserved → 重新生成
        String newId = candidateId;
        if (existingSubModuleIds.contains(candidateId)) {
            newId = base62Generator.generateUnique('s', existingSubModuleIds);
            boolean reserved = reservedDeletedNodeIds != null
                    && (reservedDeletedNodeIds.contains(candidateId) || reservedDeletedNodeIds.contains(rawSubId));
            log.warn("{} subModule aiId={} → newId={}",
                    reserved ? "ID_RESERVED_FROM_DELETED" : "ID_COLLISION",
                    candidateId, newId);
        }
        SubModuleDto created = new SubModuleDto();
        created.setId(newId);
        module.getSubModules().put(newId, created);
        existingSubModuleIds.add(newId);
        return created;
    }

    private FunctionDto resolveFunctionForMerge(SubModuleDto sub,
                                                Set<String> existingFunctionIds,
                                                String candidateId,
                                                String rawFnId,
                                                String fnName,
                                                Set<String> reservedDeletedNodeIds) {
        // 1) 同名优先（本子模块内）
        if (StringUtils.hasText(fnName)) {
            FunctionDto byName = findFunctionByName(sub, fnName);
            if (byName != null) {
                if (!byName.getId().equals(candidateId) && !byName.getId().equals(rawFnId)) {
                    log.info("NAME_REMAP function aiId={} name={} sub={} → reuse={}",
                            candidateId, fnName, sub.getId(), byName.getId());
                }
                return byName;
            }
        }
        // 2) 本子模块内 ID 命中
        FunctionDto byId = sub.getFunctions().get(candidateId);
        if (byId == null && !candidateId.equals(rawFnId)) {
            byId = sub.getFunctions().get(rawFnId);
            if (byId != null && !namesConflict(byId.getFunctionName(), fnName)) {
                log.info("将历史遗留 6 位功能 ID {} 重命名为 5 位 ID {}", rawFnId, candidateId);
                sub.getFunctions().remove(rawFnId);
                byId.setId(candidateId);
                sub.getFunctions().put(candidateId, byId);
                existingFunctionIds.add(candidateId);
                return byId;
            }
        }
        if (byId != null) {
            if (namesConflict(byId.getFunctionName(), fnName)) {
                String newId = base62Generator.generateUnique('f', existingFunctionIds);
                log.warn("ID_HIJACK_BLOCKED function aiId={} aiName={} existingName={} sub={} → newId={}",
                        candidateId, fnName, byId.getFunctionName(), sub.getId(), newId);
                FunctionDto created = new FunctionDto();
                created.setId(newId);
                sub.getFunctions().put(newId, created);
                existingFunctionIds.add(newId);
                return created;
            }
            return byId;
        }
        // 3) 跨子模块 id 冲突 / reserved → 重新生成
        String newId = candidateId;
        if (existingFunctionIds.contains(candidateId)) {
            newId = base62Generator.generateUnique('f', existingFunctionIds);
            boolean reserved = reservedDeletedNodeIds != null
                    && (reservedDeletedNodeIds.contains(candidateId) || reservedDeletedNodeIds.contains(rawFnId));
            log.warn("{} function aiId={} → newId={}",
                    reserved ? "ID_RESERVED_FROM_DELETED" : "ID_COLLISION",
                    candidateId, newId);
        }
        FunctionDto created = new FunctionDto();
        created.setId(newId);
        sub.getFunctions().put(newId, created);
        existingFunctionIds.add(newId);
        return created;
    }

    /** AI 名称非空且与已有名称不同 → 冲突（ID 劫持） */
    private boolean namesConflict(String existingName, String aiName) {
        if (!StringUtils.hasText(aiName)) {
            return false;
        }
        if (!StringUtils.hasText(existingName)) {
            return false;
        }
        return !aiName.trim().equals(existingName.trim());
    }

    /**
     * AI 合并后：按基线同名强制改回旧 module/sub/function ID，避免落库成对新增+删除。
     */
    private void reconcileModuleIdsWithBaseline(ModuleHierarchy current, ModuleHierarchy baseline) {
        if (current == null || current.getModules() == null || baseline == null || baseline.getModules() == null) {
            return;
        }
        Map<String, ModuleDto> baselineByName = new LinkedHashMap<>();
        for (ModuleDto bm : baseline.getModules().values()) {
            String key = normalizeModuleNameKey(bm.getModuleName());
            if (key != null) {
                baselineByName.putIfAbsent(key, bm);
            }
        }

        Map<String, ModuleDto> rebuilt = new LinkedHashMap<>();
        Set<String> consumedCurrentIds = new HashSet<>();

        // 1) 已与基线同 id 且名称不冲突的节点先入座（继承空壳 / 已复用）
        for (ModuleDto m : current.getModules().values()) {
            ModuleDto bm = baseline.getModules().get(m.getId());
            if (bm == null || namesConflict(bm.getModuleName(), m.getModuleName())) {
                continue;
            }
            reconcileSubFunctionIdsWithBaseline(m, bm);
            rebuilt.put(m.getId(), m);
            consumedCurrentIds.add(m.getId());
        }

        // 2) 其余：同名 → 并入基线 id；无同名 → 原样保留
        for (ModuleDto m : current.getModules().values()) {
            if (consumedCurrentIds.contains(m.getId())) {
                continue;
            }
            String key = normalizeModuleNameKey(m.getModuleName());
            ModuleDto bm = key == null ? null : baselineByName.get(key);
            if (bm != null) {
                ModuleDto target = rebuilt.get(bm.getId());
                if (target != null) {
                    log.info("POST_AI_ID_REMAP merge name={} dropId={} keepId={}",
                            m.getModuleName(), m.getId(), bm.getId());
                    mergeModuleChildrenByName(target, m);
                } else {
                    log.info("POST_AI_ID_REMAP name={} {} → {}", m.getModuleName(), m.getId(), bm.getId());
                    m.setId(bm.getId());
                    reconcileSubFunctionIdsWithBaseline(m, bm);
                    rebuilt.put(bm.getId(), m);
                }
                consumedCurrentIds.add(m.getId());
            } else {
                rebuilt.put(m.getId(), m);
                consumedCurrentIds.add(m.getId());
            }
        }
        current.setModules(rebuilt);
    }

    /** 将 from 的子树按同名合并进 into（保留 into 的 id） */
    private void mergeModuleChildrenByName(ModuleDto into, ModuleDto from) {
        if (into == null || from == null || from.getSubModules() == null) {
            return;
        }
        if (into.getKeywords() != null && from.getKeywords() != null) {
            for (String kw : from.getKeywords()) {
                if (StringUtils.hasText(kw) && !into.getKeywords().contains(kw)) {
                    into.getKeywords().add(kw);
                }
            }
        }
        for (SubModuleDto fromSub : from.getSubModules().values()) {
            SubModuleDto intoSub = findSubModuleByName(into, fromSub.getSubModuleName());
            if (intoSub == null) {
                into.getSubModules().put(fromSub.getId(), fromSub);
            } else {
                mergeSubModuleChildrenByName(intoSub, fromSub);
            }
        }
    }

    private void mergeSubModuleChildrenByName(SubModuleDto into, SubModuleDto from) {
        if (into.getKeywords() != null && from.getKeywords() != null) {
            for (String kw : from.getKeywords()) {
                if (StringUtils.hasText(kw) && !into.getKeywords().contains(kw)) {
                    into.getKeywords().add(kw);
                }
            }
        }
        if (from.getFunctions() == null) {
            return;
        }
        for (FunctionDto fromFn : from.getFunctions().values()) {
            FunctionDto intoFn = findFunctionByName(into, fromFn.getFunctionName());
            if (intoFn == null) {
                into.getFunctions().put(fromFn.getId(), fromFn);
            } else {
                if (fromFn.getClassPaths() != null) {
                    intoFn.getClassPaths().addAll(fromFn.getClassPaths());
                }
                if (fromFn.getMethodSignatures() != null) {
                    intoFn.getMethodSignatures().addAll(fromFn.getMethodSignatures());
                }
            }
        }
    }

    /** 在已改回基线 moduleId 的模块上，按同名把 sub/function id 也对齐基线 */
    private void reconcileSubFunctionIdsWithBaseline(ModuleDto currentMod, ModuleDto baselineMod) {
        if (currentMod == null || baselineMod == null
                || currentMod.getSubModules() == null || baselineMod.getSubModules() == null) {
            return;
        }
        Map<String, SubModuleDto> rebuiltSubs = new LinkedHashMap<>();
        for (SubModuleDto sm : currentMod.getSubModules().values()) {
            SubModuleDto bsm = findSubModuleByName(baselineMod, sm.getSubModuleName());
            if (bsm != null && !bsm.getId().equals(sm.getId())) {
                log.info("POST_AI_ID_REMAP sub name={} {} → {}", sm.getSubModuleName(), sm.getId(), bsm.getId());
                SubModuleDto existing = rebuiltSubs.get(bsm.getId());
                if (existing != null) {
                    mergeSubModuleChildrenByName(existing, sm);
                    continue;
                }
                sm.setId(bsm.getId());
                reconcileFunctionIdsWithBaseline(sm, bsm);
            } else if (bsm != null) {
                reconcileFunctionIdsWithBaseline(sm, bsm);
            }
            SubModuleDto clash = rebuiltSubs.get(sm.getId());
            if (clash != null && clash != sm) {
                mergeSubModuleChildrenByName(clash, sm);
            } else {
                rebuiltSubs.put(sm.getId(), sm);
            }
        }
        currentMod.setSubModules(rebuiltSubs);
    }

    private void reconcileFunctionIdsWithBaseline(SubModuleDto currentSub, SubModuleDto baselineSub) {
        if (currentSub == null || baselineSub == null
                || currentSub.getFunctions() == null || baselineSub.getFunctions() == null) {
            return;
        }
        Map<String, FunctionDto> rebuilt = new LinkedHashMap<>();
        for (FunctionDto fn : currentSub.getFunctions().values()) {
            FunctionDto bf = findFunctionByName(baselineSub, fn.getFunctionName());
            if (bf != null && !bf.getId().equals(fn.getId())) {
                log.info("POST_AI_ID_REMAP function name={} {} → {}", fn.getFunctionName(), fn.getId(), bf.getId());
                FunctionDto existing = rebuilt.get(bf.getId());
                if (existing != null) {
                    if (fn.getClassPaths() != null) {
                        existing.getClassPaths().addAll(fn.getClassPaths());
                    }
                    if (fn.getMethodSignatures() != null) {
                        existing.getMethodSignatures().addAll(fn.getMethodSignatures());
                    }
                    continue;
                }
                fn.setId(bf.getId());
            }
            FunctionDto clash = rebuilt.get(fn.getId());
            if (clash != null && clash != fn) {
                if (fn.getClassPaths() != null) {
                    clash.getClassPaths().addAll(fn.getClassPaths());
                }
                if (fn.getMethodSignatures() != null) {
                    clash.getMethodSignatures().addAll(fn.getMethodSignatures());
                }
            } else {
                rebuilt.put(fn.getId(), fn);
            }
        }
        currentSub.setFunctions(rebuilt);
    }

    private ModuleDto findModuleByName(ModuleHierarchy hierarchy, String moduleName) {
        if (hierarchy == null || hierarchy.getModules() == null || !StringUtils.hasText(moduleName)) {
            return null;
        }
        for (ModuleDto m : hierarchy.getModules().values()) {
            if (moduleName.equals(m.getModuleName())) {
                return m;
            }
        }
        return null;
    }

    private SubModuleDto findSubModuleByName(ModuleDto module, String subModuleName) {
        if (module == null || module.getSubModules() == null || !StringUtils.hasText(subModuleName)) {
            return null;
        }
        for (SubModuleDto sm : module.getSubModules().values()) {
            if (subModuleName.equals(sm.getSubModuleName())) {
                return sm;
            }
        }
        return null;
    }

    private FunctionDto findFunctionByName(SubModuleDto sub, String functionName) {
        if (sub == null || sub.getFunctions() == null || !StringUtils.hasText(functionName)) {
            return null;
        }
        for (FunctionDto fn : sub.getFunctions().values()) {
            if (functionName.equals(fn.getFunctionName())) {
                return fn;
            }
        }
        return null;
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
        nodeMapper.deleteByTaskId(taskId);
        if (hierarchy.getModules().isEmpty()) {
            return;
        }
        assertHierarchyNamesPresent(hierarchy);
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
            modRow.setCreatedDate(now);
            modRow.setUpdatedDate(now);
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
                subRow.setCreatedDate(now);
                subRow.setUpdatedDate(now);
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
                    fnRow.setCreatedDate(now);
                    fnRow.setUpdatedDate(now);
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
