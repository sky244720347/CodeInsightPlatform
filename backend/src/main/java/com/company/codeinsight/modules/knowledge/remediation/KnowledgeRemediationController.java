package com.company.codeinsight.modules.knowledge.remediation;

import com.company.codeinsight.common.response.ApiResponse;
import com.company.codeinsight.modules.knowledge.remediation.dto.DocumentRemediationRequest;
import com.company.codeinsight.modules.knowledge.remediation.dto.EntrypointRemediationRequest;
import com.company.codeinsight.modules.knowledge.remediation.dto.HierarchyRemediationRequest;
import com.company.codeinsight.modules.knowledge.remediation.dto.ReleaseDocumentEditRequest;
import com.company.codeinsight.modules.knowledge.remediation.dto.RemediationTaskResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "知识纠错", description = "从知识查询页触发的分段重跑与文档直写")
@RestController
@RequestMapping("/knowledge/remediation")
@RequiredArgsConstructor
public class KnowledgeRemediationController {

    private final KnowledgeRemediationService remediationService;
    private final KnowledgeReleaseEditService releaseEditService;

    @Operation(summary = "扫描入口调整后重跑（从 MODULE_HIERARCHY 起全量重算）")
    @PostMapping("/entrypoints")
    public ApiResponse<RemediationTaskResponse> entrypoints(@RequestBody EntrypointRemediationRequest request) {
        return ApiResponse.success(remediationService.remediateEntrypoints(request));
    }

    @Operation(summary = "模块层级调整后重跑（从 GENERATING_DOC 起，按 moduleIds 范围）")
    @PostMapping("/hierarchy")
    public ApiResponse<RemediationTaskResponse> hierarchy(@RequestBody HierarchyRemediationRequest request) {
        return ApiResponse.success(remediationService.remediateHierarchy(request));
    }

    @Operation(summary = "单模块/多模块文档重跑")
    @PostMapping("/documents")
    public ApiResponse<RemediationTaskResponse> documents(@RequestBody DocumentRemediationRequest request) {
        return ApiResponse.success(remediationService.remediateDocuments(request));
    }

    @Operation(summary = "提交发布文档人工修订（待审核）")
    @PostMapping("/documents/edit")
    public ApiResponse<Map<String, Long>> submitDocumentEdit(@RequestBody ReleaseDocumentEditRequest request) {
        Long id = releaseEditService.submitEdit(request);
        return ApiResponse.success(Map.of("editId", id));
    }

    @Operation(summary = "批准修订并直写 NAS release 文件")
    @PostMapping("/documents/edit/{editId}/approve")
    public ApiResponse<Void> approveDocumentEdit(
            @PathVariable Long editId,
            @RequestParam(required = false, defaultValue = "system") String operator) {
        releaseEditService.approveEdit(editId, operator);
        return ApiResponse.success(null);
    }
}
