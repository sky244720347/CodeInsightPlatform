package com.company.codeinsight.modules.task.service;

import com.company.codeinsight.modules.quotacontrol.service.SystemConfigService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

/**
 * 重解析阶段并发闸门（AST / 入口发现 / 增量影响 / 层级构建）— <b>本机维度</b>。
 * <p>{@code parse.concurrency} 限制当前 JVM 同时进行重解析的任务数，用于控制本机堆峰值。</p>
 */
@Slf4j
@Service
public class ParseConcurrencyLimiter {

    @Autowired
    private SystemConfigService systemConfigService;

    private volatile Semaphore localSemaphore;

    /** 本节点当前持有解析许可的 taskId */
    private final ConcurrentMap<Long, Boolean> localHeld = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        rebuild(systemConfigService.getInt("parse.concurrency", 1));
    }

    public synchronized void rebuild(int permits) {
        int n = Math.max(1, permits);
        this.localSemaphore = new Semaphore(n, true);
        log.info("ParseConcurrencyLimiter 本机并发已重建: permits={}", n);
    }

    public boolean isHeld(Long taskId) {
        return taskId != null && localHeld.containsKey(taskId);
    }

    /**
     * 阻塞获取本机解析许可；可被中断。已持有则直接返回。
     */
    public void acquireBlocking(Long taskId) throws InterruptedException {
        if (taskId == null) {
            return;
        }
        if (localHeld.putIfAbsent(taskId, Boolean.TRUE) != null) {
            return;
        }
        try {
            ensureLocal();
            localSemaphore.acquire();
            log.info("ParseConcurrencyLimiter acquired(local) taskId={}", taskId);
        } catch (InterruptedException e) {
            localHeld.remove(taskId);
            throw e;
        } catch (RuntimeException e) {
            localHeld.remove(taskId);
            throw e;
        }
    }

    public void release(Long taskId) {
        if (taskId == null) {
            return;
        }
        if (localHeld.remove(taskId) == null) {
            return;
        }
        if (localSemaphore != null) {
            localSemaphore.release();
        }
        log.info("ParseConcurrencyLimiter released(local) taskId={}", taskId);
    }

    public int availablePermits() {
        return localSemaphore == null ? 0 : localSemaphore.availablePermits();
    }

    public Set<Long> localHeldTaskIds() {
        return Set.copyOf(localHeld.keySet());
    }

    @PreDestroy
    public void releaseAllLocalHeld() {
        for (Long taskId : Set.copyOf(localHeld.keySet())) {
            release(taskId);
        }
    }

    private void ensureLocal() {
        if (localSemaphore == null) {
            rebuild(systemConfigService.getInt("parse.concurrency", 1));
        }
    }
}
