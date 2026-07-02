package com.company.codeinsight.modules.task.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.codeinsight.common.response.ApiResponse;
import com.company.codeinsight.common.response.PageResult;
import com.company.codeinsight.modules.draft.dto.SaveDraftRequest;
import com.company.codeinsight.modules.draft.service.DraftService;
import com.company.codeinsight.modules.entrypoint.model.EntrypointReviewView;
import com.company.codeinsight.modules.entrypoint.service.EntrypointReviewService;
import com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy;
import com.company.codeinsight.modules.hierarchy.service.ModuleHierarchyService;
import com.company.codeinsight.modules.log.service.OperationLogService;
import com.company.codeinsight.modules.task.dto.TaskLogSummaryDto;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.service.DecompileTaskService;
import com.company.codeinsight.modules.task.service.TaskExecutionLogger;
import com.company.codeinsight.modules.task.service.TaskLogSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 反编译及扫描任务控制器
 * 提供全量初始化分析任务创建、增量分析任务创建、任务列表获取、任务启动/中止/重试及异步进度轮询等 REST 接口。
 */
@Tag(name = "知识构建任务管理", description = "代码拉取、静态解析与AI分析任务的启停及监控接口")
@RestController
@RequestMapping("/tasks")
@Validated
public class DecompileTaskController {

    @Autowired
    private DecompileTaskService decompileTaskService;

    @Autowired
    private DraftService draftService;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private ModuleHierarchyService moduleHierarchyService;

    @Autowired
    private EntrypointReviewService entrypointReviewService;

    @Autowired
    private TaskLogSummaryService taskLogSummaryService;

    @Autowired
    private TaskExecutionLogger taskExecutionLogger;

    /**
     * 创建全量解析分析任务（扫描整个分支所有代码文件并重新进行 AI 归纳）
     */
    @Operation(summary = "创建全量初始化任务")
    @PostMapping("/initial")
    public ApiResponse<DecompileTask> createInitial(@RequestBody TaskCreateRequest request) {
        DecompileTask task = decompileTaskService.createInitialTask(request.getSystemId(), request.getRepositoryId(),
                request.getModularizePromptId(), request.getDocumentPromptId(),
                request.getModelName(), request.getEntryScanConfig(),
                request.getRequireHierarchyReview(), request.getRequireEntrypointReview());
        operationLogService.logOperation(request.getSystemId(), task.getId(), "CREATE_TASK",
                "创建全量初始化知识构建任务" +
                        (Boolean.TRUE.equals(request.getRequireHierarchyReview()) ? "（启用模块层级调试）" : "（跳过模块层级调试）") +
                        (Boolean.TRUE.equals(request.getRequireEntrypointReview()) ? "（启用知识入口复核）" : "（跳过知识入口复核）"),
                null, true);
        return ApiResponse.success(task);
    }

    /**
     * 创建增量解析分析任务（自动根据 Git Commit 差异，只对有变化的代码切片执行更新与 AI 分析）
     */
    @Operation(summary = "创建增量分析任务")
    @PostMapping("/incremental")
    public ApiResponse<DecompileTask> createIncremental(@RequestBody TaskCreateRequest request) {
        DecompileTask task = decompileTaskService.createIncrementalTask(request.getSystemId(), request.getRepositoryId(),
                request.getModularizePromptId(), request.getDocumentPromptId(),
                request.getModelName(), request.getEntryScanConfig(),
                request.getRequireHierarchyReview(), request.getRequireEntrypointReview());
        operationLogService.logOperation(request.getSystemId(), task.getId(), "CREATE_TASK",
                "创建增量分析知识构建任务" +
                        (Boolean.TRUE.equals(request.getRequireHierarchyReview()) ? "（启用模块层级调试）" : "（跳过模块层级调试）") +
                        (Boolean.TRUE.equals(request.getRequireEntrypointReview()) ? "（启用知识入口复核）" : "（跳过知识入口复核）"),
                null, true);
        return ApiResponse.success(task);
    }

    /**
     * 根据主键查询特定任务最新状态与基本元数据
     */
    @Operation(summary = "任务详情")
    @GetMapping("/{id}")
    public ApiResponse<DecompileTask> getTask(@PathVariable Long id) {
        DecompileTask task = decompileTaskService.getById(id);
        return ApiResponse.success(task);
    }

