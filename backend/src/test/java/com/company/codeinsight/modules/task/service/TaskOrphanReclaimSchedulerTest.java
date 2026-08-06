package com.company.codeinsight.modules.task.service;

import com.company.codeinsight.common.cluster.ClusterInstanceId;
import com.company.codeinsight.common.cluster.ClusterProperties;
import com.company.codeinsight.common.cluster.InstanceHeartbeat;
import com.company.codeinsight.common.config.CodeInsightEnvProperties;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.draft.service.DraftService;
import com.company.codeinsight.modules.log.service.OperationLogService;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskOrphanReclaimSchedulerTest {

    @Mock
    private DecompileTaskMapper taskMapper;
    @Mock
    private DecompileTaskService decompileTaskService;
    @Mock
    private TaskQueueClaimService claimService;
    @Mock
    private TaskConcurrencyLimiter taskConcurrencyLimiter;
    @Mock
    private ClusterProperties clusterProperties;
    @Mock
    private ClusterInstanceId clusterInstanceId;
    @Mock
    private InstanceHeartbeat instanceHeartbeat;
    @Mock
    private OperationLogService operationLogService;
    @Mock
    private KnowledgeDraftMapper knowledgeDraftMapper;
    @Mock
    private DraftService draftService;
    @Mock
    private CodeInsightEnvProperties envProperties;

    private TaskOrphanReclaimScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new TaskOrphanReclaimScheduler(
                taskMapper,
                decompileTaskService,
                claimService,
                taskConcurrencyLimiter,
                clusterProperties,
                clusterInstanceId,
                instanceHeartbeat,
                operationLogService,
                knowledgeDraftMapper,
                draftService,
                envProperties);
        lenient().when(clusterProperties.resolveTaskLeaseGraceMinutes()).thenReturn(10);
        lenient().when(envProperties.isDev()).thenReturn(false);
    }

    @Test
    void cluster_heartbeatDeadButLeaseValid_isNotOrphan() {
        when(clusterProperties.isEnabled()).thenReturn(true);

        DecompileTask task = new DecompileTask();
        task.setClaimedBy("dead-node:1:abcdef12");
        task.setLeaseUntil(LocalDateTime.now().plusMinutes(8));

        assertFalse(scheduler.isOrphan(task));
    }

    @Test
    void cluster_leaseJustExpired_withinGrace_isNotOrphan() {
        when(clusterProperties.isEnabled()).thenReturn(true);

        DecompileTask task = new DecompileTask();
        task.setClaimedBy("dead-node:1:abcdef12");
        task.setLeaseUntil(LocalDateTime.now().minusMinutes(1));

        assertFalse(scheduler.isOrphan(task));
    }

    @Test
    void cluster_pastGrace_butHeartbeatAlive_isNotOrphan() {
        when(clusterProperties.isEnabled()).thenReturn(true);
        when(instanceHeartbeat.isAlive("live-node:1:abcdef12")).thenReturn(true);

        DecompileTask task = new DecompileTask();
        task.setClaimedBy("live-node:1:abcdef12");
        task.setLeaseUntil(LocalDateTime.now().minusMinutes(15));

        assertFalse(scheduler.isOrphan(task));
    }

    @Test
    void cluster_pastGrace_andHeartbeatDead_isOrphan() {
        when(clusterProperties.isEnabled()).thenReturn(true);
        when(instanceHeartbeat.isAlive("dead-node:1:abcdef12")).thenReturn(false);

        DecompileTask task = new DecompileTask();
        task.setClaimedBy("dead-node:1:abcdef12");
        task.setLeaseUntil(LocalDateTime.now().minusMinutes(15));

        assertTrue(scheduler.isOrphan(task));
        assertEquals("LEASE_GRACE_EXPIRED+HEARTBEAT_DEAD", scheduler.describeOrphanReason(task));
    }

    @Test
    void cluster_noClaim_isOrphan() {
        DecompileTask task = new DecompileTask();
        assertTrue(scheduler.isOrphan(task));
        assertEquals("NO_CLAIM", scheduler.describeOrphanReason(task));
    }

    @Test
    void singleNode_claimedByOtherProcess_isOrphan() {
        when(clusterProperties.isEnabled()).thenReturn(false);
        when(clusterInstanceId.get()).thenReturn("self:1:aaaaaaaa");

        DecompileTask task = new DecompileTask();
        task.setClaimedBy("old-process:1:bbbbbbbb");
        task.setLeaseUntil(LocalDateTime.now().plusMinutes(20));

        assertTrue(scheduler.isOrphan(task));
        assertEquals("CLAIMED_BY_OTHER_PROCESS", scheduler.describeOrphanReason(task));
    }

    @Test
    void dev_reclaimOnce_skipsScan() {
        when(envProperties.isDev()).thenReturn(true);

        assertEquals(0, scheduler.reclaimOnce("test"));
        verify(taskMapper, never()).selectList(org.mockito.ArgumentMatchers.any());
    }
}
