package com.company.codeinsight.modules.callchain.service.impl;

import com.company.codeinsight.modules.callchain.model.ImpactTrace;
import com.company.codeinsight.modules.callchain.model.IncrementalImpact;
import com.company.codeinsight.modules.callchain.service.IncrementalImpactPersistence;
import com.company.codeinsight.modules.entrypoint.model.EntryPoint;
import com.company.codeinsight.modules.hierarchy.model.ModuleDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy;
import com.company.codeinsight.modules.hierarchy.service.ModuleHierarchyService;
import com.company.codeinsight.modules.scanner.model.IncrementalContext;
import com.company.codeinsight.modules.task.dto.EntryPointSummaryDto;
import com.company.codeinsight.modules.task.dto.ImpactTraceDto;
import com.company.codeinsight.modules.task.dto.IncrementalImpactDto;
import com.company.codeinsight.modules.task.dto.ModuleSummaryDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class IncrementalImpactPersistenceImpl implements IncrementalImpactPersistence {

    public static final String FILE_NAME = "incremental-impact.json";

    private final ModuleHierarchyService moduleHierarchyService;
    private final ObjectMapper objectMapper;

    @Value("${code-insight.storage.local-path:./storage}")
    private String storageBase;

    public IncrementalImpactPersistenceImpl(ModuleHierarchyService moduleHierarchyService) {
        this.moduleHierarchyService = moduleHierarchyService;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void persist(Long taskId, IncrementalImpact impact, IncrementalContext ctx) {
        if (taskId == null || impact == null || !impact.isIncremental()) {
            return;
        }
        IncrementalImpactDto dto = toDto(taskId, impact, ctx);
        File file = impactFile(taskId);
        try {
            file.getParentFile().mkdirs();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, dto);
        } catch (IOException e) {
            log.warn("写入 incremental-impact.json 失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    @Override
    public void delete(Long taskId) {
        if (taskId == null) {
            return;
        }
        File file = impactFile(taskId);
        if (file.exists() && !file.delete()) {
            log.warn("删除 incremental-impact.json 失败 taskId={}", taskId);
        }
    }

    public IncrementalImpactDto read(Long taskId) {
        File file = impactFile(taskId);
        if (!file.exists()) {
            return null;
        }
        try {
            return objectMapper.readValue(file, IncrementalImpactDto.class);
        } catch (IOException e) {
            log.warn("读取 incremental-impact.json 失败 taskId={}: {}", taskId, e.getMessage());
            return null;
        }
    }

    private File impactFile(Long taskId) {
        return new File(storageBase, "task_" + taskId + "/" + FILE_NAME);
    }

    private IncrementalImpactDto toDto(Long taskId, IncrementalImpact impact, IncrementalContext ctx) {
        IncrementalImpactDto dto = new IncrementalImpactDto();
        dto.setIncremental(true);
        dto.setAvailable(true);
        dto.setScanMode(impact.getScanMode());
        dto.setBaselineCommitId(impact.getBaselineCommitId());
        dto.setHeadCommitId(impact.getHeadCommitId());
        if (ctx != null) {
            dto.setChangedPaths(new ArrayList<>(ctx.getChangedPaths()));
            dto.setDeletedPaths(new ArrayList<>(ctx.getDeletedPaths()));
        }
        dto.setHierarchyRetargetEntryCount(impact.getHierarchyRetargetEntries().size());
        for (EntryPoint ep : impact.getHierarchyRetargetEntries()) {
            EntryPointSummaryDto summary = new EntryPointSummaryDto();
            summary.setClassName(ep.getClassName());
            summary.setFilePath(ep.getFilePath());
            dto.getHierarchyRetargetEntries().add(summary);
        }
        dto.getDocRetargetModuleIds().addAll(impact.getDocRetargetModuleIds());
        ModuleHierarchy hierarchy = moduleHierarchyService.loadByTaskId(taskId);
        for (String moduleId : impact.getDocRetargetModuleIds()) {
            ModuleSummaryDto ms = new ModuleSummaryDto();
            ms.setModuleId(moduleId);
            ModuleDto module = hierarchy.getModules().get(moduleId);
            ms.setModuleName(module != null ? module.getModuleName() : moduleId);
            dto.getDocRetargetModules().add(ms);
        }
        for (ImpactTrace trace : impact.getTraces()) {
            ImpactTraceDto t = new ImpactTraceDto();
            t.setChangedFqcn(trace.changedFqcn());
            t.setPath(trace.path());
            t.setModuleId(trace.moduleId());
            t.setModuleName(trace.moduleName());
            t.setKind(trace.kind().name());
            dto.getTraces().add(t);
        }
        dto.setDegradedModuleCount(impact.getDegradedModuleCount());
        dto.setComputedAt(LocalDateTime.now().toString());
        return dto;
    }
}
