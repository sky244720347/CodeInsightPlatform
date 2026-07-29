package com.company.codeinsight.modules.system;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.log.service.OperationLogService;
import com.company.codeinsight.modules.prompt.entity.DecompilePrompt;
import com.company.codeinsight.modules.prompt.mapper.DecompilePromptMapper;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.service.CodeRepositoryService;
import com.company.codeinsight.modules.system.dto.SystemImportItemResult;
import com.company.codeinsight.modules.system.dto.SystemImportResult;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.service.SystemApplicationService;
import com.company.codeinsight.modules.system.service.impl.SystemImportServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 系统 Excel 导入：幂等跳过与默认提示词绑定。
 */
@ExtendWith(MockitoExtension.class)
class SystemImportServiceImplTest {

    @Mock
    private SystemApplicationService systemApplicationService;
    @Mock
    private CodeRepositoryService codeRepositoryService;
    @Mock
    private DecompilePromptMapper decompilePromptMapper;
    @Mock
    private OperationLogService operationLogService;

    @InjectMocks
    private SystemImportServiceImpl systemImportService;

    @BeforeEach
    void stubDefaultPrompts() {
        DecompilePrompt mod = new DecompilePrompt();
        mod.setId(11L);
        DecompilePrompt doc = new DecompilePrompt();
        doc.setId(22L);
        when(decompilePromptMapper.selectOne(any())).thenReturn(mod, doc);
    }

    @Test
    void createsSystemAndRepoWhenAbsent() {
        when(systemApplicationService.getOne(any(), eq(false))).thenReturn(null);
        SystemApplication createdSys = new SystemApplication();
        createdSys.setId(100L);
        createdSys.setName("PH-NCS");
        when(systemApplicationService.createSystemDraft(any())).thenReturn(createdSys);
        when(codeRepositoryService.getOne(any(), eq(false))).thenReturn(null);
        CodeRepository createdRepo = new CodeRepository();
        createdRepo.setId(200L);
        when(codeRepositoryService.createRepository(any())).thenAnswer(inv -> {
            CodeRepository r = inv.getArgument(0);
            r.setId(200L);
            return r;
        });

        SystemImportResult result = systemImportService.importFromExcel(xlsx(
                "系统,系统中文名,系统描述,负责人,git完整地址\n"
                        + "PH-NCS,客服,描述,zhangsan,https://code.example/a.git\n"));

        assertEquals(1, result.getTotalRows());
        assertEquals(1, result.getSystemCreated());
        assertEquals(1, result.getRepoCreated());
        assertEquals(0, result.getSkipped());
        assertEquals(SystemImportItemResult.STATUS_CREATED, result.getItems().get(0).getStatus());

        ArgumentCaptor<CodeRepository> repoCap = ArgumentCaptor.forClass(CodeRepository.class);
        verify(codeRepositoryService).createRepository(repoCap.capture());
        assertEquals(11L, repoCap.getValue().getModularizePromptId());
        assertEquals(22L, repoCap.getValue().getDocumentPromptId());
        assertEquals("master", repoCap.getValue().getBranch());
        assertEquals("/", repoCap.getValue().getScanRoot());
        assertTrue(repoCap.getValue().getEntryScanConfig() != null
                && repoCap.getValue().getEntryScanConfig().contains("CONTROLLER"));
    }

    @Test
    void reusesSystemAndSkipsExistingRepoUrl() {
        SystemApplication existing = new SystemApplication();
        existing.setId(100L);
        existing.setName("PH-NCS");
        when(systemApplicationService.getOne(any(), eq(false))).thenReturn(existing);
        CodeRepository existingRepo = new CodeRepository();
        existingRepo.setId(200L);
        when(codeRepositoryService.getOne(any(), eq(false))).thenReturn(existingRepo);

        SystemImportResult result = systemImportService.importFromExcel(xlsx(
                "系统,系统中文名,系统描述,负责人,git完整地址\n"
                        + "PH-NCS,客服,描述,zhangsan,https://code.example/a.git\n"));

        assertEquals(1, result.getSystemReused());
        assertEquals(0, result.getRepoCreated());
        assertEquals(1, result.getSkipped());
        verify(systemApplicationService, never()).createSystemDraft(any());
        verify(codeRepositoryService, never()).createRepository(any());
    }

