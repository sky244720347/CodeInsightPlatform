package com.company.codeinsight.modules.system.support;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.system.dto.SystemImportExcelRow;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 解析系统批量导入 Excel（.xlsx）。
 */
public final class SystemExcelParser {

    public static final int MAX_ROWS = 200;

    private SystemExcelParser() {
    }

    public static List<ParsedRow> parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请上传 Excel 文件");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new BusinessException("仅支持 .xlsx 文件");
        }
        List<ParsedRow> rows = new ArrayList<>();
        try (InputStream in = file.getInputStream()) {
            EasyExcel.read(in, SystemImportExcelRow.class, new AnalysisEventListener<SystemImportExcelRow>() {
                @Override
                public void invoke(SystemImportExcelRow data, AnalysisContext context) {
                    if (rows.size() >= MAX_ROWS) {
                        throw new BusinessException("单次最多导入 " + MAX_ROWS + " 行");
                    }
                    int excelRow = context.readRowHolder().getRowIndex() + 1;
                    if (isBlankRow(data)) {
                        return;
                    }
                    rows.add(new ParsedRow(excelRow, data));
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    // no-op
                }
            }).sheet().headRowNumber(1).doRead();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Excel 解析失败: " + e.getMessage());
        }
        if (rows.isEmpty()) {
            throw new BusinessException("Excel 无有效数据行");
        }
        return rows;
    }

    private static boolean isBlankRow(SystemImportExcelRow data) {
        if (data == null) {
            return true;
        }
        return !StringUtils.hasText(data.getSystemName())
                && !StringUtils.hasText(data.getGitUrl())
                && !StringUtils.hasText(data.getOwner())
                && !StringUtils.hasText(data.getNameCn())
                && !StringUtils.hasText(data.getDescription());
    }

    public record ParsedRow(int excelRow, SystemImportExcelRow data) {
    }
}
