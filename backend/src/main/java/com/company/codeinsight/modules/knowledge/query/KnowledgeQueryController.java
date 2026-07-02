package com.company.codeinsight.modules.knowledge.query;

import com.company.codeinsight.common.response.ApiResponse;
import com.company.codeinsight.modules.entrypoint.model.EntrypointReviewView;
import com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy;
import com.company.codeinsight.modules.knowledge.query.dto.KnowledgeContextView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "知识查询", description = "按仓库浏览已发布入口 / 层级 / 文档上下文（只读）")
@RestController
@RequestMapping("/knowledge")
@RequiredArgsConstructor
public class KnowledgeQueryController {

    private final KnowledgeQueryService knowledgeQueryService;

    @Operation(summary = "仓库生效知识上下文（三页共享筛选条）")
    @GetMapping("/context")
    public ApiResponse<KnowledgeContextView> context(@RequestParam Long repositoryId) {
        return ApiResponse.success(knowledgeQueryService.getContext(repositoryId));
    }

    @Operation(summary = "已发布扫描入口清单（只读）")
    @GetMapping("/entrypoints")
    public ApiResponse<List<EntrypointReviewView>> entrypoints(
            @RequestParam Long repositoryId,
            @RequestParam(required = false) Long systemId) {
        return ApiResponse.success(knowledgeQueryService.listPublishedEntrypoints(systemId, repositoryId));
    }

    @Operation(summary = "已发布模块层级（只读）")
    @GetMapping("/hierarchy")
    public ApiResponse<ModuleHierarchy> hierarchy(
            @RequestParam Long repositoryId,
            @RequestParam(required = false) Long systemId) {
        return ApiResponse.success(knowledgeQueryService.getPublishedHierarchy(systemId, repositoryId));
    }
}
