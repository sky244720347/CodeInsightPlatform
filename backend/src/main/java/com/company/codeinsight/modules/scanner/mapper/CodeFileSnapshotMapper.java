package com.company.codeinsight.modules.scanner.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.codeinsight.modules.scanner.entity.CodeFileSnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * 代码扫描文件快照数据持久层 Mapper 接口。
 * <p>无业务 UK：覆盖写使用 MP 逻辑删除 + 新 insert（禁止物理 DELETE）。</p>
 */
@Mapper
public interface CodeFileSnapshotMapper extends BaseMapper<CodeFileSnapshot> {
}
