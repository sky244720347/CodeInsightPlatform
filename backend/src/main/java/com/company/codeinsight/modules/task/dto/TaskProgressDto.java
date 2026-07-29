package com.company.codeinsight.modules.task.dto;

import lombok.Data;

/**
 * 任务进度轻量 DTO（前端轮询用）。
 * <p>独立顶层类，避免 Controller 内部类在 spring-boot 热加载时 ClassNotFound。</p>
 */
@Data
public class TaskProgressDto {
    private String status;
    private Integer progress;
    private String errorReason;
}
