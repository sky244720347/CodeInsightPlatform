package com.company.codeinsight.modules.token.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * Token 用量审计明细实体类
 * 对应数据库中的 ci_token_usage_audit 表，审计单次大模型调用的输入/输出 Token、评估扣费成本及状态。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_token_usage_audit")
public class TokenUsageAudit extends BaseEntity {

    /**
     * 自增主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属业务系统 ID
     */
    private Long systemId;

    /**
     * 关联的分析任务 ID
     */
    private Long taskId;

    /**
     * 触发调用的用户 ID
     */
    private Long userId;

    /**
     * 调用的提示词模板版本号
     */
    private Integer promptVersion;

    /**
     * 调用的 AI 大模型唯一标识
     */
    private String modelName;

    /**
     * 输入（请求）所消耗的 Token 数量
     */
    private Integer inputTokens;

    /**
     * 输出（响应）所消耗的 Token 数量
     */
    private Integer outputTokens;

    /**
     * 本次调用的总 Token 数量（inputTokens + outputTokens）
     */
    private Integer totalTokens;

    /**
     * 本次调用评估折算的费用成本金额
     */
    private BigDecimal cost;

    /**
     * 调用动作场景类型：INITIAL-全量初始化分析, INCREMENTAL-增量分析, TEST-模板试跑测试
     */
    private String type;

    /**
     * 大模型调用执行状态：0-失败, 1-成功
     */
    private Integer status;
}
