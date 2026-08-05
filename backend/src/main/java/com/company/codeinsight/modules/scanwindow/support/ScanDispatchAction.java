package com.company.codeinsight.modules.scanwindow.support;

/** 定时 commit 轮询对单仓的下发动作。 */
public enum ScanDispatchAction {
    SKIP,
    INITIAL,
    INCREMENTAL
}
