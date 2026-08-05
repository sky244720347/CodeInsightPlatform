package com.company.codeinsight.modules.scanwindow.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按自然日记录「探测已完成」的仓库，保证全局轮询可跨 tick / 重启补扫未完成仓。
 * <p>优先 Redis（集群一致）；无 Redis 时退化为本机内存（仅 dev 单机可用）。</p>
 */
@Slf4j
@Component
public class ScanDailyCoverageStore {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE; // yyyyMMdd
    private static final String KEY_PREFIX = "scan:probe:done:";

    private final StringRedisTemplate redisTemplate;

    /** Redis 不可用时的本机兜底：day → repoIds */
    private final ConcurrentHashMap<String, Set<Long>> localDone = new ConcurrentHashMap<>();

    public ScanDailyCoverageStore(org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redis) {
        this.redisTemplate = redis.getIfAvailable();
    }

    public String todayKey() {
        return LocalDate.now().format(DAY);
    }

    public boolean isDone(Long repositoryId) {
        if (repositoryId == null) {
            return false;
        }
        String day = todayKey();
        if (redisTemplate != null) {
            try {
                Boolean member = redisTemplate.opsForSet().isMember(redisKey(day), String.valueOf(repositoryId));
                return Boolean.TRUE.equals(member);
            } catch (Exception e) {
                log.warn("scan coverage redis isMember failed, fallback local: {}", e.toString());
            }
        }
        return localDone.getOrDefault(day, Collections.emptySet()).contains(repositoryId);
    }

    public void markDone(Long repositoryId) {
        if (repositoryId == null) {
            return;
        }
        String day = todayKey();
        if (redisTemplate != null) {
            try {
                String key = redisKey(day);
                redisTemplate.opsForSet().add(key, String.valueOf(repositoryId));
                redisTemplate.expire(key, Duration.ofDays(3));
                return;
            } catch (Exception e) {
                log.warn("scan coverage redis markDone failed, fallback local: {}", e.toString());
            }
        }
        localDone.computeIfAbsent(day, d -> ConcurrentHashMap.newKeySet()).add(repositoryId);
    }

    public long doneCount() {
        String day = todayKey();
        if (redisTemplate != null) {
            try {
                Long size = redisTemplate.opsForSet().size(redisKey(day));
                return size != null ? size : 0L;
            } catch (Exception e) {
                log.warn("scan coverage redis size failed: {}", e.toString());
            }
        }
        return localDone.getOrDefault(day, Collections.emptySet()).size();
    }

    private static String redisKey(String day) {
        return KEY_PREFIX + day;
    }
}
