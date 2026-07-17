package com.company.codeinsight.modules.businessknowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.storage.EnvStorageResolver;
import com.company.codeinsight.common.util.DataUriUtil;
import com.company.codeinsight.modules.businessknowledge.entity.BusinessKnowledge;
import com.company.codeinsight.modules.businessknowledge.mapper.BusinessKnowledgeMapper;
import com.company.codeinsight.modules.businessknowledge.service.BusinessKnowledgeService;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.mapper.SystemApplicationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 业务知识配置服务：正文外置 NAS，Redis 读缓存（blank 不命中/不回填）。
 */
@Slf4j
@Service
public class BusinessKnowledgeServiceImpl
        extends ServiceImpl<BusinessKnowledgeMapper, BusinessKnowledge>
        implements BusinessKnowledgeService {

    private static final int MAX_CONTENT_LENGTH = 64 * 1024;
    private static final String REDIS_KEY_PREFIX = "ci:meta:biz-knowledge:";
    private static final Duration REDIS_TTL = Duration.ofHours(6);

    @Autowired
    private SystemApplicationMapper systemMapper;

    @Autowired
    private EnvStorageResolver storageResolver;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean save(BusinessKnowledge entity) {
        if (entity == null) {
            return false;
        }
        String body = entity.getContent();
        if (!StringUtils.hasText(entity.getContentUri())) {
            entity.setContentUri("");
        }
        entity.setContent(null);
        boolean ok = super.save(entity);
        if (ok && entity.getSystemId() != null) {
            persistContent(entity, body);
            entity.setContent(body);
        }
        return ok;
    }

    @Override
    public boolean updateById(BusinessKnowledge entity) {
        if (entity == null || entity.getId() == null) {
            return false;
        }
        String body = entity.getContent();
        entity.setContent(null);
        if (!StringUtils.hasText(entity.getContentUri())) {
            BusinessKnowledge existing = super.getById(entity.getId());
            if (existing != null) {
                entity.setContentUri(existing.getContentUri());
                entity.setContentHash(existing.getContentHash());
                if (entity.getSystemId() == null) {
                    entity.setSystemId(existing.getSystemId());
                }
            }
        }
        boolean ok = super.updateById(entity);
        if (ok && body != null && entity.getSystemId() != null) {
            persistContent(entity, body);
            entity.setContent(body);
        } else if (ok) {
            hydrate(entity);
        }
        return ok;
    }

    @Override
    public BusinessKnowledge getById(java.io.Serializable id) {
        BusinessKnowledge bk = super.getById(id);
        hydrate(bk);
        return bk;
    }

    @Override
    public BusinessKnowledge getBySystemId(Long systemId) {
        if (systemId == null) {
            return null;
        }
        BusinessKnowledge bk = baseMapper.selectOne(
                new LambdaQueryWrapper<BusinessKnowledge>()
                        .eq(BusinessKnowledge::getSystemId, systemId)
        );
        hydrate(bk);
        return bk;
    }

    @Override
    public String getContentBySystemId(Long systemId) {
        if (systemId == null) {
            return "";
        }
        Long systemCount = systemMapper.selectCount(
                new LambdaQueryWrapper<SystemApplication>()
                        .eq(SystemApplication::getId, systemId)
                        .eq(SystemApplication::getIsDeleted, 0)
        );
        if (systemCount == null || systemCount == 0) {
            return "";
        }
        String cached = getCache(systemId);
        if (cached != null) {
            return cached;
        }
        BusinessKnowledge bk = baseMapper.selectOne(
                new LambdaQueryWrapper<BusinessKnowledge>()
                        .eq(BusinessKnowledge::getSystemId, systemId)
        );
        if (bk == null) {
            return "";
        }
        hydrate(bk);
        return bk.getContent() == null ? "" : bk.getContent();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BusinessKnowledge upsert(Long systemId, String content, String updatedBy) {
        if (systemId == null) {
            throw new BusinessException("systemId 不能为空");
        }
        SystemApplication system = systemMapper.selectOne(
                new LambdaQueryWrapper<SystemApplication>()
                        .eq(SystemApplication::getId, systemId)
                        .eq(SystemApplication::getIsDeleted, 0)
        );
        if (system == null) {
            throw new BusinessException("系统不存在或已删除: " + systemId);
        }

        String normalized = content == null ? "" : content;
        if (normalized.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException("业务知识正文超过 " + MAX_CONTENT_LENGTH + " 字符上限");
        }

        BusinessKnowledge existing = baseMapper.selectOne(
                new LambdaQueryWrapper<BusinessKnowledge>()
                        .eq(BusinessKnowledge::getSystemId, systemId)
        );
        if (existing == null) {
            BusinessKnowledge created = new BusinessKnowledge();
            created.setSystemId(systemId);
            created.setContent(normalized);
            created.setContentUri("");
            created.setVersion(1);
            created.setUpdatedBy(updatedBy);
            this.save(created);
            return created;
        }
        existing.setContent(normalized);
        existing.setVersion(existing.getVersion() == null ? 1 : existing.getVersion() + 1);
        if (StringUtils.hasText(updatedBy)) {
            existing.setUpdatedBy(updatedBy);
        }
        this.updateById(existing);
        return existing;
    }

    private void persistContent(BusinessKnowledge entity, String body) {
        if (entity == null || entity.getSystemId() == null) {
            return;
        }
        String uri = DataUriUtil.buildBusinessKnowledgeUri(entity.getSystemId());
        String hash = DataUriUtil.writeUtf8(uri, body == null ? "" : body, storageResolver);
        entity.setContentUri(uri);
        entity.setContentHash(hash);
        super.update(new LambdaUpdateWrapper<BusinessKnowledge>()
                .eq(BusinessKnowledge::getId, entity.getId())
                .set(BusinessKnowledge::getContentUri, uri)
                .set(BusinessKnowledge::getContentHash, hash));
        evictCache(entity.getSystemId());
        putCache(entity.getSystemId(), body);
    }

    private void hydrate(BusinessKnowledge bk) {
        if (bk == null || bk.getSystemId() == null) {
            return;
        }
        String cached = getCache(bk.getSystemId());
        if (cached != null) {
            bk.setContent(cached);
            return;
        }
        String body = "";
        if (StringUtils.hasText(bk.getContentUri())) {
            body = DataUriUtil.readUtf8(bk.getContentUri(), storageResolver);
        }
        bk.setContent(body == null ? "" : body);
        putCache(bk.getSystemId(), bk.getContent());
    }

    /** blank 不命中：返回 null 表示 miss；空串不当作缓存命中 */
    private String getCache(Long systemId) {
        try {
            String v = stringRedisTemplate.opsForValue().get(REDIS_KEY_PREFIX + systemId);
            return StringUtils.hasText(v) ? v : null;
        } catch (Exception e) {
            log.debug("biz-knowledge redis get degraded systemId={}: {}", systemId, e.getMessage());
            return null;
        }
    }

    private void putCache(Long systemId, String body) {
        if (systemId == null || !StringUtils.hasText(body)) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(REDIS_KEY_PREFIX + systemId, body, REDIS_TTL);
        } catch (Exception e) {
            log.debug("biz-knowledge redis put degraded systemId={}: {}", systemId, e.getMessage());
        }
    }

    private void evictCache(Long systemId) {
        if (systemId == null) {
            return;
        }
        try {
            stringRedisTemplate.delete(REDIS_KEY_PREFIX + systemId);
        } catch (Exception e) {
            log.debug("biz-knowledge redis del degraded systemId={}: {}", systemId, e.getMessage());
        }
    }
}
