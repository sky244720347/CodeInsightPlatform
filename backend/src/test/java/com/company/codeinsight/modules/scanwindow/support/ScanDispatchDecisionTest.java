package com.company.codeinsight.modules.scanwindow.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanDispatchDecisionTest {

    @Test
    void hasBaselineRequiresPublishedAndCommit() {
        assertFalse(ScanDispatchDecision.hasBaseline(null, "abc"));
        assertFalse(ScanDispatchDecision.hasBaseline(1L, null));
        assertFalse(ScanDispatchDecision.hasBaseline(1L, "  "));
        assertTrue(ScanDispatchDecision.hasBaseline(1L, "abc123"));
    }

    @Test
    void noBaselineAlwaysInitial() {
        assertEquals(ScanDispatchAction.INITIAL,
                ScanDispatchDecision.decide(false, "head1", null, false));
        assertEquals(ScanDispatchAction.INITIAL,
                ScanDispatchDecision.decide(false, "head1", "old", true));
    }

    @Test
    void unchangedSkipsWhenForceOff() {
        assertEquals(ScanDispatchAction.SKIP,
                ScanDispatchDecision.decide(true, "abc", "abc", false));
    }

    @Test
    void unchangedInitialWhenForceOn() {
        assertEquals(ScanDispatchAction.INITIAL,
                ScanDispatchDecision.decide(true, "abc", "abc", true));
    }

    @Test
    void changedIsIncremental() {
        assertEquals(ScanDispatchAction.INCREMENTAL,
                ScanDispatchDecision.decide(true, "newhead", "oldhead", false));
        assertEquals(ScanDispatchAction.INCREMENTAL,
                ScanDispatchDecision.decide(true, "newhead", "oldhead", true));
    }

    @Test
    void emptyHeadSkips() {
        assertEquals(ScanDispatchAction.SKIP,
                ScanDispatchDecision.decide(false, null, null, false));
        assertEquals(ScanDispatchAction.SKIP,
                ScanDispatchDecision.decide(true, "  ", "abc", true));
    }

    @Test
    void shortShaPrefixMatches() {
        assertTrue(ScanDispatchDecision.commitsEqual(
                "abcdef0123456789", "abcdef01"));
        assertEquals(ScanDispatchAction.SKIP,
                ScanDispatchDecision.decide(true, "abcdef0123456789", "abcdef01", false));
    }
}
