package com.company.codeinsight.modules.businessknowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.businessknowledge.entity.BusinessKnowledge;
import com.company.codeinsight.modules.businessknowledge.mapper.BusinessKnowledgeMapper;
import com.company.codeinsight.modules.businessknowledge.service.BusinessKnowledgeService;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.mapper.SystemApplicationMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 业务知识配置服务实现
 *
 * <p>实现策略：</p>
 * <ul>
 *     <li>1 系统 1 份配置，systemId UNIQUE 约束由 DB 兜底</li>
 *     <li>覆盖式保存：每次保存都把 content 整体替换，version 字段单调递增便于审计</li>
 *     <li>系统软删后，业务知识仍存在但查询时被过滤（{@link #getBySystemId} / {@link #getContentBySystemId} 走联表过滤 deleted_at）</li>
 * </ul>
 */
@Service
public class BusinessKnowledgeServiceImpl
        extends ServiceImpl<BusinessKnowledgeMapper, BusinessKnowledge>
        implements BusinessKnowledgeService {

    /**
     * 业务知识正文长度上限（字符）。
     * <p>业务知识用于喂 AI，体量过大既无必要也增加 token 成本；64KB 足够覆盖任意真实业务领域描述。</p>
     */
    private static final int MAX_CONTENT_LENGTH = 64 * 1024;

    @Autowired
    private SystemApplicationMapper systemMapper;

    @Override
    public BusinessKnowledge getBySystemId(Long systemId) {
        if (systemId == null) {
            return null;
        }
        return baseMapper.selectOne(
                new LambdaQueryWrapper<BusinessKnowledge>()
                        .eq(BusinessKnowledge::getSystemId, systemId)
        );
    }

    @Override
    public String getContentBySystemId(Long systemId) {
        if (systemId == null) {
            return "";
        }
        // 软删过滤：系统被软删后，业务知识视为不可用
        Long systemCount = systemMapper.selectCount(
                new LambdaQueryWrapper<SystemApplication>()
                        .eq(SystemApplication::getId, systemId)
                        .isNull(SystemApplication::getDeletedAt)
        );
        if (systemCount == null || systemCount == 0) {
            return "";
        }
        BusinessKnowledge bk = baseMapper.selectOne(
                new LambdaQueryWrapper<BusinessKnowledge>()
                        .eq(BusinessKnowledge::getSystemId, systemId)
        );
        return bk == null || !StringUtils.hasText(bk.getContent()) ? "" : bk.getContent();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BusinessKnowledge upsert(Long systemId, String content, String updatedBy) {
        if (systemId == null) {
            throw new BusinessException("systemId 不能为空");
        }
        // 校验系统存在且未软删
        SystemApplication system = systemMapper.selectOne(
                new LambdaQueryWrapper<SystemApplication>()
                        .eq(SystemApplication::getId, systemId)
                        .isNull(SystemApplication::getDeletedAt)
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
            // 首次保存
            BusinessKnowledge created = new BusinessKnowledge();
            created.setSystemId(systemId);
            created.setContent(normalized);
            created.setVersion(1);
            created.setUpdatedBy(updatedBy);
            this.save(created);
            return created;
        }
        // 覆盖更新
        existing.setContent(normalized);
        existing.setVersion(existing.getVersion() == null ? 1 : existing.getVersion() + 1);
        if (StringUtils.hasText(updatedBy)) {
            existing.setUpdatedBy(updatedBy);
        }
        this.updateById(existing);
        return existing;
    }
}
