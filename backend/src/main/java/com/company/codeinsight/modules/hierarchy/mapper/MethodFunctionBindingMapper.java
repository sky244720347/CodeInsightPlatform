package com.company.codeinsight.modules.hierarchy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.codeinsight.modules.hierarchy.entity.MethodFunctionBinding;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/**
 * 方法→功能 反向绑定 Mapper
 * <p>对应 {@code ci_method_function_binding}；方案 B：活行唯一
 * {@code uk_mfb_task_class_method_active}；覆盖写 = 逻辑删 + plain INSERT（禁止 ON CONFLICT upsert）。</p>
 */
@Mapper
public interface MethodFunctionBindingMapper extends BaseMapper<MethodFunctionBinding> {

    /**
     * 单条 INSERT（调用方须先逻辑删腾出活行唯一键）。
     */
    @Insert("INSERT INTO ci_method_function_binding " +
            "(task_id, system_id, module_node_id, sub_module_node_id, function_node_id, " +
            " class_name, method_signature, source, confidence, created_date, updated_date) " +
            "VALUES " +
            "(#{taskId}, #{systemId}, #{moduleNodeId}, #{subModuleNodeId}, #{functionNodeId}, " +
            " #{className}, #{methodSignature}, #{source}, #{confidence}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
    int insertBinding(MethodFunctionBinding binding);

    /**
     * 批量 INSERT（调用方须先逻辑删腾出活行唯一键）。
     */
    @Insert("<script>" +
            "INSERT INTO ci_method_function_binding " +
            "(task_id, system_id, module_node_id, sub_module_node_id, function_node_id, " +
            " class_name, method_signature, source, confidence, created_date, updated_date) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.taskId}, #{item.systemId}, #{item.moduleNodeId}, #{item.subModuleNodeId}, #{item.functionNodeId}, " +
            " #{item.className}, #{item.methodSignature}, #{item.source}, #{item.confidence}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)" +
            "</foreach>" +
            "</script>")
    int batchInsertBindings(@Param("list") List<MethodFunctionBinding> list);

    /**
     * 按任务 + 功能查询该功能的所有入口方法绑定（用于 Phase 2 BFS 根方法）。
     */
    @Select("SELECT * FROM ci_method_function_binding " +
            "WHERE task_id = #{taskId} AND function_node_id = #{functionNodeId} AND is_deleted = 0 " +
            "ORDER BY class_name, method_signature")
    List<MethodFunctionBinding> selectByTaskAndFunction(@Param("taskId") Long taskId,
                                                       @Param("functionNodeId") String functionNodeId);

    /**
     * 按任务 + 类查询该入口类的所有方法绑定（用于知识入口复核页）。
     */
    @Select("SELECT * FROM ci_method_function_binding " +
            "WHERE task_id = #{taskId} AND class_name = #{className} AND is_deleted = 0 " +
            "ORDER BY method_signature")
    List<MethodFunctionBinding> selectByTaskAndClass(@Param("taskId") Long taskId,
                                                    @Param("className") String className);

    /**
     * 按任务 + 模块查询所有方法绑定（用于模块详情 / 影响面聚合）。
     */
    @Select("SELECT * FROM ci_method_function_binding " +
            "WHERE task_id = #{taskId} AND module_node_id = #{moduleNodeId} AND is_deleted = 0 " +
            "ORDER BY sub_module_node_id, function_node_id, class_name, method_signature")
    List<MethodFunctionBinding> selectByTaskAndModule(@Param("taskId") Long taskId,
                                                      @Param("moduleNodeId") String moduleNodeId);

    /**
     * 全量逻辑删除（任务重跑 AI 前）。禁止物理 DELETE。
     */
    @Update("UPDATE ci_method_function_binding SET is_deleted = 1, updated_date = CURRENT_TIMESTAMP " +
            "WHERE task_id = #{taskId} AND is_deleted = 0")
    int deleteByTaskId(@Param("taskId") Long taskId);

    /**
     * 按任务 + 功能节点集合逻辑删除（入口维度重写 binding 前腾键）。
     */
    @Update("<script>" +
            "UPDATE ci_method_function_binding SET is_deleted = 1, updated_date = CURRENT_TIMESTAMP " +
            "WHERE task_id = #{taskId} AND is_deleted = 0 AND function_node_id IN " +
            "<foreach collection='functionNodeIds' item='fid' open='(' separator=',' close=')'>#{fid}</foreach>" +
            "</script>")
    int deleteByTaskIdAndFunctionNodeIds(@Param("taskId") Long taskId,
                                         @Param("functionNodeIds") Collection<String> functionNodeIds);

    /**
     * 按即将写入的 (class_name, method_signature) 逻辑删除活行，避免跨 function 搬迁时撞
     * {@code uk_mfb_task_class_method_active}。
     */
    @Update("<script>" +
            "UPDATE ci_method_function_binding SET is_deleted = 1, updated_date = CURRENT_TIMESTAMP " +
            "WHERE task_id = #{taskId} AND is_deleted = 0 AND (" +
            "<foreach collection='list' item='item' separator=' OR '>" +
            "(class_name = #{item.className} AND method_signature = #{item.methodSignature})" +
            "</foreach>)" +
            "</script>")
    int deleteByTaskIdAndClassMethodKeys(@Param("taskId") Long taskId,
                                         @Param("list") List<MethodFunctionBinding> list);
}
