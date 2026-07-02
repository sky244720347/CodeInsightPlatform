package com.company.codeinsight.modules.businessknowledge.controller;

import com.company.codeinsight.common.response.ApiResponse;
import com.company.codeinsight.modules.businessknowledge.entity.BusinessKnowledge;
import com.company.codeinsight.modules.businessknowledge.service.BusinessKnowledgeService;
import com.company.codeinsight.modules.log.service.OperationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 业务知识配置 REST 控制器
 *
 * <p>提供：</p>
 * <ul>
 *     <li>{@code GET /business-knowledge?systemId=...} — 获取配置（无配置时返回 null）</li>
 *     <li>{@code PUT /business-knowledge} — 覆盖式保存（存在则更新，否则插入）</li>
 * </ul>
 *
 * <p>AI 调用方不通过本 Controller——直接注入 {@link BusinessKnowledgeService} 调 {@code getContentBySystemId}。</p>
 */
@Tag(name = "业务知识配置", description = "按系统维度维护业务知识 Markdown，作为 {business_knowledge.md} 占位符喂给模块提取 AI")
@RestController
@RequestMapping("/business-knowledge")
@Validated
public class BusinessKnowledgeController {

    @Autowired
    private BusinessKnowledgeService businessKnowledgeService;

    @Autowired
    private OperationLogService operationLogService;

    @Operation(summary = "按系统 ID 获取业务知识配置")
    @GetMapping
    public ApiResponse<BusinessKnowledge> getBySystemId(
            @RequestParam @NotNull Long systemId) {
        return ApiResponse.success(businessKnowledgeService.getBySystemId(systemId));
    }

    @Operation(summary = "覆盖式保存业务知识（存在则更新 version+1，否则插入）")
    @PutMapping
    public ApiResponse<BusinessKnowledge> upsert(@Valid @RequestBody UpsertRequest request) {
        BusinessKnowledge saved = businessKnowledgeService.upsert(
                request.getSystemId(),
                request.getContent(),
                request.getUpdatedBy()
        );
        operationLogService.logOperation(
                saved.getSystemId(),
                null,
                "UPSERT_BUSINESS_KNOWLEDGE",
                "保存业务知识: systemId=" + saved.getSystemId()
                        + ", version=" + saved.getVersion()
                        + ", contentLength=" + (saved.getContent() == null ? 0 : saved.getContent().length()),
                null,
                true
        );
        return ApiResponse.success(saved);
    }

    /** 覆盖式保存请求载荷 */
    @Data
    public static class UpsertRequest {
        @NotNull
        private Long systemId;
        /** Markdown 正文，可为空（视为空串） */
        private String content;
        /** 修改人（可为空） */
        private String updatedBy;
    }
}
