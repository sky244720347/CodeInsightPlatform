package com.company.codeinsight.modules.quotacontrol.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.codeinsight.modules.quotacontrol.entity.SystemConfig;
import com.company.codeinsight.modules.quotacontrol.mapper.SystemConfigMapper;
import com.company.codeinsight.modules.quotacontrol.service.SystemConfigService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 系统配置服务实现。
 * <p>
 * 启动时一次性把表里的 key-value 加载到内存（ConcurrentHashMap）；
 * 写后调用 {@link #refreshCache()} 刷新，保证运行期一致性。
 * </p>
 */
@Service
public class SystemConfigServiceImpl extends ServiceImpl<SystemConfigMapper, SystemConfig> implements SystemConfigService {

    /** 运行期缓存（key → value）。所有 get 方法都从缓存读；写时同步刷新。 */
    private final ConcurrentMap<String, String> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        refreshCache();
    }

    @Override
    public void refreshCache() {
        cache.clear();
        List<SystemConfig> all = this.list(new LambdaQueryWrapper<SystemConfig>().orderByAsc(SystemConfig::getKey));
        for (SystemConfig c : all) {
            if (c.getKey() != null && c.getValue() != null) {
                cache.put(c.getKey(), c.getValue());
            }
        }
    }

    @Override
    public String getString(String key) {
        if (key == null) return null;
        return cache.get(key);
    }

    @Override
    public int getInt(String key, int defaultValue) {
        String v = getString(key);
        if (v == null || v.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        String v = getString(key);
        if (v == null || v.isBlank()) return defaultValue;
        return Boolean.parseBoolean(v.trim());
    }

    @Override
    public void putString(String key, String value, String description, String updatedBy) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("key/value 不能为空");
        }
        SystemConfig existing = this.getById(key);
        String truncated = com.company.codeinsight.common.util.DbStringLimits.truncate(
                value, com.company.codeinsight.common.util.DbStringLimits.CONFIG_VALUE);
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
            if (description != null) existing.setDescription(description);
            existing.setUpdatedBy(updatedBy);
            existing.setUpdatedDate(LocalDateTime.now());
            this.updateById(existing);
        }
        cache.put(key, truncated);
    }

    @Override
    public List<SystemConfig> listAll() {
        List<SystemConfig> all = this.list(new LambdaQueryWrapper<SystemConfig>().orderByAsc(SystemConfig::getKey));
        return all == null ? Collections.emptyList() : all;
    }
}
