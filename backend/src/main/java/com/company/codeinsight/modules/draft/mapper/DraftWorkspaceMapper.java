package com.company.codeinsight.modules.draft.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.codeinsight.modules.draft.entity.DraftWorkspace;
import org.apache.ibatis.annotations.Mapper;

/**
 * 评审工作区 Mapper。方案 B：活行唯一 {@code uk_draft_workspace_task_active}；
 * 无活行时 {@code insert}（逻辑删后腾键）。
 */
@Mapper
public interface DraftWorkspaceMapper extends BaseMapper<DraftWorkspace> {
}
