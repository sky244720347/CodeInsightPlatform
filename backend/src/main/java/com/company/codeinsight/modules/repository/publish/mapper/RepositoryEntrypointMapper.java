package com.company.codeinsight.modules.repository.publish.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.codeinsight.modules.repository.publish.entity.RepositoryEntrypointEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 仓库已发布入口 Mapper。方案 B：覆盖写 = {@link #deleteByRepositoryId} + {@code insert}。
 */
@Mapper
public interface RepositoryEntrypointMapper extends BaseMapper<RepositoryEntrypointEntity> {

    @Select("SELECT * FROM ci_repository_entrypoint WHERE repository_id = #{repositoryId} AND is_deleted = 0 ORDER BY sort_order ASC, id ASC")
    List<RepositoryEntrypointEntity> selectByRepositoryId(@Param("repositoryId") Long repositoryId);

    /** 逻辑删除仓库下全部已发布入口（禁止物理 DELETE） */
    @Update("UPDATE ci_repository_entrypoint SET is_deleted = 1, updated_date = CURRENT_TIMESTAMP " +
            "WHERE repository_id = #{repositoryId} AND is_deleted = 0")
    int deleteByRepositoryId(@Param("repositoryId") Long repositoryId);
}
