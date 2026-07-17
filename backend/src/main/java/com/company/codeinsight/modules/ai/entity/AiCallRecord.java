package com.company.codeinsight.modules.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 调用历史明细实体类
 * 映射数据库中的 ci_ai_call_record 表，记录每一次大模型 API 的请求与响应审计日志。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_ai_call_record")
public class AiCallRecord extends BaseEntity {

    /**
     * 自增主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联的任务 ID
     */
    private Long taskId;

    /**
     * 关联的代码切片 ID
     */
    private Long chunkId;

    /**
     * 所使用的提示词模板 ID
     */
    private Long promptId;

    /**
     * 提示词模板的版本号
     */
    private Integer promptVersion;

    /**
     * 所调用的 AI 大模型唯一标识名称（例如 gpt-4o 等）
     */
    private String modelName;

    /**
     * 本次调用请求消耗的 Input Token 数量
     */
    private Integer inputToken;

    /**
     * 本次调用响应返回的 Output Token 数量
     */
    private Integer outputToken;

    /**
     * 请求的文本或载荷文件在本地/远程的 URI 存储路径
     */
    private String requestUri;

    /**
     * 响应的文本或载荷文件在本地/远程的 URI 存储路径
     */
    private String responseUri;

    /**
     * 是否执行成功：0-失败, 1-成功
     */
    private Integer isSuccess;

    /**
     * 大模型 API 请求报错或失败的具体原因记录
     */
    private String errorReason;

    /**
     * 本次大模型调用的网络耗时时长（单位：毫秒）
     */
    private Long durationMs;

    /** 调用阶段标识：MODULE_HIERARCHY / GENERATING_DOC 等 */
    private String callStage;
}
