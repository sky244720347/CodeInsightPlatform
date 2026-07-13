package com.company.codeinsight.modules.repository.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.codeinsight.common.response.ApiResponse;
import com.company.codeinsight.common.response.PageResult;
import com.company.codeinsight.modules.log.service.OperationLogService;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.service.CodeRepositoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 代码仓库管理控制器
 * 提供代码仓库连接的创建、编辑保存、Git 连接测试验证（包括未保存和已保存的连通性校验）以及密码参数脱敏的 API 访问端点。
 */
@Tag(name = "代码库管理", description = "代码库配置及连接测试接口")
@RestController
@RequestMapping("/repositories")
@Validated
public class CodeRepositoryController {

    @Autowired
    private CodeRepositoryService codeRepositoryService;

    @Autowired
    private OperationLogService operationLogService;

    /**
     * 新增代码库配置：自动推进系统状态至 REPO_CONFIGURED / SCAN_CONFIGURED
     */
    @Operation(summary = "新增代码库")
    @PostMapping
    public ApiResponse<CodeRepository> createRepository(@Valid @RequestBody CodeRepository repository) {
        CodeRepository created = codeRepositoryService.createRepository(repository);
        operationLogService.logOperation(created.getSystemId(), null, "CREATE_REPO", "创建代码库: " + created.getGitUrl(), null, true);
        maskPassword(created);
        return ApiResponse.success(created);
    }

    /**
     * 编辑代码库配置。密码占位 "******" 自动还原为旧凭证；
     * 若 entryScanConfig 由 null 变为非空，自动推进系统到 SCAN_CONFIGURED。
     */
    @Operation(summary = "编辑代码库")
    @PutMapping("/{id}")
    public ApiResponse<CodeRepository> updateRepository(@PathVariable Long id, @Valid @RequestBody CodeRepository repository) {
        CodeRepository updated = codeRepositoryService.updateRepository(id, repository);
        operationLogService.logOperation(updated.getSystemId(), null, "UPDATE_REPO", "更新代码库: " + updated.getGitUrl(), null, true);
        maskPassword(updated);
        return ApiResponse.success(updated);
    }

    /**
     * 获取指定 ID 的代码仓库元数据（进行密码脱敏）
     */
    @Operation(summary = "代码库详情")
    @GetMapping("/{id}")
    public ApiResponse<CodeRepository> getRepository(@PathVariable Long id) {
        CodeRepository repository = codeRepositoryService.getById(id);
        maskPassword(repository);
        return ApiResponse.success(repository);
    }

    /**
     * 分页查询已登记注册的 Git 仓库配置信息
     */
    @Operation(summary = "代码库分页查询")
    @GetMapping
    public ApiResponse<PageResult<CodeRepository>> listRepositories(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long systemId,
            @RequestParam(required = false) String gitUrl,
            @RequestParam(required = false) Boolean hasPublished) {
        Page<CodeRepository> page = codeRepositoryService.listRepositoriesPage(current, size, systemId, gitUrl, hasPublished);
        page.getRecords().forEach(this::maskPassword);
        PageResult<CodeRepository> result = new PageResult<>(page.getTotal(), page.getSize(), page.getCurrent(), page.getRecords());
        return ApiResponse.success(result);
    }

    /**
     * 临时测试 Git 仓库网络连通性与认证凭证有效性（未保存配置前测试）
     */
    @Operation(summary = "测试 Git 连接 (未保存)")
    @PostMapping("/test-connection")
    public ApiResponse<Boolean> testConnectionBeforeSave(@RequestBody CodeRepository repository) {
        boolean connected = codeRepositoryService.testConnection(
                repository.getGitUrl(),
                repository.getBranch(),
                repository.getUsername(),
                "******".equals(repository.getPassword()) && repository.getId() != null ?
                        codeRepositoryService.getById(repository.getId()).getPassword() : repository.getPassword()
        );
        return ApiResponse.success(connected);
    }

    /**
     * 对已经持久化保存的代码仓库进行连通性连接测试
     */
    @Operation(summary = "测试 Git 连接 (已保存)")
    @PostMapping("/{id}/test-connection")
    public ApiResponse<Boolean> testConnectionSaved(@PathVariable Long id) {
        boolean connected = codeRepositoryService.testConnection(id);
        return ApiResponse.success(connected);
    }

    /**
     * 软删除代码库：存在活跃任务时拒绝。
     */
    @Operation(summary = "软删除代码库")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteRepository(@PathVariable Long id) {
        codeRepositoryService.softDeleteRepository(id);
        operationLogService.logOperation(null, null, "DELETE_REPO", "软删除代码库 ID=" + id, null, true);
        return ApiResponse.success();
    }

    /**
     * 对关键认证凭证敏感字段进行统一的掩码星号脱敏安全处理
     */
    private void maskPassword(CodeRepository repo) {
        if (repo != null && StringUtils.hasText(repo.getPassword())) {
            repo.setPassword("******");
        }
    }
}

