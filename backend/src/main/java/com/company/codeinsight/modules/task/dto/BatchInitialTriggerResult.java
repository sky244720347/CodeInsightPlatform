package com.company.codeinsight.modules.task.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 一键全量触发 — 异步任务状态 / 汇总结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchInitialTriggerResult {

    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    /** 异步作业 ID；提交后立刻返回，可用于轮询 */
    private String jobId;

    /** ACCEPTED / RUNNING / COMPLETED / FAILED */
    private String status;

    private int totalRepos;
    /** 已处理仓数（含触发/跳过/失败） */
    private int processedRepos;
    private int triggered;
    private int skipped;
    private int failed;

    /** 作业级说明（如失败原因） */
    private String message;

    @Builder.Default
    private List<BatchInitialItemResult> items = new ArrayList<>();
}
