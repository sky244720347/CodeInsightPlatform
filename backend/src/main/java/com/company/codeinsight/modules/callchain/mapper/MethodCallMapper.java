package com.company.codeinsight.modules.callchain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.codeinsight.modules.callchain.entity.MethodCall;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 方法调用链路 Mapper 接口
 * 继承 MyBatis-Plus BaseMapper，自动获得单表 CRUD 能力。
 */
@Mapper
public interface MethodCallMapper extends BaseMapper<MethodCall> {

    /**
     * 给定 candidate full signatures（{@code className#methodName(ParamTypes)}），
     * 返回该任务下 {@code ci_method_call.caller_signature} 列实际存在的子集。
     * <p>用于方法→功能反向绑定时的存在性交叉校验：
     * AI 在 {@code functions[].class_paths × method_signatures} 笛卡尔积出的 (class, sig) 元组，
     * 如果该类根本没有这个方法（即没有 caller 边）则剔除，避免鬼魂绑定。</p>
     *
     * <p>典型调用：传入约 200 条候选 full signature，返回这些中真正能在 ci_method_call
     * 找到 caller 边的子集。</p>
     *
     * @param taskId          关联任务 ID（已落表的 ci_method_call.task_id）
     * @param fullSignatures  候选 full signature，格式 "className#methodName(ParamTypes)"
     * @return 数据库中实际存在的 caller_signature 列表
     */
    @Select("<script>" +
            "SELECT DISTINCT caller_signature FROM ci_method_call " +
            "WHERE task_id = #{taskId} " +
            "AND caller_signature IN " +
            "<foreach collection='fullSignatures' item='sig' open='(' separator=',' close=')'>" +
            "#{sig}" +
            "</foreach>" +
            "</script>")
    List<String> selectExistingCallerSignatures(@Param("taskId") Long taskId,
                                                 @Param("fullSignatures") List<String> fullSignatures);
}