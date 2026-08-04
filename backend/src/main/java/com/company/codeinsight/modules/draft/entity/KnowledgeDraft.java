package com.company.codeinsight.modules.draft.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 模块知识草稿实体类
 * 对应数据库中的 ci_knowledge_draft 表，记录从代码分片（Chunk）总结聚合而成的模块级 Markdown 文档草稿的修订状态与存储路径。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_knowledge_draft")
public class KnowledgeDraft extends BaseEntity {

    /**
     * 自增主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属的评审工作区 ID
     */
    private Long workspaceId;

    /**
     * 父级草稿 ID（自引用）。NULL 表示该草稿是模块目录树的根节点。
     * 用于在数据库中持久化"目录 → 模块 → 子模块"的层级关系。
     */
    private Long parentId;

    /**
     * 草稿 Markdown 相对文档路径
     */
    private String filePath;

    /**
     * 业务知识子模块的唯一分组名称
     */
    private String moduleName;

    /**
     * 正文 Markdown 内容文件在磁盘上的物理 URI 路径
     */
    private String contentUri;

    /**
     * 草稿流转状态：与 ci_task.status 词汇解耦，仅描述文档自身生命周期。
     * 取值见 {@link com.company.codeinsight.modules.draft.enums.DraftStatus}：
     * DRAFT-AI 已生成待处理, REGENERATING-单篇异步重跑中, EDITING-复核人已编辑,
     * CONFIRMED-已确认, PUSHED-已推送, ARCHIVED-已归档。
     * 流水线落库亦可能使用 AI_GENERATED / PENDING_REVIEW 字面值（与枚举并存，前端按字典展示）。
     * 注意：历史上曾与任务状态共用 PENDING_REVIEW / REVIEWING / REVISED 字面值；
     * 自 v0.2 起统一改用本枚举，存量数据由 schema.sql 末尾的幂等 UPDATE 完成迁移。
     */
    private String status;

    /**
     * 同级排序权重（升序）。值越小越靠前。默认为 0。
     */
    private Integer sortOrder;

    /**
     * 文档内容的哈希校验和（MD5），用于增量检测与版本比较
     */
    private String hash;

    /**
     * v1: INCREMENTAL 任务基线继承（NULL=本次生成；非空=从该基线任务继承）
     * <p>AI 重生成覆盖继承草稿时必须显式置 null 落库，故 updateStrategy=ALWAYS
     * （MyBatis-Plus 默认忽略 null，会导致 DIFF 仍分桶为「继承」）。</p>
     */
    @TableField(value = "baseline_task_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long baselineTaskId;

    /**
     * 功能节点 ID（f 前缀），与 {@code ci_method_function_binding.function_node_id} 对齐；
     * function 粒度草稿生成时写入，单篇「重跑此篇」定位用。
     */
    private String functionNodeId;

    /**
     * 正文最后一次由 AI/流水线生成的时间。
     * <p>继承文档保留基线原文时间；人工编辑不刷新；重跑成功刷新。</p>
     */
    @TableField("generated_at")
    private java.time.LocalDateTime generatedAt;
}
