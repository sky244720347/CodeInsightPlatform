package com.company.codeinsight.modules.quotacontrol.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.company.codeinsight.modules.quotacontrol.entity.SystemConfig;

import java.util.List;

public interface SystemConfigService extends IService<SystemConfig> {

    /**
     * 读取单个配置（文本）。未配置时返回 null。
     * <p>路径：Redis {@code ci:config:kv:{key}} → miss 则 PostgreSQL → 回填 Redis。</p>
     */
    String getString(String key);

    /**
     * 读取单个配置（int）。配置缺失或非数字时回退 defaultValue。
     */
    int getInt(String key, int defaultValue);

    /**
     * 读取单个配置（boolean）。配置缺失或非 true/false 时回退 defaultValue。
     */
    boolean getBoolean(String key, boolean defaultValue);

    /**
     * 写/更新单个配置（PostgreSQL 权威；成功后 DEL 对应 Redis key）。
     */
    void putString(String key, String value, String description, String updatedBy);

    /**
     * 列出所有配置（按 key 排序）。直读 PostgreSQL，不走 Redis。
     */
    List<SystemConfig> listAll();
}
