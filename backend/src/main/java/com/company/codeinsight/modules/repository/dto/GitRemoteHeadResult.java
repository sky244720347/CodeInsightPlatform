package com.company.codeinsight.modules.repository.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ls-remote 解析远端 HEAD / 指定分支 tip 的结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitRemoteHeadResult {

    private Long repositoryId;

    /** 40 位 commit id；失败或 inconclusive 时为 null */
    private String headCommit;

    private boolean reachable;

    /** 超时等不确定结论：不下发任务、不误标不通 */
    private boolean inconclusive;

    private String message;
}
