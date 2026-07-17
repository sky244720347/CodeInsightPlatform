package com.company.codeinsight.modules.prompt;

import com.company.codeinsight.modules.prompt.service.impl.DecompilePromptServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时为 schema 2 条 DEFAULT 提示词补写 NAS 正文（classpath 兜底）。
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class PromptDefaultsBootstrap implements ApplicationRunner {

    private final DecompilePromptServiceImpl promptService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            promptService.bootstrapSchemaDefaultContents();
        } catch (Exception e) {
            log.warn("PromptDefaultsBootstrap failed: {}", e.getMessage());
        }
    }
}
