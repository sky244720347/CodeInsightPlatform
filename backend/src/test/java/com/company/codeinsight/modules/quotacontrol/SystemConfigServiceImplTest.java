package com.company.codeinsight.modules.quotacontrol;

import com.company.codeinsight.modules.quotacontrol.entity.SystemConfig;
import com.company.codeinsight.modules.quotacontrol.service.impl.SystemConfigServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.Serializable;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemConfigServiceImplTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private AtomicReference<SystemConfig> dbRow;
    private TestableSystemConfigService service;

    @BeforeEach
    void setUp() {
        dbRow = new AtomicReference<>();
        service = new TestableSystemConfigService(dbRow);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void getString_redisHit_skipsDb() {
        when(valueOperations.get("ci:config:kv:token.limit-enabled")).thenReturn("true");

        Assertions.assertEquals("true", service.getString("token.limit-enabled"));
        Assertions.assertEquals(0, service.dbReads.get());
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void getString_redisMiss_loadsPgAndFillsCache() {
        when(valueOperations.get("ci:config:kv:ai.concurrency")).thenReturn(null);
        SystemConfig row = new SystemConfig();
        row.setKey("ai.concurrency");
        row.setValue("8");
        dbRow.set(row);

        Assertions.assertEquals("8", service.getString("ai.concurrency"));
        Assertions.assertEquals(1, service.dbReads.get());
        verify(valueOperations).set(eq("ci:config:kv:ai.concurrency"), eq("8"), eq(Duration.ofHours(1)));
    }

    @Test
    void getString_redisError_degradesToPg() {
        when(valueOperations.get("ci:config:kv:task.concurrency"))
                .thenThrow(new RuntimeException("redis down"));
        SystemConfig row = new SystemConfig();
        row.setKey("task.concurrency");
        row.setValue("3");
        dbRow.set(row);

        Assertions.assertEquals("3", service.getString("task.concurrency"));
        verify(valueOperations).set(eq("ci:config:kv:task.concurrency"), eq("3"), eq(Duration.ofHours(1)));
    }

    @Test
    void putString_updatesPgAndEvictsRedis() {
        SystemConfig existing = new SystemConfig();
        existing.setKey("ai.concurrency");
        existing.setValue("4");
        dbRow.set(existing);

        service.putString("ai.concurrency", "10", "AI 并发", "tester");

        Assertions.assertEquals("10", dbRow.get().getValue());
        Assertions.assertTrue(service.updated.get());
        verify(stringRedisTemplate).delete("ci:config:kv:ai.concurrency");
    }

    @Test
    void putString_evictFailure_doesNotRollbackPg() {
        SystemConfig existing = new SystemConfig();
        existing.setKey("ai.concurrency");
        existing.setValue("4");
        dbRow.set(existing);
        doThrow(new RuntimeException("del failed")).when(stringRedisTemplate).delete(anyString());

        service.putString("ai.concurrency", "12", null, "tester");

        Assertions.assertEquals("12", dbRow.get().getValue());
    }

    @Test
    void getInt_parsesCachedValue() {
        when(valueOperations.get("ci:config:kv:ai.concurrency")).thenReturn("6");
        Assertions.assertEquals(6, service.getInt("ai.concurrency", 4));
    }

    @Test
    void getString_emptyStringIsValidCacheHit() {
        when(valueOperations.get("ci:config:kv:custom")).thenReturn("");
        Assertions.assertEquals("", service.getString("custom"));
        Assertions.assertEquals(0, service.dbReads.get());
    }

    /** 可注入 DB 行的薄实现，绕过 MyBatis ServiceImpl。 */
    private static class TestableSystemConfigService extends SystemConfigServiceImpl {
        private final AtomicReference<SystemConfig> dbRow;
        private final AtomicBoolean updated = new AtomicBoolean(false);
        private final AtomicInteger dbReads = new AtomicInteger(0);

        private TestableSystemConfigService(AtomicReference<SystemConfig> dbRow) {
            this.dbRow = dbRow;
        }

        @Override
        public SystemConfig getById(Serializable id) {
            dbReads.incrementAndGet();
            SystemConfig row = dbRow.get();
            if (row == null || row.getKey() == null || !row.getKey().equals(String.valueOf(id))) {
                return null;
            }
            return row;
        }

        @Override
        public boolean save(SystemConfig entity) {
            dbRow.set(entity);
            return true;
        }

        @Override
        public boolean updateById(SystemConfig entity) {
            dbRow.set(entity);
            updated.set(true);
            return true;
        }
    }
}
