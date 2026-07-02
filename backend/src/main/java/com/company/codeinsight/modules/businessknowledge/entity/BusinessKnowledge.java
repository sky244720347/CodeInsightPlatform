package com.company.codeinsight.modules.businessknowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 业务知识配置实体类
 * <p>对应数据库中的 {@code ci_business_knowledge} 表，按系统维度维护 1 份业务知识 Markdown 正文，</p>
 * <p>用于在 AI 调用前替换模块提取提示词中的 {@code {business_knowledge.md}} 占位符，</p>
 * <p>辅助 AI 更精准地匹配和提取业务模块功能。</p>
 *
 * <p>约束：</p>
 * <ul>
 *     <li>{@code systemId} UNIQUE：1 个系统对应 1 份配置</li>
 *     <li>覆盖式保存：每次保存 {@code version} 自增 1，{@code content} 整体替换</li>
 *     <li>无历史版本：靠备份/审计外手段恢复</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_business_knowledge")
public class BusinessKnowledge extends BaseEntity {

    /**
     * 自增主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属业务系统 ID（FK → ci_system.id，UNIQUE）
     */
    private Long systemId;

    /**
     * 业务知识 Markdown 正文
     * <p>写入到提示词模板的 {@code {business_knowledge.md}} 占位符位置。</p>
     */
    private String content;

    /**
     * 保存次数（每次保存 +1）
     * <p>仅作审计计数，不参与版本回滚。</p>
     */
    private Integer version;

    /**
     * 最后修改人（来自会话用户）
     */
    private String updatedBy;
}
