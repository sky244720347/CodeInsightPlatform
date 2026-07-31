package com.company.codeinsight.modules.entrypoint.service;

import com.company.codeinsight.modules.entrypoint.model.DiscoveredEntrypoint;
import com.company.codeinsight.modules.entrypoint.model.EntrypointMethodView;
import com.company.codeinsight.modules.entrypoint.model.EntrypointReviewView;
import com.company.codeinsight.modules.entrypoint.model.EntryPoint;
import com.company.codeinsight.modules.entrypoint.model.EntryPointConfig;
import com.company.codeinsight.modules.entrypoint.model.ExcludeTarget;
import com.company.codeinsight.modules.scanner.model.IncrementalContext;
import com.company.codeinsight.modules.task.entity.DecompileTask;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 知识入口复核服务
 * <p>在 SPLITTING_TASK 完成后、调用 AI 之前，由流水线触发一次入口识别并落表 ci_entrypoint，
 * 等待用户在 ENTRYPOINT_REVIEW 状态下确认（继续）或驳回（终止任务）。</p>
 *
 * <p>该服务把"识别"与"落表"绑成一次原子动作，避免中间态被 AI 阶段误用；AI 阶段改为读取
 * {@link #loadEnabledEntries(Long)}，保证用户确认过的入口集合是 AI 调度的真实输入。</p>
 *
 * @see com.company.codeinsight.modules.entrypoint.service.EntryPointDiscoveryService
 */
public interface EntrypointReviewService {

    /**
     * 全量识别入口（含方法列表）并落表 ci_entrypoint（delete-then-insert by taskId）。
     * <p>无论 requireEntrypointReview 是否启用都执行，保证 AI 阶段总能读到一致的数据。</p>
     *
     * @return 识别并落表后的入口列表（供流水线 execLog 输出统计）
     */
    List<DiscoveredEntrypoint> discoverAndPersist(Long taskId, File projectDir, EntryPointConfig config);

    /**
     * v1: 增量识别入口（含方法列表）并落表 ci_entrypoint。
     * <p>语义：
     * <ul>
     *   <li>INITIAL 任务（ctx == null 或 ctx.isIncremental() == false）：走原全量识别路径（delete-then-insert）</li>
     *   <li>INCREMENTAL 任务（ctx.isIncremental() == true）：
     *     <ol>
     *       <li>基线入口继承由流水线 {@code DecompileTaskServiceImpl} 在 PARSING 前统一完成（本方法不再 inherit）</li>
     *       <li>删除本任务 ci_entrypoint 中 deleted 对应的行</li>
     *       <li>删除本任务 ci_entrypoint 中 changed 对应的行（基线继承的同 file_path 数据一并清理）</li>
     *       <li>仅对 changedPaths 内的 .java 文件做入口识别（{@link EntryPointDiscoveryService#discoverEntriesInFiles}）</li>
     *       <li>insert 识别结果（baseline_task_id 按基线是否已有该类标记变更/新增）</li>
     *     </ol>
     *   </li>
     * </ul>
     * </p>
     */
    List<DiscoveredEntrypoint> discoverAndPersist(Long taskId, File projectDir, EntryPointConfig config,
                                                  IncrementalContext ctx);

    /**
     * 复核页用：返回 task 下所有入口（含反序列化后的方法列表），按 sort_order / id 升序。
     * <p>只读视图，仅由 controller 层在序列化前反序列化 methods_json 字段。</p>
     */
    List<EntrypointReviewView> listByTaskId(Long taskId);

    /**
     * AI 阶段用：返回已落表的 enabled=true 入口（仅 class 级），构造为现有 {@link EntryPoint} DTO。
     * <p>不携带方法信息——AI 调度只看入口类。</p>
     */
    List<EntryPoint> loadEnabledEntries(Long taskId);

    /**
     * 读取任务创建时落库的入口扫描快照（{@code ci_task.entry_scan_config}）。
     * 运行时不再 merge 仓库配置；快照为空时回退平台默认预置。
     */
    EntryPointConfig resolveConfig(DecompileTask task);

    /**
     * 入口复核确认时：将复核页追加的指定排除写入任务配置，并同步过滤 ci_entrypoint 落表数据。
     */
    void applyReviewExcludes(Long taskId, List<ExcludeTarget> additionalExcludes);

    /** 按入口类全限定名索引已落表的方法列表（供模块层级 method_signatures 兜底） */
    Map<String, List<EntrypointMethodView>> loadMethodsByClassName(Long taskId);

    /**
     * v1: 入口 diff 视图（前端 Phase 4 UI 用）
     * <p>INITIAL 任务或无基线时返回 3 个空 list；INCREMENTAL 任务返回 3 类：</p>
     * <ul>
     *   <li>newRows：本次新增（baselineTaskId == null）</li>
     *   <li>inheritedRows：基线继承（baselineTaskId != null）</li>
     *   <li>deletedRows：本次删除（基线有 + 本次无）</li>
     * </ul>
     */
    com.company.codeinsight.modules.entrypoint.dto.EntrypointDiffDto getEntrypointDiff(Long taskId);
}