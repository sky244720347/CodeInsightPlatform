package com.company.codeinsight.modules.callchain.service.impl;

import com.company.codeinsight.modules.callchain.mapper.MethodCallMapper;
import com.company.codeinsight.modules.callchain.model.EntryMethodHit;
import com.company.codeinsight.modules.callchain.model.ImpactTrace;
import com.company.codeinsight.modules.callchain.model.ImpactTraceKind;
import com.company.codeinsight.modules.callchain.model.IncrementalImpact;
import com.company.codeinsight.modules.callchain.service.IncrementalImpactAnalyzer;
import com.company.codeinsight.modules.callchain.service.MethodCallReverseGraphService;
import com.company.codeinsight.modules.callchain.support.IncrementalImpactSupport;
import com.company.codeinsight.modules.entrypoint.model.EntryPoint;
import com.company.codeinsight.modules.hierarchy.model.ModuleDto;
import com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy;
import com.company.codeinsight.modules.hierarchy.service.impl.ModuleHierarchyServiceImpl;
import com.company.codeinsight.modules.scanner.model.IncrementalContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IncrementalImpactAnalyzerImpl implements IncrementalImpactAnalyzer {

    private static final int DEFAULT_MAX_DEPTH = 15;

    private final MethodCallReverseGraphService reverseGraphService;
    private final MethodCallMapper methodCallMapper;

    @Override
    public IncrementalImpact analyze(Long taskId,
                                     File projectDir,
                                     IncrementalContext ctx,
                                     ModuleHierarchy hierarchy,
                                     List<EntryPoint> enabledEntries,
                                     String baselineCommitId,
                                     String headCommitId,
                                     String scanMode) {
        if (ctx == null || !ctx.isIncremental()) {
            return IncrementalImpact.fullScan();
        }
        long t0 = System.currentTimeMillis();
        Set<EntryPoint> hierarchyRetarget = new LinkedHashSet<>();
        Set<String> docModules = new LinkedHashSet<>();
        List<ImpactTrace> traces = new ArrayList<>();
        Set<String> changedFqSet = new HashSet<>();
        Set<String> entryClassNames = enabledEntries == null ? Set.of()
                : enabledEntries.stream()
                .map(EntryPoint::getClassName)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (String path : ctx.getChangedPaths()) {
            if (!path.endsWith(".java")) {
                continue;
            }
            String fq = ModuleHierarchyServiceImpl.deriveFqcnFromPath(path);
            if (!StringUtils.hasText(fq)) {
                continue;
            }
            changedFqSet.add(fq);
            EntryPoint matched = matchEntry(fq, path, enabledEntries);
            if (matched != null) {
                hierarchyRetarget.add(matched);
                for (String moduleId : IncrementalImpactSupport.mapEntryClassToModuleIds(hierarchy, fq)) {
                    docModules.add(moduleId);
                    traces.add(new ImpactTrace(fq, matched.getClassName() + " (入口直接变更)",
                            moduleId, moduleName(hierarchy, moduleId), ImpactTraceKind.ENTRY_DIRECT));
                }
                continue;
            }
            List<EntryMethodHit> hits = reverseGraphService.resolveCallingEntries(
                    taskId, fq, entryClassNames, DEFAULT_MAX_DEPTH);
            for (EntryMethodHit hit : hits) {
                for (String moduleId : IncrementalImpactSupport.mapEntryMethodToModuleIds(hierarchy, hit)) {
                    docModules.add(moduleId);
                    String chain = buildChainPath(fq, hit);
                    traces.add(new ImpactTrace(fq, chain, moduleId, moduleName(hierarchy, moduleId),
                            ImpactTraceKind.REVERSE_BFS));
                }
            }
        }

        for (String path : ctx.getDeletedPaths()) {
            String fq = ModuleHierarchyServiceImpl.deriveFqcnFromPath(path);
            if (StringUtils.hasText(fq)) {
                changedFqSet.add(fq);
            }
        }

        // Phase 3：扩展 changedFqSet，把"具象实现改动"隐含扩展到所有 polymorphic ancestors
        // 这样 module.classPaths 里只引接口不引具象的函数也会被判定为受改动影响。
        Set<String> expandedChangedFqSet = IncrementalImpactSupport.expandChangedFqSetWithPolymorphicAncestors(
                taskId, changedFqSet, methodCallMapper);
        if (expandedChangedFqSet.size() > changedFqSet.size()) {
            log.debug("IncrementalImpactAnalyzer 多态扩展 — taskId={} 原始 changedFqCount={} 扩展后={}",
                    taskId, changedFqSet.size(), expandedChangedFqSet.size());
        }

        int degradedCount = 0;
        if (hierarchy != null && hierarchy.getModules() != null) {
            for (ModuleDto module : hierarchy.getModules().values()) {
                if (!IncrementalImpactSupport.moduleTouchedByChange(module, expandedChangedFqSet)) {
                    continue;
                }
                if (!docModules.contains(module.getId())) {
                    degradedCount++;
                    traces.add(new ImpactTrace(
                            String.join(", ", changedFqSet),
                            "classPaths 直接命中",
                            module.getId(),
                            module.getModuleName(),
                            ImpactTraceKind.DEGRADED_CLASS_PATH));
                }
                docModules.add(module.getId());
            }
        }

        long duration = System.currentTimeMillis() - t0;
        log.info("IncrementalImpactAnalyzer taskId={} changedClasses={} entryRetarget={} docModules={} reverseHits={} degraded={} durationMs={}",
                taskId, changedFqSet.size(), hierarchyRetarget.size(), docModules.size(),
                traces.stream().filter(t -> t.kind() == ImpactTraceKind.REVERSE_BFS).count(),
                degradedCount, duration);

        return IncrementalImpact.of(true, hierarchyRetarget, docModules, traces, degradedCount,
                baselineCommitId, headCommitId, scanMode, duration);
    }

    private EntryPoint matchEntry(String fqcn, String relativePath, List<EntryPoint> entries) {
        if (entries == null) {
            return null;
        }
        for (EntryPoint entry : entries) {
            if (fqcn.equals(entry.getClassName()) || relativePath.equals(entry.getFilePath())) {
                return entry;
            }
        }
        return null;
    }

    private String moduleName(ModuleHierarchy hierarchy, String moduleId) {
        if (hierarchy == null || hierarchy.getModules() == null) {
            return moduleId;
        }
        ModuleDto module = hierarchy.getModules().get(moduleId);
        return module != null ? module.getModuleName() : moduleId;
    }

    private String buildChainPath(String changedFqcn, EntryMethodHit hit) {
        String changedMethod = IncrementalImpactSupport.simpleClassName(changedFqcn);
        String entryMethod = IncrementalImpactSupport.extractMethodName(hit.entryMethodSignature());
        return changedMethod + "#? → " + IncrementalImpactSupport.simpleClassName(hit.entryClassName())
                + "#" + entryMethod;
    }
}
