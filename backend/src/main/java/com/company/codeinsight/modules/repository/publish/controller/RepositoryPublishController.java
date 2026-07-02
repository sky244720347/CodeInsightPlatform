package com.company.codeinsight.modules.repository.publish.controller;

import com.company.codeinsight.common.response.ApiResponse;
import com.company.codeinsight.modules.repository.publish.dto.RepositoryPublishSnapshotView;
import com.company.codeinsight.modules.repository.publish.service.RepositoryPublishService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "仓库发布", description = "发布快照查询")
@RestController
@RequestMapping("/repositories")
public class RepositoryPublishController {

    @Autowired
    private RepositoryPublishService repositoryPublishService;

    @Operation(summary = "仓库发布快照历史")
    @GetMapping("/{repositoryId}/publish/snapshots")
    public ApiResponse<List<RepositoryPublishSnapshotView>> listSnapshots(@PathVariable Long repositoryId) {
        return ApiResponse.success(repositoryPublishService.listSnapshots(repositoryId));
    }
}
