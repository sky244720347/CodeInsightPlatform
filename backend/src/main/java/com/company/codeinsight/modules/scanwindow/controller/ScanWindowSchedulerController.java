package com.company.codeinsight.modules.scanwindow.controller;

import com.company.codeinsight.common.response.ApiResponse;
import com.company.codeinsight.modules.scanwindow.scheduler.ScanWindowScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/scan-windows/scheduler")
@RequiredArgsConstructor
public class ScanWindowSchedulerController {

    private final ScanWindowScheduler scheduler;

    @GetMapping("/cron")
    public ApiResponse<Map<String, Object>> getCron() {
        return ApiResponse.success(Map.of(
                "cron", scheduler.getCurrentCron(),
                "nextRuns", scheduler.getNextRuns(5),
                "enabled", scheduler.isEnabled()
        ));
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

    @GetMapping("/enabled")
    public ApiResponse<Boolean> getEnabled() {
        return ApiResponse.success(scheduler.isEnabled());
    }

    @PutMapping("/enabled")
    public ApiResponse<Boolean> setEnabled(@RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.getOrDefault("enabled", true);
        scheduler.setEnabled(enabled);
        return ApiResponse.success(scheduler.isEnabled());
    }
}
