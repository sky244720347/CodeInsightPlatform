package com.company.codeinsight.modules.scanwindow.scheduler;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanDispatchDeferMessageTest {

    @Test
    void techStackNotConfiguredMessage() {
        String msg = ScanWindowScheduler.formatDispatchDeferMessage(
                new BusinessException(ErrorCode.TECH_STACK_NOT_CONFIGURED));
        assertTrue(msg.contains("技术栈未配置"));
    }

    @Test
    void techStackUnsupportedMessage() {
        String msg = ScanWindowScheduler.formatDispatchDeferMessage(
                new BusinessException(ErrorCode.TECH_STACK_UNSUPPORTED, "技术栈「Go」不在可执行配置中"));
        assertTrue(msg.contains("暂不支持"));
    }
}
