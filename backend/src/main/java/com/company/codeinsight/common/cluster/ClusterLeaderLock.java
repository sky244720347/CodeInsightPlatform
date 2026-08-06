package com.company.codeinsight.common.cluster;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis SET NX 的简易 Leader 选举：同一 lockKey 仅一个实例持有。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClusterLeaderLock {

    private final StringRedisTemplate redisTemplate;
    private final ClusterInstanceId instanceId;
    private final ClusterProperties clusterProperties;

    public boolean tryAcquireLeader(String lockKey) {
        return tryAcquireLeader(lockKey, clusterProperties.getLeaderLockTtlSeconds());
    }

    /**
     * @param ttlSeconds Leader 键 TTL；持有方再次调用会续租。须大于单次业务最坏耗时，并在长任务中周期性续租。
     */
    public boolean tryAcquireLeader(String lockKey, int ttlSeconds) {
        int ttl = Math.max(5, ttlSeconds);
        String holder = instanceId.get();
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(
                lockKey,
                holder,
                Duration.ofSeconds(ttl));
        if (Boolean.TRUE.equals(ok)) {
            return true;
        }
        String current = redisTemplate.opsForValue().get(lockKey);
        if (holder.equals(current)) {
            redisTemplate.expire(lockKey, ttl, TimeUnit.SECONDS);
            return true;
        }
        return false;
    }

    public void releaseLeader(String lockKey) {
        String holder = instanceId.get();
        String current = redisTemplate.opsForValue().get(lockKey);
        if (holder.equals(current)) {
            redisTemplate.delete(lockKey);
        }
    }
}
