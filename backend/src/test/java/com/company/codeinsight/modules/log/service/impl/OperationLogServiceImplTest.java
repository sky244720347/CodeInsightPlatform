package com.company.codeinsight.modules.log.service.impl;

import com.company.codeinsight.common.auth.ClientIpContext;
import com.company.codeinsight.common.auth.OperatorContext;
import com.company.codeinsight.common.net.LocalAddressSet;
import com.company.codeinsight.modules.log.entity.OperationLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

/**
 * 操作日志须写入可辨识机器的 IP / OperatorContext，禁止再写死 127.0.0.1 + Owner。
 */
class OperationLogServiceImplTest {

    @BeforeEach
    void setUp() {
        new LocalAddressSet().init();
    }

    @AfterEach
    void tearDown() {
        ClientIpContext.clear();
        OperatorContext.clear();
    }

    @Test
    void logOperation_usesClientIpAndOperatorContext() {
        OperationLogServiceImpl svc = Mockito.spy(new OperationLogServiceImpl());
        doReturn(true).when(svc).save(any(OperationLog.class));

        OperatorContext.set("alice", 42L, "USER");
        ClientIpContext.set("203.0.113.55");

        svc.logOperation(1L, 9L, "TASK_TRANSIT", "detail", null, true);

        ArgumentCaptor<OperationLog> cap = ArgumentCaptor.forClass(OperationLog.class);
        verify(svc).save(cap.capture());
        OperationLog saved = cap.getValue();
        Assertions.assertEquals("203.0.113.55", saved.getIpAddress());
        Assertions.assertEquals("alice", saved.getUsername());
        Assertions.assertEquals(42L, saved.getUserId());
        Assertions.assertNotEquals("Owner", saved.getUsername());
    }

    @Test
    void logOperation_schedulerThread_usesMachineIp() {
        OperationLogServiceImpl svc = Mockito.spy(new OperationLogServiceImpl());
        doReturn(true).when(svc).save(any(OperationLog.class));

        ClientIpContext.clear();
        OperatorContext.clear();

        svc.logOperation(null, 3L, "TASK_ORPHAN_RECLAIM", "orphan", null, true);

        ArgumentCaptor<OperationLog> cap = ArgumentCaptor.forClass(OperationLog.class);
        verify(svc).save(cap.capture());
        Assertions.assertEquals(LocalAddressSet.preferredMachineIpStatic(), cap.getValue().getIpAddress());
        Assertions.assertEquals(OperatorContext.DEFAULT_OPERATOR, cap.getValue().getUsername());
    }
}
