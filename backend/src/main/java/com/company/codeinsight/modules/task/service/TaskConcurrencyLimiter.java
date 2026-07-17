package com.company.codeinsight.modules.task.service;

import com.company.codeinsight.common.cluster.ClusterProperties;
import com.company.codeinsight.common.cluster.RedisDistributedPermits;
import com.company.codeinsight.modules.quotacontrol.service.SystemConfigService;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.mapper.SystemApplicationMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

/**
 * 知识构建任务并发闸门。
 * <ul>
 *   <li>集群模式（非 {@code code-insight.env=dev}）：Redis Set 分布式计数</li>
 *   <li>单机模式：JVM {@link Semaphore}（兼容本地开发）</li>
 * </ul>
 */
@Slf4j
@Service
public class TaskConcurrencyLimiter {

    private static final String POOL_GLOBAL = "task:global";

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private SystemApplicationMapper systemMapper;

    @Autowired
    private ClusterProperties clusterProperties;

    @Autowired(required = false)
    private RedisDistributedPermits redisPermits;

    private volatile Semaphore globalLocal;
    private final ConcurrentMap<Long, Semaphore> perSystemLocal = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        rebuildGlobal(systemConfigService.getInt("task.concurrency", 2));
    }

    public synchronized void rebuildGlobal(int permits) {
        int n = Math.max(1, permits);
        this.globalLocal = new Semaphore(n, true);
        log.info("TaskConcurrencyLimiter 全局并发已重建: permits={}, cluster={}", n, clusterProperties.isEnabled());
    }

    /**
     * @param taskId 非空时作为 Redis holder（task:{id}）
     */
    public boolean tryAcquire(Long systemId, Long taskId) {
        if (clusterProperties.isEnabled()) {
            return tryAcquireCluster(systemId, taskId);
        }
        return tryAcquireLocal(systemId);
    }

    /** 兼容旧调用（单机路径无 taskId） */
    public boolean tryAcquire(Long systemId) {
        return tryAcquire(systemId, null);
    }

    public void release(Long systemId, Long taskId) {
        if (clusterProperties.isEnabled() && taskId != null && redisPermits != null) {
            redisPermits.release(POOL_GLOBAL, holderToken(taskId));
            if (systemId != null) {
                redisPermits.release(systemPool(systemId), holderToken(taskId));
            }
            return;
        }
        releaseLocal(systemId);
    }

    public void release(Long systemId) {
        release(systemId, null);
    }

    public int globalAvailablePermits() {
        if (clusterProperties.isEnabled() && redisPermits != null) {
            int max = systemConfigService.getInt("task.concurrency", 2);
            long used = redisPermits.count(POOL_GLOBAL);
            return Math.max(0, max - (int) used);
        }
        return globalLocal == null ? 0 : globalLocal.availablePermits();
    }

    private boolean tryAcquireCluster(Long systemId, Long taskId) {
        if (redisPermits == null || taskId == null) {
            log.warn("集群模式需要 RedisDistributedPermits 与 taskId");
            return false;
        }
        int globalMax = systemConfigService.getInt("task.concurrency", 2);
        String holder = holderToken(taskId);
        if (!redisPermits.tryAcquire(POOL_GLOBAL, holder, globalMax)) {
            return false;
        }
        int sysMax = resolveSystemMax(systemId);
        if (!redisPermits.tryAcquire(systemPool(systemId), holder, sysMax)) {
            redisPermits.release(POOL_GLOBAL, holder);
            return false;
        }
        return true;
    }

    private boolean tryAcquireLocal(Long systemId) {
        if (globalLocal == null) {
            rebuildGlobal(systemConfigService.getInt("task.concurrency", 2));
        }
        Semaphore sys = getOrCreateSystemSemaphoreLocal(systemId);
        boolean gotGlobal = globalLocal.tryAcquire();
        if (!gotGlobal) {
            return false;
        }
        boolean gotSys = sys.tryAcquire();
        if (!gotSys) {
            globalLocal.release();
            return false;
        }
        return true;
    }

    private void releaseLocal(Long systemId) {
        if (systemId != null) {
            Semaphore sys = perSystemLocal.get(systemId);
            if (sys != null) {
                try {
                    sys.release();
                } catch (Exception ignored) {
                }
            }
        }
        if (globalLocal != null) {
            try {
                globalLocal.release();
            } catch (Exception ignored) {
            }
        }
    }

    private Semaphore getOrCreateSystemSemaphoreLocal(Long systemId) {
        if (systemId == null) {
            return perSystemLocal.computeIfAbsent(-1L, k -> new Semaphore(1, true));
        }
        return perSystemLocal.computeIfAbsent(systemId, k -> {
            int permits = resolveSystemMax(systemId);
            log.info("TaskConcurrencyLimiter 系统 {} 初始化并发(local): permits={}", systemId, permits);
            return new Semaphore(permits, true);
        });
    }

    private int resolveSystemMax(Long systemId) {
        int permits = 1;
        if (systemId != null) {
            try {
                SystemApplication sys = systemMapper.selectById(systemId);
                if (sys != null && sys.getMaxConcurrentTasks() != null && sys.getMaxConcurrentTasks() > 0) {
                    permits = sys.getMaxConcurrentTasks();
                }
            } catch (Exception e) {
                log.warn("读取系统 {} max_concurrent_tasks 失败", systemId, e);
            }
        }
        return permits;
    }

    private static String systemPool(Long systemId) {
        return "task:sys:" + (systemId == null ? "unknown" : systemId);
    }

    private static String holderToken(Long taskId) {
        return "task:" + taskId;
    }
}
