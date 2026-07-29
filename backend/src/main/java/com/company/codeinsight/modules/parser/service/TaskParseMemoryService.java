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

    private final JavaParserService javaParserService;

    /** 幂等释放该任务的解析缓存。 */
    public void evict(Long taskId) {
        if (taskId == null) {
            return;
        }
        try {
            javaParserService.evictTaskCaches(taskId);
        } catch (Exception e) {
            log.warn("TaskParseMemoryService.evict 失败 taskId={}: {}", taskId, e.getMessage());
        }
    }
}
