package com.company.codeinsight.modules.scanwindow.support;

public final class ScanProbeStatus {
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED_LOCAL = "SKIPPED_LOCAL";
    public static final String INCONCLUSIVE = "INCONCLUSIVE";
    /** @deprecated 历史流水；新路径用 {@link #DEFERRED_DISPATCH} */
    public static final String DISPATCH_FAILED = "DISPATCH_FAILED";
    /** 探测成功但下发暂不可行（技术栈未打标/不支持等），不记日覆盖，后续 tick 重试 */
    public static final String DEFERRED_DISPATCH = "DEFERRED_DISPATCH";

    private ScanProbeStatus() {
    }
}
