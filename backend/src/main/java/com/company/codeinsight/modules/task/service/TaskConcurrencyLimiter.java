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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

/**
 * 任务并发双闸：
 * <ul>
 *   <li>{@code task.concurrency} — <b>本机</b> JVM Semaphore（护内存）</li>
 *   <li>系统 {@code maxConcurrentTasks} — <b>集群</b> Redis {@code task:sys:{systemId}}
 *       （单机/无 Redis 时退化为本机 Semaphore；防同系统多节点互刷知识）</li>
 * </ul>
 */
@Slf4j
@Service
public class TaskConcurrencyLimiter {

    private static final String POOL_SYS_PREFIX = "task:sys:";

    /** 流水线占用系统集群闸期间的典型状态（对账用） */
    private static final Set<String> SYSTEM_PERMIT_HOLDING_STATUSES = Set.of(
            TaskStatus.PULL_QUEUED.name(),
            TaskStatus.PULLING_CODE.name(),
            TaskStatus.PARSE_QUEUED.name(),
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

    /** 本机任务总闸 */
    private volatile Semaphore nodeLocal;

    /** 单机模式下的 per-system 闸；集群模式系统闸走 Redis */
    private final ConcurrentMap<Long, Semaphore> perSystemLocal = new ConcurrentHashMap<>();

    /** 本节点当前持有的 taskId → systemId */
    private final ConcurrentMap<Long, Long> localHeldTasks = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        rebuildGlobal(systemConfigService.getInt("task.concurrency", 4));
    }

    public synchronized void rebuildGlobal(int permits) {
        int n = Math.max(1, permits);
        this.nodeLocal = new Semaphore(n, true);
        perSystemLocal.clear();
        log.info("TaskConcurrencyLimiter 本机任务闸已重建: nodePermits={}, systemGate={}",
                n, useClusterSystemGate() ? "redis-cluster" : "local-semaphore");
    }

    /**
     * 先占本机任务槽，再占系统闸（集群 Redis / 本机 Semaphore）。
     */
    public boolean tryAcquire(Long systemId, Long taskId) {
        if (nodeLocal == null) {
            rebuildGlobal(systemConfigService.getInt("task.concurrency", 4));
        }
        if (taskId != null && localHeldTasks.containsKey(taskId)) {
            return true;
        }
        if (!nodeLocal.tryAcquire()) {
            return false;
        }
        if (!tryAcquireSystem(systemId, taskId)) {
            nodeLocal.release();
            return false;
        }
        if (taskId != null) {
            localHeldTasks.put(taskId, systemId);
        }
        return true;
    }

    public boolean tryAcquire(Long systemId) {
        return tryAcquire(systemId, null);
    }

    public boolean isHeldLocally(Long taskId) {
        return taskId != null && localHeldTasks.containsKey(taskId);
    }

    public Set<Long> localHeldTaskIds() {
        return Set.copyOf(localHeldTasks.keySet());
    }

    public void release(Long systemId, Long taskId) {
        if (taskId != null) {
            Long heldSys = localHeldTasks.remove(taskId);
            if (heldSys == null) {
                return;
            }
            systemId = heldSys;
        }
        releaseSystem(systemId, taskId);
        if (nodeLocal != null) {
            try {
                nodeLocal.release();
            } catch (Exception ignored) {
            }
        }
    }

    public void release(Long systemId) {
        releaseSystem(systemId, null);
        if (nodeLocal != null) {
            try {
                nodeLocal.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** 本机尚可拉起的任务槽位数。 */
    public int globalAvailablePermits() {
        return nodeLocal == null ? 0 : nodeLocal.availablePermits();
    }

    /**
     * 对账集群系统闸孤儿 holder（任务已终态 / 租约失效 / 认领节点心跳已死）。
     */
    public int reconcileWithDatabase() {
        if (!useClusterSystemGate()) {
            return 0;
        }
        Set<String> sysPools = redisPermits.listPoolsByPrefix(POOL_SYS_PREFIX);
        int removed = 0;
        for (String sysPool : sysPools) {
            Set<String> members = redisPermits.members(sysPool);
            Set<String> stale = new HashSet<>();
            for (String holder : members) {
                if (isStaleSystemHolder(holder)) {
                    stale.add(holder);
                }
            }
            if (!stale.isEmpty()) {
                redisPermits.reconcileStale(sysPool, stale);
                removed += stale.size();
            }
        }
        if (removed > 0) {
            log.warn("系统并发许可对账完成：清理僵尸 holder={} pools={}", removed, sysPools.size());
        }
        return removed;
    }

    /** 续租本节点已占用的系统 Redis 池 TTL。 */
    public void renewLocalHeldPermits() {
        if (!useClusterSystemGate() || localHeldTasks.isEmpty()) {
            return;
        }
        long ttl = clusterProperties.getTaskPermitTtlSeconds();
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

    private boolean tryAcquireSystem(Long systemId, Long taskId) {
        int sysMax = resolveSystemMax(systemId);
        if (useClusterSystemGate()) {
            if (taskId == null) {
                log.warn("集群系统闸需要 taskId，拒绝 acquire systemId={}", systemId);
                return false;
            }
            String holder = holderToken(taskId);
            long ttl = clusterProperties.getTaskPermitTtlSeconds();
            return redisPermits.tryAcquire(systemPool(systemId), holder, sysMax, ttl);
        }
        Semaphore sys = getOrCreateSystemSemaphoreLocal(systemId, sysMax);
        return sys.tryAcquire();
    }

    private void releaseSystem(Long systemId, Long taskId) {
        if (useClusterSystemGate()) {
            if (taskId != null) {
                redisPermits.release(systemPool(systemId), holderToken(taskId));
            }
            return;
        }
        Long key = systemId == null ? -1L : systemId;
        Semaphore sys = perSystemLocal.get(key);
        if (sys != null) {
            try {
                sys.release();
            } catch (Exception ignored) {
            }
        }
    }

    private boolean useClusterSystemGate() {
        return clusterProperties.isEnabled() && redisPermits != null;
    }

    private boolean isStaleSystemHolder(String holder) {
        Long taskId = parseTaskId(holder);
        if (taskId == null) {
            return true;
        }
        if (localHeldTasks.containsKey(taskId)) {
            return false;
        }
        DecompileTask task = taskMapper.selectById(taskId);
        if (task == null || !StringUtils.hasText(task.getStatus())
                || !SYSTEM_PERMIT_HOLDING_STATUSES.contains(task.getStatus())) {
            return true;
        }
        LocalDateTime leaseUntil = task.getLeaseUntil();
        if (leaseUntil != null && leaseUntil.isBefore(LocalDateTime.now())) {
            return true;
        }
        String claimedBy = task.getClaimedBy();
        if (StringUtils.hasText(claimedBy) && instanceHeartbeat != null
                && !instanceHeartbeat.isAlive(claimedBy)) {
            return true;
        }
        if (!StringUtils.hasText(claimedBy) && leaseUntil == null) {
            return true;
        }
        return false;
    }

    private Semaphore getOrCreateSystemSemaphoreLocal(Long systemId, int sysMax) {
        Long key = systemId == null ? -1L : systemId;
        return perSystemLocal.computeIfAbsent(key, k -> {
            log.info("TaskConcurrencyLimiter 系统 {} 本机系统闸: permits={}", systemId, sysMax);
            return new Semaphore(Math.max(1, sysMax), true);
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
