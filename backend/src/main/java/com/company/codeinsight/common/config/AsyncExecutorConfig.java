package com.company.codeinsight.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 知识确认后异步建版 / NAS 入队专用线程池。
 */
@Configuration
public class AsyncExecutorConfig {

    public static final String KNOWLEDGE_PUBLISH_EXECUTOR = "knowledgePublishExecutor";

    @Bean(name = KNOWLEDGE_PUBLISH_EXECUTOR)
    public Executor knowledgePublishExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("knowledge-publish-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
