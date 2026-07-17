package com.company.codeinsight.modules.draft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.codeinsight.modules.draft.entity.DraftSourceReference;
import org.apache.ibatis.annotations.Mapper;

/**
 * 草稿关联代码来源数据持久层 Mapper 接口
 * 继承 MyBatis-Plus 的 BaseMapper，实现对 ci_draft_source_reference 表的 CRUD 操作。
 * <p>无业务 UK：覆盖写使用 MP 逻辑删除 + 新 insert（禁止物理 DELETE）。</p>
 */
@Mapper
public interface DraftSourceReferenceMapper extends BaseMapper<DraftSourceReference> {
}
