package com.company.codeinsight.modules.draft.controller;

import com.company.codeinsight.common.response.ApiResponse;
import com.company.codeinsight.modules.draft.dto.DraftTreeNode;
import com.company.codeinsight.modules.draft.dto.PreviewSystemDto;
import com.company.codeinsight.modules.draft.dto.SaveDraftRequest;
import com.company.codeinsight.modules.draft.entity.*;
import com.company.codeinsight.modules.draft.service.DraftEditLockService;
import com.company.codeinsight.modules.draft.service.DraftService;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识草稿复核管理控制器
 * 提供任务工作区获取、草稿读取、物理保存、Redis 防抖自动暂存、确认通过、驳回、修订历史及审核意见等 REST API 端点。
 */
@Tag(name = "草稿复核管理", description = "草稿区工作流、版本保存、自动缓存及在线编辑接口")
@RestController
@RequestMapping("/drafts")
public class DraftController {

    @Autowired
    private DraftService draftService;

    @Autowired
    private DraftEditLockService draftEditLockService;

    /**
     * 根据分析任务 ID 获取生成关联的工作区元数据及包含的草稿列表
     *
     * @param taskId 任务 ID
     * @return 返回 map，包含 workspace 详情和 drafts 列表
     */
    @Operation(summary = "根据任务 ID 获取工作区详情")
    @GetMapping("/workspace/task/{taskId}")
    public ApiResponse<Map<String, Object>> getWorkspaceByTask(@PathVariable Long taskId) {
        DraftWorkspace ws = draftService.getWorkspaceByTaskId(taskId);
        if (ws == null) {
            return ApiResponse.success(null);
        }
        List<KnowledgeDraft> list = draftService.listDraftsByWorkspace(ws.getId());
        
        Map<String, Object> res = new HashMap<>();
        res.put("workspace", ws);
        res.put("drafts", list);
        return ApiResponse.success(res);
    }

    /**
     * 查询指定工作区下的所有草稿模块基本信息
     */
    @Operation(summary = "查询工作区下的草稿列表")
    @GetMapping("/workspace/{workspaceId}")
    public ApiResponse<List<KnowledgeDraft>> listDrafts(@PathVariable Long workspaceId) {
        List<KnowledgeDraft> list = draftService.listDraftsByWorkspace(workspaceId);
        return ApiResponse.success(list);
    }

    /**
     * 查询指定工作区下的草稿目录树（DB 自引用 parent_id 递归构建）。
     * 前端可把返回值直接喂给 AntD Tree 组件。
     */
    @Operation(summary = "查询工作区下草稿目录树")
    @GetMapping("/workspace/{workspaceId}/tree")
    public ApiResponse<List<DraftTreeNode>> getWorkspaceTree(@PathVariable Long workspaceId) {
        List<DraftTreeNode> tree = draftService.getWorkspaceTree(workspaceId);
        return ApiResponse.success(tree);
    }

    /**
     * v1: 草稿 diff 视图（前端 Phase 4 UI 用）
     * <p>返回 newRows / inheritedRows / deletedRows 3 类。INITIAL 任务返回 3 个空 list。</p>
     */
    @GetMapping("/workspace/{workspaceId}/tree/diff")
    public ApiResponse<com.company.codeinsight.modules.draft.dto.DraftTreeDiffDto> getWorkspaceTreeDiff(
            @PathVariable Long workspaceId) {
        return ApiResponse.success(draftService.getWorkspaceTreeDiff(workspaceId));
    }

    /**
     * 单篇知识文档的正文 DIFF：返回基线 + 本次两份正文的 contentUri，
     * 前端用 Monaco DiffEditor 自行计算 diff 并左右对比展示。
     */
    @Operation(summary = "单篇文档正文DIFF")
    @GetMapping("/{id}/content-diff")
    public ApiResponse<com.company.codeinsight.modules.draft.dto.DocumentDiffDto> getDocumentDiff(
            @PathVariable Long id) {
        return ApiResponse.success(draftService.getDocumentDiff(id));
    }

