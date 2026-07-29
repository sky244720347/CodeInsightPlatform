package com.company.codeinsight.modules.task.service;

import com.company.codeinsight.modules.task.dto.BatchInitialTriggerResult;

/**
 * 全平台一键触发全量 INITIAL 任务（仅入队 PENDING，由各节点抢占执行）。
 */
public interface BatchInitialTriggerService {

    /**
     * 异步提交：立刻返回 jobId；后台逐仓 create+start。
     * <p>同时只允许一个作业；已有进行中作业时拒绝。</p>
     */
    BatchInitialTriggerResult submitAsync();

    /**
     * 查询异步作业进度 / 结果。
     */
    BatchInitialTriggerResult getJob(String jobId);
}
