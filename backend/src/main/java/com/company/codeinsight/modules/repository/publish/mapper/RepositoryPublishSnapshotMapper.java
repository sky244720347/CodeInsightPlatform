package com.company.codeinsight.modules.repository.publish.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.codeinsight.modules.repository.publish.entity.RepositoryPublishSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RepositoryPublishSnapshotMapper extends BaseMapper<RepositoryPublishSnapshot> {

    @Select("SELECT * FROM ci_repository_publish_snapshot WHERE version_id = #{versionId} LIMIT 1")
    RepositoryPublishSnapshot selectByVersionId(@Param("versionId") Long versionId);

    @Select("SELECT * FROM ci_repository_publish_snapshot WHERE repository_id = #{repositoryId} ORDER BY published_at DESC")
    List<RepositoryPublishSnapshot> selectByRepositoryId(@Param("repositoryId") Long repositoryId);
}
