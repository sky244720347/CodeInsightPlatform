package com.company.codeinsight.modules.repository.publish.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.codeinsight.modules.repository.publish.entity.RepositoryModuleHierarchyNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 仓库已发布模块层级 Mapper。方案 B：覆盖写 = {@link #deleteByRepositoryId} + {@code insert}
 * （useGeneratedKeys 由 BaseMapper.insert 回填 id）。
 */
@Mapper
public interface RepositoryModuleHierarchyMapper extends BaseMapper<RepositoryModuleHierarchyNode> {

    @Select("SELECT * FROM ci_repository_module_hierarchy WHERE repository_id = #{repositoryId} AND is_deleted = 0 ORDER BY id ASC")
    List<RepositoryModuleHierarchyNode> selectByRepositoryId(@Param("repositoryId") Long repositoryId);

    /** 逻辑删除仓库下全部已发布层级（禁止物理 DELETE） */
    @Update("UPDATE ci_repository_module_hierarchy SET is_deleted = 1, updated_date = CURRENT_TIMESTAMP " +
            "WHERE repository_id = #{repositoryId} AND is_deleted = 0")
    int deleteByRepositoryId(@Param("repositoryId") Long repositoryId);
}
