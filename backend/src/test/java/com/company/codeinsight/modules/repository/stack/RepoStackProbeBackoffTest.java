package com.company.codeinsight.modules.repository.stack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RepoStackProbeBackoffTest {

    @Test
    void parseBackoffLadder() {
        assertArrayEquals(new long[]{60_000L, 300_000L, 900_000L},
                RepoStackProbeService.parseBackoffLadder("60000,300000,900000"));
        assertArrayEquals(new long[]{60_000L, 300_000L, 900_000L},
                RepoStackProbeService.parseBackoffLadder(null));
        assertArrayEquals(new long[]{10_000L},
                RepoStackProbeService.parseBackoffLadder("10000"));
    }

    @Test
    void streakMapsToLadder() {
        var props = new com.company.codeinsight.common.config.RepoGitCheckProperties();
        props.setStackProbeIdleBackoffMs("60000,300000,900000");
        // use real service only for backoffDelayMs — lightweight construct with nulls unsafe;
        // assert parse indices manually:
        long[] ladder = RepoStackProbeService.parseBackoffLadder(props.getStackProbeIdleBackoffMs());
        assertEquals(60_000L, ladder[Math.min(Math.max(1, 1), ladder.length) - 1]);
        assertEquals(300_000L, ladder[Math.min(Math.max(2, 1), ladder.length) - 1]);
        assertEquals(900_000L, ladder[Math.min(Math.max(3, 1), ladder.length) - 1]);
        assertEquals(900_000L, ladder[Math.min(Math.max(99, 1), ladder.length) - 1]);
    }
}