    /**
     * 分页多条件查询分析任务列表
     * <p>支持简单搜索（keyword 匹配任务 ID 或模型名）与精准搜索（创建时间范围 + 模型名精确匹配），
     * 与状态分组 chips（statuses）、系统、类型、触发来源等条件可叠加。</p>
     */
    @Operation(summary = "任务列表查询")
    @GetMapping
    public ApiResponse<PageResult<DecompileTask>> listTasks(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long systemId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(name = "statuses", required = false) java.util.List<String> statuses,
            @RequestParam(required = false) String triggerSource,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String modelName,
            @RequestParam(required = false) String createdAtStart,
            @RequestParam(required = false) String createdAtEnd) {
        Page<DecompileTask> page = decompileTaskService.listTasksPage(
                current, size, systemId, status, type, statuses, triggerSource,
                keyword, modelName, createdAtStart, createdAtEnd);
        PageResult<DecompileTask> result = new PageResult<>(page.getTotal(), page.getSize(), page.getCurrent(), page.getRecords());
        return ApiResponse.success(result);
    }

    /**
     * 触发并启动一个处于待运行的分析任务（触发异步流水线线程）
     */
    @Operation(summary = "启动任务")
    @PostMapping("/{id}/start")
    public ApiResponse<Void> startTask(@PathVariable Long id) {
        decompileTaskService.startTask(id);
        return ApiResponse.success();
    }

    /**
     * 任务级「确认通过」：把任务下整组草稿一次性置为 CONFIRMED，
     * 工作区晋升 COMPLETED，任务推进到 CONFIRMED。
     *
     * <p>这是复核工作区「确认通过」按钮的真实语义入口 — 操作粒度是任务，不是单个文件。
     * 细粒度的单文件确认仍可使用 {@code POST /drafts/{id}/confirm}，但不会再触发级联状态升级。</p>
     *
     * @param id      任务 ID
     * @param author  操作人（默认 Admin）
     * @param comment 可选的任务级通过意见（可空）
     */
    @Operation(summary = "任务级「确认通过」")
    @PostMapping("/{id}/confirm")
    public ApiResponse<Void> confirmTask(
            @PathVariable Long id,
            @RequestBody SaveDraftRequest body) {
        String author = body.getAuthor() != null ? body.getAuthor() : "Admin";
        String comment = body.getComment();
        draftService.confirmTask(id, author, comment);
        operationLogService.logOperation(
                null, id, "CONFIRM_TASK",
                "任务级确认通过整组草稿" + (comment != null && !comment.isBlank() ? "，意见：" + comment : ""),
                null, true
        );
        return ApiResponse.success();
    }

    /**
     * 强行终止一个正在进行中或挂起状态的分析任务
     */
    @Operation(summary = "终止任务")
    @PostMapping("/{id}/terminate")
    public ApiResponse<Void> terminateTask(@PathVariable Long id) {
        decompileTaskService.terminateTask(id);
        return ApiResponse.success();
    }

    /**
     * 重试运行一个分析失败的扫描任务
     */
    @Operation(summary = "重试任务")
    @PostMapping("/{id}/retry")
    public ApiResponse<Void> retryTask(@PathVariable Long id) {
        decompileTaskService.retryTask(id);
        return ApiResponse.success();
    }

    /**
     * 便捷前端 2.5s 定时器轮询状态及数字进度条的轻量级 DTO 查询接口
     */
    @Operation(summary = "查询任务进度")
    @GetMapping("/{id}/progress")
    public ApiResponse<TaskProgressDto> getProgress(@PathVariable Long id) {
        DecompileTask task = decompileTaskService.getById(id);
        if (task == null) {
            return ApiResponse.error("任务不存在");
        }
        TaskProgressDto dto = new TaskProgressDto();
        dto.setStatus(task.getStatus());
        dto.setProgress(task.getProgress());
        dto.setErrorReason(task.getErrorReason());
        return ApiResponse.success(dto);
    }

    /**
     * 读取任务真实执行日志（pipeline 写入的 pipeline.log 文件）
     */
    @Operation(summary = "读取任务执行日志")
    @GetMapping("/{id}/log")
    public ApiResponse<String> getExecutionLog(@PathVariable Long id) {
        return ApiResponse.success(taskExecutionLogger.readLastRunContent(id));
    }

    /**
     * 读取任务执行日志的结构化摘要（阶段耗时、文件/切片计数、AI 成功失败数、Mock 标记、当前进度）。
     * 供前端"执行日志"卡片与"查看完整日志"模态框顶栏使用，与 {@link #getExecutionLog(Long)} 互补。
     */
    @Operation(summary = "读取任务执行日志摘要")
    @GetMapping("/{id}/log/summary")
    public ApiResponse<TaskLogSummaryDto> getExecutionLogSummary(@PathVariable Long id) {
        return ApiResponse.success(taskLogSummaryService.summarize(id));
    }

