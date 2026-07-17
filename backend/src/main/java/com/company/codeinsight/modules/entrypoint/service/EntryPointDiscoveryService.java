package com.company.codeinsight.modules.entrypoint.service;

import com.company.codeinsight.modules.entrypoint.model.DiscoveredEntrypoint;
import com.company.codeinsight.modules.entrypoint.model.EntryPoint;
import com.company.codeinsight.modules.entrypoint.model.EntryPointConfig;

import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * 反编译项目入口识别服务
 * 通过注解识别（主）+ 调用链反查（辅）双路并联，发现项目中的业务入口类。
 * 同时支持任务级 EntryPointConfig 自定义入口识别与排除规则。
 */
public interface EntryPointDiscoveryService {

    /** 兼容旧调用：config=null 走默认 Controller/JOB/MQ 行为 */
    List<EntryPoint> discoverEntries(Long taskId, File projectDir);

    /** 新调用：传自定义 EntryPointConfig */
    List<EntryPoint> discoverEntries(Long taskId, File projectDir, EntryPointConfig config);

    /**
     * 在 {@link #discoverEntries(Long, File, EntryPointConfig)} 基础上，每条入口额外附带 {@code DiscoveredMethod} 列表
     * <p>仅用于 ENTRYPOINT_REVIEW 阶段落表 ci_entrypoint.methods_json；不参与 AI 调度逻辑。</p>
     *
     * <p>方法抽取策略：
     * <ul>
     *   <li>CONTROLLER：仅含 {@code requestMapping != null} 的方法（即带 @RequestMapping / @GetMapping / @PostMapping 等）</li>
     *   <li>SCHEDULED_JOB / MQ_LISTENER / COMPONENT：当前 parser 暂未抽取方法级注解，
     *       回退为该类所有非 private 方法，{@code annotation} 字段填 {@code "class-level: @xxx"}</li>
     *   <li>APPLICATION / MAIN：{@code main()} 方法</li>
     * </ul>
     * </p>
     */
    List<DiscoveredEntrypoint> discoverEntriesWithMethods(Long taskId, File projectDir, EntryPointConfig config);

    /**
     * v1: 增量入口识别 — 仅对指定相对路径列表的 .java 文件做识别。
     * <p>实现要点：
     * <ul>
     *   <li>复用 {@link #discoverEntriesWithMethods(Long, File, EntryPointConfig)} 的判定规则
     *       （4 类入口：Controller / ScheduledJob / MqListener / Other），不走全项目 parseDirectory</li>
     *   <li>对 relativePaths 内的每个文件调 {@code JavaParserService.parseFile} 单文件解析</li>
     *   <li>单文件解析失败不中断整批；类自身判定失败不识别为入口</li>
     *   <li>判定逻辑（{@code isExcluded / matchEntryType / addEntry}）与全量识别保持一致</li>
     * </ul>
     * </p>
     *
     * @param taskId        任务 ID
     * @param projectDir    项目目录（temp_repos/task_{taskId}）
     * @param config        入口识别配置（include/exclude 规则）
     * @param relativePaths 相对路径列表（相对于 projectDir，例如 "src/main/java/com/demo/UserController.java"）
     * @return 识别到的入口（含方法列表）
     */
    List<DiscoveredEntrypoint> discoverEntriesInFiles(Long taskId, File projectDir, EntryPointConfig config,
                                                      Set<String> relativePaths);

    /** 兼容旧调用 */
    String collectReachableSource(Long taskId, String entryClassName, File projectDir);

    /**
     * 新调用：带配置，BFS 时排除命中排除规则的依赖类。
     * 入口类自身若命中排除规则 → 返回空字符串。
     */
    String collectReachableSource(Long taskId, String entryClassName, File projectDir, EntryPointConfig config);

    /**
     * 只读取入口类自身的源文件（不做 BFS 依赖展开）
     * 模块层级提炼阶段使用：AI 只看入口类的方法签名和注解即可判定业务领域归属，不需要看依赖类的实现细节。
     *
     * @param projectDir 项目目录（temp_repos/task_{taskId}）
     * @param entry      入口类 DTO（含 filePath 或 className）
     * @param config     扫描配置（含排除规则，为空则不校验排除）
     * @return 入口类的完整源码；命中排除规则或文件不存在返回空字符串
     */
    String readEntrySource(File projectDir, EntryPoint entry, EntryPointConfig config);
}