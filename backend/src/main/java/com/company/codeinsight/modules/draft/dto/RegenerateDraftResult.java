package com.company.codeinsight.modules.draft.dto;

import lombok.Data;

/**
 * 单篇草稿 AI 重跑结果。
 *
 * <p>{@code accepted=true} 表示请求已入队、后台正在生成（HTTP 立即返回）；
 * 前端应轮询 {@code status}，直至离开 {@code REGENERATING}。</p>
 */
@Data
public class RegenerateDraftResult {
    private Long draftId;
    /** 当前草稿状态（接受时为 REGENERATING；完成后为 AI 落库状态） */
    private String status;
    /** true = 异步已接受，尚未跑完 AI */
    private boolean accepted;
    private String contentUri;
    private String functionNodeId;
    private int referenceCount;
    /** 最近一次重跑失败原因（轮询时可带出；成功时为空） */
    private String errorMessage;
}
