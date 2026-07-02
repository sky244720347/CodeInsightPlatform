package com.company.codeinsight.modules.entrypoint.trial;

import com.company.codeinsight.common.response.ApiResponse;
import com.company.codeinsight.common.response.PageResult;
import com.company.codeinsight.modules.entrypoint.model.DiscoveredEntrypoint;
import com.company.codeinsight.modules.entrypoint.model.EntryPointConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "入口扫描试跑", description = "仓库入口扫描规则试跑（不创建真实任务）")
@RestController
@RequestMapping("/repositories/{repoId}/trial-run")
@RequiredArgsConstructor
public class TrialRunController {

    private final TrialRunService trialRunService;

    @Operation(summary = "触发仓库入口扫描试跑")
    @PostMapping
    public ApiResponse<EntryScanTrialEntity> trigger(
            @PathVariable Long repoId,
            @RequestParam Long systemId,
            @RequestBody EntryPointConfig config,
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        return ApiResponse.success(trialRunService.trigger(systemId, repoId, config, operator));
    }

    @Operation(summary = "查询仓库当前进行中的试跑")
    @GetMapping("/active")
    public ApiResponse<EntryScanTrialEntity> getActive(@PathVariable Long repoId) {
        return ApiResponse.success(trialRunService.getActive(repoId));
    }

    @Operation(summary = "查询仓库最近一次试跑摘要")
    @GetMapping("/latest")
    public ApiResponse<EntryScanTrialSummary> getLatest(@PathVariable Long repoId) {
        return ApiResponse.success(trialRunService.getLatestSummary(repoId));
    }

    @Operation(summary = "试跑历史分页")
    @GetMapping("/history")
    public ApiResponse<PageResult<EntryScanTrialSummary>> listHistory(
            @PathVariable Long repoId,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.success(trialRunService.listHistory(repoId, current, size));
    }

    @Operation(summary = "查询仓库当前是否有进行中的试跑（用于按钮 disabled 控制）")
    @GetMapping("/lock")
    public ApiResponse<Boolean> isLocked(@PathVariable Long repoId) {
        return ApiResponse.success(trialRunService.isLocked(repoId));
    }

    @Operation(summary = "查询试跑结果（含状态与入口列表）")
    @GetMapping("/{trialId}")
    public ApiResponse<EntryScanTrialEntity> get(@PathVariable Long repoId, @PathVariable Long trialId) {
        return ApiResponse.success(trialRunService.get(trialId));
    }

    @Operation(summary = "取消试跑")
    @DeleteMapping("/{trialId}")
    public ApiResponse<Boolean> cancel(
            @PathVariable Long repoId,
            @PathVariable Long trialId,
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        return ApiResponse.success(trialRunService.cancel(trialId, operator));
    }

    @Operation(summary = "解析试跑结果中的入口列表（供前端回填选择使用）")
    @GetMapping("/{trialId}/entries")
    public ApiResponse<List<DiscoveredEntrypoint>> getEntries(@PathVariable Long repoId, @PathVariable Long trialId) {
        EntryScanTrialEntity t = trialRunService.get(trialId);
        if (t == null) return ApiResponse.success(List.of());
        return ApiResponse.success(trialRunService.parseResultEntries(t.getResultJson()));
    }
}
