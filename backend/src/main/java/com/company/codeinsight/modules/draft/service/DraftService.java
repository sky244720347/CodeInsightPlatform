package com.company.codeinsight.modules.draft.service;

import com.company.codeinsight.modules.draft.dto.PreviewSystemDto;
import com.company.codeinsight.modules.draft.entity.*;
import com.company.codeinsight.modules.task.entity.DecompileTask;

import java.util.List;

/**
 * 知识草稿复核管理服务接口
 * 负责知识工作区获取、草稿编辑保存、自动暂存、确认发布等业务逻辑的定义。
 * 自 v0.3 起移除驳回相关方法；复核反馈通过直接编辑修改草稿。
 */
public interface DraftService {

    /**
     * 查询指定工作区下的所有模块草稿
     */
    List<KnowledgeDraft> listDraftsByWorkspace(Long workspaceId);

    /**
     * 是否允许部分文档未逐篇确认时仍执行「任务整体通过」。
     * 对应配置 {@code code-insight.review.allow-partial-pass}。
     */
    boolean isAllowPartialPass();

    /**
     * 查询指定工作区下的草稿目录树（基于 parent_id 自引用递归构建）。
     * 返回的列表只包含顶级节点，子节点通过 DraftTreeNode.children 递归嵌套。
     */
    List<com.company.codeinsight.modules.draft.dto.DraftTreeNode> getWorkspaceTree(Long workspaceId);

    /**
     * 根据任务 ID 查询对应生成的工作区信息
     */
    DraftWorkspace getWorkspaceByTaskId(Long taskId);

    /**
     * 获取单个草稿实体的基本信息
     */
    KnowledgeDraft getDraftById(Long draftId);

    /**
     * 读取指定草稿存储在物理目录下的 Markdown 正文全文内容
     */
    String getDraftContent(Long draftId);

    /**
     * 手动编辑保存草稿：同步写入物理磁盘文件，并在修订记录表中插入修改记录
     */
    void saveDraft(Long draftId, String content, String author, String remark);

    /**
     * 自动暂存草稿：防抖极速暂存，临时缓存写入
     */
    void autoSaveDraft(Long draftId, String content, String author);

    /**
     * 单文件级「确认通过」：仅将该草稿状态置为 CONFIRMED。
     * 若 {@code comment} 非空，会写入一条 type=PASS 的复核意见。
     *
     * <p><b>不</b>触发工作区/任务的级联状态推进 — 这是任务级入口
     * {@link #confirmTask(Long, String, String)} 的职责。调用方若想一次性确认整组草稿并把任务推进到
     * CONFIRMED，应使用 {@code confirmTask}。</p>
     *
     * <p>推送锁定：任务处于 PUSHING / PUSHED 时本方法抛出 {@link com.company.codeinsight.common.exception.BusinessException}。</p>
     *
     * @param draftId 草稿 ID
     * @param author  操作人
     * @param comment 可选的通过意见（可空）
     */
    void confirmDraft(Long draftId, String author, String comment);

    /**
     * 任务级「确认通过」：将 {@code taskId} 下整个工作区的草稿一次性置为 CONFIRMED，
     * 工作区晋升 COMPLETED，任务状态机推进到 CONFIRMED。
     *
     * <p>这是复核工作区「确认通过」按钮的真正语义入口 — 操作粒度是任务，不是单个文件。</p>
     *
     * <p>副作用：</p>
     * <ol>
     *   <li>工作区下所有 draft.status → CONFIRMED（统一戳 updated_date）</li>
     *   <li>workspace.status → COMPLETED</li>
     *   <li>若任务当前处于 PENDING_REVIEW / REVIEWING，状态机推进到 CONFIRMED</li>
     *   <li>若 {@code comment} 非空，往工作区下第一篇草稿挂一条 type=PASS 的复核意见（带「任务级通过」前缀）</li>
     *   <li>事务提交后异步触发建版与 NAS 入队（失败不回滚本次确认）</li>
     * </ol>
     *
     * <p>推送锁定：任务处于 PUSHING / PUSHED 时本方法抛出 {@link com.company.codeinsight.common.exception.BusinessException}。</p>
     *
     * @param taskId  任务 ID
     * @param author  操作人
     * @param comment 可选的任务级通过意见（可空）
     */
    void confirmTask(Long taskId, String author, String comment);

    /**
     * 创建知识版本 / 入队推送前的任务级就绪校验：任务须为 CONFIRMED，且工作区内每篇草稿均为 CONFIRMED 或 PUSHED。
     * <p>INCREMENTAL 任务额外校验：workspace 草稿集合须与当前模块层级一致（无多余/缺失）。</p>
     */
    void assertTaskReadyForKnowledgePublish(Long taskId);

    /**
     * 查询草稿的所有修订版本历史
     */
    List<DraftRevision> getRevisions(Long draftId);

    /**
     * 查询草稿的所有评审反馈意见列表
     */
    List<DraftReviewComment> getComments(Long draftId);

