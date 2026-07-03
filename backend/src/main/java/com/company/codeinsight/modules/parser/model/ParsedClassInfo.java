package com.company.codeinsight.modules.parser.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 静态语法解析提取的类信息载体
 * 包含类名、包结构、所属组件类型、接口映射、依赖其它类、含有的方法以及 SQL 数据表引用信息。
 */
@Data
public class ParsedClassInfo {

    /**
     * Java 类名称（如 DecompileTaskController）
     */
    private String className;

    /**
     * Java 类所在的包路径（如 com.company.codeinsight.modules.task.controller）
     */
    private String packageName;

    /**
     * 源文件相对于项目根目录的路径（如 accounting-service/src/main/java/com/demo/UserController.java）
     * 由 Regex/Ast JavaParserService.parseDirectory 在扫描时写入，供入口识别与源码读取使用
     */
    private String sourceRelativePath;

    /**
     * 类的组件归属角色类型（如 CONTROLLER, SERVICE, MAPPER, ENTITY 等）
     */
    private String type;

    /**
     * 类级别的 HTTP 路由 RequestMapping 映射路径（如 /tasks）
     */
    private String requestMapping;

    /**
     * 声明在类头部的所有注解全称列表（如 ["RestController", "RequestMapping", "Validated"]）
     */
    private List<String> annotations = new ArrayList<>();

    /**
     * 该类所依赖/引用的其它类名称集合，用于分析系统依赖关系图
     */
    private List<String> dependencies = new ArrayList<>();

    /**
     * 该类声明的所有成员方法摘要信息列表
     */
    private List<MethodInfo> methods = new ArrayList<>();

    /**
     * 类中存在的方法与方法调用级链条的明细列表
     */
    private List<MethodCallInfo> methodCalls = new ArrayList<>();

    /**
     * 该类是否包含 Java SE 标准入口方法 public static void main(String[] args)
     * 由 Regex/Ast JavaParserService.parseFile 在识别到时置为 true
     */
    private boolean hasMainMethod;

    /**
     * 该类直接继承的父类全限定名（extends 子句的第一个类型）
     * 由 Regex/Ast JavaParserService.parseFile 提取；interface / enum / @interface 时该字段保持 null
     */
    private String extendsClass;

    /**
     * 该类实现的接口列表（implements 子句，按逗号分隔）
     * 元素可能为 FQ 也可能为简单类名（依赖源码写法）
     */
    private List<String> implementsList = new ArrayList<>();

    /**
     * 该类中涉及到的底层数据库表名称集合（如 ["ci_chunk", "ci_task"]）
     */
    private List<String> tables = new ArrayList<>();

    /**
     * 提取出的 SQL 常规操作引用详情列表
     */
    private List<SqlReference> sqlReferences = new ArrayList<>();

    /**
     * 静态成员方法元数据类
     */
    @Data
    public static class MethodInfo {
        /**
         * 方法名
         */
        private String name;
        /**
         * 方法返回值类型
         */
        private String returnType;
        /**
         * 方法入参签名定义列表
         */
        private String arguments;
        /**
         * 方法级别的接口路由 RequestMapping 映射（如 /{id}/autosave）
         */
        private String requestMapping;
        /**
         * HTTP 请求方法类型（GET/POST/PUT/DELETE 等）
         */
        private String httpMethod;
        /**
         * 方法体起始行（1-indexed，含方法签名行）
         * 由 Regex/Ast JavaParserService.parseFile 在解析时写入
         * 用于阶段 2 按方法签名截取源码（filterClassToMethods）
         */
        private Integer startLine;
        /**
         * 方法体结束行（1-indexed，含闭合括号）
         */
        private Integer endLine;
    }

    /**
     * 方法内部调用依赖的明细信息类
     */
    @Data
    public static class MethodCallInfo {
        /**
         * 调用者的方法名
         */
        private String callerMethod;
        /**
         * 所调用依赖的引用类/字段名称
         */
        private String dependencyName;
        /**
         * 被调用的目标方法名称
         */
        private String targetMethod;
        /**
         * 调用方方法完整签名："methodName(ParamType1, ParamType2)"（不含类名，不含返回类型）
         * 例："listUsers(Integer, Integer)"
         * 落表到 ci_method_call.caller_signature 时前面拼上类名
         */
        private String callerSignature;
        /**
         * 被调方方法完整签名："methodName(ParamType1, ParamType2)"（MVP 阶段不带参数，等于 targetMethod）
         */
        private String targetSignature;
        /**
         * 完整的调用代码行表达式
         */
        private String expression;
        /**
         * 在源文件中的第几行
         */
        private Integer lineNumber;
        /**
         * Phase 3：声明类型的所有项目内具体候选子类 FQ（多态候选，逗号分隔）。
         * 例如 "com.x.ServiceA,com.x.ServiceB"。
         * 落到 ci_method_call.dependency_candidates，给 #9 反向 BFS 在多态调用下也能找到真实被改的入口。
         */
        private String dependencyCandidates;
    }

    /**
     * SQL 语句与数据表依赖操作描述类
     */
    @Data
    public static class SqlReference {
        /**
         * SQL 操作类型（如 SELECT, INSERT, UPDATE, DELETE）
         */
        private String operation;
        /**
         * 涉及的数据表名列表
         */
        private List<String> tables = new ArrayList<>();
        /**
         * SELECT 查询的字段字段列表
         */
        private List<String> selectedFields = new ArrayList<>();
        /**
         * WHERE 语句的过滤条件字段列表
         */
        private List<String> conditionFields = new ArrayList<>();
        /**
         * INSERT 插入的表字段列表
         */
        private List<String> insertedFields = new ArrayList<>();
        /**
         * UPDATE 修改的表字段列表
         */
        private List<String> updatedFields = new ArrayList<>();
        /**
         * SQL JOIN 操作联接到的其它数据表
         */
        private List<String> joinedTables = new ArrayList<>();
    }
}