    /**
     * 读取指定草稿的 Markdown 正文全文内容
     */
    @Operation(summary = "读取草稿正文内容")
    @GetMapping("/{id}/content")
    public ApiResponse<String> getContent(@PathVariable Long id) {
        String content = draftService.getDraftContent(id);
        return ApiResponse.success(content);
    }

    /**
     * 手动编辑物理保存草稿：物理写入文件并写入修订记录表。
     * content 等字段通过请求体（JSON）传递，避免长 Markdown 正文因 URL 查询参数长度限制而失败。
     */
    @Operation(summary = "保存修改草稿")
    @PostMapping("/{id}/save")
    public ApiResponse<Void> save(
            @PathVariable Long id,
            @RequestBody SaveDraftRequest body) {
        String content = body.getContent();
        String author = body.getAuthor() != null ? body.getAuthor() : "Admin";
        String remark = body.getRemark() != null ? body.getRemark() : "手动编辑保存";
        draftService.saveDraft(id, content, author, remark);
        return ApiResponse.success();
    }

    /**
     * 自动暂存草稿内容：1.8s防抖自动保存，快速将草稿缓存写入临时缓冲介质以防止意外丢失。
     * content 通过请求体传递，避免 URL 查询参数限制。
     */
    @Operation(summary = "自动暂存草稿内容")
    @PostMapping("/{id}/autosave")
    public ApiResponse<Void> autoSave(
            @PathVariable Long id,
            @RequestBody SaveDraftRequest body) {
        String content = body.getContent();
        String author = body.getAuthor() != null ? body.getAuthor() : "Admin";
        draftService.autoSaveDraft(id, content, author);
        return ApiResponse.success();
    }

    @Operation(summary = "获取草稿编辑锁（集群模式）")
    @PostMapping("/{id}/edit-lock/acquire")
    public ApiResponse<Void> acquireEditLock(
            @PathVariable Long id,
            @RequestBody SaveDraftRequest body) {
        String author = body.getAuthor() != null ? body.getAuthor() : "Admin";
        draftEditLockService.acquire(id, author);
        return ApiResponse.success();
    }

    @Operation(summary = "续租草稿编辑锁")
    @PostMapping("/{id}/edit-lock/renew")
    public ApiResponse<Void> renewEditLock(
            @PathVariable Long id,
            @RequestBody SaveDraftRequest body) {
        String author = body.getAuthor() != null ? body.getAuthor() : "Admin";
        draftEditLockService.renew(id, author);
        return ApiResponse.success();
    }

    @Operation(summary = "释放草稿编辑锁")
    @PostMapping("/{id}/edit-lock/release")
    public ApiResponse<Void> releaseEditLock(
            @PathVariable Long id,
            @RequestBody SaveDraftRequest body) {
        String author = body.getAuthor() != null ? body.getAuthor() : "Admin";
        draftEditLockService.release(id, author);
        return ApiResponse.success();
    }

    /**
     * 确认并通过草稿：将草稿置为 CONFIRMED (已确认) 状态。
     * author / comment 通过请求体传递。
     */
    @Operation(summary = "确认并通过草稿（可选填写通过意见）")
    @PostMapping("/{id}/confirm")
    public ApiResponse<Void> confirm(
            @PathVariable Long id,
            @RequestBody SaveDraftRequest body) {
        String author = body.getAuthor() != null ? body.getAuthor() : "Admin";
        draftService.confirmDraft(id, author, body.getComment());
        return ApiResponse.success();
    }

    /**
     * 查询指定草稿的历史版本修改修订记录列表
     */
    @Operation(summary = "查询修订历史记录")
    @GetMapping("/{id}/revisions")
    public ApiResponse<List<DraftRevision>> getRevisions(@PathVariable Long id) {
        List<DraftRevision> list = draftService.getRevisions(id);
        return ApiResponse.success(list);
    }

