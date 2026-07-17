package com.company.codeinsight.modules.token.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.codeinsight.modules.model.dto.AiModelMetricSummary;
import com.company.codeinsight.modules.model.dto.AiModelMetricTrendPoint;
import com.company.codeinsight.modules.token.entity.TokenUsageAudit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Token 用量审计持久层 Mapper 接口
 * 继承 MyBatis-Plus 的 BaseMapper，实现对 ci_token_usage_audit 表的 CRUD 操作。
 */
@Mapper
public interface TokenUsageAuditMapper extends BaseMapper<TokenUsageAudit> {

    @Select("""
        SELECT
            model_name AS modelName,
            COUNT(*) AS totalCalls,
            COALESCE(SUM(total_tokens), 0) AS totalTokens,
            COALESCE(SUM(cost), 0) AS totalCost
        FROM ci_token_usage_audit
        WHERE is_deleted = 0
        GROUP BY model_name
        """)
    List<AiModelMetricSummary> selectModelMetricSummaries();

    @Select("""
        SELECT
            TO_CHAR(created_date::date, 'YYYY-MM-DD') AS date,
            COUNT(*) AS calls,
            COALESCE(SUM(total_tokens), 0) AS tokens,
            COALESCE(SUM(cost), 0) AS cost
        FROM ci_token_usage_audit
        WHERE model_name = #{modelName}
          AND is_deleted = 0
          AND created_date >= #{startAt}
          AND created_date < #{endAt}
        GROUP BY created_date::date
        ORDER BY created_date::date ASC
        """)
    List<AiModelMetricTrendPoint> selectModelMetricTrend(
        @Param("modelName") String modelName,
        @Param("startAt") LocalDateTime startAt,
        @Param("endAt") LocalDateTime endAt
    );
}

