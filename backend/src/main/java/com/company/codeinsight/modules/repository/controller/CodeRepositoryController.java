package com.company.codeinsight.modules.repository.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.codeinsight.common.response.ApiResponse;
import com.company.codeinsight.common.response.PageResult;
import com.company.codeinsight.modules.log.service.OperationLogService;
import com.company.codeinsight.modules.repository.dto.GitBatchCheckAccepted;
import com.company.codeinsight.modules.repository.dto.GitConnectivityResult;
import com.company.codeinsight.modules.repository.dto.GitConnectivitySummary;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.model.TechStackCatalog;
import com.company.codeinsight.modules.repository.service.CodeRepositoryService;
import com.company.codeinsight.modules.repository.service.RepoGitConnectivityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
    private RepoGitConnectivityService repoGitConnectivityService;

    @Autowired
    private OperationLogService operationLogService;

    /**
     * 代码库类型 → 技术栈级联目录（code 与 label 一致）。
     */
    @Operation(summary = "技术栈级联目录")
    @GetMapping("/tech-stack-catalog")
    public ApiResponse<Map<String, List<String>>> techStackCatalog() {
        return ApiResponse.success(TechStackCatalog.all());
    }

    @Operation(summary = "Git 连通性汇总（监控预留）")
    @GetMapping("/git-connectivity-summary")
    public ApiResponse<GitConnectivitySummary> gitConnectivitySummary() {
        return ApiResponse.success(repoGitConnectivityService.summarize());
    }

    /**
     * 新增代码库配置：自动推进系统状态至 REPO_CONFIGURED / SCAN_CONFIGURED
     */
    @Operation(summary = "新增代码库")
    @PostMapping
    public ApiResponse<CodeRepository> createRepository(@Valid @RequestBody CodeRepository repository) {
        CodeRepository created = codeRepositoryService.createRepository(repository);
        operationLogService.logOperation(created.getSystemId(), null, "CREATE_REPO", "创建代码库: " + created.getGitUrl(), null, true);
        maskPassword(created);
        // 新建后异步探测连通性
        try {
            repoGitConnectivityService.submitBatchForSystem(created.getSystemId());
        } catch (Exception ignored) {
            // 不影响创建主流程
        }
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
     * 测试 Git 连接。带 id 时测完落库；无 id 仅探测。
     * <p>返回结构化结果；前端亦可读 {@code reachable} 当 boolean 使用。</p>
     */
    @Operation(summary = "测试 Git 连接")
    @PostMapping("/test-connection")
    public ApiResponse<GitConnectivityResult> testConnectionBeforeSave(@RequestBody CodeRepository repository) {
        GitConnectivityResult result = codeRepositoryService.testConnectionDetailed(repository);
        return ApiResponse.success(result);
    }

    /**
     * 对已经持久化保存的代码仓库进行连通性连接测试并落库
     */
    @Operation(summary = "测试 Git 连接 (已保存)")
    @PostMapping("/{id}/test-connection")
    public ApiResponse<GitConnectivityResult> testConnectionSaved(@PathVariable Long id) {
        return ApiResponse.success(repoGitConnectivityService.checkAndPersist(id));
    }

    @Operation(summary = "按系统异步批量检测 Git 连通性")
    @PostMapping("/batch-test-connection")
    public ApiResponse<GitBatchCheckAccepted> batchTestConnection(@RequestParam Long systemId) {
        return ApiResponse.success(repoGitConnectivityService.submitBatchForSystem(systemId));
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
