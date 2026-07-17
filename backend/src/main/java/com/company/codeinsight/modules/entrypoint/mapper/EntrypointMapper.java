package com.company.codeinsight.modules.entrypoint.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.codeinsight.modules.entrypoint.entity.EntrypointEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 知识入口复核表 mapper（对应 ci_entrypoint）
 * <p>方案 B：活行唯一 {@code uk_entrypoint_task_class_active}；覆盖写 = 逻辑删 + insert。</p>
 */
@Mapper
public interface EntrypointMapper extends BaseMapper<EntrypointEntity> {

    /**
     * 按 taskId 列出所有入口行（按 sort_order, id 升序）
     */
    @Select("SELECT * FROM ci_entrypoint WHERE task_id = #{taskId} AND is_deleted = 0 ORDER BY sort_order ASC, id ASC")
    List<EntrypointEntity> selectByTaskId(@Param("taskId") Long taskId);

    /**
     * 按 taskId 逻辑删除该任务下全部入口（覆盖写前清理；禁止物理 DELETE）。
     */
    @Update("UPDATE ci_entrypoint SET is_deleted = 1, updated_date = CURRENT_TIMESTAMP " +
            "WHERE task_id = #{taskId} AND is_deleted = 0")
    int deleteByTaskId(@Param("taskId") Long taskId);

    /**
     * v1: 从基线任务继承入口数据到本任务。
     * <p>仅复制基线任务中 file_path 不在 excludedPaths 集合内的行；
     * 复制时把 task_id 改成 currentTaskId，并保留每行自身的 baseline_task_id
     * （用于审计追溯源头）。方案 B：plain INSERT…SELECT，源端过滤 is_deleted=0。</p>
     */
    @Insert("<script>" +
            "INSERT INTO ci_entrypoint " +
            "(task_id, system_id, class_name, file_path, entry_type, annotation, remark, methods_json, sort_order, baseline_task_id, created_date, updated_date) " +
            "SELECT " +
            "  #{currentTaskId}, system_id, class_name, file_path, entry_type, annotation, remark, methods_json, sort_order, " +
            "  #{baselineTaskId}, created_date, updated_date " +
            "FROM ci_entrypoint " +
            "WHERE task_id = #{baselineTaskId} " +
            "AND is_deleted = 0 " +
            "<if test='excludedPaths != null and excludedPaths.size() &gt; 0'>" +
            "AND (file_path IS NULL OR file_path NOT IN " +
            "<foreach collection='excludedPaths' item='p' open='(' separator=',' close=')'>#{p}</foreach>)" +
            "</if>" +
            "</script>")
    int inheritFromBaseline(@Param("currentTaskId") Long currentTaskId,
                            @Param("baselineTaskId") Long baselineTaskId,
                            @Param("excludedPaths") java.util.Collection<String> excludedPaths);
}
