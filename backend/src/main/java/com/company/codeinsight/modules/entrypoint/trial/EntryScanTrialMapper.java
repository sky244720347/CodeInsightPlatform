package com.company.codeinsight.modules.entrypoint.trial;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EntryScanTrialMapper extends BaseMapper<EntryScanTrialEntity> {

    @Select("SELECT DISTINCT repository_id FROM ci_entry_scan_trial WHERE is_deleted = 0")
    List<Long> listDistinctRepositoryIds();
}