    /**
     * 查询指定草稿的人工评审复核批注意见列表
     */
    @Operation(summary = "查询评审意见记录")
    @GetMapping("/{id}/comments")
    public ApiResponse<List<DraftReviewComment>> getComments(@PathVariable Long id) {
        List<DraftReviewComment> list = draftService.getComments(id);
        return ApiResponse.success(list);
    }

    /**
     * 任务级复核意见聚合：把 task 下整组草稿的复核意见一次性取出，
     * 附带来源草稿的 moduleName / filePath，便于复核人按任务粒度浏览整组意见
     * （含 confirmTask 写入的 `[任务级通过]` 任务级记录）。
     *
     * <p>这是复核工作区「复核意见」按钮的真实语义入口 — 操作粒度是任务，不是单个文件。
     * 单文件级仍可使用 {@code GET /drafts/{id}/comments}。</p>
     */
    @Operation(summary = "查询任务级复核意见聚合")
    @GetMapping("/task/{taskId}/comments")
    public ApiResponse<List<com.company.codeinsight.modules.draft.dto.TaskCommentDto>> listTaskComments(@PathVariable Long taskId) {
        return ApiResponse.success(draftService.listAllCommentsByTask(taskId));
    }

    /**
     * 查询该草稿模块关联的底层代码来源文件及行号范围列表
     */
    @Operation(summary = "查询源文件引用")
    @GetMapping("/{id}/references")
    public ApiResponse<List<DraftSourceReference>> getReferences(@PathVariable Long id) {
        List<DraftSourceReference> list = draftService.getSourceReferences(id);
        return ApiResponse.success(list);
    }

    // ========================================================================
    // 复核工作区「可预览系统」相关端点
    // ========================================================================

    /**
     * 复核工作区首页：列出所有「可预览」系统及其复核任务计数。
     * 用于前端下拉选择与角标展示。
     */
    @Operation(summary = "查询可预览系统（复核工作区首页）")
    @GetMapping("/preview-systems")
    public ApiResponse<List<PreviewSystemDto>> listPreviewSystems() {
        List<PreviewSystemDto> list = draftService.listPreviewSystems();
        return ApiResponse.success(list);
    }

    /**
     * 复核工作区二级筛选：列出指定系统下可复核的任务列表。
     * status 为可选过滤（多值用英文逗号分隔），留空时默认 PENDING_REVIEW/REVIEWING/CONFIRMED。
     */
    @Operation(summary = "查询可复核任务（按系统与状态筛选）")
    @GetMapping("/review-tasks")
    public ApiResponse<List<DecompileTask>> listReviewableTasks(
            @RequestParam(required = false) Long systemId,
            @RequestParam(required = false) String status) {
        List<String> statuses = (status == null || status.isBlank())
                ? Collections.emptyList()
                : Arrays.stream(status.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        List<DecompileTask> list = draftService.listReviewableTasks(systemId, statuses);
        return ApiResponse.success(list);
    }

    /**
     * 新建任务前置条件查询：可选按系统+仓库收窄作用域，避免全表扫描。
     *
     * <p>当 {@code systemId} 和 {@code repositoryId} 均不传时退化为全局查询
     * （兼容旧版前端）。建议前端在创建任务向导步骤 0 选择系统+仓库后，
     * 立即带参调用本接口做精确校验。</p>
     *
     * @param systemId     可选：所属业务系统 ID
     * @param repositoryId 可选：所属代码库 ID
     * @return {@link com.company.codeinsight.modules.draft.dto.RepositoryReadinessDto}
     */
    @Operation(summary = "新建任务前置条件查询（可选按系统+仓库收窄）")
    @GetMapping("/readiness")
    public ApiResponse<com.company.codeinsight.modules.draft.dto.RepositoryReadinessDto> getReadiness(
            @RequestParam(required = false) Long systemId,
            @RequestParam(required = false) Long repositoryId) {
        if (systemId != null || repositoryId != null) {
            return ApiResponse.success(draftService.findReadiness(systemId, repositoryId));
        }
        return ApiResponse.success(draftService.findGlobalReadiness());
    }
}

