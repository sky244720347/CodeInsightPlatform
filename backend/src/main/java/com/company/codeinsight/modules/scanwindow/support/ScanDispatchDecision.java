package com.company.codeinsight.modules.scanwindow.support;

import org.springframework.util.StringUtils;

/**
 * 定时扫描下发决策（纯函数，见 docs/scheduled-commit-poll-scan-plan.md §3.2）。
 */
public final class ScanDispatchDecision {

    private ScanDispatchDecision() {
    }

    /** 有增量基线 ≔ 已发布版本指针非空且 last_commit_id 非空。 */
    public static boolean hasBaseline(Long lastPublishedVersionId, String lastCommitId) {
        return lastPublishedVersionId != null && StringUtils.hasText(lastCommitId);
    }

    /**
     * @param hasBaseline          是否具备 PUSHED 基线
     * @param remoteHead           ls-remote 解析到的 tip commit
     * @param lastCommitId         仓库发布基线 commit
     * @param forceFullOnUnchanged 验证开关：有基线且 HEAD 无变化时仍下发 INITIAL
     */
    public static ScanDispatchAction decide(boolean hasBaseline,
                                            String remoteHead,
                                            String lastCommitId,
                                            boolean forceFullOnUnchanged) {
        if (!StringUtils.hasText(remoteHead)) {
            return ScanDispatchAction.SKIP;
        }
        if (!hasBaseline) {
            return ScanDispatchAction.INITIAL;
        }
        if (commitsEqual(remoteHead, lastCommitId)) {
            return forceFullOnUnchanged ? ScanDispatchAction.INITIAL : ScanDispatchAction.SKIP;
        }
        return ScanDispatchAction.INCREMENTAL;
    }

    /** 全长或一方为另一方前缀（兼容短 SHA）时视为相同。 */
    static boolean commitsEqual(String a, String b) {
        if (!StringUtils.hasText(a) || !StringUtils.hasText(b)) {
            return false;
        }
        String x = a.trim().toLowerCase();
        String y = b.trim().toLowerCase();
        return x.equals(y) || x.startsWith(y) || y.startsWith(x);
    }
}
