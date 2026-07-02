package com.company.codeinsight.modules.prompt.controller;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.response.ApiResponse;
import com.company.codeinsight.common.response.PageResult;
import com.company.codeinsight.modules.log.service.OperationLogService;
import com.company.codeinsight.modules.prompt.dto.PromptTestResultDto;
import com.company.codeinsight.modules.prompt.dto.SyncPromptFromResourceResultDto;
import com.company.codeinsight.modules.prompt.entity.DecompilePrompt;
import com.company.codeinsight.modules.prompt.service.DecompilePromptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * AI 提示词模板管理控制器
 * 提供提示词配置的创建、版本编辑递增、克隆复制、启用状态切换、删除约束、以及基于样本代码的测试试跑 REST API 端点。
 */
@Tag(name = "提示词管理", description = "用于代码总结的 AI 提示词模板管理")
@Slf4j
@RestController
@RequestMapping("/prompts")
@Validated
public class DecompilePromptController {

    private static final String DEFAULT_PROMPT_TYPE = "MODULARIZE";

    @Autowired
    private DecompilePromptService decompilePromptService;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private com.company.codeinsight.modules.system.mapper.SystemApplicationMapper systemMapper;
    @Autowired
    private com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper repoMapper;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 创建新的提示词模板，初始默认版本号为 1 且启用
     */
    @Operation(summary = "创建提示词模板")
    @PostMapping
    public ApiResponse<DecompilePrompt> createPrompt(@Valid @RequestBody DecompilePrompt prompt) {
        prompt.setId(null);
        prompt.setVersion(1);
        prompt.setPromptType(normalizePromptType(prompt.getPromptType()));
        if (prompt.getIsDefault() == null) {
            prompt.setIsDefault(0);
        }
        // category 缺省为 DEFAULT(全局默认);前端可传 USER 表示用户自定义(scope 隔离)
        if (!StringUtils.hasText(prompt.getCategory())) {
            prompt.setCategory("DEFAULT");
        }
        // USER 类型的提示词必须带 scopeId,用于按仓库/系统隔离
        if ("USER".equalsIgnoreCase(prompt.getCategory()) && prompt.getScopeId() == null) {
            throw new BusinessException("USER 类型提示词必须指定 scopeId(归属的仓库或系统 ID)");
        }
        // lifecycle 缺省为 RELEASED(兼容历史);前端可显式传 DRAFT 表示先创建草稿
        if (!StringUtils.hasText(prompt.getLifecycle())) {
            prompt.setLifecycle(DecompilePrompt.LIFECYCLE_RELEASED);
        }
        // "设为默认"只对 DEFAULT 类别生效(避免误把 USER 提示词设为全局默认)
        if ("DEFAULT".equalsIgnoreCase(prompt.getCategory()) && prompt.getIsDefault() == 1) {
            clearDefaultPrompts(prompt.getPromptType(), null, prompt.getIsDefault());
        }
        decompilePromptService.save(prompt);
        operationLogService.logOperation(null, null, "CREATE_PROMPT", "创建提示词模板: " + prompt.getName(), null, true);
        return ApiResponse.success(prompt);
    }