    /**
     * 查询任务的模块层级（人工复核断点用）
     * <p>
     * 返回从 ci_module_hierarchy 重建的 ModuleHierarchy DTO（含 classPaths）。
     * 主要用于前端在 MODULE_HIERARCHY_REVIEW 状态下拉取并展示可编辑的模块树。
     */
    @Operation(summary = "查询任务模块层级")
    @GetMapping("/{id}/module-hierarchy")
    public ApiResponse<ModuleHierarchy> getModuleHierarchy(@PathVariable Long id) {
        DecompileTask task = decompileTaskService.getById(id);
        if (task == null) {
            return ApiResponse.error("任务不存在");
        }
        ModuleHierarchy hierarchy = moduleHierarchyService.loadByTaskId(id);
        hierarchy.setTaskId(id);
        hierarchy.setSystemId(task.getSystemId());
        return ApiResponse.success(hierarchy);
    }

    /**
     * 替换任务的模块层级（人工复核断点用）
     * <p>
     * 前端在复核抽屉内编辑完成后整体提交：后端校验 ID/层级结构后 deleteByTaskId + 全量 insert 落表。
     * 不修改 task 自身状态；状态的推进由 resume 接口负责。
     */
    @Operation(summary = "替换任务模块层级")
    @PutMapping("/{id}/module-hierarchy")
    public ApiResponse<ModuleHierarchy> replaceModuleHierarchy(@PathVariable Long id, @RequestBody ModuleHierarchy replacement) {
        DecompileTask task = decompileTaskService.getById(id);
        if (task == null) {
            return ApiResponse.error("任务不存在");
        }
        ModuleHierarchy saved = moduleHierarchyService.replaceHierarchy(id, replacement);
        operationLogService.logOperation(
                task.getSystemId(),
                id,
                "EDIT_MODULE_HIERARCHY",
                "人工复核模块层级: 模块数=" + saved.getModules().size(),
                null,
                true
        );
        return ApiResponse.success(saved);
    }

    /**
     * 模块层级人工复核完成后恢复流水线
     * <p>
     * 仅当任务处于 MODULE_HIERARCHY_REVIEW 时可调用；后续流转 GENERATING_DOC → 生成草稿 → PENDING_REVIEW。
     */
    @Operation(summary = "恢复模块层级复核后流水线")
    @PostMapping("/{id}/module-hierarchy/resume")
    public ApiResponse<Void> resumeModuleHierarchyReview(@PathVariable Long id) {
        decompileTaskService.resumeAfterHierarchyReview(id);
        return ApiResponse.success();
    }

    /**
     * 查询任务的知识入口复核清单（人工复核断点用）
     * <p>从 ci_entrypoint 表读取入口列表（methods_json 已在 service 层反序列化为强类型），
     * 主要用于前端在 ENTRYPOINT_REVIEW 状态下拉取并展示只读入口清单。</p>
     */
    @Operation(summary = "查询任务知识入口复核清单")
    @GetMapping("/{id}/entrypoints")
    public ApiResponse<java.util.List<EntrypointReviewView>> getEntrypoints(@PathVariable Long id) {
        DecompileTask task = decompileTaskService.getById(id);
        if (task == null) {
            return ApiResponse.error("任务不存在");
        }
        return ApiResponse.success(entrypointReviewService.listByTaskId(id));
    }

    /**
     * 知识入口人工复核完成后恢复流水线（确认并继续）
     * <p>仅当任务处于 ENTRYPOINT_REVIEW 时可调用；后续流转 AI_ANALYZING → MODULE_HIERARCHY →（按 requireHierarchyReview
     * 选择 GENERATING_DOC 或 MODULE_HIERARCHY_REVIEW）。</p>
     */
    @Operation(summary = "恢复知识入口复核后流水线")
    @PostMapping("/{id}/entrypoints/resume")
    public ApiResponse<Void> resumeEntrypointReview(
            @PathVariable Long id,
            @RequestBody(required = false) ResumeEntrypointRequest body) {
        DecompileTask task = decompileTaskService.getById(id);
        decompileTaskService.resumeAfterEntrypointReview(
                id, body == null ? null : body.getExcludeTargets());
        operationLogService.logOperation(
                task == null ? null : task.getSystemId(),
                id,
                "EDIT_ENTRYPOINT_REVIEW",
                "确认知识入口，开始 AI 提炼模块层级",
                null, true
        );
        return ApiResponse.success();
    }

