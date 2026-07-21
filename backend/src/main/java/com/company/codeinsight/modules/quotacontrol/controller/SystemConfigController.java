package com.company.codeinsight.modules.quotacontrol.controller;

import com.company.codeinsight.common.auth.OperatorContext;
import com.company.codeinsight.common.response.ApiResponse;
import com.company.codeinsight.modules.quotacontrol.dto.SystemConfigUpdateRequest;
import com.company.codeinsight.modules.quotacontrol.entity.SystemConfig;
import com.company.codeinsight.modules.quotacontrol.service.AiConcurrencyService;
import com.company.codeinsight.modules.quotacontrol.service.SystemConfigService;
import com.company.codeinsight.modules.task.service.TaskConcurrencyLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "系统配置", description = "运行期可在线修改的全局配置（key-value）")
@RestController
@RequestMapping("/system-config")
public class SystemConfigController {

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private AiConcurrencyService aiConcurrencyService;

    @Autowired
    private TaskConcurrencyLimiter taskConcurrencyLimiter;

    @Operation(summary = "列出所有配置")
    @GetMapping
    public ApiResponse<List<SystemConfig>> list() {
        return ApiResponse.success(systemConfigService.listAll());
    }

    @Operation(summary = "读取单个配置")
    @GetMapping("/{key}")
    public ApiResponse<SystemConfig> get(@PathVariable String key) {
        return ApiResponse.success(systemConfigService.getById(key));
    }

    @Operation(summary = "写/更新单个配置")
    @PutMapping("/{key}")
    public ApiResponse<Void> put(@PathVariable String key, @RequestBody SystemConfigUpdateRequest body) {
        String desc = body.getDescription();
        // 保留旧 description：调用方没传时不覆盖
        if (desc == null) {
            SystemConfig old = systemConfigService.getById(key);
            if (old != null) {
                desc = old.getDescription();
            }
        }
        systemConfigService.putString(key, body.getValue(), desc, OperatorContext.get());
        if ("ai.concurrency".equals(key)) {
            try {
                aiConcurrencyService.rebuild(Integer.parseInt(body.getValue()));
            } catch (NumberFormatException ignored) {
            }
        }
        if ("task.concurrency".equals(key)) {
            try {
                taskConcurrencyLimiter.rebuildGlobal(Integer.parseInt(body.getValue()));
            } catch (NumberFormatException ignored) {
            }
        }
        return ApiResponse.success();
    }
}
