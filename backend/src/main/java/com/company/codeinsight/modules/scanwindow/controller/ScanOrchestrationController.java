package com.company.codeinsight.modules.scanwindow.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.codeinsight.common.response.ApiResponse;
import com.company.codeinsight.modules.scanwindow.dto.ScanOrchestrationSummary;
import com.company.codeinsight.modules.scanwindow.dto.ScanProbeRecordView;
import com.company.codeinsight.modules.scanwindow.scheduler.ScanWindowScheduler;
import com.company.codeinsight.modules.scanwindow.service.ScanProbeRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * 任务编排（commit 探测）API。见 docs/scan-orchestration-ui-plan.md。
 */
@RestController
@RequestMapping("/scan/orchestration")
@RequiredArgsConstructor
public class ScanOrchestrationController {

    private final ScanProbeRecordService probeRecordService;
    private final ScanWindowScheduler scheduler;

    @GetMapping("/summary")
    public ApiResponse<ScanOrchestrationSummary> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        ScanOrchestrationSummary s = probeRecordService.summary(date);
        s.setSchedulerEnabled(scheduler.isEnabled());
        s.setCron(scheduler.getCurrentCron());
        s.setNextRuns(scheduler.getNextRuns(5));
        return ApiResponse.success(s);
    }

    @GetMapping("/records")
    public ApiResponse<Page<ScanProbeRecordView>> records(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(probeRecordService.pageRecords(date, status, keyword, current, size));
    }

    @PutMapping("/cron")
    public ApiResponse<Map<String, Object>> updateCron(@RequestBody Map<String, String> body) {
        String cron = body.get("cron");
        scheduler.updateCron(cron);
        return ApiResponse.success(Map.of(
                "cron", scheduler.getCurrentCron(),
                "nextRuns", scheduler.getNextRuns(5)
        ));
    }

    @PutMapping("/enabled")
    public ApiResponse<Boolean> setEnabled(@RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.getOrDefault("enabled", true);
        scheduler.setEnabled(Boolean.TRUE.equals(enabled));
        return ApiResponse.success(scheduler.isEnabled());
    }
}
