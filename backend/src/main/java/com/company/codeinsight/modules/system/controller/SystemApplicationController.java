package com.company.codeinsight.modules.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.codeinsight.common.response.ApiResponse;
import com.company.codeinsight.common.response.PageResult;
import com.company.codeinsight.modules.log.service.OperationLogService;
import com.company.codeinsight.modules.system.dto.SystemImportResult;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.service.SystemApplicationService;
import com.company.codeinsight.modules.system.service.SystemImportService;
import com.company.codeinsight.modules.system.vo.SystemSummaryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 业务系统应用管理控制器
 * 提供接入应用系统的新增登记、信息编辑、详情获取、分页模糊条件筛选以及软删除端点。
 */
@Tag(name = "系统管理", description = "系统的增删改查接口")
@RestController
@RequestMapping("/systems")
@Validated
public class SystemApplicationController {

    @Autowired
    private SystemApplicationService systemApplicationService;

    @Autowired
    private SystemImportService systemImportService;

    @Autowired
    private OperationLogService operationLogService;

    @Operation(summary = "新增系统（向导 Step 1：基本信息）")
    @PostMapping
    public ApiResponse<SystemApplication> createSystem(@Valid @RequestBody SystemApplication system) {
        SystemApplication created = systemApplicationService.createSystemDraft(system);
        operationLogService.logOperation(created.getId(), null, "CREATE_SYSTEM", "创建系统: " + created.getName(), null, true);
        return ApiResponse.success(created);
    }

    @Operation(summary = "Excel 批量导入系统与仓库（仅上传文件；已有系统/仓库 URL 则跳过）")
    @PostMapping(value = "/import-excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SystemImportResult> importSystemsFromExcel(@RequestPart("file") MultipartFile file) {
        return ApiResponse.success(systemImportService.importFromExcel(file));
    }

    @Operation(summary = "下载系统批量导入 Excel 模板")
    @GetMapping("/import-excel/template")
    public ResponseEntity<byte[]> downloadImportTemplate() {
        byte[] body = systemImportService.buildImportTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"system-import-template.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    @Operation(summary = "编辑系统基本信息（name + component 查重）")
    @PutMapping("/{id}")
    public ApiResponse<SystemApplication> updateSystem(@PathVariable Long id, @Valid @RequestBody SystemApplication system) {
        SystemApplication updated = systemApplicationService.updateSystemBasicInfo(id, system);
        operationLogService.logOperation(id, null, "UPDATE_SYSTEM", "更新系统: " + updated.getName(), null, true);
        return ApiResponse.success(updated);
    }

    @Operation(summary = "系统详情")
    @GetMapping("/{id}")
    public ApiResponse<SystemApplication> getSystem(@PathVariable Long id) {
        SystemApplication system = systemApplicationService.getById(id);
        return ApiResponse.success(system);
    }

    @Operation(summary = "系统分页查询（带聚合指标）")
    @GetMapping
    public ApiResponse<PageResult<SystemSummaryVO>> listSystems(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String component,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) Boolean hasPublished) {
        Page<SystemSummaryVO> page = systemApplicationService.listSystemsPage(current, size, name, component, owner, hasPublished);
        PageResult<SystemSummaryVO> result = new PageResult<>(page.getTotal(), page.getSize(), page.getCurrent(), page.getRecords());
        return ApiResponse.success(result);
    }

    @Operation(summary = "软删除系统（级联软删其下所有代码库）")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteSystem(@PathVariable Long id) {
        systemApplicationService.softDeleteSystem(id);
        operationLogService.logOperation(id, null, "DELETE_SYSTEM", "软删除系统 ID=" + id + "（含其下代码库）", null, true);
        return ApiResponse.success();
    }
}
