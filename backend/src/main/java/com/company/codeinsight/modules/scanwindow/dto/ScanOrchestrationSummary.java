package com.company.codeinsight.modules.scanwindow.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 进度口径：
 * <pre>
 * 探测总数 = probeTargetTotal
 * 已探测 = settledSuccessCount + retryPendingCount
 *   ├ 下发成功(了结) = settledSuccessCount
 *   └ 待重试         = retryPendingCount
 * 未探测 = unprobedCount
 * </pre>
 */
@Data
@Builder
public class ScanOrchestrationSummary {
    private String probeDate;
    /** 探测总数：需探测的远程仓 */
    private long probeTargetTotal;
    /** 已探测 = 下发成功 + 待重试 */
    private long probedCount;
    /** 下发成功/当日了结（SUCCESS 或 FAILED） */
    private long settledSuccessCount;
    /** 待重试（延期下发 / 超时，尚未了结） */
    private long retryPendingCount;
    /** 未探测 */
    private long unprobedCount;
    /** 当日流水总行数（明细用） */
    private long attemptCount;
    /** 其中真正 create+start 的流水行数 */
    private long dispatchedCount;
    private boolean schedulerEnabled;
    private String cron;
    private List<String> nextRuns;
    private boolean globalPollEnabled;
    private boolean forceFullOnUnchanged;
    private boolean dailyCoverageEnabled;
}
