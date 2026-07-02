package com.company.codeinsight.modules.repository.publish.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.codeinsight.modules.repository.publish.entity.RepositoryEntrypointEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RepositoryEntrypointMapper extends BaseMapper<RepositoryEntrypointEntity> {

    @Select("SELECT * FROM ci_repository_entrypoint WHERE repository_id = #{repositoryId} ORDER BY sort_order ASC, id ASC")
    List<RepositoryEntrypointEntity> selectByRepositoryId(@Param("repositoryId") Long repositoryId);

    @Delete("DELETE FROM ci_repository_entrypoint WHERE repository_id = #{repositoryId}")
    int deleteByRepositoryId(@Param("repositoryId") Long repositoryId);
}
