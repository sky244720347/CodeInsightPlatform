package com.company.codeinsight.common.net;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalAddressSetTest {

    private LocalAddressSet localAddressSet;

    @BeforeEach
    void setUp() {
        localAddressSet = new LocalAddressSet();
        localAddressSet.init();
    }

    @Test
    void matchesLocal_loopback() {
        Assertions.assertTrue(localAddressSet.matchesLocal("127.0.0.1"));
        Assertions.assertTrue(localAddressSet.matchesLocal("::1"));
    }

    @Test
    void matchesLocal_rejectsBlankAndForeign() {
        Assertions.assertFalse(localAddressSet.matchesLocal(null));
        Assertions.assertFalse(localAddressSet.matchesLocal(""));
        Assertions.assertFalse(localAddressSet.matchesLocal("1.2.3.4"));
    }

    @Test
    void preferredMachineIp_nonBlank() {
        Assertions.assertNotNull(localAddressSet.preferredMachineIp());
        Assertions.assertFalse(localAddressSet.preferredMachineIp().isBlank());
    }

    @Test
    void normalize_stripsZoneId() {
        Assertions.assertEquals(
                LocalAddressSet.normalize("fe80::1"),
                LocalAddressSet.normalize("fe80::1%eth0"));
    }
}
