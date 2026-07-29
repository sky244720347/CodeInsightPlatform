package com.company.codeinsight.modules.task.support;

/**
 * 断点续跑排队：{@code ci_task.resume_from} 取值（与纠错任务的 AI_ANALYZING / GENERATING_DOC 区分）。
 */
public final class TaskResumeConstants {

    /** 入口复核通过后：从 AI 归纳段续跑 */
    public static final String AFTER_ENTRYPOINT = "AFTER_ENTRYPOINT";

    /** 模块层级复核通过后：从基线继承 / 文档生成续跑 */
    public static final String AFTER_HIERARCHY = "AFTER_HIERARCHY";

    private TaskResumeConstants() {
    }
}
