package com.company.codeinsight.modules.callchain.model;

import com.company.codeinsight.modules.entrypoint.model.EntryPoint;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 增量影响分析结果：决定 MODULE_HIERARCHY / GENERATING_DOC 阶段谁需要跑 AI。
 */
public final class IncrementalImpact {

    private final boolean incremental;
    private final Set<EntryPoint> hierarchyRetargetEntries;
    private final Set<String> docRetargetModuleIds;
    private final List<ImpactTrace> traces;
    private final int degradedModuleCount;
    private final String baselineCommitId;
    private final String headCommitId;
    private final String scanMode;
    private final long analyzeDurationMs;

    private IncrementalImpact(boolean incremental,
                              Set<EntryPoint> hierarchyRetargetEntries,
                              Set<String> docRetargetModuleIds,
                              List<ImpactTrace> traces,
                              int degradedModuleCount,
                              String baselineCommitId,
                              String headCommitId,
                              String scanMode,
                              long analyzeDurationMs) {
        this.incremental = incremental;
        this.hierarchyRetargetEntries = hierarchyRetargetEntries == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(hierarchyRetargetEntries));
        this.docRetargetModuleIds = docRetargetModuleIds == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(docRetargetModuleIds));
        this.traces = traces == null ? List.of() : List.copyOf(traces);
        this.degradedModuleCount = degradedModuleCount;
        this.baselineCommitId = baselineCommitId;
        this.headCommitId = headCommitId;
        this.scanMode = scanMode;
        this.analyzeDurationMs = analyzeDurationMs;
    }

    public static IncrementalImpact fullScan() {
        return new IncrementalImpact(false, Set.of(), Set.of(), List.of(), 0,
                null, null, "INITIAL", 0);
    }

    public static IncrementalImpact emptyIncremental(String baselineCommitId, String headCommitId, String scanMode) {
        return new IncrementalImpact(true, Set.of(), Set.of(), List.of(), 0,
                baselineCommitId, headCommitId, scanMode, 0);
    }

    public static IncrementalImpact of(boolean incremental,
                                       Set<EntryPoint> hierarchyRetargetEntries,
                                       Set<String> docRetargetModuleIds,
                                       List<ImpactTrace> traces,
                                       int degradedModuleCount,
                                       String baselineCommitId,
                                       String headCommitId,
                                       String scanMode,
                                       long analyzeDurationMs) {
        return new IncrementalImpact(incremental, hierarchyRetargetEntries, docRetargetModuleIds,
                traces, degradedModuleCount, baselineCommitId, headCommitId, scanMode, analyzeDurationMs);
    }

    public boolean isIncremental() {
        return incremental;
    }

    public Set<EntryPoint> getHierarchyRetargetEntries() {
        return hierarchyRetargetEntries;
    }

    public Set<String> getDocRetargetModuleIds() {
        return docRetargetModuleIds;
    }

    public List<ImpactTrace> getTraces() {
        return traces;
    }

    public int getDegradedModuleCount() {
        return degradedModuleCount;
    }

    public String getBaselineCommitId() {
        return baselineCommitId;
    }

    public String getHeadCommitId() {
        return headCommitId;
    }

    public String getScanMode() {
        return scanMode;
    }

    public long getAnalyzeDurationMs() {
        return analyzeDurationMs;
    }

    public boolean isHierarchyRetarget(EntryPoint entry) {
        if (entry == null || hierarchyRetargetEntries.isEmpty()) {
            return false;
        }
        for (EntryPoint ep : hierarchyRetargetEntries) {
            if (entry.getClassName() != null && entry.getClassName().equals(ep.getClassName())) {
                return true;
            }
            if (entry.getFilePath() != null && entry.getFilePath().equals(ep.getFilePath())) {
                return true;
            }
        }
        return false;
    }

    public long reverseBfsHitCount() {
        return traces.stream().filter(t -> t.kind() == ImpactTraceKind.REVERSE_BFS).count();
    }
}
