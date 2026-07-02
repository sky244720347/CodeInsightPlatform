package com.company.codeinsight.modules.entrypoint.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.exception.BusinessException;
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
import java.util.List;
import java.util.Map;

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<DiscoveredEntrypoint> discoverAndPersist(Long taskId, File projectDir, EntryPointConfig config) {
        if (taskId == null) {
            throw new BusinessException("taskId 不能为空");
        }
        if (projectDir == null || !projectDir.exists()) {
            log.warn("discoverAndPersist: projectDir 无效，跳过入口识别 taskId={}", taskId);
            return Collections.emptyList();
        }

        // 1. 全量识别入口 + 方法（无论后续 review flag 如何，都先识别一次）
        List<DiscoveredEntrypoint> discovered;
        try {
            discovered = entryPointDiscoveryService.discoverEntriesWithMethods(taskId, projectDir, config);
        } catch (Exception e) {
            log.error("discoverEntriesWithMethods 失败，taskId={}", taskId, e);
            // 识别失败时清空旧行，保证 AI 阶段不会读到陈旧数据
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

        // taskId → systemId 用于冗余字段
        DecompileTask taskRef = new DecompileTask();
        taskRef.setId(taskId);
        // systemId 在调用方 resolveConfig 完成后已知；这里仅依赖 task_id 落表行；
        // 真实 systemId 由 controller / 调用方在调用本方法前查好并经由 EntryPoint.remark 携带不现实，
        // 所以我们在 insert 时直接从 task 缓存拿 systemId；此处约定调用方在调用本方法前已经把 task 持久化。
        // 为了不破坏现有签名，systemId 暂由调用方经 DiscoveredEntrypoint.base.remark 以外的方式传不进去——
        // 简化方案：调用方在 pipeline 中已知 task，直接读数据库得到 systemId。
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
            row.setSortOrder(order++);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            for (EntrypointEntity row : rows) {
                entrypointMapper.insert(row);
            }
        }
        log.info("discoverAndPersist done. taskId={} persisted={}", taskId, rows.size());
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
        task.setUpdatedAt(LocalDateTime.now());
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
                row.setUpdatedAt(LocalDateTime.now());
                entrypointMapper.updateById(row);
            }
        }
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