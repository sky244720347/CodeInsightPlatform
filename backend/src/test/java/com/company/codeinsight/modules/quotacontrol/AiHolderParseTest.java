package com.company.codeinsight.modules.quotacontrol;

import com.company.codeinsight.modules.quotacontrol.service.AiConcurrencyService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AiHolderParseTest {

    @Test
    void parse_currentFormat_withClusterInstanceId() {
        var p = AiConcurrencyService.parseAiHolder("ai:host:1234:abcd1234:550e8400-e29b-41d4-a716-446655440000");
        assertNotNull(p);
        assertEquals("host:1234:abcd1234", p.instanceId());
        assertEquals("550e8400-e29b-41d4-a716-446655440000", p.token());
    }

    @Test
    void parse_legacyUuid_returnsNull() {
        assertNull(AiConcurrencyService.parseAiHolder("ai:dead-beef-uuid-only"));
        assertNull(AiConcurrencyService.parseAiHolder("ai:only"));
    }

    @Test
    void parse_invalid() {
        assertNull(AiConcurrencyService.parseAiHolder(null));
        assertNull(AiConcurrencyService.parseAiHolder("task:1"));
        assertNull(AiConcurrencyService.parseAiHolder(""));
    }
}
