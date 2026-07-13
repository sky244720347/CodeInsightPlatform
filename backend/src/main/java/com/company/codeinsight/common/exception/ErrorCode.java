package com.company.codeinsight.common.exception;

import lombok.Getter;

/**
 * 统一业务错误码常量。
 * <p>门禁类错误码区间 2000-2099（增量任务域）。前端可按 code 区分错误种类。</p>
 */
@Getter
public enum ErrorCode {

    // === 增量任务域（2000-2099）===

    /**
     * 仓库从未发布过任何知识版本（lastPublishedVersionId / lastCommitId 为空），
     * 创建 INCREMENTAL 任务时硬性拒绝。
     */
    INCREMENTAL_NO_BASELINE(2001,
            "仓库尚未发布过任何知识版本，无法创建增量任务。请先完成一次 INITIAL 任务并推送，或显式选择 INITIAL 重跑全量。"),

    /**
     * 仓库使用本地路径模式（gitUrl 指向本地目录），
     * INCREMENTAL 任务不支持本地 diff，硬性拒绝。
     */
    INCREMENTAL_LOCAL_PATH_NOT_SUPPORTED(2002,
            "本地路径模式不支持增量任务，请切换到 Git 仓库后再发起增量。"),

    /**
     * 增量基线 commit 不可解析（force-push / rebase 等），
     * 运行期不降级为全量，直接抛错让任务 FAIL。
     */
    INCREMENTAL_DIFF_FAILED(2003,
            "增量基线 commit 不可解析（force-push / rebase 或本地仓库被覆盖），请手动核查 ci_operation_log 后再决定重试方式。");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}