    @Test
    void reusesSystemAndCreatesSecondRepo() {
        SystemApplication existing = new SystemApplication();
        existing.setId(100L);
        existing.setName("PH-NCS");
        when(systemApplicationService.getOne(any(), eq(false))).thenReturn(existing);
        when(codeRepositoryService.getOne(any(), eq(false))).thenReturn(null);
        when(codeRepositoryService.createRepository(any())).thenAnswer(inv -> {
            CodeRepository r = inv.getArgument(0);
            r.setId(201L);
            return r;
        });

        SystemImportResult result = systemImportService.importFromExcel(xlsx(
                "系统,系统中文名,系统描述,负责人,git完整地址\n"
                        + "PH-NCS,客服,描述,zhangsan,https://code.example/b.git\n"));

        assertEquals(0, result.getSystemCreated());
        assertEquals(1, result.getSystemReused());
        assertEquals(1, result.getRepoCreated());
        assertEquals(SystemImportItemResult.STATUS_SYSTEM_REUSED, result.getItems().get(0).getStatus());
        verify(systemApplicationService, never()).createSystemDraft(any());
        verify(codeRepositoryService, times(1)).createRepository(any());
    }

    @Test
    void createsSystemNameUppercase() {
        when(systemApplicationService.getOne(any(), eq(false))).thenReturn(null);
        when(systemApplicationService.createSystemDraft(any())).thenAnswer(inv -> {
            SystemApplication s = inv.getArgument(0);
            s.setId(100L);
            return s;
        });
        when(codeRepositoryService.getOne(any(), eq(false))).thenReturn(null);
        when(codeRepositoryService.createRepository(any())).thenAnswer(inv -> {
            CodeRepository r = inv.getArgument(0);
            r.setId(200L);
            return r;
        });

        systemImportService.importFromExcel(xlsx(
                "系统,系统中文名,系统描述,负责人,git完整地址\n"
                        + "ph-ncs,客服,描述,zhangsan,https://code.example/a.git\n"));

        ArgumentCaptor<SystemApplication> sysCap = ArgumentCaptor.forClass(SystemApplication.class);
        verify(systemApplicationService).createSystemDraft(sysCap.capture());
        assertEquals("PH-NCS", sysCap.getValue().getName());
    }

    @Test
    void reusesSystemIgnoreCase() {
        SystemApplication existing = new SystemApplication();
        existing.setId(100L);
        existing.setName("PH-NCS");
        when(systemApplicationService.getOne(any(), eq(false))).thenReturn(existing);
        when(codeRepositoryService.getOne(any(), eq(false))).thenReturn(null);
        when(codeRepositoryService.createRepository(any())).thenAnswer(inv -> {
            CodeRepository r = inv.getArgument(0);
            r.setId(201L);
            return r;
        });

        SystemImportResult result = systemImportService.importFromExcel(xlsx(
                "系统,系统中文名,系统描述,负责人,git完整地址\n"
                        + "ph-ncs,客服,描述,zhangsan,https://code.example/b.git\n"));

        assertEquals(0, result.getSystemCreated());
        assertEquals(1, result.getSystemReused());
        assertEquals(1, result.getRepoCreated());
        verify(systemApplicationService, never()).createSystemDraft(any());
        verify(codeRepositoryService, times(1)).createRepository(any());
    }

    @Test
    void failsWhenDefaultPromptMissing() {
        when(decompilePromptMapper.selectOne(any())).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> systemImportService.importFromExcel(xlsx(
                        "系统,系统中文名,系统描述,负责人,git完整地址\n"
                                + "PH-NCS,客服,描述,zhangsan,https://code.example/a.git\n")));
        assertTrue(ex.getMessage().contains("默认提示词"));
        verify(operationLogService, never()).logOperation(any(), any(), any(), any(), any(), any(Boolean.class));
    }

    private static MockMultipartFile xlsx(String csvFallbackUnused) {
        // EasyExcel 需要真正的 xlsx；用 POI 写最小工作簿
        try {
            org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet();
            String[] lines = csvFallbackUnused.split("\n");
            for (int i = 0; i < lines.length; i++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(i);
                String[] cols = lines[i].split(",", -1);
                for (int c = 0; c < cols.length; c++) {
                    row.createCell(c).setCellValue(cols[c]);
                }
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            wb.write(bos);
            wb.close();
            return new MockMultipartFile("file", "import.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    bos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
