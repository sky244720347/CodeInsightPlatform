package com.company.codeinsight.modules.scanner.service;

import com.company.codeinsight.modules.scanner.entity.IncrementalScanRecord;
import com.company.codeinsight.modules.scanner.mapper.IncrementalScanMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * v1: 增量扫描结果落盘服务
 * <p>在 PULLING_CODE 阶段末尾被调用，把 INCREMENTAL 任务的 git diff 结果
 * （changedPaths / deletedPaths / baselineTaskId / baselineCommitId / headCommitId）
 * 持久化到 ci_incremental_scan 表。供后续 PARSING_CODE / ENTRYPOINT_DISCOVERY /
 * MODULE_HIERARCHY / GENERATING_DOC 阶段读取。</p>
 *
 * <p>方案 B：覆盖写 = {@code logicDeleteByTaskId} + {@code insert}（禁止物理 DELETE / upsert）。</p>
 */
@Slf4j
@Service
public class IncrementalScanPersistenceService {

    @Autowired
    private IncrementalScanMapper incrementalScanMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 持久化扫描结果。INITIAL 任务也会写入（baselineTaskId / baselineCommitId 为 NULL），
     * 便于流水线下游按 taskId 统一查询。
     *
     * @param taskId          本次任务 ID
     * @param systemId        系统 ID
     * @param repositoryId    仓库 ID
     * @param baselineTaskId  基线任务 ID（INITIAL 任务为 null）
     * @param baselineCommitId 基线 commit（INITIAL 任务为 null）
     * @param headCommitId    本次 HEAD commit
     * @param changedPaths    本次变更文件路径集合
     * @param deletedPaths    本次删除文件路径集合
     */
    public void persist(Long taskId, Long systemId, Long repositoryId,
                        Long baselineTaskId, String baselineCommitId, String headCommitId,
                        Set<String> changedPaths, Set<String> deletedPaths) {
        if (taskId == null) {
            return;
        }
        String scanMode = baselineTaskId == null ? "INITIAL" : "INCREMENTAL";
        IncrementalScanRecord record = new IncrementalScanRecord();
        record.setId(null);
        record.setTaskId(taskId);
        record.setSystemId(systemId);
        record.setRepositoryId(repositoryId);
        record.setBaselineTaskId(baselineTaskId);
        record.setBaselineCommitId(baselineCommitId);
        record.setHeadCommitId(headCommitId);
        record.setScanMode(scanMode);
        record.setChangedPaths(serializePaths(changedPaths));
        record.setDeletedPaths(serializePaths(deletedPaths));
        record.setInheritedCount(0);   // 基线继承完成后会回填
        record.setIsDeleted(0);
        record.setCreatedBy("sys");
        record.setUpdatedBy("sys");
        record.setCreatedDate(LocalDateTime.now());
        record.setUpdatedDate(LocalDateTime.now());

        // 方案 B：逻辑删腾出活行唯一键，再 plain insert
        incrementalScanMapper.logicDeleteByTaskId(taskId);
        incrementalScanMapper.insert(record);
        log.info("增量扫描落盘 — taskId={} scanMode={} baselineTaskId={} changed={} deleted={}",
                taskId, scanMode, baselineTaskId,
                changedPaths == null ? 0 : changedPaths.size(),
                deletedPaths == null ? 0 : deletedPaths.size());
    }

    /**
     * 更新已落盘记录的 inheritedCount（基线继承完成后调用）
     */
    public void updateInheritedCount(Long taskId, int inheritedCount) {
        if (taskId == null) {
            return;
        }
        IncrementalScanRecord record = incrementalScanMapper.selectActiveByTaskId(taskId);
        if (record == null) {
            return;
        }
        record.setInheritedCount(inheritedCount);
        record.setUpdatedDate(LocalDateTime.now());
        incrementalScanMapper.updateById(record);
    }

    /**
     * 查询本任务的扫描结果（仅活行）
     */
    public IncrementalScanRecord findByTaskId(Long taskId) {
        if (taskId == null) {
            return null;
        }
        return incrementalScanMapper.selectActiveByTaskId(taskId);
    }

    /**
     * 反序列化 changedPaths / deletedPaths 字段（JSON 字符串 → Set）
     */
    public Set<String> deserializePaths(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptySet();
        }
        try {
            return new LinkedHashSet<>(objectMapper.readValue(json, new TypeReference<List<String>>() {}));
        } catch (Exception e) {
            log.warn("反序列化路径失败：{}", e.getMessage());
            return Collections.emptySet();
        }
    }

    private String serializePaths(Set<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(paths);
        } catch (Exception e) {
            log.warn("序列化路径失败：{}", e.getMessage());
            return "[]";
        }
    }
}
