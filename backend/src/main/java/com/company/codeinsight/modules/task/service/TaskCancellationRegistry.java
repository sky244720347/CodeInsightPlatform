package com.company.codeinsight.modules.task.service;

import com.company.codeinsight.common.exception.TaskCancelledException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * 任务协作取消注册表：terminate 置位并取消在飞 HTTP / 文档 worker / 流水线 Future。
 * <p>正常路径仅做内存读；重试 / 新一轮流水线须 {@link #clear(Long)}。</p>
 */
@Slf4j
@Component
public class TaskCancellationRegistry {

    private final ConcurrentHashMap<Long, Boolean> cancelled = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<CompletableFuture<?>>> httpFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<Future<?>>> workFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Future<?>> pipelineFutures = new ConcurrentHashMap<>();

    /** terminate 入口：置取消位并 cancel 已登记 Future。 */
    public void requestCancel(Long taskId) {
        if (taskId == null) {
            return;
        }
        cancelled.put(taskId, Boolean.TRUE);
        int http = cancelAll(httpFutures.remove(taskId));
        int work = cancelAll(workFutures.remove(taskId));
        Future<?> pipeline = pipelineFutures.remove(taskId);
        boolean pipelineCancelled = false;
        if (pipeline != null && !pipeline.isDone()) {
            pipelineCancelled = pipeline.cancel(true);
        }
        log.info("TaskCancellationRegistry.requestCancel taskId={} httpCancelled≈{} workCancelled≈{} pipelineCancel={}",
                taskId, http, work, pipelineCancelled);
    }

    public boolean isCancelled(Long taskId) {
        return taskId != null && Boolean.TRUE.equals(cancelled.get(taskId));
    }

    public void throwIfCancelled(Long taskId) {
        if (isCancelled(taskId)) {
            throw new TaskCancelledException(taskId);
        }
    }

    /** 新一轮跑 / 重试前清除取消位与残留 Future 登记。 */
    public void clear(Long taskId) {
        if (taskId == null) {
            return;
        }
        cancelled.remove(taskId);
        httpFutures.remove(taskId);
        workFutures.remove(taskId);
        pipelineFutures.remove(taskId);
    }

    public void registerHttp(Long taskId, CompletableFuture<?> future) {
        if (taskId == null || future == null) {
            return;
        }
        if (isCancelled(taskId)) {
            future.cancel(true);
            throw new TaskCancelledException(taskId);
        }
        httpFutures.computeIfAbsent(taskId, id -> ConcurrentHashMap.newKeySet()).add(future);
        future.whenComplete((r, ex) -> unregisterHttp(taskId, future));
    }

    public void unregisterHttp(Long taskId, CompletableFuture<?> future) {
        if (taskId == null || future == null) {
            return;
        }
        Set<CompletableFuture<?>> set = httpFutures.get(taskId);
        if (set != null) {
            set.remove(future);
            if (set.isEmpty()) {
                httpFutures.remove(taskId, set);
            }
        }
    }

    public void registerWork(Long taskId, Future<?> future) {
        if (taskId == null || future == null) {
            return;
        }
        if (isCancelled(taskId)) {
            future.cancel(true);
            return;
        }
        workFutures.computeIfAbsent(taskId, id -> ConcurrentHashMap.newKeySet()).add(future);
    }

    public void unregisterWork(Long taskId, Future<?> future) {
        if (taskId == null || future == null) {
            return;
        }
        Set<Future<?>> set = workFutures.get(taskId);
        if (set != null) {
            set.remove(future);
            if (set.isEmpty()) {
                workFutures.remove(taskId, set);
            }
        }
    }

    public void registerPipeline(Long taskId, Future<?> future) {
        if (taskId == null || future == null) {
            return;
        }
        pipelineFutures.put(taskId, future);
    }

    public void unregisterPipeline(Long taskId) {
        if (taskId != null) {
            pipelineFutures.remove(taskId);
        }
    }

    /** 是否仍有未完成的流水线 / HTTP / 文档 worker（与 taskCache 解耦）。 */
    public boolean hasActiveWork(Long taskId) {
        if (taskId == null) {
            return false;
        }
        Future<?> pipeline = pipelineFutures.get(taskId);
        if (pipeline != null && !pipeline.isDone()) {
            return true;
        }
        Set<CompletableFuture<?>> https = httpFutures.get(taskId);
        if (https != null) {
            for (CompletableFuture<?> f : https) {
                if (f != null && !f.isDone()) {
                    return true;
                }
            }
        }
        Set<Future<?>> works = workFutures.get(taskId);
        if (works != null) {
            for (Future<?> f : works) {
                if (f != null && !f.isDone()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 测试 / 运维：当前取消位与登记规模。 */
    public Map<String, Integer> debugSizes(Long taskId) {
        int http = sizeOf(httpFutures.get(taskId));
        int work = sizeOf(workFutures.get(taskId));
        int pipeline = pipelineFutures.containsKey(taskId) ? 1 : 0;
        return Map.of(
                "cancelled", isCancelled(taskId) ? 1 : 0,
                "http", http,
                "work", work,
                "pipeline", pipeline);
    }

    private static int cancelAll(Set<? extends Future<?>> set) {
        if (set == null || set.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Future<?> f : set) {
            if (f != null && !f.isDone() && f.cancel(true)) {
                n++;
            }
        }
        return n;
    }

    private static int sizeOf(Set<?> set) {
        return set == null ? 0 : set.size();
    }
}
