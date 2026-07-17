package com.company.codeinsight.modules.scanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.modules.callchain.mapper.MethodCallMapper;
import com.company.codeinsight.modules.draft.entity.DraftWorkspace;
import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import com.company.codeinsight.modules.draft.mapper.DraftWorkspaceMapper;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.common.storage.EnvStorageResolver;
import com.company.codeinsight.modules.knowledge.entity.KnowledgeVersion;
import com.company.codeinsight.modules.knowledge.mapper.KnowledgeVersionMapper;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.common.util.DraftFileUtil;
import com.company.codeinsight.modules.entrypoint.mapper.EntrypointMapper;
import com.company.codeinsight.modules.hierarchy.entity.ModuleHierarchyNode;
import com.company.codeinsight.modules.hierarchy.mapper.ModuleHierarchyNodeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v1: INCREMENTAL 任务基线继承服务
 * <p>负责把最近一次 PUSHED 任务的数据复制到本次 INCREMENTAL 任务，遵循：
 * <ul>
 *   <li>入口识别数据（ci_entrypoint）：仅复制 file_path 不在本次 changed/deleted 集合内的行</li>
 *   <li>方法调用链（ci_method_call）：同上，按 file_path 过滤</li>
 *   <li>模块层级（ci_module_hierarchy）：整树复制；parent_id 按 node_id 重映射到本任务行</li>
 * </ul>
 */
@Slf4j
@Service
public class BaselineInheritanceService {

    private static final String LEVEL_MODULE = "MODULE";
    private static final String LEVEL_SUB_MODULE = "SUB_MODULE";
    private static final String LEVEL_FUNCTION = "FUNCTION";

    @Autowired
    private EntrypointMapper entrypointMapper;

    @Autowired
    private MethodCallMapper methodCallMapper;

    @Autowired
    private ModuleHierarchyNodeMapper moduleHierarchyNodeMapper;

    @Autowired
    private DraftWorkspaceMapper draftWorkspaceMapper;

    @Autowired
    private KnowledgeDraftMapper draftMapper;

    @Autowired
    private EnvStorageResolver storageResolver;

    @Autowired
    private CodeRepositoryMapper repositoryMapper;

    @Autowired
    private KnowledgeVersionMapper versionMapper;

    /**
     * 从基线任务继承入口数据到本任务。
     * @param currentTaskId  本次任务 ID
     * @param baselineTaskId 基线任务 ID（最近 PUSHED 任务的 ID）
     * @param excludedPaths  本次变更 + 删除路径集合
     * @return 复制的入口行数
     */
    @Transactional(rollbackFor = Exception.class)
    public int inheritEntrypoints(Long currentTaskId, Long baselineTaskId, Set<String> excludedPaths) {
        if (baselineTaskId == null) {
            return 0;
        }
        List<String> excluded = excludedPaths == null
                ? java.util.Collections.emptyList()
                : new ArrayList<>(excludedPaths);
        if (excluded.isEmpty()) {
            // MyBatis foreach 空集合会生成非法 SQL；无排除时传占位避免 NOT IN ()
            excluded = java.util.List.of("__ci_inherit_no_exclude__");
        }
        int n = entrypointMapper.inheritFromBaseline(currentTaskId, baselineTaskId, excluded);
        log.info("基线继承入口 — taskId={} ← baselineTaskId={} 复制 {} 行（excluded={}）",
                currentTaskId, baselineTaskId, n, excludedPaths == null ? 0 : excludedPaths.size());
        return n;
    }

    /**
     * 从基线任务继承方法调用链到本任务。
     */
    @Transactional(rollbackFor = Exception.class)
    public int inheritMethodCalls(Long currentTaskId, Long baselineTaskId, Set<String> excludedPaths) {
        if (baselineTaskId == null) {
            return 0;
        }
        List<String> excluded = excludedPaths == null
                ? java.util.Collections.emptyList()
                : new ArrayList<>(excludedPaths);
        if (excluded.isEmpty()) {
            excluded = java.util.List.of("__ci_inherit_no_exclude__");
        }
        int n = methodCallMapper.inheritFromBaseline(currentTaskId, baselineTaskId, excluded);
        log.info("基线继承方法调用链 — taskId={} ← baselineTaskId={} 复制 {} 行（excluded={}）",
                currentTaskId, baselineTaskId, n, excludedPaths == null ? 0 : excludedPaths.size());
        return n;
    }

