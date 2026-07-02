package com.company.codeinsight.modules.knowledge.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.codeinsight.common.response.ApiResponse;
import com.company.codeinsight.common.response.PageResult;
import com.company.codeinsight.modules.knowledge.entity.KnowledgeVersion;
import com.company.codeinsight.modules.knowledge.service.KnowledgeService;
import com.company.codeinsight.modules.push.enums.PushMethod;
import com.company.codeinsight.modules.push.service.PushService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 知识推送与版本管理控制器
 * 提供知识库新版本创建、Git 推送提交、ZIP 压缩包二进制流导出及版本记录的分页查询端点。
 */
@Tag(name = "知识推送与版本", description = "知识元数据版本生成、Git 推送提交、ZIP 数据导出接口")
@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private PushService pushService;

    @Autowired
    private com.company.codeinsight.modules.repository.publish.service.RepositoryPublishService repositoryPublishService;

    /**
     * 根据复核通过的任务创建知识版本记录
     *
     * @param taskId      任务 ID
     * @param versionNum  自定义版本号（如 v1.0.0）
     * @param confirmedBy 操作确认负责人用户名
     */
    @Operation(summary = "创建新知识版本")
    @PostMapping("/version")
    public ApiResponse<KnowledgeVersion> createVersion(
            @RequestParam Long taskId,
            @RequestParam String versionNum,
            @RequestParam(required = false, defaultValue = "Admin") String confirmedBy) {
        KnowledgeVersion version = knowledgeService.createVersion(taskId, versionNum, confirmedBy);
        return ApiResponse.success(version);
    }

    /**
     * 提交推送当前版本到目标 Git 代码库（异步队列）
     * 任务进入 Redis 队列后立即返回，由后台调度器异步执行实际推送。
     */
    @Operation(summary = "提交发布至 NAS / Git（异步队列，默认 NAS）")
    @PostMapping("/{versionId}/push")
    public ApiResponse<Void> push(
            @PathVariable Long versionId,
            @RequestParam(defaultValue = "NAS") String method) {
        PushMethod pushMethod = PushMethod.valueOf(method.toUpperCase());
        pushService.enqueuePush(versionId, pushMethod);
        return ApiResponse.success();
    }

    @Operation(summary = "按版本回滚仓库发布态")
    @PostMapping("/{versionId}/rollback-repository")
    public ApiResponse<com.company.codeinsight.modules.repository.publish.entity.RepositoryPublishSnapshot> rollbackRepository(
            @PathVariable Long versionId) {
        return ApiResponse.success(
                repositoryPublishService.rollbackToVersionByVersionId(
                        versionId, com.company.codeinsight.common.auth.OperatorContext.get()));
    }


    /**
     * 导出当前版本的所有文档为 ZIP 格式的二进制数据流进行本地下载
     */
    @Operation(summary = "导出为 ZIP 包二进制流")
    @GetMapping("/{versionId}/export")
    public ResponseEntity<byte[]> exportZip(@PathVariable Long versionId) {
        byte[] bytes = knowledgeService.exportZip(versionId);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=code-insight-knowledge-" + versionId + ".zip");
        headers.add(HttpHeaders.CONTENT_TYPE, "application/octet-stream");
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    /**
     * 知识发布版本的分页列表查询
     */
    @Operation(summary = "知识版本分页查询")
    @GetMapping("/page")
    public ApiResponse<PageResult<KnowledgeVersion>> getPage(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long systemId,
            @RequestParam(required = false) Long repositoryId) {
        Page<KnowledgeVersion> page = knowledgeService.listVersionsPage(current, size, systemId, repositoryId);
        PageResult<KnowledgeVersion> result = new PageResult<>(page.getTotal(), page.getSize(), page.getCurrent(), page.getRecords());
        return ApiResponse.success(result);
    }
}