    /**
     * 更新已存在的提示词模板内容,自动递增版本号做审计回溯
     * <p><b>仅 DRAFT 状态可直接编辑,RELEASED/ARCHIVED 不可编辑</b>(如需改动请先调用 POST /clone 创建新草稿)。</p>
     *
     * <p><b>幂等保护</b>:name + content 与已存完全一致时,直接返回原对象,</p>
     * <p>不写 DB、不递增 version,避免无意义提交产生空版本号与污染版本历史。</p>
     */
    @Operation(summary = "编辑提示词模板")
    @PutMapping("/{id}")
    public ApiResponse<DecompilePrompt> updatePrompt(@PathVariable Long id, @Valid @RequestBody DecompilePrompt prompt) {
        DecompilePrompt existing = decompilePromptService.getById(id);
        if (existing == null) {
            throw new BusinessException("提示词模板不存在");
        }
        // RELEASED/ARCHIVED 锁定:只能通过 clone 创建新 DRAFT 来修改
        if (!DecompilePrompt.LIFECYCLE_DRAFT.equals(existing.getLifecycle())) {
            throw new BusinessException("已发布/已归档的提示词不可直接编辑;请使用复制(POST /prompts/{id}/clone)创建新草稿");
        }

        // 幂等保护：name + content 与已存完全一致 → 不写 DB、不 +1
        boolean nameChanged = !Objects.equals(existing.getName(), prompt.getName());
        boolean contentChanged = !Objects.equals(existing.getContent(), prompt.getContent());
        if (!nameChanged && !contentChanged) {
            log.info("updatePrompt no-op: id={}, name+content 未变化,跳过 version+1", id);
            return ApiResponse.success(existing);
        }

        prompt.setId(id);
        prompt.setVersion(existing.getVersion() + 1);
        if (!StringUtils.hasText(prompt.getPromptType())) {
            prompt.setPromptType(normalizePromptType(existing.getPromptType()));
        }
        if (prompt.getIsDefault() == null) {
            prompt.setIsDefault(existing.getIsDefault());
        }
        prompt.setPromptType(normalizePromptType(prompt.getPromptType()));
        // 不允许在 update 中改 lifecycle(必须先 publish)
        prompt.setLifecycle(DecompilePrompt.LIFECYCLE_DRAFT);
        clearDefaultPrompts(prompt.getPromptType(), id, prompt.getIsDefault());
        decompilePromptService.updateById(prompt);
        operationLogService.logOperation(null, null, "UPDATE_PROMPT", "更新提示词模板: " + prompt.getName() + ", 新版本号: " + prompt.getVersion(), null, true);
        return ApiResponse.success(prompt);
    }

    /**
     * 发布草稿:DRAFT → RELEASED,锁定
     */
    @Operation(summary = "发布提示词(草稿 → 已发布)")
    @PostMapping("/{id}/publish")
    public ApiResponse<DecompilePrompt> publishPrompt(@PathVariable Long id) {
        DecompilePrompt updated = decompilePromptService.publishPrompt(id);
        operationLogService.logOperation(null, null, "PUBLISH_PROMPT", "发布提示词: " + updated.getName(), null, true);
        return ApiResponse.success(updated);
    }

    /**
     * 归档已发布的提示词(RELEASED → ARCHIVED)
     */
    @Operation(summary = "归档已发布的提示词")
    @PostMapping("/{id}/archive")
    public ApiResponse<DecompilePrompt> archivePrompt(@PathVariable Long id) {
        DecompilePrompt updated = decompilePromptService.archivePrompt(id);
        operationLogService.logOperation(null, null, "ARCHIVE_PROMPT", "归档提示词: " + updated.getName(), null, true);
        return ApiResponse.success(updated);
    }

    /**
     * 克隆指定的提示词模板，生成一份副本作为基础模板
     */
    @Operation(summary = "克隆/复制提示词模板")
    @PostMapping("/{id}/clone")
    public ApiResponse<DecompilePrompt> clonePrompt(@PathVariable Long id) {
        DecompilePrompt cloned = decompilePromptService.clonePrompt(id);
        operationLogService.logOperation(null, null, "CLONE_PROMPT", "克隆提示词模板, 源模板ID: " + id + ", 新模板ID: " + cloned.getId(), null, true);
        return ApiResponse.success(cloned);
    }

    /**
     * 克隆指定的提示词模板，生成一份副本作为基础模板
     */
    @Operation(summary = "提示词模板详情")
    @GetMapping("/{id}")
    public ApiResponse<DecompilePrompt> getPrompt(@PathVariable Long id) {
        DecompilePrompt prompt = decompilePromptService.getById(id);
        return ApiResponse.success(prompt);
    }

    /**
     * 分页多条件检索提示词模板
     *
     * @param promptType 提示词用途过滤：MODULARIZE-模块提取 / DOCUMENT_GENERATION-文档生成；不传则查询全部
     */
    @Operation(summary = "提示词模板分页查询")
    @GetMapping
    public ApiResponse<PageResult<DecompilePrompt>> listPrompts(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String promptType,
            @RequestParam(required = false) String lifecycle,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Long scopeId,
            @RequestParam(required = false) Integer isDefault) {
        Page<DecompilePrompt> page = decompilePromptService.listPromptsPage(
                current,
                size,
                name,
                normalizeOptionalPromptType(promptType),
                lifecycle,
                category,
                scopeId, isDefault);
        PageResult<DecompilePrompt> result = new PageResult<>(page.getTotal(), page.getSize(), page.getCurrent(), page.getRecords());
        return ApiResponse.success(result);
    }