    /**
     * 从基线任务继承模块层级整树到本任务。
     * <p>不能原样复制 {@code parent_id}（那是基线行的自增主键）。按 MODULE → SUB → FUNCTION
     * 分层插入，用稳定的 {@code node_id} 把子节点的 parent_id 映到本任务父行 id，
     * 否则 {@code loadByTaskId} 挂不上子树，AI 只能看到空壳模块并重新发明 ID。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public int inheritModuleHierarchy(Long currentTaskId, Long baselineTaskId) {
        if (baselineTaskId == null || currentTaskId == null) {
            return 0;
        }
        // 幂等：重跑 MODULE_HIERARCHY 时先逻辑删本任务已有层级
        moduleHierarchyNodeMapper.deleteByTaskId(currentTaskId);

        List<ModuleHierarchyNode> baseline = moduleHierarchyNodeMapper.selectList(
                new LambdaQueryWrapper<ModuleHierarchyNode>()
                        .eq(ModuleHierarchyNode::getTaskId, baselineTaskId)
                        .orderByAsc(ModuleHierarchyNode::getId));
        if (baseline.isEmpty()) {
            log.info("基线继承模块层级 — taskId={} ← baselineTaskId={} 基线无节点", currentTaskId, baselineTaskId);
            return 0;
        }

        Map<Long, ModuleHierarchyNode> byOldPk = new HashMap<>();
        List<ModuleHierarchyNode> modules = new ArrayList<>();
        List<ModuleHierarchyNode> subs = new ArrayList<>();
        List<ModuleHierarchyNode> functions = new ArrayList<>();
        for (ModuleHierarchyNode n : baseline) {
            byOldPk.put(n.getId(), n);
            if (LEVEL_MODULE.equals(n.getLevel())) {
                modules.add(n);
            } else if (LEVEL_SUB_MODULE.equals(n.getLevel())) {
                subs.add(n);
            } else if (LEVEL_FUNCTION.equals(n.getLevel())) {
                functions.add(n);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        List<ModuleHierarchyNode> modRows = new ArrayList<>(modules.size());
        for (ModuleHierarchyNode src : modules) {
            modRows.add(copyNodeForInherit(src, currentTaskId, null, now));
        }
        if (!modRows.isEmpty()) {
            moduleHierarchyNodeMapper.batchInsert(modRows);
        }
        Map<String, Long> modulePkByNodeId = loadNodeIdToPk(currentTaskId, LEVEL_MODULE);

        List<ModuleHierarchyNode> subRows = new ArrayList<>(subs.size());
        for (ModuleHierarchyNode src : subs) {
            Long newParentPk = resolveNewParentPk(src.getParentId(), byOldPk, modulePkByNodeId);
            if (newParentPk == null) {
                log.warn("基线继承跳过子模块 nodeId={}：无法解析父 MODULE（oldParentPk={}）",
                        src.getNodeId(), src.getParentId());
                continue;
            }
            subRows.add(copyNodeForInherit(src, currentTaskId, newParentPk, now));
        }
        if (!subRows.isEmpty()) {
            moduleHierarchyNodeMapper.batchInsert(subRows);
        }
        Map<String, Long> subPkByNodeId = loadNodeIdToPk(currentTaskId, LEVEL_SUB_MODULE);

        List<ModuleHierarchyNode> fnRows = new ArrayList<>(functions.size());
        for (ModuleHierarchyNode src : functions) {
            Long newParentPk = resolveNewParentPk(src.getParentId(), byOldPk, subPkByNodeId);
            if (newParentPk == null) {
                log.warn("基线继承跳过功能 nodeId={}：无法解析父 SUB_MODULE（oldParentPk={}）",
                        src.getNodeId(), src.getParentId());
                continue;
            }
            fnRows.add(copyNodeForInherit(src, currentTaskId, newParentPk, now));
        }
        if (!fnRows.isEmpty()) {
            moduleHierarchyNodeMapper.batchInsert(fnRows);
        }

        int total = modRows.size() + subRows.size() + fnRows.size();
        log.info("基线继承模块层级 — taskId={} ← baselineTaskId={} 复制 {} 行（module={} sub={} fn={}，parent_id 已按 node_id 重映射）",
                currentTaskId, baselineTaskId, total, modRows.size(), subRows.size(), fnRows.size());
        return total;
    }

    private Map<String, Long> loadNodeIdToPk(Long taskId, String level) {
        Map<String, Long> map = new HashMap<>();
        List<ModuleHierarchyNode> rows = moduleHierarchyNodeMapper.selectList(
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
     * 基线子节点的 parent_id 指向基线父行 PK → 取父行 node_id → 查本任务同 node_id 的新 PK。
     */
    private Long resolveNewParentPk(Long oldParentPk,
                                    Map<Long, ModuleHierarchyNode> byOldPk,
                                    Map<String, Long> newPkByNodeId) {
        if (oldParentPk == null) {
            return null;
        }
        ModuleHierarchyNode parent = byOldPk.get(oldParentPk);
        if (parent == null || !StringUtils.hasText(parent.getNodeId())) {
            return null;
        }
        return newPkByNodeId.get(parent.getNodeId());
    }

