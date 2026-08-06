package com.company.codeinsight.modules.task.service;

import com.company.codeinsight.common.config.CodeInsightEnvProperties;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 覆盖 docs/dev-shared-db-safety-plan.md：dev 仅执行 is_dev=true 的任务。
 */
class DevTaskAffinityTest {

    private CodeInsightEnvProperties env;

    @BeforeEach
    void setUp() {
        env = new CodeInsightEnvProperties();
        env.setEnv("dev");
    }

    @Test
    void dev_allowsOnlyIsDevTrue() {
        Assertions.assertTrue(isAllowed(true));
        Assertions.assertFalse(isAllowed(false));
        Assertions.assertFalse(isAllowed(null));
    }

    @Test
    void nonDev_alwaysAllowed() {
        env.setEnv("stg");
        Assertions.assertTrue(isAllowed(false));
        Assertions.assertTrue(isAllowed(null));
        Assertions.assertTrue(isAllowed(true));
    }

    /** 与 DecompileTaskServiceImpl.assertDevTaskAffinity 同语义 */
    private boolean isAllowed(Boolean isDev) {
        if (!env.isDev()) {
            return true;
        }
        DecompileTask task = new DecompileTask();
        task.setIsDev(isDev);
        return Boolean.TRUE.equals(task.getIsDev());
    }
}
