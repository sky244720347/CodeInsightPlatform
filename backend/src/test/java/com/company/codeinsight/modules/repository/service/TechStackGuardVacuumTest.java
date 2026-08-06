package com.company.codeinsight.modules.repository.service;

import com.company.codeinsight.common.config.TaskTechStackProperties;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TechStackGuardVacuumTest {

    private final TechStackGuard guard = new TechStackGuard(new TaskTechStackProperties());

    @Test
    void allowsVacuum() {
        CodeRepository repo = new CodeRepository();
        repo.setRepoType("  ");
        repo.setTechStack("");
        assertDoesNotThrow(() -> guard.normalizeAndValidate(repo));
        assertNull(repo.getRepoType());
        assertNull(repo.getTechStack());
    }

    @Test
    void requiresPairWhenPartial() {
        CodeRepository repo = new CodeRepository();
        repo.setRepoType("后端");
        repo.setTechStack(null);
        assertThrows(BusinessException.class, () -> guard.normalizeAndValidate(repo));
    }

    @Test
    void acceptsValidPair() {
        CodeRepository repo = new CodeRepository();
        repo.setRepoType(" 后端 ");
        repo.setTechStack(" Java ");
        assertDoesNotThrow(() -> guard.normalizeAndValidate(repo));
        assertEquals("后端", repo.getRepoType());
        assertEquals("Java", repo.getTechStack());
    }
}
