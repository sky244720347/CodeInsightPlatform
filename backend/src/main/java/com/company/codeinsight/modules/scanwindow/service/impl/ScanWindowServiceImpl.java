package com.company.codeinsight.modules.scanwindow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.scanwindow.entity.ScanWindowEntity;
import com.company.codeinsight.modules.scanwindow.mapper.ScanWindowMapper;
import com.company.codeinsight.modules.scanwindow.service.ScanWindowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScanWindowServiceImpl implements ScanWindowService {

    private final ScanWindowMapper mapper;

    @Override
    public ScanWindowEntity getByRepository(Long repositoryId) {
        return mapper.selectOne(new LambdaQueryWrapper<ScanWindowEntity>()
                .eq(ScanWindowEntity::getRepositoryId, repositoryId)
                .last("LIMIT 1"));
    }

    @Override
    public ScanWindowEntity upsert(ScanWindowEntity w) {
        if (w.getWeekDays() == null) w.setWeekDays(127);
        if (w.getHour() == null) w.setHour(2);
        if (w.getMinute() == null) w.setMinute(0);
        if (w.getEnabled() == null) w.setEnabled(true);
        if (w.getHour() < 0 || w.getHour() > 23) throw new BusinessException("hour 必须在 0-23");
        if (w.getMinute() < 0 || w.getMinute() > 59) throw new BusinessException("minute 必须在 0-59");
        if (w.getWeekDays() < 0 || w.getWeekDays() > 127) throw new BusinessException("weekDays 位掩码必须在 0-127");
        if (w.getRepositoryId() == null) throw new BusinessException("repositoryId 不能为空");

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        ScanWindowEntity existing = getByRepository(w.getRepositoryId());
        if (existing == null) {
            w.setId(null);
            w.setCreatedDate(now);
            w.setUpdatedDate(now);
            w.setIsDeleted(0);
            mapper.insert(w);
            return getByRepository(w.getRepositoryId());
        }
        existing.setWeekDays(w.getWeekDays());
        existing.setHour(w.getHour());
        existing.setMinute(w.getMinute());
        existing.setEnabled(w.getEnabled());
        if (w.getLastFiredAt() != null) {
            existing.setLastFiredAt(w.getLastFiredAt());
        }
        existing.setUpdatedDate(now);
        mapper.updateById(existing);
        return getByRepository(w.getRepositoryId());
    }

    @Override
    public void deleteByRepository(Long repositoryId) {
        mapper.delete(new LambdaQueryWrapper<ScanWindowEntity>()
                .eq(ScanWindowEntity::getRepositoryId, repositoryId));
    }

    @Override
    public List<ScanWindowEntity> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<ScanWindowEntity>()
                .orderByDesc(ScanWindowEntity::getUpdatedDate));
    }

    @Override
    public List<ScanWindowEntity> listEnabled() {
        return mapper.selectList(new LambdaQueryWrapper<ScanWindowEntity>()
                .eq(ScanWindowEntity::getEnabled, true));
    }
}
