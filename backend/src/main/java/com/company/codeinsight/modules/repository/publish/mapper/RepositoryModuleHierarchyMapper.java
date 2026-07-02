package com.company.codeinsight.modules.repository.publish.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.codeinsight.modules.repository.publish.entity.RepositoryModuleHierarchyNode;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RepositoryModuleHierarchyMapper extends BaseMapper<RepositoryModuleHierarchyNode> {

    @Select("SELECT * FROM ci_repository_module_hierarchy WHERE repository_id = #{repositoryId} ORDER BY id ASC")
    List<RepositoryModuleHierarchyNode> selectByRepositoryId(@Param("repositoryId") Long repositoryId);

    @Delete("DELETE FROM ci_repository_module_hierarchy WHERE repository_id = #{repositoryId}")
    int deleteByRepositoryId(@Param("repositoryId") Long repositoryId);
}
