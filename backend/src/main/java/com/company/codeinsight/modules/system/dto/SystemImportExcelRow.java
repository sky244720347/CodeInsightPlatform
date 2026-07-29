package com.company.codeinsight.modules.system.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * Excel 批量导入行（表头与业务表格对齐）。
 * <p>无关列（rep_name / rep_namespace）可存在但不参与入库。</p>
 */
@Data
public class SystemImportExcelRow {

    @ExcelProperty("系统")
    private String systemName;

    @ExcelProperty("系统中文名")
    private String nameCn;

    @ExcelProperty("系统描述")
    private String description;

    @ExcelProperty("负责人")
    private String owner;

    @ExcelProperty("git完整地址")
    private String gitUrl;
}
