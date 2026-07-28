package com.company.codeinsight.modules.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 反编译及分析任务数据持久层 Mapper 接口
 * 继承 MyBatis-Plus 的 BaseMapper，实现对 ci_task 数据库表的单表 CRUD。
 */
@Mapper
public interface DecompileTaskMapper extends BaseMapper<DecompileTask> {

    /**
     * 在事务内调用：锁定下一条可调度 PENDING 任务主键（SKIP LOCKED）。
     * 无可用任务时返回 null。
     */
    Long selectNextPendingIdForUpdate();

    /**
     * CAS 接管认领：status 与 claimed_by 与期望一致时，写入新 claimed_by / lease。
     * {@code expectedClaimedBy} 为 null 时匹配 DB claimed_by IS NULL。
     *
     * @return 影响行数（1=成功）
     */
    int casTakeoverClaim(@Param("id") Long id,
                         @Param("expectedStatus") String expectedStatus,
                         @Param("expectedClaimedBy") String expectedClaimedBy,
                         @Param("newClaimedBy") String newClaimedBy,
                         @Param("now") java.time.LocalDateTime now,
                         @Param("newLeaseUntil") java.time.LocalDateTime newLeaseUntil);
}

