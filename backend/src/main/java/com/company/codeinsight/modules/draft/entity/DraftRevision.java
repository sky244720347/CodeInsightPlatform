package com.company.codeinsight.modules.draft.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 草稿修订版本历史实体类
 * 对应数据库中的 ci_draft_revision 表，记录对某篇模块草稿的每一次保存修改的历史版本信息及差分描述。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_draft_revision")
public class DraftRevision extends BaseEntity {

    /**
     * 自增主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联的草稿 ID
     */
    private Long draftId;

    /**
     * 该次修订版本所对应在磁盘存储的物理 URI 路径
     */
    private String contentUri;

    /**
     * 做出本次修改保存的负责人用户名
     */
    private String author;

    /**
     * 本次修改的说明备注（通常自动附带增减行数的差异统计）
     */
    private String remark;
}
