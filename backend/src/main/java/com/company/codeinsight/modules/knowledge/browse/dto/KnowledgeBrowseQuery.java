package com.company.codeinsight.modules.knowledge.browse.dto;

import lombok.Data;

/**
 * 知识查看列表查询入参。
 * <p>所有字段除 systemId 外都可为空；为空表示不施加该维度过滤。</p>
 */
@Data
public class KnowledgeBrowseQuery {

    /** 可选：按系统聚合；为空表示跨全部系统（列表模式） */
    private Long systemId;

    /** 可选：按仓库过滤 */
    private Long repositoryId;

    /** 分页页码，从 1 开始，默认 1 */
    private Long current = 1L;

    /** 分页大小，默认 20 */
    private Long size = 20L;

    /** 文件类型过滤：DRAFT / INDEX / MANIFEST / ALL（默认 ALL） */
    private String type;

    /** 简单搜索关键字：按文件名 LIKE '%keyword%'（不区分大小写） */
    private String keyword;

    /** 可选：限定具体任务 ID */
    private Long taskId;

    /** 可选：限定具体知识版本 ID（用于按版本过滤） */
    private Long versionId;

    /** 可选：草稿状态过滤（DRAFT / EDITING / CONFIRMED / PUSHED / ARCHIVED），仅 type=DRAFT 时生效 */
    private String status;

    /** 可选：updatedDate 下界（ISO 字符串；按更新时间筛） */
    private String createdDateStart;

    /** 可选：updatedDate 上界（ISO 字符串；按更新时间筛） */
    private String createdDateEnd;
}