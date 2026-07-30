package com.company.codeinsight.modules.repository.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 按系统异步批量检测受理结果。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitBatchCheckAccepted {

    private boolean accepted;
    private Long systemId;
    private int repoCount;
    private String message;
}
