package com.company.codeinsight.common.cluster;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 集群节点存活心跳：用于 AI/任务许可对账时识别「已死实例」残留。
 * <p>key = {@code ci:permits:instance:{ClusterInstanceId}}，TTL 短，由调度器续租。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstanceHeartbeat {

    static final String KEY_PREFIX = "ci:permits:instance:";

    private final StringRedisTemplate redisTemplate;
    private final ClusterInstanceId clusterInstanceId;
    private final ClusterProperties clusterProperties;

    public String currentInstanceId() {
        return clusterInstanceId.get();
    }

    /** 写入/续租本节点心跳 */
    public void touch() {
        if (!clusterProperties.isEnabled()) {
            return;
        }
        String key = KEY_PREFIX + clusterInstanceId.get();
        long ttl = Math.max(30, clusterProperties.getInstanceHeartbeatTtlSeconds());
        try {
            redisTemplate.opsForValue().set(key, "1", ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("实例心跳续租失败 instanceId={}: {}", clusterInstanceId.get(), e.getMessage());
        }
    }

    public boolean isAlive(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return false;
        }
        try {
            Boolean exists = redisTemplate.hasKey(KEY_PREFIX + instanceId);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.warn("查询实例心跳失败 instanceId={}: {}", instanceId, e.getMessage());
            // 降级：查失败时视为存活，避免误删在飞许可
            return true;
        }
    }

    /** 本机是否即该 instanceId（含本节点刚 touch 前的自洽） */
    public boolean isSelf(String instanceId) {
        return clusterInstanceId.get().equals(instanceId);
    }

    @jakarta.annotation.PreDestroy
    public void clearSelf() {
        if (!clusterProperties.isEnabled()) {
            return;
        }
        try {
            redisTemplate.delete(KEY_PREFIX + clusterInstanceId.get());
        } catch (Exception e) {
            log.debug("停机删除心跳失败: {}", e.getMessage());
        }
    }

    /** 扫描当前仍存活的实例 ID（运维/测试用） */
    public Set<String> listAliveInstanceIds() {
        try {
            Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
            if (keys == null || keys.isEmpty()) {
                return Set.of();
            }
            Set<String> ids = new HashSet<>();
            for (String k : keys) {
                if (k != null && k.startsWith(KEY_PREFIX)) {
                    ids.add(k.substring(KEY_PREFIX.length()));
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("列举存活实例失败: {}", e.getMessage());
            return Collections.emptySet();
        }
    }
}