    /**
     * 任务级复核意见聚合：把 task 下整组草稿的复核意见一次性取出，
     * 并按来源草稿补齐 moduleName / filePath，便于复核人按任务粒度浏览整组意见
     * （含 confirmTask 写入的 `[任务级通过]` 任务级记录）。
     *
     * <p>这是复核工作区「复核意见」按钮的真实语义入口 — 与单文件级
     * {@link #getComments(Long)} 并存，前者面向整组，后者面向单篇。</p>
     *
     * @param taskId 任务 ID
     * @return 按 createdDate desc 排序的评论列表；工作区为空时返回空列表
     */
    java.util.List<com.company.codeinsight.modules.draft.dto.TaskCommentDto> listAllCommentsByTask(Long taskId);

    /**
     * 查询该草稿模块涉及的代码源文件引用指引列表
     */
    List<DraftSourceReference> getSourceReferences(Long draftId);

    /**
     * 单篇「重跑此篇」：异步接受。门禁通过后立刻将草稿置为 REGENERATING 并后台调 AI；
     * 本方法在入队后立即返回（{@code accepted=true}），不阻塞 HTTP。
     */
    com.company.codeinsight.modules.draft.dto.RegenerateDraftResult regenerateDraft(
            Long draftId, String author, String remark);

    /**
     * 查询单篇重跑进度（含失败原因，若有）。
     */
    com.company.codeinsight.modules.draft.dto.RegenerateDraftResult getRegenerateStatus(Long draftId);

    /**
     * 超时或进程重启后的 REGENERATING 草稿恢复：回退状态并记录失败原因。
     */
    void recoverStaleRegeneratingDraft(Long draftId, String reason);

    /**
     * 复核工作区首页：列出所有「可预览」业务系统（至少有一条状态在 PENDING_REVIEW /
     * REVIEWING / CONFIRMED 的任务），并按状态汇总该系统下各阶段任务计数。
     * 用于前端下拉/角标展示。结果按 totalReviewableCount 倒序排列。
     */
    List<PreviewSystemDto> listPreviewSystems();

    /**
     * 复核工作区二级筛选：列出指定系统下处于可复核状态的任务列表。
     *
     * @param systemId 业务系统 ID；为空时返回所有系统的复核任务
     * @param statuses 任务状态集合；为空时默认取 PENDING_REVIEW / REVIEWING / CONFIRMED
     */
    List<DecompileTask> listReviewableTasks(Long systemId, List<String> statuses);

    /**
     * 全局新建任务前置条件查询：扫描所有 ci_knowledge_draft，找出仍处于非终态
     * （DRAFT / EDITING）的草稿明细，作为新建任务闸门的输入。
     *
     * <p>语义与 {@link com.company.codeinsight.modules.task.service.DecompileTaskService}
     * 创建任务时的拦截校验一致：只要全局存在至少一条非终态草稿，就视为未就绪。</p>
     *
     * @return 就绪度聚合 DTO；阻塞列表为空时 ready=true
     */
    com.company.codeinsight.modules.draft.dto.RepositoryReadinessDto findGlobalReadiness();

    /**
     * 基于系统+仓库的新建任务前置条件查询，作用域收窄到指定组合。
     *
     * <p>与 {@link #findGlobalReadiness()} 的区别：只检查属于 {@code systemId + repositoryId}
     * 组合的未确认草稿，避免 A 系统的草稿阻塞 B 系统创建任务。</p>
     *
     * @param systemId     业务系统 ID（为空时退化为全局查询）
     * @param repositoryId 代码库 ID（为空时仅按 systemId 过滤）
     * @return 就绪度聚合 DTO
     */
    com.company.codeinsight.modules.draft.dto.RepositoryReadinessDto findReadiness(Long systemId, Long repositoryId);

    /**
     * v2: 草稿 diff 视图（前端 Phase 4 UI 用）
     * <p>INITIAL 任务或无基线时返回 4 个空 list；INCREMENTAL 任务返回 4 类：</p>
     * <ul>
     *   <li>newRows：本次新增（baselineTaskId == null 且 module_name 不在基线中）</li>
 *   <li>modifiedRows：本次重生成覆盖基线（module_name 在基线中，且非未触碰继承）</li>
 *   <li>inheritedRows：未触碰基线继承（baselineTaskId != null 且 status∈{CONFIRMED,PUSHED}）</li>
     *   <li>deletedRows：本次删除（基线 workspace 有 + 本次 workspace 无）</li>
     * </ul>
     */
    com.company.codeinsight.modules.draft.dto.DraftTreeDiffDto getWorkspaceTreeDiff(Long workspaceId);

    /**
     * v2: 单篇草稿的正文 DIFF — 返回基线 + 本次两份正文内容 URI，
     * 前端用 Monaco DiffEditor 自行计算 diff 并左右对比展示。
     *
     * @param draftId 本次 workspace 中的草稿 ID（须为 modifiedRows 中的草稿）
     * @return 包含 baselineContentUri 和 currentContentUri 的 DTO；无基线时 baselineContentUri 为 null
     */
    com.company.codeinsight.modules.draft.dto.DocumentDiffDto getDocumentDiff(Long draftId);
}

