package com.company.codeinsight.modules.task.service;

import com.company.codeinsight.common.cluster.ClusterProperties;
import com.company.codeinsight.common.cluster.InstanceHeartbeat;
import com.company.codeinsight.common.cluster.RedisDistributedPermits;
import com.company.codeinsight.modules.quotacontrol.service.SystemConfigService;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.mapper.SystemApplicationMapper;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.enums.TaskStatus;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

/**
 * 知识构建任务并发闸门。
 * <ul>
 *   <li>集群模式（非 {@code code-insight.env=dev}）：Redis Set 分布式计数 + 启动/周期 DB 对账</li>
 *   <li>单机模式：JVM {@link Semaphore}（兼容开发环境）</li>
 * </ul>
 */
@Slf4j
@Service
public class TaskConcurrencyLimiter {

    private static final String POOL_GLOBAL = "task:global";
    private static final String POOL_SYS_PREFIX = "task:sys:";

    /** 流水线进行中应继续占用 Redis 许可的状态（与 finally release 时机对齐） */
    private static final Set<String> PERMIT_HOLDING_STATUSES = Set.of(
            TaskStatus.PULLING_CODE.name(),
            TaskStatus.PARSING_CODE.name(),
            TaskStatus.SPLITTING_TASK.name(),
            TaskStatus.AI_ANALYZING.name(),
            TaskStatus.MODULE_HIERARCHY.name(),
            TaskStatus.BASELINE_DOC_INHERIT.name(),
            TaskStatus.GENERATING_DOC.name(),
            TaskStatus.PUSHING.name()
    );

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private SystemApplicationMapper systemMapper;

    @Autowired
    private ClusterProperties clusterProperties;

    @Autowired(required = false)
    private RedisDistributedPermits redisPermits;

    @Autowired(required = false)
    private InstanceHeartbeat instanceHeartbeat;

    @Autowired
    private DecompileTaskMapper taskMapper;

    private volatile Semaphore globalLocal;
    private final ConcurrentMap<Long, Semaphore> perSystemLocal = new ConcurrentHashMap<>();

    /** 本节点当前持有的 taskId → systemId（集群续租 / 优雅停机） */
    private final ConcurrentMap<Long, Long> localHeldTasks = new ConcurrentHashMap<>();

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
        if (taskId != null) {
            localHeldTasks.remove(taskId);
        }
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

    /**
     * 按 DB 状态 + lease + 认领节点心跳清理 Redis 孤儿/僵尸 holder。
     *
     * @return 从 global 清理的 holder 数量
     */
    public int reconcileWithDatabase() {
        if (!clusterProperties.isEnabled() || redisPermits == null) {
            return 0;
        }
        Set<String> holders = redisPermits.members(POOL_GLOBAL);
        Set<String> stale = new HashSet<>();
        for (String holder : holders) {
            if (isStaleTaskHolder(holder)) {
                stale.add(holder);
            }
        }

        // 系统池双向：孤立或不合法成员
        Set<String> sysPools = redisPermits.listPoolsByPrefix(POOL_SYS_PREFIX);
        Map<String, Set<String>> sysStale = new HashMap<>();
        for (String sysPool : sysPools) {
            Set<String> sysMembers = redisPermits.members(sysPool);
            Set<String> remove = new HashSet<>();
            for (String h : sysMembers) {
                if (!holders.contains(h) || isStaleTaskHolder(h)) {
                    remove.add(h);
                }
            }
            if (!remove.isEmpty()) {
                sysStale.put(sysPool, remove);
            }
        }

        if (stale.isEmpty() && sysStale.isEmpty()) {
            return 0;
        }
        if (!stale.isEmpty()) {
            redisPermits.reconcileStale(POOL_GLOBAL, stale);
            for (String sysPool : sysPools) {
                redisPermits.reconcileStale(sysPool, stale);
            }
            redisPermits.reconcileStale(systemPool(null), stale);
        }
        for (Map.Entry<String, Set<String>> e : sysStale.entrySet()) {
            redisPermits.reconcileStale(e.getKey(), e.getValue());
        }
        int removed = stale.size() + sysStale.values().stream().mapToInt(Set::size).sum();
        log.warn("任务并发许可对账完成：globalStale={} sysExtra={} 示例={}",
                stale.size(),
                sysStale.values().stream().mapToInt(Set::size).sum(),
                stale.stream().limit(5).toList());
        return removed;
    }

