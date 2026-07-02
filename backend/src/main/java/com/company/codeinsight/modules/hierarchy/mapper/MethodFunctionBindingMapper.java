package com.company.codeinsight.modules.hierarchy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.codeinsight.modules.hierarchy.entity.MethodFunctionBinding;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 方法→功能 反向绑定 Mapper
 * <p>对应 {@code ci_method_function_binding}；提供 upsert、按 function 查询、全量清理（重跑时）三个核心能力。</p>
 *
 * <p>使用 PostgreSQL 原生 {@code INSERT ... ON CONFLICT ... DO UPDATE} 实现 upsert，
 * 单条 SQL 完成 (task_id, class_name, method_signature) 唯一键去重。</p>
 */
@Mapper
public interface MethodFunctionBindingMapper extends BaseMapper<MethodFunctionBinding> {

    /**
     * 单条 UPSERT：冲突时按 function 更新，updated_at 同步刷新。
     */
    @Insert("INSERT INTO ci_method_function_binding " +
            "(task_id, system_id, module_node_id, sub_module_node_id, function_node_id, " +
            " class_name, method_signature, source, confidence, created_at, updated_at) " +
            "VALUES " +
            "(#{taskId}, #{systemId}, #{moduleNodeId}, #{subModuleNodeId}, #{functionNodeId}, " +
            " #{className}, #{methodSignature}, #{source}, #{confidence}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) " +
            "ON CONFLICT (task_id, class_name, method_signature) DO UPDATE SET " +
            "  system_id          = EXCLUDED.system_id, " +
            "  module_node_id     = EXCLUDED.module_node_id, " +
            "  sub_module_node_id = EXCLUDED.sub_module_node_id, " +
            "  function_node_id   = EXCLUDED.function_node_id, " +
            "  source             = EXCLUDED.source, " +
            "  confidence         = EXCLUDED.confidence, " +
            "  updated_at         = CURRENT_TIMESTAMP " +
            "WHERE EXCLUDED.source IN ('AI', 'USER') " +
            "   OR ci_method_function_binding.source = 'MIGRATED'")
    int upsertBinding(MethodFunctionBinding binding);

    /**
     * 批量 UPSERT：基于单条 UPSERT 的 foreach 展开；SQL 通过 {@code <script>} 标签转义。
     */
    @Insert("<script>" +
            "INSERT INTO ci_method_function_binding " +
            "(task_id, system_id, module_node_id, sub_module_node_id, function_node_id, " +
            " class_name, method_signature, source, confidence, created_at, updated_at) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.taskId}, #{item.systemId}, #{item.moduleNodeId}, #{item.subModuleNodeId}, #{item.functionNodeId}, " +
            " #{item.className}, #{item.methodSignature}, #{item.source}, #{item.confidence}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)" +
            "</foreach>" +
            "ON CONFLICT (task_id, class_name, method_signature) DO UPDATE SET " +
            "  system_id          = EXCLUDED.system_id, " +
            "  sub_module_node_id = EXCLUDED.sub_module_node_id, " +
            "  function_node_id   = EXCLUDED.function_node_id, " +
            "  source             = EXCLUDED.source, " +
            "  confidence         = EXCLUDED.confidence, " +
            "  updated_at         = CURRENT_TIMESTAMP " +
            "WHERE EXCLUDED.source IN ('AI', 'USER') " +
            "   OR ci_method_function_binding.source = 'MIGRATED'" +
            "</script>")
    int batchUpsertBindings(@Param("list") List<MethodFunctionBinding> list);

    /**
     * 按任务 + 功能查询该功能的所有入口方法绑定（用于 Phase 2 BFS 根方法）。
     */
    @Select("SELECT * FROM ci_method_function_binding " +
            "WHERE task_id = #{taskId} AND function_node_id = #{functionNodeId} " +
            "ORDER BY class_name, method_signature")
    List<MethodFunctionBinding> selectByTaskAndFunction(@Param("taskId") Long taskId,
                                                       @Param("functionNodeId") String functionNodeId);

    /**
     * 按任务 + 类查询该入口类的所有方法绑定（用于知识入口复核页）。
     */
    @Select("SELECT * FROM ci_method_function_binding " +
            "WHERE task_id = #{taskId} AND class_name = #{className} " +
            "ORDER BY method_signature")
    List<MethodFunctionBinding> selectByTaskAndClass(@Param("taskId") Long taskId,
                                                    @Param("className") String className);

    /**
     * 按任务 + 模块查询所有方法绑定（用于模块详情 / 影响面聚合）。
     */
    @Select("SELECT * FROM ci_method_function_binding " +
            "WHERE task_id = #{taskId} AND module_node_id = #{moduleNodeId} " +
            "ORDER BY sub_module_node_id, function_node_id, class_name, method_signature")
    List<MethodFunctionBinding> selectByTaskAndModule(@Param("taskId") Long taskId,
                                                      @Param("moduleNodeId") String moduleNodeId);

    /**
     * 全量清理（任务重跑 AI 前）。
     */
    @Delete("DELETE FROM ci_method_function_binding WHERE task_id = #{taskId}")
    int deleteByTaskId(@Param("taskId") Long taskId);
}
