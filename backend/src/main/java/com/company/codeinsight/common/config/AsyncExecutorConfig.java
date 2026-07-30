package com.company.codeinsight.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步线程池：知识发布、一键全量入队等后台作业。
 */
@Configuration
public class AsyncExecutorConfig {

    public static final String KNOWLEDGE_PUBLISH_EXECUTOR = "knowledgePublishExecutor";
    public static final String BATCH_INITIAL_EXECUTOR = "batchInitialExecutor";
    public static final String REPO_GIT_CHECK_EXECUTOR = "repoGitCheckExecutor";

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

    /** 一键全量：单线程排队，避免多批并发刷库 */
    @Bean(name = BATCH_INITIAL_EXECUTOR)
    public Executor batchInitialExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("batch-initial-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }

    /** 仓库 Git 连通性探测（全库轮询 / 按系统批量） */
    @Bean(name = REPO_GIT_CHECK_EXECUTOR)
    public Executor repoGitCheckExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("repo-git-check-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }
}
