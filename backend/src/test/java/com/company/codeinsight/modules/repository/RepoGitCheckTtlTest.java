package com.company.codeinsight.modules.repository;

import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.service.RepoGitConnectivityService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

class RepoGitCheckTtlTest {

    @Test
    void uncheckedAlwaysDue() {
        CodeRepository r = new CodeRepository();
        Assertions.assertTrue(RepoGitConnectivityService.isDueForScheduledCheck(
                r, LocalDateTime.now(), 21_600_000L, 1_800_000L));
    }

    @Test
    void reachableFreshNotDue() {
        CodeRepository r = new CodeRepository();
        r.setGitReachable(RepoGitConnectivityService.REACHABLE);
        r.setGitCheckedAt(LocalDateTime.now().minusHours(1));
        Assertions.assertFalse(RepoGitConnectivityService.isDueForScheduledCheck(
                r, LocalDateTime.now(), 21_600_000L, 1_800_000L));
    }

    @Test
    void reachableStaleDue() {
        CodeRepository r = new CodeRepository();
        r.setGitReachable(RepoGitConnectivityService.REACHABLE);
        r.setGitCheckedAt(LocalDateTime.now().minusHours(7));
        Assertions.assertTrue(RepoGitConnectivityService.isDueForScheduledCheck(
                r, LocalDateTime.now(), 21_600_000L, 1_800_000L));
    }

    @Test
    void unreachableFreshNotDue() {
        CodeRepository r = new CodeRepository();
        r.setGitReachable(RepoGitConnectivityService.UNREACHABLE);
        r.setGitCheckedAt(LocalDateTime.now().minusMinutes(10));
        Assertions.assertFalse(RepoGitConnectivityService.isDueForScheduledCheck(
                r, LocalDateTime.now(), 21_600_000L, 1_800_000L));
    }

    @Test
    void unreachableStaleDue() {
        CodeRepository r = new CodeRepository();
        r.setGitReachable(RepoGitConnectivityService.UNREACHABLE);
        r.setGitCheckedAt(LocalDateTime.now().minusMinutes(40));
        Assertions.assertTrue(RepoGitConnectivityService.isDueForScheduledCheck(
                r, LocalDateTime.now(), 21_600_000L, 1_800_000L));
    }
}
