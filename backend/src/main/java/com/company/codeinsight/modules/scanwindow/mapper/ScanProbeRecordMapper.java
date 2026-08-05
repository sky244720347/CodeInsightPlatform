package com.company.codeinsight.modules.scanwindow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.codeinsight.modules.scanwindow.entity.ScanProbeRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

@Mapper
public interface ScanProbeRecordMapper extends BaseMapper<ScanProbeRecordEntity> {

    /**
     * 下发成功 / 当日了结：存在 SUCCESS 或 FAILED（无变动跳过、已下发、探测明确失败等）。
     */
    @Select("""
            SELECT COUNT(DISTINCT repository_id) FROM ci_scan_probe_record
            WHERE probe_date = #{probeDate}
              AND COALESCE(is_deleted, 0) = 0
              AND status IN ('SUCCESS', 'FAILED')
            """)
    long countSettledDistinct(@Param("probeDate") LocalDate probeDate);

    /**
     * 待重试：有延期/超时流水，且当日尚无 SUCCESS/FAILED 了结。
     */
    @Select("""
            SELECT COUNT(DISTINCT repository_id) FROM ci_scan_probe_record
            WHERE probe_date = #{probeDate}
              AND COALESCE(is_deleted, 0) = 0
              AND status IN ('INCONCLUSIVE', 'DEFERRED_DISPATCH', 'DISPATCH_FAILED')
              AND repository_id NOT IN (
                SELECT DISTINCT repository_id FROM ci_scan_probe_record
                WHERE probe_date = #{probeDate}
                  AND COALESCE(is_deleted, 0) = 0
                  AND status IN ('SUCCESS', 'FAILED')
              )
            """)
    long countRetryPendingDistinct(@Param("probeDate") LocalDate probeDate);

    @Select("""
            SELECT COUNT(*) FROM ci_scan_probe_record
            WHERE probe_date = #{probeDate}
              AND COALESCE(is_deleted, 0) = 0
              AND status = #{status}
            """)
    long countByDateAndStatus(@Param("probeDate") LocalDate probeDate, @Param("status") String status);

    @Select("""
            SELECT COUNT(*) FROM ci_scan_probe_record
            WHERE probe_date = #{probeDate}
              AND COALESCE(is_deleted, 0) = 0
              AND dispatch_action IN ('INITIAL', 'INCREMENTAL')
            """)
    long countDispatched(@Param("probeDate") LocalDate probeDate);

    @Select("""
            SELECT COALESCE(MAX(attempt_no), 0) FROM ci_scan_probe_record
            WHERE probe_date = #{probeDate}
              AND repository_id = #{repositoryId}
              AND COALESCE(is_deleted, 0) = 0
            """)
    int maxAttemptNo(@Param("probeDate") LocalDate probeDate, @Param("repositoryId") Long repositoryId);
}
