package com.company.codeinsight.modules.push.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.company.codeinsight.common.response.ApiResponse;
import com.company.codeinsight.modules.push.entity.PushTask;
import com.company.codeinsight.modules.push.service.PushService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 推送记录查询（手动入队已下线，发布由知识确认后自动 NAS 推送）。
 */
@Tag(name = "推送记录", description = "查询知识版本的 NAS 推送任务历史")
@RestController
@RequestMapping("/push")
public class PushController {

    @Autowired
    private PushService pushService;

    @Operation(summary = "查询版本关联的推送任务列表")
    @GetMapping("/version/{versionId}/tasks")
    public ApiResponse<List<PushTask>> getPushTasks(@PathVariable Long versionId) {
        return ApiResponse.success(pushService.listTasksByVersion(versionId));
    }
}
