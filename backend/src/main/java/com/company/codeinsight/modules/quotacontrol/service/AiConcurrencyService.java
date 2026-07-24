package com.company.codeinsight.modules.quotacontrol.service;

import com.company.codeinsight.common.cluster.ClusterInstanceId;
import com.company.codeinsight.common.cluster.ClusterProperties;
import com.company.codeinsight.common.cluster.InstanceHeartbeat;
import com.company.codeinsight.common.cluster.RedisDistributedPermits;
import com.company.codeinsight.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * AI 调用并发控制器。
 * <p>集群模式使用 Redis 分布式计数；单机模式使用 JVM Semaphore。
 * holder 形如 {@code ai:{ClusterInstanceId}:{uuid}}；启动/周期清理本机孤儿、死实例与旧格式残留。</p>
 */
@Slf4j
@Service
public class AiConcurrencyService {

    private static final String POOL_AI = "ai:global";

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private ClusterProperties clusterProperties;

    @Autowired
    private ClusterInstanceId clusterInstanceId;

    @Autowired(required = false)
    private RedisDistributedPermits redisPermits;

    @Autowired(required = false)
    private InstanceHeartbeat instanceHeartbeat;

    private volatile Semaphore localSemaphore;

    /** 当前线程持有的 Redis holder */
    private final ThreadLocal<String> redisHolder = new ThreadLocal<>();

    /** 本节点当前仍占用的全部 AI holder（续租 / 优雅停机 / 对账） */
    private final Set<String> localHeldHolders = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public void init() {
        rebuild(systemConfigService.getInt("ai.concurrency", 4));
        log.info("AiConcurrencyService instanceId={}", clusterInstanceId.get());
    }

    public synchronized void rebuild(int permits) {
        int n = Math.max(1, permits);
        this.localSemaphore = new Semaphore(n, true);
        log.info("AI 并发已重建: permits={}, cluster={}", n, clusterProperties.isEnabled());
    }

    public void tryAcquire() {
        if (clusterProperties.isEnabled() && redisPermits != null) {
            int max = systemConfigService.getInt("ai.concurrency", 4);
            String holder = "ai:" + clusterInstanceId.get() + ":" + UUID.randomUUID();
            long ttl = clusterProperties.getTaskPermitTtlSeconds();
            if (!redisPermits.tryAcquire(POOL_AI, holder, max, ttl)) {
                throw new BusinessException("AI 调用并发已达上限，请稍后重试");
            }
            redisHolder.set(holder);
            localHeldHolders.add(holder);
            return;
        }
        if (localSemaphore == null) {
            rebuild(systemConfigService.getInt("ai.concurrency", 4));
        }
        if (!localSemaphore.tryAcquire()) {
            throw new BusinessException("AI 调用并发已达上限，请稍后重试");
        }
    }

    public void release() {
        if (clusterProperties.isEnabled() && redisPermits != null) {
            String holder = redisHolder.get();
            if (holder != null) {
                redisPermits.release(POOL_AI, holder);
                localHeldHolders.remove(holder);
                redisHolder.remove();
            }
            return;
        }
        if (localSemaphore != null) {
            localSemaphore.release();
        }
    }

    public int availablePermits() {
        if (clusterProperties.isEnabled() && redisPermits != null) {
            int max = systemConfigService.getInt("ai.concurrency", 4);
            return Math.max(0, max - (int) redisPermits.count(POOL_AI));
        }
        return localSemaphore == null ? 0 : localSemaphore.availablePermits();
    }

    /**
     * 清理：旧格式 {@code ai:{uuid}}、死实例前缀、本机非 localHeld 孤儿。
     *
     * @return 清理数量
     */
    public int reconcileLocalOrphans() {
        if (!clusterProperties.isEnabled() || redisPermits == null) {
            return 0;
        }
        String selfPrefix = "ai:" + clusterInstanceId.get() + ":";
        Set<String> members = redisPermits.members(POOL_AI);
        if (members.isEmpty()) {
            return 0;
        }
        Set<String> stale = new HashSet<>();
        for (String m : members) {
            if (m == null) {
                continue;
            }
            ParsedAiHolder parsed = ParsedAiHolder.parse(m);
            if (parsed == null) {
                // 旧格式 ai:{uuid} 或非法
                stale.add(m);
                continue;
            }
            if (m.startsWith(selfPrefix)) {
                if (!localHeldHolders.contains(m)) {
                    stale.add(m);
                }
                continue;
            }
            // 它机：心跳已死则清
            if (instanceHeartbeat != null && !instanceHeartbeat.isAlive(parsed.instanceId())) {
                stale.add(m);
            }
        }
        if (stale.isEmpty()) {
            return 0;
        }
        redisPermits.reconcileStale(POOL_AI, stale);
        log.warn("AI 并发许可对账完成：清理僵尸 holder={} 个，selfPrefix={}", stale.size(), selfPrefix);
        return stale.size();
    }

    /** 运维：清空 AI Redis 并发池，并清空本机 held */
    public long clearAllRedisPermits() {
        localHeldHolders.clear();
        redisHolder.remove();
        if (!clusterProperties.isEnabled() || redisPermits == null) {
            log.info("清空 AI Redis 并发：非集群或无 Redis，仅清空本机登记");
            return 0;
        }
        long before = redisPermits.deletePool(POOL_AI);
        log.warn("已清空 AI Redis 并发许可 before={}", before);
        return before;
    }

    /** 本节点仍持有 AI 许可时，刷新 Set key TTL */
    public void renewLocalHeldPermits() {
        if (!clusterProperties.isEnabled() || redisPermits == null || localHeldHolders.isEmpty()) {
            return;
        }
        redisPermits.touchExpire(POOL_AI, clusterProperties.getTaskPermitTtlSeconds());
    }

    @PreDestroy
    public void releaseAllLocalHeld() {
        if (localHeldHolders.isEmpty() || redisPermits == null || !clusterProperties.isEnabled()) {
            localHeldHolders.clear();
            return;
        }
        log.info("优雅停机：释放本节点 AI 并发许可 count={}", localHeldHolders.size());
        for (String holder : Set.copyOf(localHeldHolders)) {
            try {
                redisPermits.release(POOL_AI, holder);
            } catch (Exception e) {
                log.warn("停机释放 AI 许可失败 holder={}: {}", holder, e.getMessage());
            }
        }
        localHeldHolders.clear();
        redisHolder.remove();
    }

    /**
     * 解析 {@code ai:{instanceId}:{uuid}}。旧格式 {@code ai:{uuid}}（仅两段）返回 null。
     * <p>{@code ClusterInstanceId} 含冒号（host:pid:uuid），故从左侧剥 {@code ai:} 后，
     * 取最后一段为 token，其余为 instanceId。</p>
     */
    public record ParsedAiHolder(String instanceId, String token) {
        public static ParsedAiHolder parse(String holder) {
            if (holder == null || !holder.startsWith("ai:")) {
                return null;
            }
            String rest = holder.substring(3);
            int last = rest.lastIndexOf(':');
            if (last <= 0 || last >= rest.length() - 1) {
                return null;
            }
            String instanceId = rest.substring(0, last);
            String token = rest.substring(last + 1);
            if (instanceId.isBlank() || token.isBlank()) {
                return null;
            }
            return new ParsedAiHolder(instanceId, token);
        }
    }

    /** 单测入口 */
    public static ParsedAiHolder parseAiHolder(String holder) {
        return ParsedAiHolder.parse(holder);
    }
}
