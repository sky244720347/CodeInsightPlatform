package com.company.codeinsight.modules.callchain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 方法调用链路实体类
 * 对应数据库中的 ci_method_call 表，记录 AST 静态解析得到的类内方法与方法之间的调用关系明细。
 * 由知识构建任务在 PARSING_CODE 阶段批量写入，作为后续入口识别和模块整体归纳的数据基础。
 */
@Data
@TableName("ci_method_call")
public class MethodCall {

    /**
     * 自增主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属反编译分析任务 ID
     */
    private Long taskId;

    /**
     * 源文件相对路径（如 src/main/java/com/demo/UserController.java）
     */
    private String filePath;

    /**
     * 调用方所属 Java 类名称
     */
    private String className;

    /**
     * 调用方所在的方法名称（仅方法名，无参数/返回类型）
     */
    private String callerMethod;

    /**
     * 被调依赖在调用方类中所声明的字段/变量名称（含类型信息，如 userService:UserService）
     */
    private String dependencyName;

    /**
     * 实际被调用的目标方法名称（仅方法名，无参数/返回类型）
     */
    private String targetMethod;

    /**
     * 调用表达式原始文本（如 userService.findById(id)）
     */
    private String expression;

    /**
     * 调用所在源文件行号（1-indexed）
     */
    private Integer lineNumber;

    /**
     * 调用方方法完整签名（含类 + 方法 + 参数）
     * 格式："className#methodName(ParamType1, ParamType2)"
     * 例："com.demo.UserController#listUsers(Integer, Integer)"
     * 用于阶段 2 按方法粒度反查调用链，喂 AI 文档生成
     */
    @TableField("caller_signature")
    private String callerSignature;

    /**
     * 被调方方法完整签名（含类 + 方法 + 参数）
     * 格式："className#methodName(ParamType1, ParamType2)"
     * MVP 阶段仅 caller 端带完整签名，target 端等同 targetMethod（同名第一个匹配）
     */
    @TableField("target_signature")
    private String targetSignature;

    /**
     * Phase 3：声明类型的所有项目内具体候选子类 FQ（多态候选，逗号分隔）。
     * 与 dependency_name 配合使用，给 #9 反向 BFS 在多态调用下也能找到真实被改的入口。
     * 留 null 表示没识别到候选（如依赖是 final 类、注解处理器注入、跨 JAR 类型等）。
     */
    @TableField("dependency_candidates")
    private String dependencyCandidates;

    /**
     * 记录创建时间
     */
    private LocalDateTime createdAt;
}