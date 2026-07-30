package com.company.codeinsight.modules.task.service;

import com.company.codeinsight.common.cluster.ClusterInstanceId;
import com.company.codeinsight.common.cluster.ClusterProperties;
import com.company.codeinsight.common.cluster.InstanceHeartbeat;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
                draftService);
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
    void cluster_leaseExpired_isOrphan() {
        DecompileTask task = new DecompileTask();
        task.setClaimedBy("dead-node:1:abcdef12");
        task.setLeaseUntil(LocalDateTime.now().minusMinutes(1));

        assertTrue(scheduler.isOrphan(task));
    }

    @Test
    void cluster_noClaim_isOrphan() {
        DecompileTask task = new DecompileTask();
        assertTrue(scheduler.isOrphan(task));
    }
}
