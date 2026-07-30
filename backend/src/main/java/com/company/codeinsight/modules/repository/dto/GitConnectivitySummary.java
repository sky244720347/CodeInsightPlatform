package com.company.codeinsight.modules.repository.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 全库 Git 连通性汇总（监控预留）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitConnectivitySummary {

    private long total;
    private long reachable;
    private long unreachable;
    private long unchecked;
}
