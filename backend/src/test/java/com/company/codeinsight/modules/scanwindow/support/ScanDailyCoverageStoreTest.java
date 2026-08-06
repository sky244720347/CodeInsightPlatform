package com.company.codeinsight.modules.scanwindow.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScanDailyCoverageStoreTest {

    @Test
    void localFallbackMarkAndQuery() {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        ScanDailyCoverageStore store = new ScanDailyCoverageStore(provider);
        assertFalse(store.isDone(42L));
        store.markDone(42L);
        assertTrue(store.isDone(42L));
        assertFalse(store.isDone(43L));
        assertTrue(store.doneCount() >= 1);
    }
}
