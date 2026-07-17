package com.company.codeinsight.modules.draft.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 全局「新建任务前置条件」就绪度 DTO
 *
 * <p>由 {@code GET /api/drafts/readiness} 返回，用于在 TasksPage 新建任务向导第一步之前拦截：
 * 平台任意一个 {@code ci_knowledge_draft} 仍处于非终态（DRAFT / EDITING / REJECTED）即视为未就绪，
 * 必须引导复核人先去复核页完成确认 / 驳回处置。</p>
 *
 * <p>阻塞列表为空时 {@code ready=true}，调用方可放行向导；非空时 {@code ready=false}，
 * 前端按 {@code blockingDrafts} 渲染引导弹窗。</p>
 */
@Data
public class RepositoryReadinessDto {

    /**
     * 是否满足新建任务前置条件（草稿已确认 + 系统提示词已绑定）
     */
    private boolean ready;

    /**
     * 系统是否已完整绑定模块提取 + 文档生成提示词。
     */
    private boolean promptsConfigured = true;

    /**
     * 提示词未配置时的说明文案（{@code promptsConfigured=false} 时有值）。
     */
    private String promptsMessage;

    /**
     * 全局未确认草稿总数（DRAFT / EDITING / REJECTED 任意一项即计入）
     */
    private long unconfirmedCount;

    /**
     * 阻塞新建任务的草稿明细列表，按工作区/任务聚合后平铺展示
     */
    private List<BlockingDraft> blockingDrafts = new ArrayList<>();

    /**
     * 单条阻塞草稿的摘要信息，便于前端引导弹窗直接渲染。
     */
    @Data
    public static class BlockingDraft {
        /**
         * 草稿 ID
         */
        private Long draftId;

        /**
         * 模块名（草稿在目录树中的展示名）
         */
        private String moduleName;

        /**
         * 当前草稿状态：取自 ci_knowledge_draft.status 字面值
         */
        private String status;

        /**
         * 所属工作区 ID
         */
        private Long workspaceId;

        /**
         * 所属知识构建任务 ID
         */
        private Long taskId;

        /**
         * 所属业务系统 ID
         */
        private Long systemId;

        /**
         * 所属代码库 ID
         */
        private Long repositoryId;

        /**
         * 草稿最近一次更新时间，用于排序与展示
         */
        private LocalDateTime updatedDate;
    }
}