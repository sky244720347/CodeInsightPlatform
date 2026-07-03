package com.company.codeinsight.common.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DraftPathValidatorTest {

    @Test
    public void acceptsFunctionGranularityPath() {
        Assertions.assertNull(DraftPathValidator.validatePushFilePath(
                "task_38/语义类别管理/语义类别维护/语义类别级联删除.md"));
    }

    @Test
    public void rejectsIllegalPathSegment() {
        Assertions.assertNotNull(DraftPathValidator.validatePushFilePath(
                "task_38/bad:name/file.md"));
    }

    @Test
    public void rejectsTraversal() {
        Assertions.assertNotNull(DraftPathValidator.validatePushFilePath(
                "task_38/../secret.md"));
    }
}
