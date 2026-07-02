package com.company.codeinsight.modules.hierarchy.service;

import com.company.codeinsight.modules.callchain.model.IncrementalImpact;
import com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy;
import com.company.codeinsight.modules.scanner.model.IncrementalContext;

import java.io.File;

/**
 * 模块层级服务接口
 * 串联入口识别 → AI 提炼 → DTO 内存聚合 → 持久化到 ci_module_hierarchy。
 */
public interface ModuleHierarchyService {

    /**
     * 构建并持久化任务的模块层级
     * 流程：
     *   1) 从数据库加载该任务已有节点，重建 ModuleHierarchy DTO
     *   2) 调用 EntryPointDiscoveryService 识别入口
     *   3) 对每个入口：调 AI 提炼 → 解析 JSON → 合并到 DTO（复用/新增 ID）
     *   4) 把入口 className 注入到对应 function 的 classPaths
     *   5) deleteByTaskId + 全量 insert（保证幂等）
     *
     * @param taskId     关联任务 ID
     * @param projectDir 拉取的本地项目根目录（用于 collectReachableSource 读取源码）
     * @return 构建后的 ModuleHierarchy DTO
     */
    ModuleHierarchy buildAndPersist(Long taskId, File projectDir);

    /**
     * 增量感知的层级构建。{@code ctx.isIncremental()} 为 false 时等价于 {@link #buildAndPersist(Long, File)}。
     * <p>
     * 增量模式：
     * <ul>
     *   <li>对变更文件对应的入口类重新调 AI（其余入口跳过 AI、保留 DTO 中已有数据）</li>
     *   <li>对被删除文件，将对应 FQ 类名从已有 function 的 classPaths 中移除</li>
     *   <li>最终仍走 {@code deleteByTaskId + 全量 insert} 落表（保留幂等语义）</li>
     * </ul>
     */
    ModuleHierarchy buildAndPersist(Long taskId, File projectDir, IncrementalContext ctx);

    /**
     * 增量感知 + 影响面裁剪的层级构建。
     * {@code impact} 非空且 {@code impact.isIncremental()} 时，按 {@code hierarchyRetargetEntries} 决定入口 AI 调用范围。
     */
    ModuleHierarchy buildAndPersist(Long taskId, File projectDir, IncrementalContext ctx, IncrementalImpact impact);

    /**
     * 从数据库加载该任务的模块层级 DTO
     */
    ModuleHierarchy loadByTaskId(Long taskId);

    /**
     * 用前端复核后提交的新层级整体替换落表（人工复核断点使用）
     * <p>
     * 行为：先校验 ID 前缀/唯一性/名称非空等结构约束，再 deleteByTaskId + 全量 insert 保证幂等。
     * 不修改 AI 生成的入口归属（classPaths 由前端编辑后传入），其它 DTO 字段（taskId / systemId）由后端补齐。
     */
    ModuleHierarchy replaceHierarchy(Long taskId, ModuleHierarchy replacement);
}