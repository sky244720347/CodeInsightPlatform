package com.company.codeinsight.modules.scanwindow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.codeinsight.modules.scanwindow.entity.ScanWindowEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 扫描窗口 Mapper。方案 B：Service 侧 select 活行 → updateById / insert（无 upsert）。
 */
@Mapper
public interface ScanWindowMapper extends BaseMapper<ScanWindowEntity> {
}
