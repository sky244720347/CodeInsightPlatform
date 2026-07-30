package com.company.codeinsight.modules.repository.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 单次 Git 连通性检测结果（可落库后返回前端）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitConnectivityResult {

    private Long repositoryId;

    /** 是否连通 */
    private boolean reachable;

    private LocalDateTime checkedAt;

    /** 失败摘要；成功时可为空 */
    private String message;

    /**
     * 结论不确定（如超时）：不落库为「不通」，保留原状态（含未检测）。
     */
    private boolean inconclusive;
}
