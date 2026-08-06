package com.company.codeinsight.modules.scanwindow.service;

import com.company.codeinsight.modules.scanwindow.service.impl.ScanProbeRecordServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanProbeTargetTest {

    @Test
    void remoteUrlsAreTargets() {
        assertTrue(ScanProbeRecordServiceImpl.isProbeTarget("https://git.example.com/a.git"));
        assertTrue(ScanProbeRecordServiceImpl.isProbeTarget("git@git.example.com:a.git"));
    }

    @Test
    void emptyNotTarget() {
        assertFalse(ScanProbeRecordServiceImpl.isProbeTarget(null));
        assertFalse(ScanProbeRecordServiceImpl.isProbeTarget("  "));
    }
}
