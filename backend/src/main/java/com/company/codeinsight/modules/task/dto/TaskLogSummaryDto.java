package com.company.codeinsight.modules.task.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务执行日志摘要 DTO。
 */
@Data
public class TaskLogSummaryDto {

    private Long taskId;
    private String status;
    private Integer progress;
    private Long durationMs;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private String modelName;
    private Boolean aiMock;

    private List<PipelineStageStatDto> pipeline;
    private Counters counters;
    private AiCalls aiCalls;
    private AiCalls hierarchyAiCalls;
    private AiCalls docAiCalls;
    private Current current;
    private String lastError;

    @Data
    public static class Counters {
        private Integer totalFiles;
    }

    @Data
    public static class AiCalls {
        private Integer total;
        private Integer success;
        private Integer failed;
    }

    @Data
    public static class Current {
        private Integer totalFiles;
        private Integer moduleIndex;
        private Integer moduleTotal;
    }
}
