package com.company.codeinsight.modules.system.support;

import com.alibaba.excel.write.handler.SheetWriteHandler;
import com.alibaba.excel.write.metadata.holder.WriteSheetHolder;
import com.alibaba.excel.write.metadata.holder.WriteWorkbookHolder;
import com.company.codeinsight.modules.repository.model.TechStackCatalog;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddressList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 导入模板：为「代码库类型」「技术栈」写入 Excel 下拉；技术栈按类型级联（INDIRECT + 命名区域）。
 */
public class SystemImportTemplateWriteHandler implements SheetWriteHandler {

    /** 与 {@link com.company.codeinsight.modules.system.dto.SystemImportExcelRow} 列序一致（0-based） */
    static final int COL_REPO_TYPE = 5;
    static final int COL_TECH_STACK = 6;

    /** 数据行起止（含表头下一行；上限对齐单次导入行数 SystemExcelParser.MAX_ROWS） */
    private static final int FIRST_DATA_ROW = 1;
    private static final int LAST_DATA_ROW = 200;

    private static final String DICT_SHEET = "_tech_stack_dict";

    @Override
    public void afterSheetCreate(WriteWorkbookHolder writeWorkbookHolder, WriteSheetHolder writeSheetHolder) {
        Workbook workbook = writeWorkbookHolder.getWorkbook();
        Sheet mainSheet = writeSheetHolder.getSheet();
        Map<String, List<String>> catalog = TechStackCatalog.all();

        writeHiddenDictAndNames(workbook, catalog);
        addRepoTypeDropdown(mainSheet, new ArrayList<>(catalog.keySet()));
        addTechStackCascadeDropdown(mainSheet);
    }

    private void writeHiddenDictAndNames(Workbook workbook, Map<String, List<String>> catalog) {
        Sheet dict = workbook.createSheet(DICT_SHEET);
        int col = 0;
        for (Map.Entry<String, List<String>> entry : catalog.entrySet()) {
            List<String> stacks = entry.getValue();
            if (stacks == null || stacks.isEmpty()) {
                col++;
                continue;
            }
            for (int i = 0; i < stacks.size(); i++) {
                Row row = dict.getRow(i);
                if (row == null) {
                    row = dict.createRow(i);
                }
                row.createCell(col).setCellValue(stacks.get(i));
            }
            // 命名区域名 = 代码库类型文案（前端/后端/DB），供 INDIRECT(类型单元格) 引用
            String colLetter = excelColumnLetter(col);
            Name name = workbook.createName();
            name.setNameName(entry.getKey());
            name.setRefersToFormula(DICT_SHEET + "!$" + colLetter + "$1:$" + colLetter + "$" + stacks.size());
            col++;
        }
        workbook.setSheetHidden(workbook.getSheetIndex(dict), true);
    }

    private void addRepoTypeDropdown(Sheet sheet, List<String> repoTypes) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        CellRangeAddressList range = new CellRangeAddressList(
                FIRST_DATA_ROW, LAST_DATA_ROW, COL_REPO_TYPE, COL_REPO_TYPE);
        DataValidationConstraint constraint = helper.createExplicitListConstraint(
                repoTypes.toArray(String[]::new));
        DataValidation validation = helper.createValidation(constraint, range);
        validation.setSuppressDropDownArrow(true);
        validation.setShowErrorBox(true);
        validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
        validation.createErrorBox("代码库类型", "请从下拉中选择：前端 / 后端 / DB");
        validation.createPromptBox("代码库类型", "可选：前端、后端、DB");
        validation.setShowPromptBox(true);
        sheet.addValidationData(validation);
    }

    private void addTechStackCascadeDropdown(Sheet sheet) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        // F 列 = 代码库类型；相对行引用，使每行跟随本行类型
        String typeColLetter = excelColumnLetter(COL_REPO_TYPE);
        CellRangeAddressList range = new CellRangeAddressList(
                FIRST_DATA_ROW, LAST_DATA_ROW, COL_TECH_STACK, COL_TECH_STACK);
        DataValidationConstraint constraint = helper.createFormulaListConstraint(
                "INDIRECT($" + typeColLetter + "2)");
        DataValidation validation = helper.createValidation(constraint, range);
        validation.setSuppressDropDownArrow(true);
        validation.setShowErrorBox(true);
        validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
        validation.createErrorBox("技术栈", "请先选择代码库类型，再从下拉中选择对应技术栈");
        validation.createPromptBox("技术栈", "随「代码库类型」联动；须从下拉选择");
        validation.setShowPromptBox(true);
        sheet.addValidationData(validation);
    }

    /** 0-based 列号 → Excel 列字母（仅覆盖本模板用到的列） */
    static String excelColumnLetter(int zeroBasedIndex) {
        return String.valueOf((char) ('A' + zeroBasedIndex));
    }
}
