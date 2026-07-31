package com.company.codeinsight.modules.entrypoint.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.entrypoint.dto.EntrypointDiffDto;
import com.company.codeinsight.modules.entrypoint.entity.EntrypointEntity;
import com.company.codeinsight.modules.entrypoint.mapper.EntrypointMapper;
import com.company.codeinsight.modules.entrypoint.model.DiscoveredEntrypoint;
import com.company.codeinsight.modules.entrypoint.model.DiscoveredMethod;
import com.company.codeinsight.modules.entrypoint.model.EntrypointMethodView;
import com.company.codeinsight.modules.entrypoint.model.EntrypointReviewView;
import com.company.codeinsight.modules.entrypoint.model.EntryPoint;
import com.company.codeinsight.modules.entrypoint.model.EntryPointConfig;
import com.company.codeinsight.modules.entrypoint.model.EntryPointConfigCodec;
import com.company.codeinsight.modules.entrypoint.model.ExcludeTarget;
import com.company.codeinsight.modules.entrypoint.service.EntrypointReviewService;
import com.company.codeinsight.modules.entrypoint.service.EntryPointDiscoveryService;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.service.CodeRepositoryService;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识入口复核服务实现
 * <p>关键实现要点：
 * <ul>
 *   <li>discoverAndPersist：delete-then-insert by taskId，保证幂等；识别失败时清空表行以避免脏数据</li>
 *   <li>methods_json 字段在落表前序列化、读取时反序列化为强类型视图，DB 实体不暴露给前端</li>
 *   <li>loadEnabledEntries 只读不写，AI 阶段无副作用</li>
 *   <li>resolveConfig 只读任务快照；快照为空时回退平台默认预置（不读仓库）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class EntrypointReviewServiceImpl implements EntrypointReviewService {

    @Autowired
    private EntryPointDiscoveryService entryPointDiscoveryService;

    @Autowired
    private EntrypointMapper entrypointMapper;

    @Autowired
    private DecompileTaskMapper taskMapper;

    @Autowired
    private CodeRepositoryService codeRepositoryService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<DiscoveredEntrypoint> discoverAndPersist(Long taskId, File projectDir, EntryPointConfig config) {
        // 兼容旧调用：全量识别（ctx = fullScan 走全量分支）
        return discoverAndPersist(taskId, projectDir, config, com.company.codeinsight.modules.scanner.model.IncrementalContext.fullScan());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<DiscoveredEntrypoint> discoverAndPersist(Long taskId, File projectDir, EntryPointConfig config,
                                                        com.company.codeinsight.modules.scanner.model.IncrementalContext ctx) {
        if (taskId == null) {
            throw new BusinessException("taskId 不能为空");
        }
        if (projectDir == null || !projectDir.exists()) {
            log.warn("discoverAndPersist: projectDir 无效，跳过入口识别 taskId={}", taskId);
            return Collections.emptyList();
        }

        com.company.codeinsight.modules.scanner.model.IncrementalContext effective =
                ctx == null ? com.company.codeinsight.modules.scanner.model.IncrementalContext.fullScan() : ctx;

        if (!effective.isIncremental()) {
            // === INITIAL：原全量识别路径 ===
            return discoverAndPersistFull(taskId, projectDir, config);
        }

        // === v1 INCREMENTAL：基线 + 增量 ===
        // 基线入口继承只在流水线 DecompileTaskServiceImpl 做一次（与 method_calls 同理），
        // 此处禁止再 inherit，否则会撞 uk_entrypoint_task_class_active。
        // 1) deleted 文件的入口从本任务删除（清理误继承 / 路径归一化漏网）
        if (!effective.getDeletedPaths().isEmpty()) {
            entrypointMapper.delete(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EntrypointEntity>()
                            .eq(EntrypointEntity::getTaskId, taskId)
                            .in(EntrypointEntity::getFilePath, effective.getDeletedPaths())
            );
        }
        // 2) changed 文件的入口从本任务删除（基线继承的同 file_path 数据一并清理，再重识别）
        if (!effective.getChangedPaths().isEmpty()) {
            entrypointMapper.delete(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EntrypointEntity>()
                            .eq(EntrypointEntity::getTaskId, taskId)
                            .in(EntrypointEntity::getFilePath, effective.getChangedPaths())
            );
        }
        // 3) 仅对 changedPaths 内的 .java 文件做入口识别
        List<DiscoveredEntrypoint> discovered;
        try {
            discovered = entryPointDiscoveryService.discoverEntriesInFiles(
                    taskId, projectDir, config, effective.getChangedPaths());
        } catch (Exception e) {
            log.error("discoverEntriesInFiles 失败，taskId={}", taskId, e);
            // 识别失败时清空本次任务的入口行（基线继承的保留），保证 AI 阶段不会读到陈旧数据
            throw new BusinessException("知识入口识别失败：" + e.getMessage());
        }
        if (discovered == null || discovered.isEmpty()) {
            log.info("discoverAndPersist: taskId={} 增量识别无新增入口", taskId);
            return Collections.emptyList();
        }

        // 4) 批量落表
        //    baseline_task_id：
        //      - 如果该类在基线中已存在 → 设为 baselineTaskId（方法 diff 时能正确识别"变更"）
        //      - 如果该类是真正新增的 → 设为 NULL（前端显示"本次新增"）
        Long systemId = lookupSystemId(taskId);
        LocalDateTime now = LocalDateTime.now();
        // 加载基线入口的 class_name 集合，用于判断"是新增还是变更"
        java.util.Set<String> baselineClassNames = java.util.Collections.emptySet();
        if (effective.getBaselineTaskId() != null) {
            baselineClassNames = entrypointMapper.selectByTaskId(effective.getBaselineTaskId())
                    .stream().map(EntrypointEntity::getClassName)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());
        }
        int order = 0;
        List<EntrypointEntity> rows = new ArrayList<>(discovered.size());
        for (DiscoveredEntrypoint dep : discovered) {
            EntryPoint base = dep.getBase();
            if (base == null || !StringUtils.hasText(base.getClassName())) continue;
            boolean existedInBaseline = baselineClassNames.contains(base.getClassName());
            EntrypointEntity row = new EntrypointEntity();
            row.setTaskId(taskId);
            row.setSystemId(systemId);
            row.setClassName(base.getClassName());
            row.setFilePath(base.getFilePath());
            row.setEntryType(base.getEntryType());
            row.setAnnotation(base.getAnnotation());
            row.setRemark(base.getRemark());
            row.setMethodsJson(serializeMethods(dep.getMethods()));
            // v1 fix: 基线里有 → 不是"新增"，是"变更"（方法 diff 时再判定是否有变化）
            row.setBaselineTaskId(existedInBaseline ? effective.getBaselineTaskId() : null);
            row.setSortOrder(order++);
            row.setCreatedDate(now);
            row.setUpdatedDate(now);
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            for (EntrypointEntity row : rows) {
                entrypointMapper.insert(row);
            }
        }
        log.info("discoverAndPersist (INCREMENTAL) done. taskId={} baselineTaskId={} changed={} deleted={} newPersisted={}",
                taskId, effective.getBaselineTaskId(),
                effective.getChangedPaths().size(), effective.getDeletedPaths().size(), rows.size());
        return discovered;
    }

    /**
     * INITIAL 任务走全量识别路径（保留原 delete-then-insert 行为，避免影响历史任务）
     */
    private List<DiscoveredEntrypoint> discoverAndPersistFull(Long taskId, File projectDir, EntryPointConfig config) {
        // 1. 全量识别入口 + 方法
        List<DiscoveredEntrypoint> discovered;
        try {
            discovered = entryPointDiscoveryService.discoverEntriesWithMethods(taskId, projectDir, config);
        } catch (Exception e) {
            log.error("discoverEntriesWithMethods 失败，taskId={}", taskId, e);
            entrypointMapper.deleteByTaskId(taskId);
            throw new BusinessException("知识入口识别失败：" + e.getMessage());
        }

        // 2. 清空旧行
        entrypointMapper.deleteByTaskId(taskId);

        // 3. 批量落表
        if (discovered == null || discovered.isEmpty()) {
            log.info("discoverAndPersist: taskId={} 未识别到入口", taskId);
            return Collections.emptyList();
        }

        Long systemId = lookupSystemId(taskId);
        LocalDateTime now = LocalDateTime.now();
        int order = 0;
        List<EntrypointEntity> rows = new ArrayList<>(discovered.size());
        for (DiscoveredEntrypoint dep : discovered) {
            EntryPoint base = dep.getBase();
            if (base == null || !StringUtils.hasText(base.getClassName())) continue;
            EntrypointEntity row = new EntrypointEntity();
            row.setTaskId(taskId);
            row.setSystemId(systemId);
            row.setClassName(base.getClassName());
            row.setFilePath(base.getFilePath());
            row.setEntryType(base.getEntryType());
            row.setAnnotation(base.getAnnotation());
            row.setRemark(base.getRemark());
            row.setMethodsJson(serializeMethods(dep.getMethods()));
            row.setBaselineTaskId(null);
            row.setSortOrder(order++);
            row.setCreatedDate(now);
            row.setUpdatedDate(now);
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            for (EntrypointEntity row : rows) {
                entrypointMapper.insert(row);
            }
        }
        log.info("discoverAndPersist (INITIAL full) done. taskId={} persisted={}", taskId, rows.size());
        return discovered;
    }

    @Override
    public List<EntrypointReviewView> listByTaskId(Long taskId) {
        if (taskId == null) return Collections.emptyList();
        List<EntrypointEntity> rows = entrypointMapper.selectByTaskId(taskId);
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        List<EntrypointReviewView> views = new ArrayList<>(rows.size());
        for (EntrypointEntity row : rows) {
            EntrypointReviewView v = new EntrypointReviewView();
            v.setId(row.getId());
            v.setTaskId(row.getTaskId());
            v.setSystemId(row.getSystemId());
            v.setClassName(row.getClassName());
            v.setFilePath(row.getFilePath());
            v.setEntryType(row.getEntryType());
            v.setAnnotation(row.getAnnotation());
            v.setRemark(row.getRemark());
            v.setEnabled(Boolean.TRUE); // 当前 UI 只读，默认全部启用
            v.setSortOrder(row.getSortOrder());
            v.setMethods(deserializeMethods(row.getMethodsJson()));
            v.setBaselineTaskId(row.getBaselineTaskId());
            views.add(v);
        }
        return views;
    }

    @Override
    public List<EntryPoint> loadEnabledEntries(Long taskId) {
        if (taskId == null) return Collections.emptyList();
        // 当前 UI 不允许禁用入口，所以 loadEnabled 等价于 selectByTaskId；保留 enabled 字段以便未来扩展
        List<EntrypointEntity> rows = entrypointMapper.selectByTaskId(taskId);
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        List<EntryPoint> out = new ArrayList<>(rows.size());
        for (EntrypointEntity row : rows) {
            EntryPoint ep = new EntryPoint();
            ep.setClassName(row.getClassName());
            ep.setFilePath(row.getFilePath());
            ep.setEntryType(row.getEntryType());
            ep.setAnnotation(row.getAnnotation());
            ep.setRemark(row.getRemark());
            out.add(ep);
        }
        return out;
    }

    @Override
    public EntryPointConfig resolveConfig(DecompileTask task) {
        if (task == null) {
            return EntryPointConfig.defaults();
        }
        if (!StringUtils.hasText(task.getEntryScanConfig())) {
            log.warn("resolveConfig: taskId={} entry_scan_config 为空，使用平台默认预置", task.getId());
            return EntryPointConfig.defaults();
        }
        return EntryPointConfigCodec.decode(task.getEntryScanConfig());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyReviewExcludes(Long taskId, List<ExcludeTarget> additionalExcludes) {
        if (taskId == null || additionalExcludes == null || additionalExcludes.isEmpty()) {
            return;
        }
        DecompileTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        EntryPointConfig cfg = resolveConfig(task);
        cfg.appendExcludeTargets(additionalExcludes);
        task.setEntryScanConfig(EntryPointConfigCodec.encode(cfg));
        task.setUpdatedDate(LocalDateTime.now());
        taskMapper.updateById(task);

        List<EntrypointEntity> rows = entrypointMapper.selectByTaskId(taskId);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (EntrypointEntity row : rows) {
            String className = row.getClassName();
            if (!StringUtils.hasText(className)) {
                continue;
            }
            if (isClassExcludedByTarget(className, cfg)) {
                entrypointMapper.deleteById(row.getId());
                continue;
            }
            List<EntrypointMethodView> methods = deserializeMethods(row.getMethodsJson());
            if (methods == null || methods.isEmpty()) {
                continue;
            }
            List<EntrypointMethodView> kept = new ArrayList<>();
            for (EntrypointMethodView m : methods) {
                if (isMethodExcludedByTarget(className, m.getMethodSignature(), cfg)) {
                    continue;
                }
                kept.add(m);
            }
            if (kept.isEmpty()) {
                entrypointMapper.deleteById(row.getId());
            } else if (kept.size() != methods.size()) {
                row.setMethodsJson(serializeMethodsFromViews(kept));
                row.setUpdatedDate(LocalDateTime.now());
                entrypointMapper.updateById(row);
            }
        }
    }

    @Override
    public EntrypointDiffDto getEntrypointDiff(Long taskId) {
        EntrypointDiffDto result = new EntrypointDiffDto();
        DecompileTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return result;
        }
        if (!"INCREMENTAL".equals(task.getType()) || task.getRepositoryId() == null) {
            // INITIAL 任务 / 无仓库：返回空 diff
            return result;
        }
        CodeRepository repo = codeRepositoryService.getById(task.getRepositoryId());
        Long baselineTaskId = repo == null ? null : repo.getLastPublishedTaskId();
        if (baselineTaskId == null) {
            return result;
        }

        // 本次入口（基线继承 + 本次新增）
        List<EntrypointEntity> currentRows = entrypointMapper.selectByTaskId(taskId);
        Set<String> currentClassNames = currentRows.stream()
                .map(EntrypointEntity::getClassName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 基线入口
        List<EntrypointEntity> baselineRows = entrypointMapper.selectByTaskId(baselineTaskId);
        Map<String, EntrypointEntity> baselineByName = new HashMap<>();
        for (EntrypointEntity r : baselineRows) {
            if (r.getClassName() != null) {
                baselineByName.put(r.getClassName(), r);
            }
        }

        // 本次新增（类级）
        List<EntrypointReviewView> newRows = currentRows.stream()
                .filter(r -> r.getBaselineTaskId() == null)
                .map(this::toReviewView)
                .collect(Collectors.toList());

        // 基线继承 → 按方法 diff 拆分为 modified / inherited
        List<EntrypointReviewView> modifiedRows = new ArrayList<>();
        List<EntrypointReviewView> inheritedRows = new ArrayList<>();
        for (EntrypointEntity row : currentRows) {
            if (row.getBaselineTaskId() == null) continue;
            EntrypointEntity baselineRow = baselineByName.get(row.getClassName());
            // v1 fix: 先 deserialize 方法并设 diffStatus，再用 currentMethods 构造 view（不调 toReviewView，
            //       避免内部再 deserialize 一次产生另一份 list 把 diffStatus 丢光）
            List<EntrypointMethodView> currentMethods = deserializeMethods(row.getMethodsJson());
            List<EntrypointMethodView> baselineMethods = baselineRow != null
                    ? deserializeMethods(baselineRow.getMethodsJson()) : Collections.emptyList();
            Set<String> baselineSigs = new HashSet<>();
            Map<String, String> baselineBodyBySig = new HashMap<>();
            for (EntrypointMethodView m : baselineMethods) {
                if (m.getMethodSignature() != null) {
                    baselineSigs.add(m.getMethodSignature());
                    if (StringUtils.hasText(m.getBodyHash())) {
                        baselineBodyBySig.put(m.getMethodSignature(), m.getBodyHash());
                    }
                }
            }
            Set<String> currentSigs = new HashSet<>();
            boolean hasMethodChanges = false;
            for (EntrypointMethodView m : currentMethods) {
                if (m.getMethodSignature() != null) {
                    currentSigs.add(m.getMethodSignature());
                    if (!baselineSigs.contains(m.getMethodSignature())) {
                        m.setDiffStatus("new");
                        hasMethodChanges = true;
                    } else {
                        String baseHash = baselineBodyBySig.get(m.getMethodSignature());
                        String curHash = m.getBodyHash();
                        // 双方都有 hash 且不同 → 内容变更；缺 hash（历史数据）→ 降级 unchanged
                        if (StringUtils.hasText(baseHash) && StringUtils.hasText(curHash)
                                && !baseHash.equals(curHash)) {
                            m.setDiffStatus("modified");
                            hasMethodChanges = true;
                        } else {
                            m.setDiffStatus("unchanged");
                        }
                    }
                }
            }
            // 基线有 + 本次无 = deleted 方法（并入 currentMethods 同一份 list）
            for (EntrypointMethodView m : baselineMethods) {
                if (m.getMethodSignature() != null && !currentSigs.contains(m.getMethodSignature())) {
                    m.setDiffStatus("deleted");
                    currentMethods.add(m);  // ← 关键：并到 currentMethods 而不是 view
                    hasMethodChanges = true;
                }
            }
            // 构造 view：直接用 currentMethods 作为 methods，diffStatus 保留
            EntrypointReviewView view = new EntrypointReviewView();
            view.setId(row.getId());
            view.setTaskId(row.getTaskId());
            view.setSystemId(row.getSystemId());
            view.setClassName(row.getClassName());
            view.setFilePath(row.getFilePath());
            view.setEntryType(row.getEntryType());
            view.setAnnotation(row.getAnnotation());
            view.setRemark(row.getRemark());
            view.setEnabled(Boolean.TRUE);
            view.setSortOrder(row.getSortOrder());
            view.setMethods(currentMethods);
            view.setBaselineTaskId(row.getBaselineTaskId());
            if (hasMethodChanges) {
                modifiedRows.add(view);
            } else {
                inheritedRows.add(view);
            }
        }

        // 本次删除（基线有 + 本次无 — 整个类被删）
        List<EntrypointReviewView> deletedRows = baselineRows.stream()
                .filter(r -> !currentClassNames.contains(r.getClassName()))
                .map(this::toReviewView)
                .peek(v -> {
                    // deleted 类的所有方法都标记为 deleted
                    if (v.getMethods() != null) {
                        v.getMethods().forEach(m -> m.setDiffStatus("deleted"));
                    }
                })
                .collect(Collectors.toList());

        result.setNewRows(newRows);
        result.setModifiedRows(modifiedRows);
        result.setInheritedRows(inheritedRows);
        result.setDeletedRows(deletedRows);
        log.info("EntrypointDiff — taskId={} baselineTaskId={} new={} modified={} inherited={} deleted={}",
                taskId, baselineTaskId, newRows.size(), modifiedRows.size(), inheritedRows.size(), deletedRows.size());
        return result;
    }

    /**
     * EntrypointEntity → EntrypointReviewView 转换器（供 getEntrypointDiff 复用）
     */
    private EntrypointReviewView toReviewView(EntrypointEntity row) {
        EntrypointReviewView v = new EntrypointReviewView();
        v.setId(row.getId());
        v.setTaskId(row.getTaskId());
        v.setSystemId(row.getSystemId());
        v.setClassName(row.getClassName());
        v.setFilePath(row.getFilePath());
        v.setEntryType(row.getEntryType());
        v.setAnnotation(row.getAnnotation());
        v.setRemark(row.getRemark());
        v.setEnabled(Boolean.TRUE);
        v.setSortOrder(row.getSortOrder());
        v.setMethods(deserializeMethods(row.getMethodsJson()));
        v.setBaselineTaskId(row.getBaselineTaskId());
        return v;
    }

    private boolean isClassExcludedByTarget(String className, EntryPointConfig cfg) {
        for (ExcludeTarget t : cfg.getEffectiveExcludeTargets()) {
            if (t != null && StringUtils.hasText(t.getClassName())
                    && className.equals(t.getClassName().trim()) && t.isClassLevel()) {
                return true;
            }
        }
        return false;
    }

    private boolean isMethodExcludedByTarget(String className, String methodSignature, EntryPointConfig cfg) {
        for (ExcludeTarget t : cfg.getEffectiveExcludeTargets()) {
            if (t == null || !StringUtils.hasText(t.getClassName())) {
                continue;
            }
            if (!className.equals(t.getClassName().trim())) {
                continue;
            }
            if (t.isClassLevel()) {
                return true;
            }
            if (StringUtils.hasText(methodSignature)
                    && methodSignature.equals(t.getMethodSignature().trim())) {
                return true;
            }
        }
        return false;
    }

    private String serializeMethodsFromViews(List<EntrypointMethodView> methods) {
        try {
            return objectMapper.writeValueAsString(methods);
        } catch (Exception e) {
            log.warn("serializeMethodsFromViews failed", e);
            return "[]";
        }
    }

    @Override
    public Map<String, List<EntrypointMethodView>> loadMethodsByClassName(Long taskId) {
        if (taskId == null) return Collections.emptyMap();
        List<EntrypointEntity> rows = entrypointMapper.selectByTaskId(taskId);
        if (rows == null || rows.isEmpty()) return Collections.emptyMap();
        Map<String, List<EntrypointMethodView>> map = new HashMap<>();
        for (EntrypointEntity row : rows) {
            if (!StringUtils.hasText(row.getClassName())) continue;
            map.put(row.getClassName(), deserializeMethods(row.getMethodsJson()));
        }
        return map;
    }

    // ============================ private helpers ============================

    private Long lookupSystemId(Long taskId) {
        try {
            DecompileTask t = taskMapper.selectById(taskId);
            return t == null ? null : t.getSystemId();
        } catch (Exception e) {
            log.warn("lookupSystemId 失败 taskId={}", taskId, e);
            return null;
        }
    }

    private String serializeMethods(List<DiscoveredMethod> methods) {
        if (methods == null || methods.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(methods);
        } catch (Exception e) {
            log.warn("serializeMethods 失败", e);
            return null;
        }
    }

    private List<EntrypointMethodView> deserializeMethods(String json) {
        if (!StringUtils.hasText(json)) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<EntrypointMethodView>>() {});
        } catch (Exception e) {
            log.warn("deserializeMethods 失败", e);
            return new ArrayList<>();
        }
    }
}