package com.company.codeinsight.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.vo.SystemSummaryVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 业务系统应用持久层 Mapper 接口
 * 继承 MyBatis-Plus 的 BaseMapper，实现对 ci_system 数据库表的常规单表 CRUD。
 */
@Mapper
public interface SystemApplicationMapper extends BaseMapper<SystemApplication> {

    /**
     * 一次性返回系统列表及 3 个聚合指标（代码库数 / 知识版本数 / 最近扫描时间）
     * 对应 XML：SystemApplicationMapper.xml
     * <p>当 {@code hasPublished} 为 true 时，仅返回至少存在一个已发布仓库（last_published_version_id IS NOT NULL）的系统。</p>
     */
    List<SystemSummaryVO> listSystemsWithSummary(@Param("name") String name,
                                                 @Param("owner") String owner,
                                                 @Param("hasPublished") Boolean hasPublished);
}

