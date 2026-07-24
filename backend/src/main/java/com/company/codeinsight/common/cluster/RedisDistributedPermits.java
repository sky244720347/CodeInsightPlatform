package com.company.codeinsight.common.cluster;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis Set 的分布式并发计数（成员为 holder token，如 task:123 / ai:{instance}:{uuid}）。
 * <p>进程崩溃时成员可能残留；{@link #release} 与 {@link #reconcileStale} 负责清理。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisDistributedPermits {

    private static final String KEY_PREFIX = "ci:permits:";
    /** 未显式传 TTL 时的兜底（AI 等旧调用） */
    private static final long DEFAULT_TTL_SECONDS = 24L * 3600L;

    /**
     * 原子：若 SCARD &lt; max 则 SADD + EXPIRE，返回 1；已持有返回 1；满员返回 0。
     */
    private static final DefaultRedisScript<Long> TRY_ACQUIRE_SCRIPT = new DefaultRedisScript<>();

    static {
        TRY_ACQUIRE_SCRIPT.setResultType(Long.class);
        TRY_ACQUIRE_SCRIPT.setScriptText(
                "local key = KEYS[1]\n"
                        + "local holder = ARGV[1]\n"
                        + "local max = tonumber(ARGV[2])\n"
                        + "local ttl = tonumber(ARGV[3])\n"
                        + "if redis.call('SISMEMBER', key, holder) == 1 then\n"
                        + "  if ttl > 0 then redis.call('EXPIRE', key, ttl) end\n"
                        + "  return 1\n"
                        + "end\n"
                        + "local size = redis.call('SCARD', key)\n"
                        + "if size >= max then return 0 end\n"
                        + "redis.call('SADD', key, holder)\n"
                        + "if ttl > 0 then redis.call('EXPIRE', key, ttl) end\n"
                        + "return 1\n"
        );
    }

    private final StringRedisTemplate redisTemplate;

    public boolean tryAcquire(String pool, String holder, int maxPermits) {
        return tryAcquire(pool, holder, maxPermits, DEFAULT_TTL_SECONDS);
    }

    /**
     * @param ttlSeconds key 过期秒数；{@code <=0} 时使用默认 24h
     */
    public boolean tryAcquire(String pool, String holder, int maxPermits, long ttlSeconds) {
        if (maxPermits <= 0 || holder == null || holder.isBlank()) {
            return false;
        }
        String redisKey = key(pool);
        long ttl = ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL_SECONDS;
        try {
            Long ok = redisTemplate.execute(
                    TRY_ACQUIRE_SCRIPT,
                    List.of(redisKey),
                    holder,
                    String.valueOf(maxPermits),
                    String.valueOf(ttl)
            );
            return ok != null && ok == 1L;
        } catch (Exception e) {
            log.warn("Lua tryAcquire 失败，降级非原子路径 pool={}: {}", pool, e.getMessage());
            return tryAcquireFallback(pool, holder, maxPermits, ttl);
        }
    }

    private boolean tryAcquireFallback(String pool, String holder, int maxPermits, long ttlSeconds) {
        String redisKey = key(pool);
        Long size = redisTemplate.opsForSet().size(redisKey);
        long current = size == null ? 0 : size;
        if (current >= maxPermits) {
            return false;
        }
        Long added = redisTemplate.opsForSet().add(redisKey, holder);
        if (added == null || added == 0L) {
            touchExpire(pool, ttlSeconds);
            return true;
        }
        // 二次校验：并发下可能超卖，超卖则回滚本 holder
        Long after = redisTemplate.opsForSet().size(redisKey);
        if (after != null && after > maxPermits) {
            redisTemplate.opsForSet().remove(redisKey, holder);
            return false;
        }
        touchExpire(pool, ttlSeconds);
        return true;
    }

    public void release(String pool, String holder) {
        redisTemplate.opsForSet().remove(key(pool), holder);
    }

    public long count(String pool) {
        Long size = redisTemplate.opsForSet().size(key(pool));
        return size == null ? 0 : size;
    }

    /** 续租 / 刷新 Set key TTL */
    public void touchExpire(String pool, long ttlSeconds) {
        long ttl = ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL_SECONDS;
        redisTemplate.expire(key(pool), ttl, TimeUnit.SECONDS);
    }

    public Set<String> members(String pool) {
        Set<String> raw = redisTemplate.opsForSet().members(key(pool));
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new HashSet<>(raw));
    }

    /** 移除 Set 中指定 holder（任务终态后清孤儿许可） */
    public void reconcileStale(String pool, Set<String> staleHolders) {
        if (staleHolders == null || staleHolders.isEmpty()) {
            return;
        }
        String redisKey = key(pool);
        for (String h : staleHolders) {
            redisTemplate.opsForSet().remove(redisKey, h);
        }
        log.debug("reconcile permits pool={} removed={}", pool, staleHolders.size());
    }

    /** 删除整个 pool key，返回删除前成员数 */
    public long deletePool(String pool) {
        String redisKey = key(pool);
        Long size = redisTemplate.opsForSet().size(redisKey);
        long before = size == null ? 0 : size;
        redisTemplate.delete(redisKey);
        return before;
    }

    /**
     * 按 pool 名前缀扫描（不含 {@code ci:permits:}），返回匹配的 pool 名。
     * 例：{@code task:sys:} → {@code task:sys:1}, {@code task:sys:2}
     */
    public Set<String> listPoolsByPrefix(String poolPrefix) {
        String pattern = KEY_PREFIX + poolPrefix + "*";
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys == null || keys.isEmpty()) {
                return Set.of();
            }
            Set<String> pools = new HashSet<>();
            for (String k : keys) {
                if (k != null && k.startsWith(KEY_PREFIX)) {
                    pools.add(k.substring(KEY_PREFIX.length()));
                }
            }
            return pools;
        } catch (Exception e) {
            log.warn("扫描 permit pools 失败 prefix={}: {}", poolPrefix, e.getMessage());
            return Set.of();
        }
    }

    private static String key(String pool) {
        return KEY_PREFIX + pool;
    }
}
