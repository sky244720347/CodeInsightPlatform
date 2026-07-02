package com.company.codeinsight.modules.hierarchy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.codeinsight.modules.hierarchy.entity.ModuleHierarchyNode;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 模块层级节点 Mapper
 */
@Mapper
public interface ModuleHierarchyNodeMapper extends BaseMapper<ModuleHierarchyNode> {

    /**
     * 多行批量 INSERT（单条 SQL 多 VALUES），利用 PostgreSQL 自动返回生成的 id 填充到实体。
     * 替代逐条 insert + 回表 SELECT，将两级 DB 往返合并为一次。
     */
    @Insert("<script>" +
            "INSERT INTO ci_module_hierarchy (task_id, system_id, level, parent_id, node_id, name, keywords, class_paths, method_signatures, confirmed, created_at, updated_at) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.taskId}, #{item.systemId}, #{item.level}, #{item.parentId}, #{item.nodeId}, #{item.name}, #{item.keywords}, #{item.classPaths}, #{item.methodSignatures}, #{item.confirmed}, #{item.createdAt}, #{item.updatedAt})" +
            "</foreach>" +
            "</script>")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int batchInsert(@Param("list") List<ModuleHierarchyNode> list);
}