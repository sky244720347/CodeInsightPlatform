package com.company.codeinsight.modules.quotacontrol.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.codeinsight.common.util.DbStringLimits;
import com.company.codeinsight.modules.quotacontrol.entity.SystemConfig;
import com.company.codeinsight.modules.quotacontrol.mapper.SystemConfigMapper;
import com.company.codeinsight.modules.quotacontrol.service.SystemConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 系统配置服务实现。
 * <p>
 * PostgreSQL 为权威源；运行时读走 Redis 值缓存（{@code ci:config:kv:{key}}），
 * miss 回源 PG 后回填；写成功后 DEL Redis。无 JVM 长缓存、无 Pub/Sub。
 * </p>
 */
@Slf4j
@Service
public class SystemConfigServiceImpl extends ServiceImpl<SystemConfigMapper, SystemConfig> implements SystemConfigService {

    static final String REDIS_KEY_PREFIX = "ci:config:kv:";
    static final Duration REDIS_TTL = Duration.ofHours(1);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public String getString(String key) {
        if (key == null) {
            return null;
        }
        String cached = getCache(key);
        if (cached != null) {
            return cached;
        }
        SystemConfig row = this.getById(key);
        if (row == null || row.getValue() == null) {
            return null;
        }
        putCache(key, row.getValue());
        return row.getValue();
    }

    @Override
    public int getInt(String key, int defaultValue) {
        String v = getString(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        String v = getString(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(v.trim());
    }

    @Override
    public void putString(String key, String value, String description, String updatedBy) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("key/value 不能为空");
        }
        SystemConfig existing = this.getById(key);
        String truncated = DbStringLimits.truncate(value, DbStringLimits.CONFIG_VALUE);
        if (existing == null) {
            SystemConfig c = new SystemConfig();
            c.setKey(key);
            c.setValue(truncated);
            c.setDescription(description);
            c.setUpdatedBy(updatedBy);
            c.setUpdatedDate(LocalDateTime.now());
            this.save(c);
        } else {
            existing.setValue(truncated);
            if (description != null) {
                existing.setDescription(description);
            }
            existing.setUpdatedBy(updatedBy);
            existing.setUpdatedDate(LocalDateTime.now());
            this.updateById(existing);
        }
        evictCache(key);
    }

    @Override
    public List<SystemConfig> listAll() {
        List<SystemConfig> all = this.list(new LambdaQueryWrapper<SystemConfig>().orderByAsc(SystemConfig::getKey));
        return all == null ? Collections.emptyList() : all;
    }

    /** Redis miss 或异常时返回 null；空串视为合法命中。 */
    private String getCache(String key) {
        try {
            return stringRedisTemplate.opsForValue().get(REDIS_KEY_PREFIX + key);
        } catch (Exception e) {
            log.debug("system-config redis get degraded key={}: {}", key, e.getMessage());
            return null;
        }
    }

    private void putCache(String key, String value) {
        if (key == null || value == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(REDIS_KEY_PREFIX + key, value, REDIS_TTL);
        } catch (Exception e) {
            log.debug("system-config redis put degraded key={}: {}", key, e.getMessage());
        }
    }

    private void evictCache(String key) {
        if (key == null) {
            return;
        }
        try {
            stringRedisTemplate.delete(REDIS_KEY_PREFIX + key);
        } catch (Exception e) {
            log.warn("system-config redis del failed key={}: {}", key, e.getMessage());
        }
    }
}
