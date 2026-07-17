package com.company.codeinsight.modules.hierarchy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.codeinsight.modules.hierarchy.entity.ModuleHierarchyNode;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 模块层级节点 Mapper
 * <p>方案 B：活行唯一 {@code uk_module_hierarchy_task_node_active}；覆盖写 = 逻辑删 + plain INSERT。</p>
 */
@Mapper
public interface ModuleHierarchyNodeMapper extends BaseMapper<ModuleHierarchyNode> {

    /**
     * 按任务逻辑删除全部节点（覆盖写前清理；禁止物理 DELETE）。
     */
    @Update("UPDATE ci_module_hierarchy SET is_deleted = 1, updated_date = CURRENT_TIMESTAMP " +
            "WHERE task_id = #{taskId} AND is_deleted = 0")
    int deleteByTaskId(@Param("taskId") Long taskId);

    /**
     * 多行批量 INSERT（调用方须先逻辑删腾出活行唯一键）。
     */
    @Insert("<script>" +
            "INSERT INTO ci_module_hierarchy (task_id, system_id, level, parent_id, node_id, name, keywords, class_paths, method_signatures, confirmed, source_entry_class, is_deleted, created_by, updated_by, created_date, updated_date) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.taskId}, #{item.systemId}, #{item.level}, #{item.parentId}, #{item.nodeId}, #{item.name}, " +
            " #{item.keywords}, #{item.classPaths}, #{item.methodSignatures}, " +
            " #{item.confirmed}, #{item.sourceEntryClass}, 0, 'sys', 'sys', #{item.createdDate}, #{item.updatedDate})" +
            "</foreach>" +
            "</script>")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int batchInsert(@Param("list") List<ModuleHierarchyNode> list);

    /**
     * @deprecated 勿再调用：原样复制 parent_id（基线自增主键）会导致本任务 loadByTaskId 子树挂不上。
     * 请使用 {@link com.company.codeinsight.modules.scanner.service.BaselineInheritanceService#inheritModuleHierarchy}。
     */
    @Deprecated
    @Insert("INSERT INTO ci_module_hierarchy " +
            "(task_id, system_id, level, parent_id, node_id, name, keywords, class_paths, method_signatures, confirmed, source_entry_class, created_date, updated_date) " +
            "SELECT " +
            "  #{currentTaskId}, system_id, level, parent_id, node_id, name, keywords, class_paths, method_signatures, confirmed, source_entry_class, created_date, updated_date " +
            "FROM ci_module_hierarchy " +
            "WHERE task_id = #{baselineTaskId} AND is_deleted = 0")
    int inheritFromBaseline(@Param("currentTaskId") Long currentTaskId,
                            @Param("baselineTaskId") Long baselineTaskId);

    /**
     * v1: 按 taskId + sourceEntryClass 逻辑删除 FUNCTION 节点（retarget 前清理）。
     */
    @Update("UPDATE ci_module_hierarchy SET is_deleted = 1, updated_date = CURRENT_TIMESTAMP " +
            "WHERE task_id = #{taskId} AND source_entry_class = #{sourceEntryClass} " +
            "AND level = 'FUNCTION' AND is_deleted = 0")
    int deleteByTaskIdAndSourceEntryClass(@Param("taskId") Long taskId,
                                           @Param("sourceEntryClass") String sourceEntryClass);

    /**
     * 按 taskId + node_id 集合逻辑删除（预处理剔除「已删入口」整模块树时使用）。
     */
    @Update("<script>" +
            "UPDATE ci_module_hierarchy SET is_deleted = 1, updated_date = CURRENT_TIMESTAMP " +
            "WHERE task_id = #{taskId} AND is_deleted = 0 AND node_id IN " +
            "<foreach collection='nodeIds' item='nid' open='(' separator=',' close=')'>#{nid}</foreach>" +
            "</script>")
    int deleteByTaskIdAndNodeIds(@Param("taskId") Long taskId,
                                 @Param("nodeIds") java.util.Collection<String> nodeIds);

}
