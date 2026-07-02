package com.company.codeinsight.modules.businessknowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.codeinsight.modules.businessknowledge.entity.BusinessKnowledge;
import org.apache.ibatis.annotations.Mapper;

/**
 * 业务知识配置数据持久层 Mapper 接口
 * <p>继承 MyBatis-Plus 的 {@link BaseMapper}，实现对 {@code ci_business_knowledge} 表的常规 CRUD。</p>
 */
@Mapper
public interface BusinessKnowledgeMapper extends BaseMapper<BusinessKnowledge> {
}
