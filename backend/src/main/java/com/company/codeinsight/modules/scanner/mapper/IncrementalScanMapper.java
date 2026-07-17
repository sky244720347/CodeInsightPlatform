package com.company.codeinsight.modules.scanner.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.codeinsight.modules.scanner.entity.IncrementalScanRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 增量扫描结果 Mapper（ci_incremental_scan）
 * <p>活行唯一：{@code uk_incremental_scan_task_active (task_id) WHERE is_deleted=0}。
 * 覆盖写 = 逻辑删 + insert（方案 B）。</p>
 */
@Mapper
public interface IncrementalScanMapper extends BaseMapper<IncrementalScanRecord> {

    @Select("SELECT * FROM ci_incremental_scan WHERE task_id=#{taskId} AND is_deleted=0 LIMIT 1")
    IncrementalScanRecord selectActiveByTaskId(@Param("taskId") Long taskId);

    @Update("UPDATE ci_incremental_scan SET is_deleted=1, updated_date=CURRENT_TIMESTAMP " +
            "WHERE task_id=#{taskId} AND is_deleted=0")
    int logicDeleteByTaskId(@Param("taskId") Long taskId);
}
