package com.company.codeinsight.modules.knowledge.remediation;

import com.company.codeinsight.common.exception.BusinessException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

public class KnowledgeReleaseEditPathTest {

    @Test
    void normalizeAcceptsRelativePath() {
        String normalized = invokeNormalize("模块A/文档.md");
        Assertions.assertEquals("模块A/文档.md", normalized);
    }

    @Test
    void normalizeRejectsTraversal() {
        Assertions.assertThrows(BusinessException.class, () -> invokeNormalize("../secret.md"));
    }

    private String invokeNormalize(String path) {
        KnowledgeReleaseEditService service = new KnowledgeReleaseEditService(null, null);
        return ReflectionTestUtils.invokeMethod(service, "normalizeRelativePath", path);
    }
}