    /**
     * 运维：清空全部任务 Redis 并发许可（global + 所有 sys 池），并清空本机 held 登记。
     *
     * @return 删除前 global 成员数
     */
    public long clearAllRedisPermits() {
        localHeldTasks.clear();
        if (!clusterProperties.isEnabled() || redisPermits == null) {
            log.info("清空任务 Redis 并发：非集群或无 Redis，仅清空本机登记");
            return 0;
        }
        long globalBefore = redisPermits.deletePool(POOL_GLOBAL);
        Set<String> sysPools = redisPermits.listPoolsByPrefix(POOL_SYS_PREFIX);
        for (String sysPool : sysPools) {
            redisPermits.deletePool(sysPool);
        }
        redisPermits.deletePool(systemPool(null));
        log.warn("已清空任务 Redis 并发许可 globalBefore={} sysPools={}", globalBefore, sysPools.size());
        return globalBefore;
    }

    /** 对本节点持有的许可续租 Redis key TTL */
    public void renewLocalHeldPermits() {
        if (!clusterProperties.isEnabled() || redisPermits == null || localHeldTasks.isEmpty()) {
            return;
        }
        long ttl = clusterProperties.getTaskPermitTtlSeconds();
        redisPermits.touchExpire(POOL_GLOBAL, ttl);
        Set<Long> sysIds = new HashSet<>(localHeldTasks.values());
        for (Long sysId : sysIds) {
            if (sysId != null) {
                redisPermits.touchExpire(systemPool(sysId), ttl);
            }
        }
    }

    @PreDestroy
    public void releaseAllLocalHeld() {
        if (localHeldTasks.isEmpty()) {
            return;
        }
        log.info("优雅停机：释放本节点任务并发许可 count={}", localHeldTasks.size());
        for (Map.Entry<Long, Long> e : List.copyOf(localHeldTasks.entrySet())) {
            try {
                release(e.getValue(), e.getKey());
            } catch (Exception ex) {
                log.warn("停机释放许可失败 taskId={}: {}", e.getKey(), ex.getMessage());
            }
        }
    }

    /**
     * holder 是否应删除。合法保留：本机 localHeld，或 DB 应占许可且 lease 有效且认领节点心跳仍在。
     */
    boolean isStaleTaskHolder(String holder) {
        Long taskId = parseTaskId(holder);
        if (taskId == null) {
            return true;
        }
        if (localHeldTasks.containsKey(taskId)) {
            return false;
        }
        DecompileTask task = taskMapper.selectById(taskId);
        if (task == null || !StringUtils.hasText(task.getStatus())
                || !PERMIT_HOLDING_STATUSES.contains(task.getStatus())) {
            return true;
        }
        // T3：lease 过期 → 僵尸
        LocalDateTime leaseUntil = task.getLeaseUntil();
        if (leaseUntil != null && leaseUntil.isBefore(LocalDateTime.now())) {
            return true;
        }
        // T3：认领节点心跳已死 → 崩溃残留（重启后 ClusterInstanceId 变化）
        String claimedBy = task.getClaimedBy();
        if (StringUtils.hasText(claimedBy) && instanceHeartbeat != null
                && !instanceHeartbeat.isAlive(claimedBy)) {
            return true;
        }
        // 无认领信息且非本机 held：单机崩溃后常见，视为僵尸
        if (!StringUtils.hasText(claimedBy) && leaseUntil == null) {
            return true;
        }
        return false;
    }

    private boolean tryAcquireCluster(Long systemId, Long taskId) {
        if (redisPermits == null || taskId == null) {
            log.warn("集群模式需要 RedisDistributedPermits 与 taskId");
            return false;
        }
        int globalMax = systemConfigService.getInt("task.concurrency", 2);
        String holder = holderToken(taskId);
        long ttl = clusterProperties.getTaskPermitTtlSeconds();
        if (!redisPermits.tryAcquire(POOL_GLOBAL, holder, globalMax, ttl)) {
            return false;
        }
        int sysMax = resolveSystemMax(systemId);
        if (!redisPermits.tryAcquire(systemPool(systemId), holder, sysMax, ttl)) {
            redisPermits.release(POOL_GLOBAL, holder);
            return false;
        }
        localHeldTasks.put(taskId, systemId);
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

    public static Long parseTaskId(String holder) {
        if (!StringUtils.hasText(holder) || !holder.startsWith("task:")) {
            return null;
        }
        // 排除 task:sys:xxx
        String rest = holder.substring("task:".length()).trim();
        if (rest.startsWith("sys:")) {
            return null;
        }
        try {
            return Long.parseLong(rest);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String systemPool(Long systemId) {
        return POOL_SYS_PREFIX + (systemId == null ? "unknown" : systemId);
    }

    private static String holderToken(Long taskId) {
        return "task:" + taskId;
    }
}
