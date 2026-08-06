package com.company.codeinsight.common.auth;

import com.company.codeinsight.common.net.LocalAddressSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

    private LocalAddressSet localAddressSet;

    @BeforeEach
    void setUp() {
        localAddressSet = new LocalAddressSet();
        localAddressSet.init();
        ClientIpContext.clear();
    }

    @AfterEach
    void tearDown() {
        ClientIpContext.clear();
    }

    @Test
    void resolve_prefersFirstXForwardedForHop() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.1");
        req.setRemoteAddr("127.0.0.1");
        Assertions.assertEquals("203.0.113.10", ClientIpResolver.resolve(req));
    }

    @Test
    void resolveEffective_fallsBackToMachineIpOnLoopback() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        String effective = ClientIpResolver.resolveEffective(req, localAddressSet.preferredMachineIp());
        Assertions.assertEquals(localAddressSet.preferredMachineIp(), effective);
    }

    @Test
    void resolveEffective_keepsRemoteNonLoopback() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("203.0.113.55");
        Assertions.assertEquals("203.0.113.55",
                ClientIpResolver.resolveEffective(req, "10.0.0.8"));
    }

    @Test
    void clientIpContext_defaultsToMachineIpWhenUnset() {
        ClientIpContext.clear();
        Assertions.assertEquals(LocalAddressSet.preferredMachineIpStatic(), ClientIpContext.get());
        Assertions.assertFalse(ClientIpContext.isPresent());

        ClientIpContext.set("10.1.2.3");
        Assertions.assertEquals("10.1.2.3", ClientIpContext.get());
        Assertions.assertTrue(ClientIpContext.isPresent());
        ClientIpContext.clear();
    }

}
