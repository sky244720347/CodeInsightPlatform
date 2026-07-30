package com.company.codeinsight.modules.parser.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 任务解析内存统一入口：驱逐 JavaParser 侧无界缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskParseMemoryService {

    /** 静态 SymbolSolver / subtype 条目数告警阈值（跨任务累加时提示运维）。 */
    private static final int GLOBAL_CACHE_WARN_THRESHOLD = 8;

    private final JavaParserService javaParserService;

    /** 幂等释放该任务的解析缓存。 */
    public void evict(Long taskId) {
        if (taskId == null) {
            return;
        }
        try {
            javaParserService.evictTaskCaches(taskId);
            String stats = javaParserService.cacheStatsSummary();
            if (stats != null && !stats.equals("n/a")) {
                log.debug("TaskParseMemoryService.evict taskId={} stats={}", taskId, stats);
            }
            warnIfGlobalCachesLarge();
        } catch (Exception e) {
            log.warn("TaskParseMemoryService.evict 失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    /**
     * 清空全部解析缓存（运维排障）。进行中的解析任务将失去缓存命中。
     */
    public void clearAll() {
        try {
            javaParserService.clearAllCaches();
            log.warn("TaskParseMemoryService.clearAll done stats={}", javaParserService.cacheStatsSummary());
        } catch (Exception e) {
            log.warn("TaskParseMemoryService.clearAll 失败: {}", e.getMessage());
        }
    }

    public String cacheStatsSummary() {
        try {
            return javaParserService.cacheStatsSummary();
        } catch (Exception e) {
            return "error:" + e.getMessage();
        }
    }

    private void warnIfGlobalCachesLarge() {
        try {
            String stats = javaParserService.cacheStatsSummary();
            // 粗略从摘要解析：若含 symbolSolver=N 且 N 偏大则告警
            int solver = extractCount(stats, "symbolSolver=");
            int subtype = extractCount(stats, "subtypeIndex=");
            if (solver >= GLOBAL_CACHE_WARN_THRESHOLD || subtype >= GLOBAL_CACHE_WARN_THRESHOLD) {
                log.warn("解析全局缓存偏大（可能未按任务驱逐干净）: {}", stats);
            }
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static int extractCount(String stats, String key) {
        if (stats == null) {
            return 0;
        }
        int i = stats.indexOf(key);
        if (i < 0) {
            return 0;
        }
        int start = i + key.length();
        int end = start;
        while (end < stats.length() && Character.isDigit(stats.charAt(end))) {
            end++;
        }
        if (end == start) {
            return 0;
        }
        try {
            return Integer.parseInt(stats.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
