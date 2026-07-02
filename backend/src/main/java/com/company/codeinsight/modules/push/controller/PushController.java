package com.company.codeinsight.modules.push.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.company.codeinsight.common.response.ApiResponse;
import com.company.codeinsight.modules.push.entity.PushTask;
import com.company.codeinsight.modules.push.enums.PushMethod;
import com.company.codeinsight.modules.push.service.PushService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 知识推送控制器
 *
 * 提供推送任务提交和推送历史查询的 REST API。
 */
@Tag(name = "知识推送", description = "异步推送知识版本到 Git 或 S3 对象存储")
@RestController
@RequestMapping("/push")
public class PushController {

    @Autowired
    private PushService pushService;

    /**
     * 提交推送任务到 Redis 队列，异步执行
     *
     * @param versionId 知识版本 ID
     * @param method    推送方式，默认 GIT
     */
    @Operation(summary = "提交推送任务到队列")
    @PostMapping("/version/{versionId}")
    public ApiResponse<Void> push(
            @PathVariable Long versionId,
            @RequestParam(defaultValue = "NAS") String method) {
        PushMethod pushMethod = PushMethod.valueOf(method.toUpperCase());
        pushService.enqueuePush(versionId, pushMethod);
        return ApiResponse.success();
    }

    /**
     * 查询指定版本关联的所有推送任务记录
     *
     * @param versionId 知识版本 ID
     * @return 推送任务列表
     */
    @Operation(summary = "查询版本关联的推送任务列表")
    @GetMapping("/version/{versionId}/tasks")
    public ApiResponse<List<PushTask>> getPushTasks(@PathVariable Long versionId) {
        return ApiResponse.success(pushService.listTasksByVersion(versionId));
    }
}
