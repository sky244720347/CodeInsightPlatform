package com.company.codeinsight.modules.repository;

import com.company.codeinsight.common.config.TaskTechStackProperties;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.exception.ErrorCode;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.model.TechStackCatalog;
import com.company.codeinsight.modules.repository.service.TechStackGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TechStackGuardTest {

    private TechStackGuard guard;

    @BeforeEach
    void setUp() {
        TaskTechStackProperties props = new TaskTechStackProperties();
        props.setSupportedTechStacks("Java");
        guard = new TechStackGuard(props);
    }

    @Test
    void catalogContainsBackendJava() {
        assertTrue(TechStackCatalog.isValidPair("后端", "Java"));
        assertTrue(TechStackCatalog.isValidPair("前端", "React"));
        assertFalse(TechStackCatalog.isValidPair("前端", "Java"));
        assertTrue(TechStackCatalog.isValidPair("前后端", "Java,Vue"));
        assertFalse(TechStackCatalog.isValidPair("前后端", "Java"));
        assertFalse(TechStackCatalog.isValidPair("前后端", "React,Vue"));
    }

    @Test
    void requireValidCatalogPairRejectsBlank() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.requireValidCatalogPair(null, "Java"));
        assertTrue(ex.getMessage().contains("代码库类型"));
    }

    @Test
    void assertExecutableAllowsJava() {
        CodeRepository repo = new CodeRepository();
        repo.setTechStack("Java");
        guard.assertExecutableForTask(repo);
    }

    @Test
    void assertExecutableAllowsFullstackWhenAnyTokenSupported() {
        CodeRepository repo = new CodeRepository();
        repo.setRepoType("前后端");
        repo.setTechStack("Java,React");
        guard.assertExecutableForTask(repo);
    }

    @Test
    void assertExecutableRejectsFullstackWhenNoTokenSupported() {
        CodeRepository repo = new CodeRepository();
        repo.setTechStack("Python,React");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.assertExecutableForTask(repo));
        assertEquals(ErrorCode.TECH_STACK_UNSUPPORTED.getCode(), ex.getCode());
    }

    @Test
    void assertExecutableRejectsUnsupported() {
        CodeRepository repo = new CodeRepository();
        repo.setTechStack("Python");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.assertExecutableForTask(repo));
        assertEquals(ErrorCode.TECH_STACK_UNSUPPORTED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("暂不支持生成知识"));
    }

    @Test
    void assertExecutableRejectsMissing() {
        CodeRepository repo = new CodeRepository();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.assertExecutableForTask(repo));
        assertEquals(ErrorCode.TECH_STACK_NOT_CONFIGURED.getCode(), ex.getCode());
    }
}