    /**
     * 删除特定的提示词模板配置（防止误删默认首选项）
     */
    @Operation(summary = "删除提示词模板")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deletePrompt(@PathVariable Long id) {
        DecompilePrompt prompt = decompilePromptService.getById(id);
        if (prompt == null) {
            throw new BusinessException("提示词模板不存在");
        }
        if (prompt.getIsDefault() != null && prompt.getIsDefault() == 1) {
            throw new BusinessException("默认提示词模板不能删除，请先将其他模板设为默认");
        }
        // 检查是否仍有仓库引用此提示词
        long refCount = repoMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.company.codeinsight.modules.repository.entity.CodeRepository>()
                        .and(w -> w.eq(com.company.codeinsight.modules.repository.entity.CodeRepository::getModularizePromptId, id)
                                .or().eq(com.company.codeinsight.modules.repository.entity.CodeRepository::getDocumentPromptId, id)));
        if (refCount > 0) {
            throw new BusinessException("该提示词仍被 " + refCount + " 个仓库引用，无法删除");
        }
        decompilePromptService.removeById(id);
        operationLogService.logOperation(null, null, "DELETE_PROMPT", "删除提示词模板: " + prompt.getName(), null, true);
        return ApiResponse.success();
    }

    /**
     * 调试试跑提示词模板：提交测试源码片断，实时调度大模型分析返回归纳结果以辅助调试
     * <p>如果请求里带 {@code resolvedContent}（前端已替换占位符），后端直接使用，不再二次替换。</p>
     */
    @Operation(summary = "试跑提示词模板")
    @PostMapping("/{id}/test-run")
    public ApiResponse<PromptTestResultDto> testRun(@PathVariable Long id, @RequestBody TestRunRequest request) {
        PromptTestResultDto result = decompilePromptService.testRun(
                id, request.getSampleCode(), request.getModelId(), request.getResolvedContent());
        operationLogService.logOperation(null, null, "TEST_RUN_PROMPT", "试跑提示词模板, ID: " + id + ", 消耗时间: " + result.getDurationMs() + "ms", null, true);
        return ApiResponse.success(result);
    }

    /**
     * 将指定提示词设为该 prompt_type 的默认；
     * 不修改 content/version/name，单纯切换 is_default 标志。
     * 同一 prompt_type 只能有一条默认（数据库 partial unique index 兜底）。
     */
    @Operation(summary = "设为默认提示词")
    @PostMapping("/{id}/default")
    public ApiResponse<DecompilePrompt> setDefault(@PathVariable Long id) {
        DecompilePrompt prompt = decompilePromptService.getById(id);
        if (prompt == null) {
            throw new BusinessException("提示词模板不存在");
        }
        if (!DecompilePrompt.LIFECYCLE_RELEASED.equals(prompt.getLifecycle())) {
            throw new BusinessException("仅已发布的提示词可设为默认");
        }
        // "设为默认"仅对 DEFAULT 类别生效(USER 提示词永远不能成为全局默认)
        if (!"DEFAULT".equalsIgnoreCase(prompt.getCategory())) {
            throw new BusinessException("仅 DEFAULT 类别提示词可设为默认;USER 提示词按 scope 隔离使用");
        }
        // 先把同类型下其他 DEFAULT 提示词的 is_default 清掉
        clearDefaultPrompts(prompt.getPromptType(), id, 1);
        prompt.setIsDefault(1);
        decompilePromptService.updateById(prompt);
        operationLogService.logOperation(null, null, "SET_DEFAULT_PROMPT",
                "设置默认提示词: ID=" + id + ", name=" + prompt.getName() + ", type=" + prompt.getPromptType(), null, true);
        return ApiResponse.success(prompt);
    }

    /**
     * 流式试跑提示词模板：每行输出一个 JSON 事件，适配 fetch + ReadableStream。
     */
    @Operation(summary = "流式试跑提示词模板")
    @PostMapping(value = "/{id}/test-run/stream", produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> testRunStream(@PathVariable Long id, @RequestBody TestRunRequest request) {
        StreamingResponseBody body = outputStream -> {
            long started = System.currentTimeMillis();
            try {
                decompilePromptService.testRunStream(id, request.getSampleCode(), request.getModelId(), request.getResolvedContent(), event -> {
                    try {
                        outputStream.write((objectMapper.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
                operationLogService.logOperation(null, null, "TEST_RUN_PROMPT_STREAM",
                        "流式试跑提示词模板, ID: " + id + ", 消耗时间: " + (System.currentTimeMillis() - started) + "ms", null, true);
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .cacheControl(CacheControl.noCache())
                .body(body);
    }

    /**
     * 把 classpath 上的 .md 资源内容同步为该 prompt_type 的默认提示词。
     * <p>运维/开发在更新了 {@code analyze_prompt.md} / {@code module_doc_prompt.md} 后调此接口，
     * 服务按 MD5 比对：内容不一致时新插入一行 RELEASED 默认提示词（version = 老+1），
     * 把旧默认归档；内容一致时返回 changed=false 不落表。</p>
     *
     * <p>典型调用：</p>
     * <pre>
     *   POST /prompts/sync-from-resource?promptType=MODULARIZE&resourcePath=analyze_prompt.md
     *   POST /prompts/sync-from-resource?promptType=DOCUMENT_GENERATION&resourcePath=module_doc_prompt.md
     * </pre>
     */
    @Operation(summary = "从 classpath .md 资源同步提示词到 DB")
    @PostMapping("/sync-from-resource")
    public ApiResponse<SyncPromptFromResourceResultDto> syncFromResource(
            @RequestParam("promptType") String promptType,
            @RequestParam(value = "resourcePath", required = false) String resourcePath) {
        String resolvedPath = resourcePath;
        if (!StringUtils.hasText(resolvedPath)) {
            if (DecompilePrompt.TYPE_MODULARIZE.equals(promptType)) {
                resolvedPath = "analyze_prompt.md";
            } else if (DecompilePrompt.TYPE_DOCUMENT_GENERATION.equals(promptType)) {
                resolvedPath = "module_doc_prompt.md";
            }
        }
        SyncPromptFromResourceResultDto result =
                decompilePromptService.syncFromResource(promptType, resolvedPath);
        operationLogService.logOperation(null, null, "SYNC_PROMPT_FROM_RESOURCE",
                "同步提示词: type=" + promptType + ", resource=" + resolvedPath
                        + ", changed=" + result.isChanged() + ", newId=" + result.getNewPromptId()
                        + ", oldId=" + result.getOldPromptId(),
                null, true);
        return ApiResponse.success(result);
    }

    /**
     * 试跑测试请求载荷类
     */
    @Data
    public static class TestRunRequest {
        /**
         * 用于测试输入的一段样例源码（如 Java 方法代码），同时也是 source_code 占位符的默认值
         */
        private String sampleCode;
        /**
         * 所需测试调用的 AI 大模型 ID
         */
        private Long modelId;
        /**
         * 前端已替换占位符的最终 prompt 正文。
         * 如果非空,后端直接使用该内容调用 AI,不再做占位符替换和 class/method 解析。
         */
        private String resolvedContent;
    }

    private void clearDefaultPrompts(String promptType, Long excludeId, Integer isDefault) {
        if (isDefault == null || isDefault != 1) {
            return;
        }
        LambdaUpdateWrapper<DecompilePrompt> updateWrapper = new LambdaUpdateWrapper<DecompilePrompt>()
                .eq(DecompilePrompt::getPromptType, normalizePromptType(promptType))
                .set(DecompilePrompt::getIsDefault, 0);
        if (excludeId != null) {
            updateWrapper.ne(DecompilePrompt::getId, excludeId);
        }
        decompilePromptService.update(updateWrapper);
    }

    private String normalizeOptionalPromptType(String promptType) {
        return StringUtils.hasText(promptType) ? promptType : null;
    }

    private String normalizePromptType(String promptType) {
        return StringUtils.hasText(promptType) ? promptType : DEFAULT_PROMPT_TYPE;
    }
}

