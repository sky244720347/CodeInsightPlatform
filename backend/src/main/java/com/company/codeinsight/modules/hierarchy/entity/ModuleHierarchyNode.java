package com.company.codeinsight.modules.hierarchy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 模块层级持久化实体（3 行结构通用）
 * 对应 ci_module_hierarchy 表：用 level + parent_id 树形表示模块/子模块/功能三级。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_module_hierarchy")
public class ModuleHierarchyNode extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联任务 ID */
    private Long taskId;

    /** 关联系统 ID */
    private Long systemId;

    /** 层级：MODULE / SUB_MODULE / FUNCTION */
    private String level;

    /** 上级节点 ID（module.parent_id = NULL） */
    private Long parentId;

    /** 5位 Base62 节点 ID（m/s/f 前缀），同任务内唯一 */
    private String nodeId;

    /** 名称（模块名 / 子模块名 / 功能名） */
    private String name;

    /** 关键词 JSON 数组字符串 */
    private String keywords;

    /** 入口类全限定名集合（仅 FUNCTION 级）JSON 数组字符串 */
    private String classPaths;

    /**
     * 该功能涉及的方法签名 JSON 数组字符串（仅 FUNCTION 级）
     * 由 AI 在模块提取阶段输出，格式 ["methodName(ParamType1, ParamType2)"]（不含返回类型）
     * 用于阶段 2 按方法签名粒度反查调用链喂 AI 文档生成
     */
    @TableField("method_signatures")
    private String methodSignatures;

    /**
     * 人工逐项复核确认标记：TRUE=已被用户在 MODULE_HIERARCHY_REVIEW 复核页勾选为已确认；
     * FALSE=未确认（默认）。仅作为审计痕迹，不影响 AI 流程。
     */
    @TableField("confirmed")
    private Boolean confirmed;

    /**
     * v1: FUNCTION 节点关联的入口类全限定名（用于按 entry 维度增量清理）
     */
    @TableField("source_entry_class")
    private String sourceEntryClass;
}
