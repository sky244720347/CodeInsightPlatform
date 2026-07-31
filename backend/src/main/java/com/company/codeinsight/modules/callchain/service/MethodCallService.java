package com.company.codeinsight.modules.callchain.service;

import com.company.codeinsight.modules.callchain.entity.MethodCall;
import com.company.codeinsight.modules.callchain.model.MethodCallEdgeLite;
import com.company.codeinsight.modules.scanner.model.IncrementalContext;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/**
 * 方法调用链路服务接口
 * 负责把 AST 静态解析产出的方法调用关系持久化到 ci_method_call 表，并提供按任务/类维度的查询与清理能力。
 */
public interface MethodCallService {

    /**
     * 全量解析指定项目目录下的所有 Java 源文件并把方法调用链落表
     * 流程：1) 清空该 task 历史记录（幂等）；2) 递归遍历 projectDir 下所有 .java 文件；
     *      3) 复用 JavaParserService.parseFile 提取 methodCalls；4) 批量入库 ci_method_call 表。
     * 单个文件解析失败时降级（log + skip），不中断整批任务。
     *
     * @param taskId     关联知识构建任务 ID
     * @param projectDir 拉取后的本地项目根目录
     * @return 实际写入 ci_method_call 的调用链条目数
     */
    int persistAstForTask(Long taskId, File projectDir);

    /**
     * 增量感知的 AST 落表。{@code ctx.isIncremental()} 为 false 时等价于 {@link #persistAstForTask(Long, File)}。
     * <p>
     * 增量模式（基线边继承由流水线 {@code BaselineInheritanceService} 负责，本方法不再 inherit）：
     * <ul>
     *   <li>软删 {@code ctx.getDeletedPaths()} / {@code ctx.getChangedPaths()} 对应调用链行</li>
     *   <li>仅对 {@code ctx.getChangedPaths()} 中的 .java 文件做 AST 解析并写入</li>
     *   <li>未变文件的调用链行原样保留（来自 pipeline 继承）</li>
     * </ul>
     * <p>本方法不包长事务：清理为短 SQL，写入按批独立提交；batch 失败抛业务异常。
     */
    int persistAstForTask(Long taskId, File projectDir, IncrementalContext ctx);

    /**
     * 查询指定任务的所有调用链条目
     *
     * @param taskId 知识构建任务 ID
     * @return 该任务下的全部方法调用链列表（按 ID 升序）
     */
    List<MethodCall> listByTaskId(Long taskId);

    /**
     * 按 id 游标分页消费轻量调用边（仅 class/dependency/filePath），供入口识别构图，避免全表实体进堆。
     *
     * @param taskId   任务 ID
     * @param consumer 每页回调；勿在回调中长时间持有整表引用
     */
    void forEachEdgeLite(Long taskId, Consumer<List<MethodCallEdgeLite>> consumer);

    /**
     * 查询指定任务下某 Java 类的所有调用链条目
     *
     * @param taskId    知识构建任务 ID
     * @param className Java 类名（可为 null，null 时返回空列表）
     * @return 该类下的全部调用链列表（按类名、方法名、行号排序）
     */
    List<MethodCall> listByClass(Long taskId, String className);

    /**
     * 删除指定任务的所有调用链记录（重跑/重试前的清理，逻辑删除）
     *
     * @param taskId 知识构建任务 ID
     */
    void deleteByTaskId(Long taskId);
}
