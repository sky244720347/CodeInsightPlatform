package com.company.codeinsight.modules.draft.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 草稿关联代码来源引用实体类
 * 对应数据库中的 ci_draft_source_reference 表，用于建立知识模块草稿文档与被解析切片源码文件及行号区间的双向追溯引用链。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_draft_source_reference")
public class DraftSourceReference extends BaseEntity {

    /**
     * 自增主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联的知识草稿 ID
     */
    private Long draftId;

    /**
     * 该模块分析对应的物理源码文件相对路径
     */
    private String filePath;

    /**
     * 所引用代码在源文件中的起始行号
     */
    private Integer startLine;

    /**
     * 所引用代码在源文件中的结束行号
     */
    private Integer endLine;

    /** 入口类全限定名（可选） */
    private String className;

    /** 方法签名 methodName(ParamTypes)，不含返回类型（可选） */
    private String methodSignature;

    /** ROOT=binding 入口；REACHABLE=BFS 下游（含同类助手） */
    private String refKind;

    /** BFS 发现序（从 0 起），代码来源列表排序用 */
    private Integer bfsOrder;
}
