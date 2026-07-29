package com.company.codeinsight.modules.system.service;

import com.company.codeinsight.modules.system.dto.SystemImportResult;
import org.springframework.web.multipart.MultipartFile;

/**
 * 系统 / 仓库 Excel 批量导入。
 */
public interface SystemImportService {

    /**
     * 上传 Excel，按「系统已有则复用、同系统下 gitUrl 已有则跳过」幂等创建。
     * <p>仓库配置与向导默认一致：branch=master、scanRoot=/、默认入口扫描、绑定全局默认提示词；Git 凭证留空。</p>
     */
    SystemImportResult importFromExcel(MultipartFile file);

    /**
     * 生成导入模板 .xlsx（含表头 + 一行示例）。
     */
    byte[] buildImportTemplate();
}