    /**
     * 知识入口人工复核驳回（终止任务）
     * <p>仅当任务处于 ENTRYPOINT_REVIEW 时可调用；流转到 CANCELLED（不入任何知识资产）。
     * 驳回理由写入 task.error_reason 与审计日志。</p>
     */
    @Operation(summary = "知识入口复核驳回")
    @PostMapping("/{id}/entrypoints/reject")
    public ApiResponse<Void> rejectEntrypointReview(@PathVariable Long id, @RequestBody(required = false) RejectRequest body) {
        DecompileTask task = decompileTaskService.getById(id);
        String reason = body == null ? null : body.getReason();
        decompileTaskService.rejectEntrypointReview(id, reason);
        operationLogService.logOperation(
                task == null ? null : task.getSystemId(),
                id,
                "REJECT_ENTRYPOINT_REVIEW",
                "驳回知识入口，终止任务" + (reason != null && !reason.isBlank() ? "，理由：" + reason : ""),
                null, true
        );
        return ApiResponse.success();
    }

    /**
     * 知识入口驳回请求体
     */
    @Data
    public static class RejectRequest {
        private String reason;
    }

    @Data
    public static class ResumeEntrypointRequest {
        private java.util.List<com.company.codeinsight.modules.entrypoint.model.ExcludeTarget> excludeTargets;
    }

    // ========== 任务队列管控 ==========

    @Operation(summary = "取消队列中的 PENDING 任务（PENDING → CANCELLED）")
    @PostMapping("/{id}/cancel")
    public ApiResponse<Void> cancelQueuedTask(@PathVariable Long id) {
        decompileTaskService.cancelQueuedTask(id);
        operationLogService.logOperation(null, id, "CANCEL_TASK", "队列中取消任务, ID=" + id, null, true);
        return ApiResponse.success();
    }

    @Data
    public static class PriorityRequest {
        private Integer priority;
    }

    @Operation(summary = "调整任务优先级（0-100，仅 PENDING 可调）")
    @PutMapping("/{id}/priority")
    public ApiResponse<Void> adjustPriority(@PathVariable Long id, @RequestBody PriorityRequest body) {
        decompileTaskService.adjustPriority(id, body.getPriority());
        operationLogService.logOperation(null, id, "ADJUST_PRIORITY", "调整任务优先级, ID=" + id + ", priority=" + body.getPriority(), null, true);
        return ApiResponse.success();
    }

    @Operation(summary = "队列列表（PENDING 任务，priority DESC + created_at ASC）")
    @GetMapping("/queue")
    public ApiResponse<PageResult<DecompileTask>> listQueuedTasks(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long systemId) {
        Page<DecompileTask> page = decompileTaskService.listQueuedTasks(current, size, systemId);
        return ApiResponse.success(new PageResult<>(page.getTotal(), page.getSize(), page.getCurrent(), page.getRecords()));
    }

    @Operation(summary = "队列总览（总数 + 平均等待时长）")
    @GetMapping("/queue/summary")
    public ApiResponse<Map<String, Object>> getQueueSummary() {
        return ApiResponse.success(decompileTaskService.getQueueSummary());
    }

    /**
     * 任务中心顶部 chips 角标：按状态分组统计各分组的任务数量。
     * 返回 key 固定为 ALL / RUNNING / PENDING_REVIEW / CONFIRMED / CLOSED。
     *
     * @param systemId 可选系统过滤
     */
    @Operation(summary = "任务状态分组统计（用于顶部 chips 角标）")
    @GetMapping("/summary")
    public ApiResponse<java.util.Map<String, Long>> getTaskSummary(
            @RequestParam(required = false) Long systemId) {
        return ApiResponse.success(decompileTaskService.countByStatusGroup(systemId));
    }

    /**
     * 创建任务参数封装对象
     */
    @Data
    public static class TaskCreateRequest {
        private Long systemId;
        private Long repositoryId;
        /** 模块提取提示词 ID（ci_prompt 主键） */
        private Long modularizePromptId;
        /** 文档生成提示词 ID（ci_prompt 主键） */
        private Long documentPromptId;
        private String modelName;
        /** 入口扫描配置（仅前端策略步骤填写时传入，null 走默认 Controller/JOB/MQ 兜底） */
        private com.company.codeinsight.modules.entrypoint.model.EntryPointConfig entryScanConfig;
        /** 是否启用模块层级调试断点；null 时按默认 TRUE 处理 */
        private Boolean requireHierarchyReview;
        /** 是否启用知识入口复核断点；null 时按默认 TRUE 处理 */
        private Boolean requireEntrypointReview;
    }

    /**
     * 任务进度快速返回传输对象
     */
    @Data
    public static class TaskProgressDto {
        private String status;
        private Integer progress;
        private String errorReason;
    }
}

