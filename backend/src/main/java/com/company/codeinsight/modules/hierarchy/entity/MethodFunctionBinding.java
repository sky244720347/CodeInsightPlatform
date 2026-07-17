package com.company.codeinsight.modules.hierarchy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 方法 → 功能 反向绑定实体。
 * <p>对应 {@code ci_method_function_binding} 表；hierarchy 阶段 AI 输出按方法粒度落表，
 * 每方法 1 行，由 {@code (task_id, class_name, method_signature)} 唯一定位到
 * {@code (module_node_id, sub_module_node_id, function_node_id)}。</p>
 *
 * <p>设计动机：在原 {@code ci_module_hierarchy.method_signatures}（function 持有方法清单）
 * 模型里，AI 漏输出时被回填成入口类全集，BFS 出大杂烩；本表把方法级归属做成反向索引，
 * 文档生成阶段按 (function_node_id) 反查 BFS 根方法即可，不再依赖 function 节点的方法清单。</p>
 *
 * @see com.company.codeinsight.modules.hierarchy.entity.ModuleHierarchyNode
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_method_function_binding")
public class MethodFunctionBinding extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联任务 ID（FK → ci_task.id） */
    private Long taskId;

    /** 冗余系统 ID，便于按系统维度查询 */
    private Long systemId;

    /** 所属模块节点 ID（m 前缀） */
    @TableField("module_node_id")
    private String moduleNodeId;

    /** 所属子模块节点 ID（s 前缀） */
    @TableField("sub_module_node_id")
    private String subModuleNodeId;

    /** 所属功能节点 ID（f 前缀） */
    @TableField("function_node_id")
    private String functionNodeId;

    /** 入口类全限定名，如 com.example.UserController */
    @TableField("class_name")
    private String className;

    /**
     * 方法签名 methodName(ParamType1,ParamType2)（不含返回类型）。
     * 风格与 ci_method_call.caller_signature 拆分后的函数名部分一致，
     * 便于按方法级反查调用链（拼上 class_name + "#" 即得到 caller_signature）。
     */
    @TableField("method_signature")
    private String methodSignature;

    /** 归属来源：AI / USER / MIGRATED */
    private String source;

    /** AI 输出的归属置信度（0-1，可空） */
    private BigDecimal confidence;
}