    private ModuleHierarchyNode copyNodeForInherit(ModuleHierarchyNode src, Long currentTaskId,
                                                   Long newParentPk, LocalDateTime now) {
        ModuleHierarchyNode copy = new ModuleHierarchyNode();
        copy.setTaskId(currentTaskId);
        copy.setSystemId(src.getSystemId());
        copy.setLevel(src.getLevel());
        copy.setParentId(newParentPk);
        copy.setNodeId(src.getNodeId());
        copy.setName(src.getName());
        copy.setKeywords(src.getKeywords());
        copy.setClassPaths(src.getClassPaths());
        copy.setMethodSignatures(src.getMethodSignatures());
        copy.setConfirmed(src.getConfirmed());
        copy.setSourceEntryClass(src.getSourceEntryClass());
        copy.setCreatedDate(now);
        copy.setUpdatedDate(now);
        return copy;
    }

    /**
     * 查询基线任务的草稿 workspace ID（用于 INCREMENTAL 任务的 workspace 引用）。
     * @param baselineTaskId 基线任务 ID
     * @return baselineTaskId 对应任务的 draft_workspace.id；若没有则返回 null
     */
    public Long lookupBaselineWorkspaceId(Long baselineTaskId) {
        if (baselineTaskId == null) {
            return null;
        }
        DraftWorkspace ws = draftWorkspaceMapper.selectOne(
                new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getTaskId, baselineTaskId));
        return ws == null ? null : ws.getId();
    }

    /**
     * 从基线任务的已发布知识（releases 目录）继承知识文档到本次任务的 workspace。
     * <p>数据源：releases/{sysId}/{repoId}/{versionNum}/，通过 ci_repository.last_published_version_id 定位。
     *moduleName 从 meta/module-map.yaml 解析，正文从 modules/{fileName} 复制。</p>
     * <p>幂等：已有同 module_name 的 draft 跳过，不覆盖。Fail-fast：release 目录或 module-map.yaml 缺失 → 抛异常。</p>
     *
     * @param currentTaskId      本次任务 ID
     * @param currentWorkspaceId 本次 workspace ID
     * @param repositoryId       仓库 ID（用于查 last_published_version_id 定位 release 目录）
     * @return 实际复制的草稿份数（跳过已有的不计）
     */
    @Transactional(rollbackFor = Exception.class)
    public int inheritDrafts(Long currentTaskId, Long currentWorkspaceId, Long repositoryId) {
        if (currentWorkspaceId == null || repositoryId == null) {
            log.warn("基线草稿继承跳过 — taskId={} workspaceId={} repositoryId={}（参数缺失）",
                    currentTaskId, currentWorkspaceId, repositoryId);
            return 0;
        }

        // 1. 查仓库的 last_published_version_id → KnowledgeVersion
        CodeRepository repo = repositoryMapper.selectById(repositoryId);
        if (repo == null || repo.getLastPublishedVersionId() == null) {
            throw new com.company.codeinsight.common.exception.BusinessException(
                    "仓库未发布过知识版本，无法继承基线文档（repositoryId=" + repositoryId + "）");
        }
        KnowledgeVersion version = versionMapper.selectById(repo.getLastPublishedVersionId());
        if (version == null || !"PUSHED".equals(version.getStatus())) {
            throw new com.company.codeinsight.common.exception.BusinessException(
                    "基线知识版本不可用（versionId=" + repo.getLastPublishedVersionId()
                            + "，status=" + (version != null ? version.getStatus() : "null") + "）");
        }

        Long baselineTaskId = version.getTaskId();

        // 2. 解析 release 目录
        java.nio.file.Path releaseDir = storageResolver.releaseDir(
                version.getSystemId(), version.getRepositoryId(), version.getVersionNum());
        if (!java.nio.file.Files.isDirectory(releaseDir)) {
            throw new com.company.codeinsight.common.exception.BusinessException(
                    "基线 release 目录不存在: " + releaseDir + "，基线数据可能已损坏");
        }
        java.nio.file.Path modulesDir = releaseDir.resolve("modules");
        java.nio.file.Path mapFile = releaseDir.resolve("meta").resolve("module-map.yaml");
        if (!java.nio.file.Files.exists(mapFile)) {
            throw new com.company.codeinsight.common.exception.BusinessException(
                    "基线 module-map.yaml 不存在: " + mapFile + "，基线数据可能已损坏");
        }

        // 3. 解析 module-map.yaml → [{moduleName, fileName}] 列表
        List<ModuleMapEntry> entries = parseModuleMapYaml(mapFile);
        if (entries.isEmpty()) {
            log.info("基线草稿继承跳过 — taskId={} ← releaseDir={}（module-map.yaml 无模块条目）",
                    currentTaskId, releaseDir);
            return 0;
        }

        // 4. 幂等：查本次 workspace 已有的 module_name
        List<KnowledgeDraft> existing = draftMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDraft>()
                        .eq(KnowledgeDraft::getWorkspaceId, currentWorkspaceId));
        java.util.Set<String> existingNames = new java.util.HashSet<>();
        for (KnowledgeDraft d : existing) {
            if (d.getModuleName() != null) {
                existingNames.add(d.getModuleName());
            }
        }

        // 5. 逐个复制 release 文件到本次 drafts 目录 + 创建 DB 行
        java.util.List<KnowledgeDraft> toInsert = new ArrayList<>();
        java.util.List<java.nio.file.Path> copiedFiles = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        java.nio.file.Path currentDraftsDir = storageResolver.draftsRoot().resolve("task_" + currentTaskId);
        try {
            java.nio.file.Files.createDirectories(currentDraftsDir);

            int sortOrder = 0;
            for (ModuleMapEntry entry : entries) {
                if (entry.moduleName == null || existingNames.contains(entry.moduleName)) {
                    continue;
                }

                // release 中的模块文件
                java.nio.file.Path releaseFile = modulesDir.resolve(entry.fileName);
                if (!java.nio.file.Files.exists(releaseFile)) {
                    throw new com.company.codeinsight.common.exception.BusinessException(
                            "基线 release 模块文件缺失：moduleName=" + entry.moduleName
                                    + "，fileName=" + entry.fileName + "，基线数据可能已损坏");
                }

                // 复制到本次 drafts 目录
                String safeName = entry.moduleName.replaceAll("[\\s/\\(\\)]", "_") + ".md";
                java.nio.file.Path currentFile = currentDraftsDir.resolve(safeName);
                java.nio.file.Files.copy(releaseFile, currentFile,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                copiedFiles.add(currentFile);

                // 计算 hash
                String content = java.nio.file.Files.readString(currentFile);
                String hash = org.springframework.util.DigestUtils.md5DigestAsHex(content.getBytes());

                // 构造新 DB 行
                String relativeDocPath = "task_" + currentTaskId + "/" + safeName;
                KnowledgeDraft copy = new KnowledgeDraft();
                copy.setWorkspaceId(currentWorkspaceId);
                copy.setParentId(null);
                copy.setFilePath(relativeDocPath);
                copy.setModuleName(entry.moduleName);
                copy.setContentUri(com.company.codeinsight.common.util.DraftFileUtil.buildDraftUri(
                        repo.getSystemId(), repositoryId, currentTaskId, relativeDocPath));
                copy.setStatus("CONFIRMED"); // 继承文档默认已确认（基线已复核通过）
                copy.setSortOrder(sortOrder++);
                copy.setHash(hash);
                copy.setBaselineTaskId(baselineTaskId);
                copy.setCreatedDate(now);
                copy.setUpdatedDate(now);
                toInsert.add(copy);
            }

            // 批量插入
            for (KnowledgeDraft d : toInsert) {
                draftMapper.insert(d);
            }

            copiedFiles.clear(); // 成功，不需要清理

            log.info("基线草稿继承 — taskId={} ← releaseDir={} versionNum={} 复制 {} 份（跳过已有 {} 份）",
                    currentTaskId, releaseDir, version.getVersionNum(), toInsert.size(), existingNames.size());
            return toInsert.size();

        } catch (Exception e) {
            // 失败时清理已复制的文件（DB 行靠 @Transactional 回滚）
            for (java.nio.file.Path p : copiedFiles) {
                try {
                    java.nio.file.Files.deleteIfExists(p);
                } catch (Exception cleanupEx) {
                    log.debug("清理残留文件失败: {}", p, cleanupEx);
                }
            }
            log.error("基线草稿继承失败 — taskId={} ← releaseDir={}", currentTaskId, releaseDir, e);
            if (e instanceof com.company.codeinsight.common.exception.BusinessException bex) {
                throw bex;
            }
            throw new com.company.codeinsight.common.exception.BusinessException("基线草稿继承失败: " + e.getMessage());
        }
    }

    /** module-map.yaml 中的一条模块映射 */
    private record ModuleMapEntry(String moduleName, String fileName) {}

    /**
     * 解析 module-map.yaml — 格式简单固定，手动行解析避免引入 YAML 依赖。
     * <p>格式：</p>
     * <pre>
     * modules:
     *   - name: "模块名"
     *     path: "docs/code-insight/modules/文件名.md"
     * </pre>
     */
    private List<ModuleMapEntry> parseModuleMapYaml(java.nio.file.Path yamlFile) {
        List<ModuleMapEntry> entries = new ArrayList<>();
        try {
            List<String> lines = java.nio.file.Files.readAllLines(yamlFile);
            String pendingName = null;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("- name:")) {
                    pendingName = parseYamlQuotedValue(trimmed.substring("- name:".length()));
                } else if (trimmed.startsWith("path:") && pendingName != null) {
                    String path = parseYamlQuotedValue(trimmed.substring("path:".length()));
                    String fileName = path;
                    int lastSlash = path.lastIndexOf('/');
                    if (lastSlash >= 0) {
                        fileName = path.substring(lastSlash + 1);
                    }
                    entries.add(new ModuleMapEntry(pendingName, fileName));
                    pendingName = null;
                }
            }
        } catch (java.io.IOException e) {
            throw new com.company.codeinsight.common.exception.BusinessException(
                    "解析 module-map.yaml 失败: " + e.getMessage());
        }
        return entries;
    }

    /** 提取 YAML 引号内的值："value" 或 'value' → value */
    private String parseYamlQuotedValue(String raw) {
        String s = raw.trim();
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"")
                || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
