package com.company.codeinsight.common.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiRetryPropertiesTest {

    @Test
    void resolveBackoffWaitMs_capsWhenUnlimited() {
        AiRetryProperties p = new AiRetryProperties();
        p.setUnlimitedAttempts(true);
        p.setBackoffMs(1000L);
        p.setUnlimitedBackoffCapMs(5000L);

        Assertions.assertEquals(3000L, p.resolveBackoffWaitMs(3, false));
        Assertions.assertEquals(5000L, p.resolveBackoffWaitMs(10, false));
    }

    @Test
    void resolveBackoffWaitMs_noCapWhenLimited() {
        AiRetryProperties p = new AiRetryProperties();
        p.setUnlimitedAttempts(false);
        p.setBackoffMs(1000L);
        p.setUnlimitedBackoffCapMs(5000L);

        Assertions.assertEquals(10000L, p.resolveBackoffWaitMs(10, false));
    }
}
