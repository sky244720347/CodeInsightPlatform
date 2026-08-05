package com.company.codeinsight.modules.repository;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.exception.ErrorCode;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.service.RepoGitConnectivityService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RepoGitConnectivityGateTest {

    @Test
    void assertReachableRejectsNullAndZero() {
        RepoGitConnectivityService svc = new RepoGitConnectivityService(
                mock(com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper.class),
                new com.company.codeinsight.common.config.RepoGitCheckProperties(),
                Runnable::run,
                mock(com.company.codeinsight.modules.repository.stack.RepoStackProbeService.class));

        CodeRepository unchecked = new CodeRepository();
        BusinessException e1 = assertThrows(BusinessException.class, () -> svc.assertReachableForTask(unchecked));
        assertEquals(ErrorCode.GIT_UNREACHABLE.getCode(), e1.getCode());

        CodeRepository bad = new CodeRepository();
        bad.setGitReachable(0);
        bad.setGitCheckMsg("timeout");
        BusinessException e2 = assertThrows(BusinessException.class, () -> svc.assertReachableForTask(bad));
        assertEquals(ErrorCode.GIT_UNREACHABLE.getCode(), e2.getCode());
        assertTrue(e2.getMessage().contains("timeout") || e2.getMessage().contains("连通失败"));

        CodeRepository ok = new CodeRepository();
        ok.setGitReachable(1);
        svc.assertReachableForTask(ok);
    }
}
