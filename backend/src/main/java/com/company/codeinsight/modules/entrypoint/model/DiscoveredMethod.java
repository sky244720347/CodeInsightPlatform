package com.company.codeinsight.modules.entrypoint.model;

import lombok.Data;

/**
 * 入口类下提取出的单个方法信息
 * <p>用于 ENTRYPOINT_REVIEW 阶段落表 ci_entrypoint.methods_json 列与前端只读展示。
 * 不参与 AI 调度逻辑（AI 阶段只看 class 级 {@link EntryPoint}）。</p>
 */
@Data
public class DiscoveredMethod {

    /**
     * 方法名（不含类名）
     */
    private String methodName;

    /**
     * 方法签名（含类名）：className#methodName(ParamType1,ParamType2)
     * 例如 "UserController#listUsers(Integer,Integer)"
     */
    private String methodSignature;

    /**
     * 方法级触发注解简称（如 "GetMapping" / "Scheduled" / "RabbitListener"）
     * 对于 SCHEDULED_JOB/MQ_LISTENER 等 parser 暂未抽取方法级注解的场景，
     * 此字段填 "class-level: @Scheduled" 等标识以让用户在 UI 上能区分。
     */
    private String annotation;

    /**
     * 仅控制器方法：HTTP 路径（如 "/users/{id}/autosave"）
     * 非控制器方法时为 null。
     */
    private String httpPath;

    /**
     * 仅控制器方法：HTTP 方法（GET/POST/PUT/DELETE/PATCH）
     * 非控制器方法时为 null。
     */
    private String httpMethod;

    /**
     * 方法体内容哈希（同 {@link com.company.codeinsight.modules.parser.model.ParsedClassInfo.MethodInfo#bodyHash}）
     * 用于 INCREMENTAL 入口 DIFF 识别「同签名内容变更」。
     */
    private String bodyHash;
}