package com.company.codeinsight.modules.callchain.model;

/**
 * 单条增量影响追溯（日志 / API 展示用）。
 */
public record ImpactTrace(
        String changedFqcn,
        String path,
        String moduleId,
        String moduleName,
        ImpactTraceKind kind) {
}
