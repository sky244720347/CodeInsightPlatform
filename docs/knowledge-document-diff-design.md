# 增量扫描 · 知识文档 DIFF 方案（v2 — 纯 release 方案）

> **管什么**：INCREMENTAL 任务的基线文档继承（从 releases 目录复制）、知识文档重生成裁剪、草稿复核页的文档 DIFF（树级 4 桶 + 正文左右对照）、以及修复已有推送/版本/ZIP 缺陷。
> **不管什么**：模块层级结构 DIFF（见 [module-hierarchy-id-reuse-fix.md](./module-hierarchy-id-reuse-fix.md)）；入口 DIFF（见 [entrypoint-review-diff-design.md](./entrypoint-review-diff-design.md)）；知识版本发布后的 diff。
> **关联**：[incremental-baseline-design.md](./incremental-baseline-design.md)、[module-hierarchy-review-diff-design.md](./module-hierarchy-review-diff-design.md)、[incremental-knowledge-prune-and-push-design.md](./incremental-knowledge-prune-and-push-design.md)（删除失效继承 + 增量全量推送）。
> **状态：已实施**（2026-07）。

---

## 〇、核心设计原则

1. **两个视图各管各的**：模块层级复核页的「继承/修改」与草稿复核页的「继承/修改」独立计算。模块结构没变标「继承」≠ 文档也一定「继承」——文档可能因入口内容变更或依赖变更而重生成并标「修改」。
2. **文档维度 = 功能粒度**：一份知识文档对应一个功能（`module_name` = `模块/子模块/功能`）。一个功能变化只重生成该功能的文档，不波及整个模块。
3. **基线文档从 releases 目录复制（纯 release 方案）**：INCREMENTAL 任务在 `GENERATING_DOC` 之前新增 `BASELINE_DOC_INHERIT` 节点，从 `ci_repository.last_published_version_id` 定位的 releases 目录复制知识文档到本次 workspace。**不从基线任务的 drafts 目录复制**——因为 `NasPushStrategy` 推送成功后会删除 draft 源文件，基线 DB 行的 `content_uri` 指向的文件已不存在。releases 目录是已发布知识的权威存储，保证文件完整。
4. **重生成判定 = 入口 DIFF 状态 + 反向 BFS**：接入 `IncrementalImpact.hierarchyRetargetEntries`（反向 BFS 命中入口），覆盖「依赖变了但入口没变」的场景。
5. **正文对比 v1 = 左右只读对照（Monaco DiffEditor）**：不逐行高亮，只告知「该文件有 DIFF」，左右并排展示基线 vs 本次 Markdown。后续可扩展为 AI 提炼 DIFF。
6. **不动提示词**：重生成时 AI 仍按全量逻辑生成（拿到新源码自然会反映变更），不向 `module_doc_prompt.md` 追加变更清单。
7. **不动模块层级 DIFF**：模块层级 DIFF 只管结构变更，不读入口 bodyHash。
8. **BASELINE_DOC_INHERIT 仅增量任务**：INITIAL 任务不经过此节点，流程不变。
9. **不再设置 `baselineWorkspaceId`**：纯 release 方案下本次 workspace 自包含，不再引用基线 workspace。`getWorkspaceTree` 不再合并基线查询。`AiSummaryServiceImpl.generateDraftDocument` 不再设置 `baselineWorkspaceId`。
10. **继承文档默认 `CONFIRMED`**：基线文档已经过上一轮人工复核并推送发布，继承后状态直接设为 `CONFIRMED`，无需重复确认。DIFF 视图只展示需人工确认的变更文档（修改+新增+删除），继承文档仅在全量视图展示。
11. **DIFF tab = 默认页（INCREMENTAL 任务）**：DIFF 视图只展示变更文档（modified + new + deleted），过滤掉 inherited 节点。全量视图展示全部文档。上一篇/下一篇导航基于过滤后的树。
12. **文档树 + 模块小窗均展示变更类型 Badge**：树节点用 Tag 标记「新增/修改/继承/删除」，删除节点文字带删除线且不可选中。模块层级小窗接入 `getModuleHierarchyDiff`，追加已删除的模块/子模块/功能节点。

---

## 一、已有缺陷与修复目标

### 1.1 三个已有缺陷（同一根因：增量 workspace 不自包含）

| 出口 | 代码 | 问题 |
|---|---|---|
| 版本创建 → `docs/code-insight` | `KnowledgeServiceImpl.createVersion:130-133` | 只读 `workspaceId = current`，继承文档不在 → `docs/code-insight` 残缺 |
| ZIP 下载 | `KnowledgeServiceImpl.exportZip:405` | 读 `docs/code-insight`，上一步就漏了 |
| NAS/Git 推送 | `NasPushStrategy.execute:66-73` | 只读 `workspaceId = current`，继承文档不在 → 推送残缺 |

**根因**：当前「物理隔离 + 查询合并」只在草稿复核查询（`getWorkspaceTree`）时合并两个 workspace，其余三个出口全部只读本次 workspace。

**修复方式**：在 `GENERATING_DOC` 之前把基线文档从 releases 目录复制到本次 workspace，使本次 workspace 自包含。三个出口不需要改查询逻辑——它们读到的 workspace 已经完整。

### 1.2 功能粒度重生成缺口

| 缺口 | 代码 | 问题 |
|---|---|---|
| 功能粒度未接入反向 BFS | `AiSummaryServiceImpl.generateDraftDocumentByFunction` | 只看 `fn.classPaths ∩ changedFqSet`；依赖变了但入口没变时不重生成 |
| `resumeAfterHierarchyReview` 退化全量 | `DecompileTaskServiceImpl` | 只调单参 `generateDraftDocument(id, prompt)`，丢失 ctx + impact |

### 1.3 旧方案从 drafts 目录复制的致命问题

旧方案从基线任务的 `ci_knowledge_draft` DB 行 + `drafts/task_{baselineTaskId}/` 目录复制知识文档。但 `NasPushStrategy.execute()` 在推送成功后**会删除 draft 源文件**（`Files.deleteIfExists(src)`），只保留 `releases/{sysId}/{repoId}/{versionNum}/modules/` 中的副本。导致：

- 基线 DB 行的 `contentUri` 指向 `draft:{sysId}:{repoId}:task_{baselineTaskId}/xxx.md`
- 但 `drafts/task_{baselineTaskId}/xxx.md` 物理文件已被删除
- `inheritDrafts` 复制时 `Files.exists(baselineFile)` 为 false → 抛「基线草稿文件缺失」异常

**纯 release 方案**：绕过 drafts 目录，直接从 releases 目录（权威存储、不可变）复制。`moduleName` 从 `meta/module-map.yaml` 解析，正文从 `modules/{fileName}` 复制。

---

## 二、决策记录

| # | 决策 | 选项 | 理由 |
|---|---|---|---|
| 1 | modified 判定 | `baselineTaskId` + status + `baselineModuleNames` | inherited = `baselineTaskId != null` 且 status∈{CONFIRMED,PUSHED}；modified = 基线有同名且非未触碰继承（含 AI 重生成、含 baseline 残留脏数据）；new = 其余 |
| 2 | 正文 diff 实现 | Monaco DiffEditor 左右只读对照 | v1 简单可用；后续可加 AI 提炼 |
| 3 | `getDocumentDiff` 返回 | 一次返回 `{ baselineContent, currentContentUri, currentDraftId, ... }`，基线正文内联返回 | 基线 draft 物理文件已删，不能二次 `getDraftContent(baselineDraftId)`；直接从 releases 读内容内联返回 |
| 4 | `DraftTreeDiffDto` 改造 | 加 `modifiedRows`；`deletedRows` 归正为真「删除」 | 兼容现有类型，语义清晰 |
| 5 | Prompt 增量提示 | 不做，只改「是否调 AI」的判定 | AI 拿到新源码自然会反映变更 |
| 6 | 模块层级 DIFF | 不动 | 模块层级 DIFF 只管结构变更；文档 DIFF 独立计算 |
| 7 | 功能粒度重生成接入反向 BFS | 接入 `hierarchyRetargetEntries` + 保留 `changedFqSet` fallback + 入口 `modifiedRows` | 覆盖「依赖变但入口没变」+「入口内容变但结构没变」场景 |
| 8 | 基线文档复制时点 | 生成时复制（新增 `BASELINE_DOC_INHERIT` 节点） | workspace 即刻自包含；下游全不用改 |
| 9 | 复制失败处理 | 失败即停（fail-fast），任务 FAILED，支持「重新继承基线文档」按钮重跑 | 不允许无声缺失文档 |
| 10 | `BASELINE_DOC_INHERIT` 适用范围 | 仅 INCREMENTAL 任务 | INITIAL 无基线，不经过此节点 |
| 11 | **基线文档数据源** | **releases 目录（纯 release 方案）** | drafts 目录文件在推送后被 `NasPushStrategy` 删除；releases 是已发布知识的不可变权威存储 |
| 12 | `moduleName` 来源 | `meta/module-map.yaml` 中 `name` 字段 | release 目录的标准元数据，与发布时 `KnowledgeServiceImpl.createVersion` 生成的格式一致 |
| 13 | `baselineTaskId` 来源 | `KnowledgeVersion.taskId`（创建该发布版本的任务 ID） | 从 release 元数据推导，不依赖 `IncrementalContext.baselineTaskId` |
| 14 | `baselineWorkspaceId` | **不再设置** | workspace 已自包含，不需要合并基线查询；`AiSummaryServiceImpl` 中设置逻辑已移除 |
| 15 | YAML 解析方式 | 手动行解析（不引入 SnakeYAML/Jackson YAML 依赖） | `module-map.yaml` 格式简单固定（`- name:` + `path:` 两行一组），手动解析足够 |
| 16 | **继承文档状态** | **`CONFIRMED`（默认已确认）** | 基线文档已经过上一轮人工复核并推送发布，继承后无需重复确认；DIFF 视图只展示需人工确认的变更文档（修改+新增），继承文档仅在全量视图展示 |
| 17 | **DIFF/全量 tab 语义** | **DIFF = 默认页，只展示变更文档（modified+new+deleted）；全量 = 展示全部** | 增量复核只需关注变化部分；无变化的继承文档默认通过，不占用复核人注意力 |
| 18 | **文档树变更类型 Badge** | **树节点 + 文档标题区均展示变更类型 Tag**（新增/修改/继承/删除） | 复核人需要一眼区分哪些是基线继承的、哪些是本次修改的、哪些是新增的 |
| 19 | **模块层级小窗 DIFF** | **接入 `getModuleHierarchyDiff`，树节点展示新增/删除/修改/继承 Badge + 追加已删除模块节点** | 模块层级小窗需要让复核人感知哪些模块是新增的、哪些被删除了 |
| 20 | **工具栏比对按钮类型感知** | **移出 Segmented，独立渲染：modified→"比对"可点击；new→"新增"Tag 不可点击；inherited→"继承"Tag 不可点击** | 复核人需一眼区分当前文档是新增/修改/继承，并可对修改篇打开左右正文比对 |
| 21 | **"通过"按钮文案** | **CONFIRMED/PUSHED 时改为"已通过" + `CheckOutlined` 图标 + disabled** | 全量+DIFF 一致，明确告知复核人该文档已审核通过 |
| 22 | **全量流水线隐藏「基线复制」** | **`INITIAL` 任务 `flowStepItems` 不渲染该步；`INCREMENTAL` 才展示** | 全量不经过 `BASELINE_DOC_INHERIT`，展示灰色「仅增量」占位易误导；隐藏后需对 Steps `current` 做索引偏移 |

---

## 三、新增流水线节点 `BASELINE_DOC_INHERIT`

### 3.1 状态机变更

```
INITIAL 任务：
  MODULE_HIERARCHY →（MODULE_HIERARCHY_REVIEW）→ GENERATING_DOC
  （不经过 BASELINE_DOC_INHERIT）

INCREMENTAL 任务：
  MODULE_HIERARCHY →（MODULE_HIERARCHY_REVIEW）→ BASELINE_DOC_INHERIT → GENERATING_DOC
```

### 3.2 节点职责

```text
BASELINE_DOC_INHERIT:
  ① 创建/获取本次 DraftWorkspace（不设 baselineWorkspaceId）
  ② baselineInheritanceService.inheritDrafts(currentTaskId, workspaceId, repositoryId)
     — 从 releases 目录复制基线知识文档到本次 workspace（见 §四）
  ③ 成功 → transitTo(GENERATING_DOC)
  ④ 失败 → 任务 FAILED，ci_operation_log 记录失败详情
```

### 3.3 两个入口路径

| 入口 | 条件 | 路径 | 代码位置 |
|---|---|---|---|
| 跳过层级复核 | `requireHierarchyReview=false` 且 `INCREMENTAL` | `MODULE_HIERARCHY → BASELINE_DOC_INHERIT → GENERATING_DOC` | `continueAfterEntrypointReview` |
| 层级复核通过 | `requireHierarchyReview=true` 用户批准 | `MODULE_HIERARCHY_REVIEW → BASELINE_DOC_INHERIT → GENERATING_DOC` | `resumeAfterHierarchyReview` |
| 全量任务 | `INITIAL` | `MODULE_HIERARCHY → GENERATING_DOC`（不经过新节点） | `continueAfterEntrypointReview` |

### 3.4 状态机相关代码改动

**`TaskStatus.java`** — 新增枚举值：

```java
MODULE_HIERARCHY_REVIEW,
/**
 * 基线文档继承中（仅 INCREMENTAL 任务：将基线 workspace 的知识文档复制到本次 workspace，
 * 使本次 workspace 自包含，修复版本创建/ZIP/推送残缺缺陷）。
 * 失败后支持「重新继承基线文档」按钮单独重跑此步骤。
 */
BASELINE_DOC_INHERIT,
GENERATING_DOC,
```

**`TaskStateMachineServiceImpl.java`** — 合法转换 + 进度：

```java
// 合法转换
case MODULE_HIERARCHY -> target == TaskStatus.MODULE_HIERARCHY_REVIEW
    || target == TaskStatus.BASELINE_DOC_INHERIT
    || target == TaskStatus.GENERATING_DOC
    || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
case MODULE_HIERARCHY_REVIEW -> target == TaskStatus.BASELINE_DOC_INHERIT
    || target == TaskStatus.GENERATING_DOC
    || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
case BASELINE_DOC_INHERIT -> target == TaskStatus.GENERATING_DOC
    || target == TaskStatus.FAILED || target == TaskStatus.CANCELLED;
// ...
case FAILED -> target == TaskStatus.PENDING
    || target == TaskStatus.BASELINE_DOC_INHERIT  // 重试基线继承
    || target == TaskStatus.ARCHIVED;

// 进度
case MODULE_HIERARCHY -> task.setProgress(75);
case MODULE_HIERARCHY_REVIEW -> task.setProgress(82);
case BASELINE_DOC_INHERIT -> task.setProgress(85);
case GENERATING_DOC -> task.setProgress(90);
```

### 3.5 `DecompileTaskServiceImpl` 中的编排方法

**`runBaselineDocInheritAndGenerateDoc`** — 核心编排方法，被 `continueAfterEntrypointReview` 和 `resumeAfterHierarchyReview` 调用：

```java
private void runBaselineDocInheritAndGenerateDoc(Long taskId, DecompileTask task,
        com.company.codeinsight.modules.scanner.model.IncrementalContext incrementalCtx,
        com.company.codeinsight.modules.callchain.model.IncrementalImpact impact) {
    // ① BASELINE_DOC_INHERIT：创建 workspace + 从 releases 复制基线文档
    execLog.log(taskId, ">>> BASELINE_DOC_INHERIT — 继承基线知识文档");
    stateMachineService.transitTo(taskId, TaskStatus.BASELINE_DOC_INHERIT, null);
    com.company.codeinsight.modules.draft.entity.DraftWorkspace ws =
        ensureWorkspaceWithBaseline(taskId, task, null);  // baselineTaskId 传 null，不设 baselineWorkspaceId
    int inherited = baselineInheritanceService.inheritDrafts(
        taskId, ws.getId(), task.getRepositoryId());       // 传 repositoryId，不传 baselineTaskId
    execLog.log(taskId, "  继承基线草稿 " + inherited + " 份");
    execLog.log(taskId, "<<< BASELINE_DOC_INHERIT 完成");

    // ② GENERATING_DOC：AI 重生成变更功能文档
    execLog.log(taskId, ">>> GENERATING_DOC — 生成文档");
    long t1 = System.currentTimeMillis();
    stateMachineService.transitTo(taskId, TaskStatus.GENERATING_DOC, null);
    aiSummaryService.generateDraftDocument(taskId,
        decompilePromptService.requireTaskPromptContent(task,
            com.company.codeinsight.modules.prompt.entity.DecompilePrompt.TYPE_DOCUMENT_GENERATION),
        incrementalCtx, impact);
    stateMachineService.transitTo(taskId, TaskStatus.PENDING_REVIEW, null);
    execLog.log(taskId, "  耗时 " + (System.currentTimeMillis() - t1) + "ms");
}
```

**`retryBaselineInherit`** — 失败恢复入口，被 Controller 调用：

```java
public void retryBaselineInherit(Long id) {
    DecompileTask task = this.getById(id);
    // 校验：task.type == INCREMENTAL 且 task.status == FAILED
    // 恢复 PipelineContext + IncrementalImpact（从缓存或重新计算）
    // 重跑 ensureWorkspaceWithBaseline(id, task, null) + inheritDrafts(id, ws.getId(), task.getRepositoryId())
    // 成功 → transitTo(GENERATING_DOC) → generateDraftDocument(id, prompt, ctx, impact)
    //        → transitTo(PENDING_REVIEW)
    // 失败 → transitTo(FAILED)
}
```

**`ensureWorkspaceWithBaseline`** — 纯 release 方案下 `baselineTaskId` 传 `null`，不设 `baselineWorkspaceId`：

```java
private DraftWorkspace ensureWorkspaceWithBaseline(Long taskId, DecompileTask task, Long baselineTaskId) {
    DraftWorkspace ws = draftWorkspaceMapper.selectOne(
        new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getTaskId, taskId));
    if (ws == null) {
        ws = new DraftWorkspace();
        ws.setTaskId(taskId);
        ws.setSystemId(task.getSystemId());
        ws.setRepositoryId(task.getRepositoryId());
        ws.setStatus("ACTIVE");
        ws.setCreatedDate(LocalDateTime.now());
        ws.setUpdatedDate(LocalDateTime.now());
        draftWorkspaceMapper.insert(ws);
        if (ws.getId() == null) {
            ws = draftWorkspaceMapper.selectOne(
                new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getTaskId, taskId));
        }
    }
    // baselineTaskId 传 null 时此分支不执行 — 不设 baselineWorkspaceId
    if (baselineTaskId != null && ws.getBaselineWorkspaceId() == null) {
        Long baselineWsId = baselineInheritanceService.lookupBaselineWorkspaceId(baselineTaskId);
        if (baselineWsId != null) {
            ws.setBaselineWorkspaceId(baselineWsId);
            ws.setUpdatedDate(LocalDateTime.now());
            draftWorkspaceMapper.updateById(ws);
        }
    }
    return ws;
}
```

### 3.6 Controller 端点

**`DecompileTaskController.java`**：

```java
@PostMapping("/{id}/retry-baseline-inherit")
public ApiResponse<Void> retryBaselineInherit(@PathVariable Long id) {
    decompileTaskService.retryBaselineInherit(id);
    return ApiResponse.success();
}
```

**`DecompileTaskService.java`** 接口新增：

```java
void retryBaselineInherit(Long id);
```

---

## 四、`inheritDrafts` 基线文档复制逻辑（纯 release 方案）

### 4.1 方法签名

```java
// BaselineInheritanceService.java
@Transactional(rollbackFor = Exception.class)
public int inheritDrafts(Long currentTaskId, Long currentWorkspaceId, Long repositoryId)
```

> **注意**：参数是 `repositoryId`（用于查 `ci_repository.last_published_version_id` 定位 release 目录），不是 `baselineTaskId`。`baselineTaskId` 从 `KnowledgeVersion.taskId` 内部推导。

### 4.2 数据源定位链

```text
repositoryId
  → CodeRepository.lastPublishedVersionId
    → KnowledgeVersion（status 必须 == "PUSHED"）
      → version.systemId + version.repositoryId + version.versionNum
        → storageResolver.releaseDir(systemId, repositoryId, versionNum)
          → {releaseDir}/meta/module-map.yaml  ← moduleName + fileName 映射
          → {releaseDir}/modules/{fileName}    ← 正文文件
```

### 4.3 完整实现代码

```java
@Transactional(rollbackFor = Exception.class)
public int inheritDrafts(Long currentTaskId, Long currentWorkspaceId, Long repositoryId) {
    if (currentWorkspaceId == null || repositoryId == null) {
        log.warn("基线草稿继承跳过 — taskId={} workspaceId={} repositoryId={}（参数缺失）",
                currentTaskId, currentWorkspaceId, repositoryId);
        return 0;
    }

    // 1. 查仓库的 last_published_version_id → KnowledgeVersion
    CodeRepository repo = repositoryMapper.selectById(repositoryId);
    if (repo == null || repo.getLastPublishedVersionId() == null) {
        throw new BusinessException(
                "仓库未发布过知识版本，无法继承基线文档（repositoryId=" + repositoryId + "）");
    }
    KnowledgeVersion version = versionMapper.selectById(repo.getLastPublishedVersionId());
    if (version == null || !"PUSHED".equals(version.getStatus())) {
        throw new BusinessException(
                "基线知识版本不可用（versionId=" + repo.getLastPublishedVersionId()
                        + "，status=" + (version != null ? version.getStatus() : "null") + "）");
    }

    Long baselineTaskId = version.getTaskId();  // baselineTaskId 从版本记录推导

    // 2. 解析 release 目录
    Path releaseDir = storageResolver.releaseDir(
            version.getSystemId(), version.getRepositoryId(), version.getVersionNum());
    if (!Files.isDirectory(releaseDir)) {
        throw new BusinessException("基线 release 目录不存在: " + releaseDir + "，基线数据可能已损坏");
    }
    Path modulesDir = releaseDir.resolve("modules");
    Path mapFile = releaseDir.resolve("meta").resolve("module-map.yaml");
    if (!Files.exists(mapFile)) {
        throw new BusinessException("基线 module-map.yaml 不存在: " + mapFile + "，基线数据可能已损坏");
    }

    // 3. 解析 module-map.yaml → [{moduleName, fileName}] 列表
    List<ModuleMapEntry> entries = parseModuleMapYaml(mapFile);
    if (entries.isEmpty()) {
        log.info("基线草稿继承跳过 — taskId={} ← releaseDir={}（module-map.yaml 无模块条目）",
                currentTaskId, releaseDir);
        return 0;
    }

    // 4. 幂等：查本次 workspace 已有的 module_name
    List<KnowledgeDraft> existing = draftMapper.selectList(
            new LambdaQueryWrapper<KnowledgeDraft>()
                    .eq(KnowledgeDraft::getWorkspaceId, currentWorkspaceId));
    Set<String> existingNames = new HashSet<>();
    for (KnowledgeDraft d : existing) {
        if (d.getModuleName() != null) existingNames.add(d.getModuleName());
    }

    // 5. 逐个复制 release 文件到本次 drafts 目录 + 创建 DB 行
    List<KnowledgeDraft> toInsert = new ArrayList<>();
    List<Path> copiedFiles = new ArrayList<>();  // 失败时清理
    LocalDateTime now = LocalDateTime.now();
    Path currentDraftsDir = storageResolver.draftsRoot().resolve("task_" + currentTaskId);
    try {
        Files.createDirectories(currentDraftsDir);

        int sortOrder = 0;
        for (ModuleMapEntry entry : entries) {
            if (entry.moduleName == null || existingNames.contains(entry.moduleName)) {
                continue;  // 幂等：跳过已有
            }

            // 5a. release 中的模块文件
            Path releaseFile = modulesDir.resolve(entry.fileName);
            if (!Files.exists(releaseFile)) {
                throw new BusinessException(
                        "基线 release 模块文件缺失：moduleName=" + entry.moduleName
                                + "，fileName=" + entry.fileName + "，基线数据可能已损坏");
            }

            // 5b. 复制到本次 drafts 目录
            String safeName = entry.moduleName.replaceAll("[\\s/\\(\\)]", "_") + ".md";
            Path currentFile = currentDraftsDir.resolve(safeName);
            Files.copy(releaseFile, currentFile, StandardCopyOption.REPLACE_EXISTING);
            copiedFiles.add(currentFile);

            // 5c. 计算 hash
            String content = Files.readString(currentFile);
            String hash = DigestUtils.md5DigestAsHex(content.getBytes());

            // 5d. 构造新 DB 行
            String relativeDocPath = "task_" + currentTaskId + "/" + safeName;
            KnowledgeDraft copy = new KnowledgeDraft();
            copy.setWorkspaceId(currentWorkspaceId);
            copy.setParentId(null);
            copy.setFilePath(relativeDocPath);
            copy.setModuleName(entry.moduleName);
            // ⚠️ buildDraftUri 的 fileName 参数必须传 relativeDocPath（含 task_{id}/ 前缀），
            // 因为 resolveDraftUri 会 strip 掉第一个 task_{xxx}/ 段。
            // buildDraftUri(sysId, repoId, taskId, relativeDocPath)
            //   → "draft:{sysId}:{repoId}:task_{id}/task_{id}/{safeName}"
            // resolveDraftUri 解析时 strip 第一个 "task_{id}/" → "task_{id}/{safeName}"
            //   → draftsRoot().resolve("task_{id}/{safeName}") ← 与 currentFile 一致 ✓
            copy.setContentUri(DraftFileUtil.buildDraftUri(
                    repo.getSystemId(), repositoryId, currentTaskId, relativeDocPath));
            copy.setStatus("CONFIRMED"); // 继承文档默认已确认（基线已复核通过），无需人工再确认
            copy.setSortOrder(sortOrder++);
            copy.setHash(hash);
            copy.setBaselineTaskId(baselineTaskId);  // 标记为基线继承
            copy.setCreatedDate(now);
            copy.setUpdatedDate(now);
            toInsert.add(copy);
        }

        // 6. 批量插入 DB（事务内）
        for (KnowledgeDraft d : toInsert) {
            draftMapper.insert(d);
        }

        copiedFiles.clear();  // 成功，不需要清理
        log.info("基线草稿继承 — taskId={} ← releaseDir={} versionNum={} 复制 {} 份（跳过已有 {} 份）",
                currentTaskId, releaseDir, version.getVersionNum(),
                toInsert.size(), existingNames.size());
        return toInsert.size();

    } catch (Exception e) {
        // 失败时清理已复制的文件（DB 行靠 @Transactional 回滚）
        for (Path p : copiedFiles) {
            try { Files.deleteIfExists(p); }
            catch (Exception cleanupEx) { log.debug("清理残留文件失败: {}", p, cleanupEx); }
        }
        log.error("基线草稿继承失败 — taskId={} ← releaseDir={}", currentTaskId, releaseDir, e);
        if (e instanceof BusinessException bex) throw bex;
        throw new BusinessException("基线草稿继承失败: " + e.getMessage());
    }
}
```

### 4.4 `module-map.yaml` 解析

**YAML 格式**（由 `KnowledgeServiceImpl.createVersion` 生成）：

```yaml
modules:
  - name: "订单管理 / 订单报价 / 订单报价查询"
    path: "docs/code-insight/modules/订单管理___订单报价___订单报价查询.md"
  - name: "用户管理 / 用户注册"
    path: "docs/code-insight/modules/用户管理___用户注册.md"
```

**解析实现**（手动行解析，不引入 YAML 依赖）：

```java
/** module-map.yaml 中的一条模块映射 */
private record ModuleMapEntry(String moduleName, String fileName) {}

private List<ModuleMapEntry> parseModuleMapYaml(Path yamlFile) {
    List<ModuleMapEntry> entries = new ArrayList<>();
    try {
        List<String> lines = Files.readAllLines(yamlFile);
        String pendingName = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- name:")) {
                pendingName = parseYamlQuotedValue(trimmed.substring("- name:".length()));
            } else if (trimmed.startsWith("path:") && pendingName != null) {
                String path = parseYamlQuotedValue(trimmed.substring("path:".length()));
                String fileName = path;
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash >= 0) fileName = path.substring(lastSlash + 1);
                entries.add(new ModuleMapEntry(pendingName, fileName));
                pendingName = null;
            }
        }
    } catch (IOException e) {
        throw new BusinessException("解析 module-map.yaml 失败: " + e.getMessage());
    }
    return entries;
}

/** 提取 YAML 引号内的值："value" 或 'value' → value */
private String parseYamlQuotedValue(String raw) {
    String s = raw.trim();
    if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"")
            || s.startsWith("'") && s.endsWith("'"))) {
        return s.substring(1, s.length() - 1);
    }
    return s;
}
```

### 4.5 关键设计

| 要点 | 说明 |
|---|---|
| **数据源** | `releases/{sysId}/{repoId}/{versionNum}/` 目录（不可变权威存储），不是 `drafts/task_{baselineTaskId}/` |
| **moduleName 来源** | `meta/module-map.yaml` 的 `name` 字段，不是 `ci_knowledge_draft.module_name` DB 行 |
| **baselineTaskId 来源** | `KnowledgeVersion.taskId`（创建该发布版本的任务），不是 `IncrementalContext.baselineTaskId` |
| **幂等** | 查 `existingNames`，已有同 `module_name` 的 draft 跳过，不重复复制、不覆盖 AI 已生成的 |
| **Fail-fast** | release 目录 / module-map.yaml / 模块文件任一缺失 → 抛异常 → `@Transactional` 回滚 → 任务 FAILED |
| **contentUri 构造** | `DraftFileUtil.buildDraftUri(sysId, repoId, taskId, relativeDocPath)`，`relativeDocPath` 含 `task_{id}/` 前缀（见 §4.3 注释） |
| **hash** | 复制后重新计算 `Files.readString` + `DigestUtils.md5DigestAsHex`，不沿用基线 hash |
| **status** | `CONFIRMED`（继承文档默认已确认 — 基线已复核通过，无需人工再确认；DIFF 视图不展示，全量视图展示为「已确认」） |
| **baselineTaskId** | 标记为基线继承，DIFF 分桶时用于区分 inherited vs modified vs new |

### 4.6 `BaselineInheritanceService` 新增的 Autowired 依赖

```java
@Autowired
private KnowledgeDraftMapper draftMapper;

@Autowired
private EnvStorageResolver storageResolver;

@Autowired
private CodeRepositoryMapper repositoryMapper;   // 新增：查 last_published_version_id

@Autowired
private KnowledgeVersionMapper versionMapper;    // 新增：查版本详情
```

### 4.7 下游简化效果

| 下游 | 改造前 | 改造后 |
|---|---|---|
| `getWorkspaceTree` | 合并 current + baseline，按 module_name 去重 | 只查 current（已全），`baseline_task_id` 标继承 |
| `getWorkspaceTreeDiff` | 需从两个 workspace 算差集 | 从 current + releases/module-map.yaml 算差集（见 §六） |
| `createVersion` | 只读 current → 漏继承 → 残缺 | 只读 current → 已全（不需改） |
| `exportZip` | 读 docs/code-insight → 残缺 | docs/code-insight 已全（不需改） |
| `NasPushStrategy` | 只读 current → 漏继承 → 残缺 | 只读 current → 已全（不需改） |
| `getDocumentDiff` | 从两个 workspace 分别读正文 | 从 current 读本次，从 releases 读基线（内联返回）（见 §七） |
| `AiSummaryServiceImpl` | 设置 `baselineWorkspaceId` + 合并基线查询 | 不设 `baselineWorkspaceId`，workspace 自包含 |

**三个已有缺陷全部消除**——不需要改 `createVersion` / `exportZip` / `NasPushStrategy` 的查询逻辑。

---

## 五、功能粒度重生成接入反向 BFS

### 5.1 重生成触发条件（三条件 OR）

```text
条件 1（git diff 直命中）：
  fn.classPaths ∩ changedFqSet ≠ ∅
  // 覆盖：变更类直接出现在功能的 classPaths 中

条件 2（反向 BFS 命中）：
  fn.classPaths ∩ bfsHitClassNames ≠ ∅
  // bfsHitClassNames = impact.hierarchyRetargetEntries.className
  // 覆盖：入口直接变更 + 依赖变更反向追到入口

条件 3（入口内容变更）：
  fn.classPaths ∩ entryModifiedClassNames ≠ ∅
  // entryModifiedClassNames = entrypointDiff.modifiedRows.className
  // 覆盖：入口 bodyHash 变了，即使模块结构没变也要重生成
```

**不重生成**：三个条件都不满足 → 沿用继承的基线文档，DIFF 标「继承」。

### 5.2 `generateDraftDocument` 改造（`AiSummaryServiceImpl.java`）

在 `generateDraftDocument` 入口处构建 `bfsHitClassNames` 和 `entryModifiedClassNames`：

```java
if ("function".equalsIgnoreCase(docGenerationGranularity)) {
    // 构建反向 BFS 命中的入口类名集合
    Set<String> bfsHitClassNames = null;
    if (impact != null && impact.isIncremental() && !impact.getHierarchyRetargetEntries().isEmpty()) {
        bfsHitClassNames = new HashSet<>();
        for (EntryPoint ep : impact.getHierarchyRetargetEntries()) {
            if (ep.getClassName() != null) bfsHitClassNames.add(ep.getClassName());
        }
    }

    // 构建入口 DIFF「内容变更」的类名集合
    Set<String> entryModifiedClassNames = null;
    if (effective.isIncremental()) {
        try {
            EntrypointDiffDto epDiff = entrypointReviewService.getEntrypointDiff(taskId);
            if (epDiff != null && epDiff.getModifiedRows() != null && !epDiff.getModifiedRows().isEmpty()) {
                entryModifiedClassNames = new HashSet<>();
                for (EntrypointReviewView v : epDiff.getModifiedRows()) {
                    if (v.getClassName() != null) entryModifiedClassNames.add(v.getClassName());
                }
            }
        } catch (Exception e) {
            log.warn("获取入口 DIFF 失败，跳过入口内容变更检测 — taskId={}", taskId, e);
        }
    }

    generateDraftDocumentByFunction(task, ws, hierarchy, projectDir, effective,
        changedFqSet, bfsHitClassNames, entryModifiedClassNames);
}
```

### 5.3 `functionTouchedByIncremental` 判定方法

```java
private boolean functionTouchedByIncremental(
        FunctionDto fn,
        Set<String> changedFqSet,
        Set<String> bfsHitClassNames,
        Set<String> entryModifiedClassNames) {
    if (fn.getClassPaths() == null || fn.getClassPaths().isEmpty()) {
        return false;
    }
    for (String cp : fn.getClassPaths()) {
        if (cp == null) continue;
        if (changedFqSet != null && changedFqSet.contains(cp)) return true;       // 条件 1
        if (bfsHitClassNames != null && bfsHitClassNames.contains(cp)) return true; // 条件 2
        if (entryModifiedClassNames != null && entryModifiedClassNames.contains(cp)) return true; // 条件 3
    }
    return false;
}
```

### 5.4 `generateDraftDocumentByFunction` 中的调用

```java
for (ModuleDto m : hierarchy.getModules().values()) {
    for (SubModuleDto sm : m.getSubModules().values()) {
        for (FunctionDto fn : sm.getFunctions().values()) {
            fnIndex++;
            // 增量判定：三个条件任一满足即重生成
            if (changedFqSet != null || bfsHitClassNames != null || entryModifiedClassNames != null) {
                boolean touched = functionTouchedByIncremental(
                    fn, changedFqSet, bfsHitClassNames, entryModifiedClassNames);
                if (!touched) { skipped++; continue; }  // 沿用继承文档
            }
            // ... 调 AI 生成 ...
        }
    }
}
```

### 5.5 `AiSummaryServiceImpl` 移除的旧逻辑

```java
// 已移除：不再设置 baselineWorkspaceId
// v2: 纯 release 方案 — BASELINE_DOC_INHERIT 已将基线文档复制到本次 workspace，
// workspace 自包含，不再设置 baselineWorkspaceId（避免 getWorkspaceTree 合并基线草稿）。
```

### 5.6 `upsertFunctionDraft` 草稿查询与覆盖修复（v2 bug fix）

**问题**：`inheritDrafts` 用扁平 `filePath`（`task_{id}/flat_name.md`）创建继承草稿，AI 的 `upsertFunctionDraft` 用嵌套 `filePath`（`task_{id}/Module/Sub/Function.md`）查询 → 查不到继承草稿 → 创建第二份草稿。两份草稿同名 `moduleName` 但不同 `filePath`，前端 `indexDrafts` 的 `byModuleName` Map 后者覆盖前者，若继承草稿排在后面则树节点指向继承草稿 → DIFF 视图错误标记为"继承"。

**修复**：`upsertFunctionDraft` 增加 `moduleName` 回退查询 + 覆盖时清 `baselineTaskId` + 重置状态。

```java
String fullModuleName = m.getModuleName() + " / " + sm.getSubModuleName() + " / " + fn.getFunctionName();

// ① 先按 filePath 查（正常路径：之前 AI 生成过、filePath 已是嵌套格式）
KnowledgeDraft draft = knowledgeDraftMapper.selectOne(
        new LambdaQueryWrapper<KnowledgeDraft>()
                .eq(KnowledgeDraft::getWorkspaceId, ws.getId())
                .eq(KnowledgeDraft::getFilePath, relativeDocPath));

// ② filePath 未找到时按 moduleName 查（增量场景：inheritDrafts 用扁平 filePath 复制了基线草稿，
//    AI 重生成时 filePath 不匹配，但 moduleName 一致 → 找到继承草稿并覆盖）
if (draft == null) {
    draft = knowledgeDraftMapper.selectOne(
            new LambdaQueryWrapper<KnowledgeDraft>()
                    .eq(KnowledgeDraft::getWorkspaceId, ws.getId())
                    .eq(KnowledgeDraft::getModuleName, fullModuleName));
}

if (draft == null) {
    // ③ 插入新草稿
    draft = new KnowledgeDraft();
    draft.setWorkspaceId(ws.getId());
    draft.setFilePath(relativeDocPath);
    draft.setModuleName(fullModuleName);
    draft.setContentUri(contentUri);
    draft.setStatus(initialStatus);
    draft.setHash(hash);
    draft.setCreatedDate(LocalDateTime.now());
    draft.setUpdatedDate(LocalDateTime.now());
    knowledgeDraftMapper.insert(draft);
} else {
    // ④ 覆盖已有草稿（无论继承还是之前 AI 生成）
    draft.setFilePath(relativeDocPath);     // 确保 filePath 更新为 AI 嵌套格式
    draft.setContentUri(contentUri);
    draft.setHash(hash);
    draft.setStatus(initialStatus);          // AI 重生成 → 需重新复核
    draft.setBaselineTaskId(null);           // AI 重生成 → 不再是基线继承，DIFF 分桶标记为 modified
    draft.setUpdatedDate(LocalDateTime.now());
    knowledgeDraftMapper.updateById(draft);
}
```

> **注意**：覆盖继承草稿后，旧的扁平 `filePath` 指向的物理文件成为孤儿（无 DB 行引用），不影响功能但占用磁盘。如需清理可在覆盖时 `Files.deleteIfExists(oldFile)`。

---

## 六、树级 DIFF 改造

### 6.1 `DraftTreeDiffDto`

```java
@Data
public class DraftTreeDiffDto {
    private List<DraftTreeNode> newRows = new ArrayList<>();       // 本次新增（baseline 无）
    private List<DraftTreeNode> modifiedRows = new ArrayList<>();  // AI 重生成，匹配基线模块名
    private List<DraftTreeNode> inheritedRows = new ArrayList<>(); // 从基线 release 复制（baselineTaskId != null）
    private List<DraftTreeNode> deletedRows = new ArrayList<>();   // 基线有 + 本次无（真删除）
}
```

### 6.2 `getWorkspaceTreeDiff` 实现（`DraftServiceImpl.java`）

**核心变化**：基线模块名从 `releases/meta/module-map.yaml` 读取，不再从 `baselineWorkspaceId` 查 DB。

```java
public DraftTreeDiffDto getWorkspaceTreeDiff(Long workspaceId) {
    DraftTreeDiffDto result = new DraftTreeDiffDto();
    DraftWorkspace ws = workspaceMapper.selectById(workspaceId);
    if (ws == null) return result;

    // 1. 本次 workspace 草稿（已自包含）
    List<KnowledgeDraft> currentDrafts = draftMapper.selectList(
            new LambdaQueryWrapper<KnowledgeDraft>()
                    .eq(KnowledgeDraft::getWorkspaceId, workspaceId));
    Set<String> currentModuleNames = new HashSet<>();
    for (KnowledgeDraft d : currentDrafts) {
        if (d.getModuleName() != null) currentModuleNames.add(d.getModuleName());
    }

    // 2. 基线模块名从 releases 目录的 module-map.yaml 读取
    Set<String> baselineModuleNames = new HashSet<>();
    Long baselineTaskId = null;
    if (ws.getRepositoryId() != null) {
        CodeRepository repo = repositoryMapper.selectById(ws.getRepositoryId());
        if (repo != null && repo.getLastPublishedVersionId() != null) {
            KnowledgeVersion version = knowledgeVersionMapper.selectById(
                repo.getLastPublishedVersionId());
            if (version != null && "PUSHED".equals(version.getStatus())) {
                baselineTaskId = version.getTaskId();
                Path releaseDir = storageResolver.releaseDir(
                    version.getSystemId(), version.getRepositoryId(), version.getVersionNum());
                Path mapFile = releaseDir.resolve("meta").resolve("module-map.yaml");
                if (Files.exists(mapFile)) {
                    baselineModuleNames.addAll(parseModuleMapNames(mapFile));
                }
            }
        }
    }

    // 3. 分类（按 baselineTaskId 字段区分）
    List<KnowledgeDraft> newDrafts = new ArrayList<>();
    List<KnowledgeDraft> modifiedDrafts = new ArrayList<>();
    List<KnowledgeDraft> inheritedDrafts = new ArrayList<>();
    for (KnowledgeDraft d : currentDrafts) {
        if (d.getModuleName() == null) continue;
        if (d.getBaselineTaskId() != null) {
            inheritedDrafts.add(d);         // 从 release 复制，未重跑
        } else if (baselineModuleNames.contains(d.getModuleName())) {
            modifiedDrafts.add(d);          // AI 重生成，匹配基线模块名
        } else {
            newDrafts.add(d);               // AI 生成，基线无
        }
    }

    // 4. deleted = 基线有 + 本次无 — 从 module-map.yaml 构造虚拟节点
    List<DraftTreeNode> deletedNodes = new ArrayList<>();
    long syntheticId = -1;
    for (String name : baselineModuleNames) {
        if (!currentModuleNames.contains(name)) {
            DraftTreeNode node = new DraftTreeNode();
            node.setId(syntheticId--);      // 合成负 ID，避免与真实 draft ID 冲突
            node.setModuleName(name);
            node.setBaselineTaskId(baselineTaskId);
            node.setIsFolder(false);
            node.setSortOrder(0);
            deletedNodes.add(node);
        }
    }

    result.setNewRows(buildTree(newDrafts));
    result.setModifiedRows(buildTree(modifiedDrafts));
    result.setInheritedRows(buildTree(inheritedDrafts));
    result.setDeletedRows(deletedNodes);    // 已是 List<DraftTreeNode>，不需要 buildTree
    return result;
}
```

### 6.3 `DraftServiceImpl` 新增的 Autowired 依赖

```java
@Autowired
private CodeRepositoryMapper repositoryMapper;  // 已有

@Autowired
private EnvStorageResolver storageResolver;     // 已有

@Autowired
private KnowledgeVersionMapper knowledgeVersionMapper;  // 新增
```

### 6.4 `parseModuleMapNames` 辅助方法

```java
/** 解析 module-map.yaml，返回所有模块名 */
private Set<String> parseModuleMapNames(Path yamlFile) {
    Set<String> names = new LinkedHashSet<>();
    try {
        List<String> lines = Files.readAllLines(yamlFile);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- name:")) {
                names.add(parseYamlQuotedValue(trimmed.substring("- name:".length())));
            }
        }
    } catch (IOException e) {
        log.warn("解析 module-map.yaml 失败: {}", yamlFile, e);
    }
    return names;
}
```

---

## 七、正文级 DIFF API

### 7.1 `DocumentDiffDto`（v2 — 基线正文内联返回）

```java
@Data
public class DocumentDiffDto {
    /** 基线正文内容（直接从 releases 目录读取，无基线匹配时为 null） */
    private String baselineContent;

    /** 本次草稿的 contentUri */
    private String currentContentUri;

    /** 基线 moduleName（用于前端标题展示） */
    private String baselineModuleName;

    /** 本次草稿的 moduleName */
    private String currentModuleName;

    /** 本次草稿 ID */
    private Long currentDraftId;
}
```

> **为什么基线正文内联返回而不是返回 `baselineDraftId`？**
> 因为基线 draft 的物理文件已被 `NasPushStrategy` 删除，前端调 `getDraftContent(baselineDraftId)` 会找不到文件。直接从 releases 目录读取正文内容内联返回，前端不需要二次请求。

### 7.2 `DraftService.getDocumentDiff` 实现

```java
public DocumentDiffDto getDocumentDiff(Long draftId) {
    KnowledgeDraft draft = draftMapper.selectById(draftId);
    if (draft == null) throw new BusinessException("草稿不存在: " + draftId);
    DraftWorkspace ws = workspaceMapper.selectById(draft.getWorkspaceId());
    if (ws == null) throw new BusinessException("工作区不存在");

    DocumentDiffDto dto = new DocumentDiffDto();
    dto.setCurrentDraftId(draft.getId());
    dto.setCurrentContentUri(draft.getContentUri());
    dto.setCurrentModuleName(draft.getModuleName());

    // 基线正文从 releases 目录读取
    if (draft.getModuleName() != null && ws.getRepositoryId() != null) {
        CodeRepository repo = repositoryMapper.selectById(ws.getRepositoryId());
        if (repo != null && repo.getLastPublishedVersionId() != null) {
            KnowledgeVersion version = knowledgeVersionMapper.selectById(
                repo.getLastPublishedVersionId());
            if (version != null && "PUSHED".equals(version.getStatus())) {
                Path releaseDir = storageResolver.releaseDir(
                    version.getSystemId(), version.getRepositoryId(), version.getVersionNum());
                Path mapFile = releaseDir.resolve("meta").resolve("module-map.yaml");
                if (Files.exists(mapFile)) {
                    String baselineFileName = findModuleFileNameInYaml(mapFile, draft.getModuleName());
                    if (baselineFileName != null) {
                        Path baselineFile = releaseDir.resolve("modules").resolve(baselineFileName);
                        if (Files.exists(baselineFile)) {
                            try {
                                dto.setBaselineContent(Files.readString(baselineFile));
                                dto.setBaselineModuleName(draft.getModuleName());
                            } catch (IOException e) {
                                log.warn("读取基线 release 文件失败: {}", baselineFile, e);
                            }
                        }
                    }
                }
            }
        }
    }
    return dto;
}
```

### 7.3 `findModuleFileNameInYaml` 辅助方法

```java
/** 在 module-map.yaml 中按 moduleName 查找对应的文件名 */
private String findModuleFileNameInYaml(Path yamlFile, String moduleName) {
    try {
        List<String> lines = Files.readAllLines(yamlFile);
        String pendingName = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- name:")) {
                pendingName = parseYamlQuotedValue(trimmed.substring("- name:".length()));
            } else if (trimmed.startsWith("path:") && pendingName != null) {
                if (moduleName.equals(pendingName)) {
                    String path = parseYamlQuotedValue(trimmed.substring("path:".length()));
                    int lastSlash = path.lastIndexOf('/');
                    return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
                }
                pendingName = null;
            }
        }
    } catch (IOException e) {
        log.warn("解析 module-map.yaml 失败: {}", yamlFile, e);
    }
    return null;
}
```

### 7.4 `DraftController` 路由

```java
@GetMapping("/{id}/content-diff")
public ApiResponse<DocumentDiffDto> getDocumentDiff(@PathVariable Long id) {
    return ApiResponse.success(draftService.getDocumentDiff(id));
}
```

路径：`GET /api/drafts/{draftId}/content-diff`

### 7.5 `DraftService` 接口

```java
DocumentDiffDto getDocumentDiff(Long draftId);
```

---

## 八、前端接线

### 8.1 `api/draft.ts`

```typescript
// 1. DraftTreeDiffDto（加 modifiedRows）
export interface DraftTreeDiffDto {
  newRows: DraftTreeNode[];
  modifiedRows: DraftTreeNode[];   // 新增
  inheritedRows: DraftTreeNode[];
  deletedRows: DraftTreeNode[];
}

// 2. DocumentDiffDto（基线正文内联返回）
export interface DocumentDiffDto {
  baselineContent: string | null;   // 直接从 releases 读取的内联正文
  currentContentUri: string;
  baselineModuleName: string | null;
  currentModuleName: string;
  currentDraftId: number;
}

// 3. API
export function getDocumentDiff(draftId: number): Promise<DocumentDiffDto> {
  return request.get(`/drafts/${draftId}/content-diff`);
}
```

### 8.2 `api/task.ts`

```typescript
export const retryBaselineInherit = (id: number): Promise<void> => {
  return request.post(`/tasks/${id}/retry-baseline-inherit`);
};
```

### 8.3 `pages/drafts/workspace.tsx` — DIFF/全量 tab 语义 + 变更类型 Badge + 模块层级 DIFF

#### 8.3.1 新增 import

```typescript
// api/draft.ts 新增
import {
  // ... 原有 import ...
  getWorkspaceTreeDiff,
  type DraftTreeDiffDto,
} from '../../api/draft';

// api/task.ts 新增
import { confirmTask, getTask, getModuleHierarchyDiff, type ModuleHierarchyDiffDto } from '../../api/task';
```

#### 8.3.2 新增状态变量

```typescript
// ============ 增量 DIFF 桶 + 层级 DIFF（INCREMENTAL 任务） ============
const [diffBuckets, setDiffBuckets] = useState<DraftTreeDiffDto | null>(null);
const [hierarchyDiff, setHierarchyDiff] = useState<ModuleHierarchyDiffDto | null>(null);
/** 递增计数器，用于手动触发 diff 桶刷新（approve 后调用） */
const [diffVersion, setDiffVersion] = useState(0);
```

#### 8.3.3 DIFF 桶 + 层级 DIFF 加载

```typescript
// INCREMENTAL 任务加载 DIFF 桶 + 层级 DIFF（用于过滤 + Badge）
useEffect(() => {
  if (!workspace?.id || !selectedTask || selectedTask.type !== 'INCREMENTAL') {
    setDiffBuckets(null);
    setHierarchyDiff(null);
    return;
  }
  Promise.all([
    getWorkspaceTreeDiff(workspace.id).catch(() => null),
    getModuleHierarchyDiff(taskId).catch(() => null),
  ]).then(([buckets, hDiff]) => {
    setDiffBuckets(buckets);
    setHierarchyDiff(hDiff);
  });
}, [workspace?.id, selectedTask, taskId, diffVersion]);
```

approve 后刷新 diff 桶：

```typescript
// handleApprove 中
await approveDraft(draftIdToApprove);
const synced = await syncWorkspaceTreeFromServer();
setDiffVersion((v) => v + 1); // 刷新 DIFF 桶
```

#### 8.3.4 `draftDiffTypeMap` — draftId → 变更类型映射

```typescript
const draftDiffTypeMap = useMemo(() => {
  const map = new Map<number, 'new' | 'modified' | 'inherited' | 'deleted'>();
  if (!diffBuckets) return map;
  diffBuckets.newRows.forEach((n) => n.id > 0 && map.set(n.id, 'new'));
  diffBuckets.modifiedRows.forEach((n) => n.id > 0 && map.set(n.id, 'modified'));
  diffBuckets.inheritedRows.forEach((n) => n.id > 0 && map.set(n.id, 'inherited'));
  diffBuckets.deletedRows.forEach((n) => n.id > 0 && map.set(n.id, 'deleted'));
  return map;
}, [diffBuckets]);
```

#### 8.3.5 `hierarchyDiffTypeMap` — 模块/子模块/功能名称路径 → 变更类型映射

```typescript
const hierarchyDiffTypeMap = useMemo(() => {
  const map = new Map<string, 'new' | 'modified' | 'inherited' | 'deleted'>();
  if (!hierarchyDiff) return map;
  const collectNames = (h, type) => {
    if (!h?.modules) return;
    Object.values(h.modules).forEach((m) => {
      if (m.moduleName) map.set(m.moduleName, type);
      if (m.subModules) {
        Object.values(m.subModules).forEach((sm) => {
          if (sm.subModuleName) map.set(`${m.moduleName}/${sm.subModuleName}`, type);
          if (sm.functions) {
            Object.values(sm.functions).forEach((fn) => {
              if (fn.functionName)
                map.set(`${m.moduleName}/${sm.subModuleName}/${fn.functionName}`, type);
            });
          }
        });
      }
    });
  };
  collectNames(hierarchyDiff.newHierarchy, 'new');
  collectNames(hierarchyDiff.modifiedHierarchy, 'modified');
  collectNames(hierarchyDiff.inheritedHierarchy, 'inherited');
  collectNames(hierarchyDiff.deletedHierarchy, 'deleted');
  return map;
}, [hierarchyDiff]);
```

#### 8.3.6 `taggedHierarchyTree` — 为层级树节点添加 diffType + 追加已删除节点

```typescript
const taggedHierarchyTree = useMemo((): DraftHierarchyTreeNode[] => {
  // 1. 为现有节点标记 diffType
  const tagNodes = (nodes, ancestors) =>
    nodes.map((n) => {
      const pathParts = [...ancestors, n.title];
      const path = pathParts.join('/');
      let diffType;
      if (n.nodeType === 'FUNCTION' && n.draftId != null) {
        diffType = draftDiffTypeMap.get(n.draftId);
      }
      if (!diffType && hierarchyDiffTypeMap.size > 0) {
        diffType = hierarchyDiffTypeMap.get(path);
        if (!diffType && n.nodeType === 'MODULE') {
          diffType = hierarchyDiffTypeMap.get(n.title);
        }
      }
      const children = n.children?.length ? tagNodes(n.children, pathParts) : undefined;
      return { ...n, diffType, children };
    });

  let result = tagNodes(hierarchyTree, []);

  // 2. 追加已删除的模块层级节点（从 hierarchyDiff.deletedHierarchy 构建）
  if (hierarchyDiff?.deletedHierarchy?.modules) {
    const deletedNodes = [];
    Object.values(hierarchyDiff.deletedHierarchy.modules).forEach((m) => {
      const modNode = {
        key: `deleted-mod-${m.id || m.moduleName}`,
        nodeType: 'MODULE',
        title: m.moduleName,
        hasDocument: false,
        diffType: 'deleted',
        children: [],
      };
      if (m.subModules) {
        Object.values(m.subModules).forEach((sm) => {
          const subNode = {
            key: `deleted-sub-${m.id}-${sm.id}`,
            nodeType: 'SUB_MODULE',
            title: sm.subModuleName,
            hasDocument: false,
            diffType: 'deleted',
            children: [],
          };
          if (sm.functions) {
            Object.values(sm.functions).forEach((fn) => {
              subNode.children.push({
                key: `deleted-fn-${m.id}-${sm.id}-${fn.id}`,
                nodeType: 'FUNCTION',
                title: fn.functionName,
                hasDocument: false,
                diffType: 'deleted',
                classPaths: fn.classPaths,
                methodSignatures: fn.methodSignatures,
              });
            });
          }
          modNode.children.push(subNode);
        });
      }
      deletedNodes.push(modNode);
    });
    if (deletedNodes.length > 0) result = [...result, ...deletedNodes];
  }

  return result;
}, [hierarchyTree, draftDiffTypeMap, hierarchyDiffTypeMap, hierarchyDiff]);
```

#### 8.3.7 `effectiveHierarchyTree` — DIFF 模式过滤（只展示变更文档）

```typescript
const isIncremental = selectedTask?.type === 'INCREMENTAL';

const effectiveHierarchyTree = useMemo(() => {
  if (selectedTask?.type !== 'INCREMENTAL' || displayMode !== 'diff') {
    return taggedHierarchyTree.map((n) => ({ ...n })); // 全量模式：返回全部（浅拷贝保持引用独立）
  }
  // DIFF 模式：只展示 modified + new + deleted，不含 inherited
  const filterNodes = (nodes) =>
    nodes
      .map((n) => {
        if (n.children?.length) {
          const filteredChildren = filterNodes(n.children);
          if (filteredChildren.length > 0) return { ...n, children: filteredChildren };
        }
        if (n.diffType && n.diffType !== 'inherited') return n;
        if (n.nodeType === 'FUNCTION' && n.hasDocument && !n.diffType) return null;
        if (n.nodeType !== 'FUNCTION' && !n.children?.length) return null;
        return null;
      })
      .filter((n) => n !== null);
  return filterNodes(taggedHierarchyTree);
}, [taggedHierarchyTree, selectedTask, displayMode]);
```

#### 8.3.8 使用 `effectiveHierarchyTree` 替代 `hierarchyTree`

> **⚠️ TDZ 注意 — 声明顺序**：`draftDiffTypeMap` → `hierarchyDiffTypeMap` → `taggedHierarchyTree` → `effectiveHierarchyTree` → `hierarchyFunctionCount` → `treeNodes` → `documentLeaves` 这整段 useMemo 必须定义在组件中**所有引用 `effectiveHierarchyTree` / `documentLeaves` 的代码之前**。
>
> 具体来说，这段代码要放在 `isEditorReadOnly` 之后、`flatLeaves` / `currentLeafIndex` / `allDraftsConfirmed` / 以及监听 `effectiveHierarchyTree` 的 `useEffect` 之前。否则会触发 TDZ：`Cannot access 'effectiveHierarchyTree' before initialization`。
>
> 正确顺序（`workspace.tsx` 中的实际行号）：
>
> ```
 * isReadOnly (memo)
 * isTaskLocked (memo)
 * isEditorReadOnly (memo)
 * draftDiffTypeMap (memo)              ← 约 376 行
 * hierarchyDiffTypeMap (memo)          ← 约 388 行
 * taggedHierarchyTree (memo)           ← 约 415 行
 * isIncremental (const)
 * effectiveHierarchyTree (memo)        ← 约 484 行
 * hierarchyFunctionCount (memo)        ← 约 507 行
 * treeNodes (memo)                     ← 约 510 行
 * documentLeaves (memo)                ← 约 516 行
 * flatLeaves (memo)                    ← 约 517 行
 * currentLeafIndex (memo)              ← 约 523 行
 * allDraftsConfirmed (memo)            ← 约 531 行
 * ... 后续 useEffect / handler ...
 * ```

```typescript
// 导航（上一篇/下一篇）基于过滤后的树
const documentLeaves = useMemo(
  () => collectDocumentLeaves(effectiveHierarchyTree),
  [effectiveHierarchyTree],
);

// 模块计数基于过滤后的树
const hierarchyFunctionCount = useMemo(
  () => countHierarchyFunctions(effectiveHierarchyTree),
  [effectiveHierarchyTree],
);

// AntD Tree 节点基于过滤后的树
const treeNodes = useMemo(
  () => buildHierarchyAntTreeNodes(effectiveHierarchyTree),
  [effectiveHierarchyTree],
);
```

#### 8.3.9 选中草稿失效后自动重选

```typescript
useEffect(() => {
  // 当 effectiveHierarchyTree 变化时（如切换 DIFF/全量 tab），如果当前选中不在有效树中，自动选第一个
  if (effectiveHierarchyTree.length > 0 && selectedDraftId) {
    const allLeaves = collectDocumentLeaves(effectiveHierarchyTree);
    const stillValid = allLeaves.some((leaf) => leaf.draftId === selectedDraftId);
    if (!stillValid) {
      setSelectedDraftId(findFirstDraftId(effectiveHierarchyTree));
    }
  } else if (effectiveHierarchyTree.length > 0 && !selectedDraftId) {
    setSelectedDraftId(findFirstDraftId(effectiveHierarchyTree));
  }
}, [effectiveHierarchyTree]);
```

#### 8.3.10 文档标题区变更类型 Tag

主面板标题（非全屏）：

```typescript
{isIncremental && selectedDraftId && (() => {
  const diffType = draftDiffTypeMap.get(selectedDraftId);
  if (diffType === 'new') return <Tag color="green">新增</Tag>;
  if (diffType === 'modified') return <Tag color="orange">修改</Tag>;
  if (diffType === 'inherited') return <Tag color="default">基线继承</Tag>;
  return null;
})()}
```

全屏标题：

```typescript
{selectedTask?.type === 'INCREMENTAL' && selectedDraftId && (() => {
  const diffType = draftDiffTypeMap.get(selectedDraftId);
  if (diffType === 'new') return <Tag color="green">新增</Tag>;
  if (diffType === 'modified') return <Tag color="orange">修改</Tag>;
  if (diffType === 'inherited') return <Tag color="default">基线继承</Tag>;
  const dn = findNodeInTreeData(treeData, selectedDraftId);
  if (dn?.baselineTaskId) return <Tag color="default">基线继承</Tag>;
  return <Tag color="geekblue">本次新增</Tag>;
})()}
```

#### 8.3.11 正文 DIFF（Monaco DiffEditor）

```typescript
// useEffect：viewMode='diff' 时加载内容
useEffect(() => {
  if (viewMode !== 'diff' || !selectedDraftId) {
    setDiffBaselineContent('');
    setDiffCurrentContent('');
    return;
  }
  let cancelled = false;
  setDiffLoading(true);
  getDocumentDiff(selectedDraftId)
    .then(async (dto) => {
      if (cancelled) return;
      const current = await getDraftContent(dto.currentDraftId);
      if (cancelled) return;
      setDiffCurrentContent(current);
      setDiffBaselineContent(dto.baselineContent ?? ''); // 基线正文直接从 DTO 内联取
    })
    .catch(() => { if (!cancelled) message.error('加载 DIFF 内容失败'); })
    .finally(() => { if (!cancelled) setDiffLoading(false); });
  return () => { cancelled = true; };
}, [viewMode, selectedDraftId]);

// 渲染：Monaco DiffEditor
{viewMode === 'diff' ? (
  diffLoading ? <Spin /> :
  diffBaselineContent ? (
    <DiffEditor
      height="100%"
      language="markdown"
      original={diffBaselineContent}
      modified={diffCurrentContent}
      theme="vs-light"
      options={{ readOnly: true, renderSideBySide: true, minimap: { enabled: false }, ... }}
    />
  ) : (
    <div>无基线版本可对比（本次新增文档）</div>
  )
) : (
  <Editor ... />
)}
```

#### 8.3.12 工具栏 DIFF 按钮改为变更类型感知

**设计**：将 `VIEW_MODE_OPTIONS` 中的 `diff` 选项移除，改为在 Segmented 旁边独立渲染一个变更类型感知的指示器。

```typescript
// VIEW_MODE_OPTIONS 只保留 edit / preview / split（移除 diff）
const VIEW_MODE_OPTIONS = [
  { value: 'edit', label: '编辑', icon: <EditOutlined /> },
  { value: 'preview', label: '预览', icon: <EyeOutlined /> },
  { value: 'split', label: '分屏', icon: <ColumnHeightOutlined /> },
  // diff 选项移除 — 改为下方独立按钮/Tag
];
```

**变更类型感知指示器**（在 Segmented 旁边渲染）：

| 文档变更类型 | 展示 | 可点击 | 行为 |
|---|---|---|---|
| `modified` | "比对" Button（`type=primary` 当 viewMode='diff'） | ✅ | 点击切换 `viewMode` 为 `'diff'` / `'preview'` |
| `new` | "新增" Tag（`color=green`） | ❌ | Tooltip: "本次新增文档，无基线版本可对比" |
| `inherited` | "继承" Tag（`color=default`） | ❌ | Tooltip: "基线继承文档，内容与已发布版本一致" |
| 非增量 / 无 diffType | 不展示 | — | — |

```typescript
const currentDraftDiffType = selectedDraftId ? draftDiffTypeMap.get(selectedDraftId) : undefined;

const diffIndicator = (() => {
  if (!isIncremental || !selectedDraftId) return null;
  if (currentDraftDiffType === 'modified') {
    return (
      <Tooltip title="与基线版本左右比对">
        <Button
          size="small"
          type={viewMode === 'diff' ? 'primary' : 'default'}
          icon={<EyeOutlined />}
          onClick={() => setViewMode(viewMode === 'diff' ? 'preview' : 'diff')}
        >
          比对
        </Button>
      </Tooltip>
    );
  }
  if (currentDraftDiffType === 'new') {
    return (
      <Tooltip title="本次新增文档，无基线版本可对比">
        <Tag color="green" style={{ margin: 0, cursor: 'default', fontSize: 12, padding: '2px 8px' }}>
          新增
        </Tag>
      </Tooltip>
    );
  }
  if (currentDraftDiffType === 'inherited') {
    return (
      <Tooltip title="基线继承文档，内容与已发布版本一致">
        <Tag color="default" style={{ margin: 0, cursor: 'default', fontSize: 12, padding: '2px 8px' }}>
          继承
        </Tag>
      </Tooltip>
    );
  }
  // 旧数据兼容：无 diffType 但有 baselineTaskId
  const dn = findNodeInTreeData(treeData, selectedDraftId);
  if (dn?.baselineTaskId) {
    return (
      <Tooltip title="基线继承文档，内容与已发布版本一致">
        <Tag color="default" style={{ margin: 0, cursor: 'default', fontSize: 12, padding: '2px 8px' }}>
          继承
        </Tag>
      </Tooltip>
    );
  }
  return null;
})();

// Segmented value 在 diff 模式时回退为 'preview'（不高亮任何选项）
const viewGroup = (
  <div className="ci-action-group" style={{ gap: 6 }}>
    <Tooltip title="切换文档视图：编辑 / 预览 / 分屏">
      <Segmented
        className="ci-view-group"
        value={viewMode === 'diff' ? 'preview' : viewMode}
        onChange={(v) => setViewMode(v as ViewMode)}
        options={VIEW_MODE_OPTIONS.map(...)}
      />
    </Tooltip>
    {diffIndicator}
  </div>
);
```

#### 8.3.13 "通过"按钮 → "已通过"（全量 + DIFF 一致）

已确认（`CONFIRMED` / `PUSHED`）的文档，"通过"按钮文案改为"已通过"，图标改为 `CheckOutlined`，且 `disabled` 保持 true。全量视图和 DIFF 视图行为一致。

```typescript
<Tooltip
  title={
    approveInFlight ? '正在提交通过，请稍候'
    : isCurrentDraftConfirmed ? '当前文档已通过'
    : '审核通过此文档（锁定，不可再编辑）'
  }
>
  <Button
    icon={isCurrentDraftConfirmed ? <CheckOutlined /> : <CheckCircleOutlined />}
    onClick={handleApprove}
    loading={approveInFlight}
    disabled={isTaskLocked || approveInFlight || isCurrentDraftConfirmed}
  >
    {isCurrentDraftConfirmed ? '已通过' : '通过'}
  </Button>
</Tooltip>
```

> **继承文档自动通过**：由于 `inheritDrafts` 将继承文档状态设为 `CONFIRMED`（§4.5），继承文档在全量视图中自动展示"已通过"按钮，无需人工点击。DIFF 视图中继承文档被过滤不展示。

### 8.4 `pages/tasks/detail.tsx` — 流程节点 + 按钮

```typescript
// runningStatuses 加 BASELINE_DOC_INHERIT（轮询用，与 UI 是否展示无关）
const runningStatuses = ['PENDING', 'PULLING_CODE', 'PARSING_CODE',
  'ENTRYPOINT_REVIEW', 'AI_ANALYZING', 'MODULE_HIERARCHY_REVIEW',
  'BASELINE_DOC_INHERIT', 'GENERATING_DOC', 'PUSHING'];

// statusMeta：step 索引按「含基线复制」的完整流水线编号（增量视角）
const statusMeta = {
  // ...
  BASELINE_DOC_INHERIT: { color: 'cyan', label: '基线文档继承', step: 6 },
  GENERATING_DOC: { color: 'gold', label: '生成文档', step: 7 },
  PENDING_REVIEW: { color: 'magenta', label: '待复核', step: 8 },
  REVIEWING: { color: 'geekblue', label: '复核中', step: 8 },
  // ...
};

// flowStepItems：全量不展示「基线复制」；增量才插入该步
const isIncremental = task.type === 'INCREMENTAL';
const flowCurrent = meta.step < 0 ? 0 : meta.step;
// statusMeta 中 GENERATING_DOC=7 / 复核=8 含基线步占位；全量隐藏该步后 current 需前移 1
const flowCurrentAdjusted =
  !isIncremental && flowCurrent >= 6 ? flowCurrent - 1 : flowCurrent;

const flowStepItems = [
  { title: '排队' },
  { title: '拉取代码' },
  { title: '静态解析' },
  { title: '入口复核', /* …跳过态略… */ },
  { title: 'AI 分析' },
  { title: '模块层级复核' },
  ...(isIncremental ? [{ title: '基线复制' }] : []),  // INITIAL 不渲染
  { title: '生成文档' },
  { title: '复核' },
];

// Steps 使用调整后的 current
<Steps current={flowCurrentAdjusted} items={flowStepItems} ... />

// 按钮：FAILED + INCREMENTAL 时显示
{task.status === 'FAILED' && task.type === 'INCREMENTAL' && (
  <Button onClick={() => retryBaselineInherit(task.id)}>
    重新继承基线文档
  </Button>
)}
```

| 任务类型 | 流水线是否展示「基线复制」 | Steps `current` |
|---|---|---|
| `INCREMENTAL` | ✅ 展示 | 直接用 `statusMeta.step` |
| `INITIAL` | ❌ 不渲染（勿再灰显「仅增量」占位） | `step >= 6` 时 `current = step - 1`（对齐「生成文档」「复核」） |

### 8.5 `utils/draftHierarchyTree.ts` — 新增 `diffType` 字段 + `indexDrafts` 同名优先

```typescript
export interface DraftHierarchyTreeNode {
  key: string;
  nodeType: 'MODULE' | 'SUB_MODULE' | 'FUNCTION';
  title: string;
  draftId?: number;
  hasDocument: boolean;
  draftStatus?: string;
  classPaths?: string[];
  methodSignatures?: string[];
  children?: DraftHierarchyTreeNode[];
  /** v2: 变更类型标记（INCREMENTAL 任务），用于树节点 Badge 展示 */
  diffType?: 'new' | 'modified' | 'inherited' | 'deleted';
}
```

**`indexDrafts` 同名草稿优先保留非继承的**（v2 bug fix）：

```typescript
function indexDrafts(leaves: DraftTreeNode[]) {
  const byModuleName = new Map<string, DraftTreeNode>();
  const byPath = new Map<string, DraftTreeNode>();
  for (const d of leaves) {
    if (d.moduleName) {
      const existing = byModuleName.get(d.moduleName);
      // v2: 同名草稿优先保留非继承的（baselineTaskId == null = AI 重生成/新增），
      // 避免继承草稿覆盖 AI 草稿导致 DIFF 视图错误标记为"继承"
      if (!existing || (existing.baselineTaskId != null && d.baselineTaskId == null)) {
        byModuleName.set(d.moduleName, d);
        byModuleName.set(normalizeLabel(d.moduleName), d);
      }
    }
    if (d.filePath) {
      byPath.set(d.filePath, d);
    }
  }
  return { byModuleName, byPath };
}
```

> **背景**：当 `inheritDrafts` 创建的继承草稿与 AI 重生成的草稿同名 `moduleName` 但不同 `filePath` 时，`byModuleName` Map 会保留最后遍历到的。若继承草稿排在后面，树节点 `draftId` 指向继承草稿 → `draftDiffTypeMap` 标为 "inherited" → DIFF 视图错误。此修复确保非继承草稿（AI 重生成）优先。

### 8.6 `utils/draftHierarchyTreeUi.tsx` — 树节点变更类型 Badge

```typescript
/** 变更类型 → Tag 颜色 + 文案 */
const diffTypeMeta: Record<string, { color: string; label: string }> = {
  new: { color: 'green', label: '新增' },
  modified: { color: 'orange', label: '修改' },
  inherited: { color: 'default', label: '继承' },
  deleted: { color: 'red', label: '删除' },
};

export function buildHierarchyAntTreeNodes(nodes: DraftHierarchyTreeNode[]): HierarchyTreeDataNode[] {
  return nodes.map((n) => {
    const isFunction = n.nodeType === 'FUNCTION';
    const typeMeta = NODE_TYPE_TAG[n.nodeType];
    const diffMeta = n.diffType ? diffTypeMeta[n.diffType] : null;
    const title = (
      <div className="ci-knowledge-tree-node" style={{ display: 'flex', alignItems: 'center', gap: 6, ... }}>
        <Tag color={typeMeta.color} style={{ margin: 0 }}>{typeMeta.label}</Tag>
        {/* 删除节点文字带删除线 + 灰色 */}
        <Text style={{
          fontSize: 13,
          textDecoration: n.diffType === 'deleted' ? 'line-through' : 'none',
          color: n.diffType === 'deleted' ? '#999' : undefined,
        }}>{n.title}</Text>
        {/* 变更类型 Badge */}
        {diffMeta && (
          <Tag color={diffMeta.color} style={{ margin: 0, fontSize: 11 }}>{diffMeta.label}</Tag>
        )}
        {/* 功能节点的类名、方法签名、文档状态 Tag（原有逻辑不变） */}
        ...
      </div>
    );

    return {
      key: n.key,
      title,
      draftId: n.draftId,
      // 删除节点不可选中
      selectable: isFunction && n.hasDocument && n.diffType !== 'deleted',
      children: n.children?.length ? buildHierarchyAntTreeNodes(n.children) : undefined,
    };
  });
}
```

### 8.7 `eslint.config.js` — 禁用 `react-hooks/immutability`

```javascript
rules: {
  // ... 原有规则 ...
  'react-hooks/immutability': 'off',  // 新增：effectiveHierarchyTree 浅拷贝返回触发此规则，已禁用
}
```

### 8.8 其他前端文件同步更新

以下文件均需将 `BASELINE_DOC_INHERIT` 加入 `statusMeta` / `runningStatuses` / `STAGE_LABELS`：

| 文件 | 更新点 |
|---|---|
| `pages/tasks/TaskListTab.tsx` | `statusMeta` 加 `BASELINE_DOC_INHERIT` |
| `pages/dashboard/index.tsx` | `runningStatuses` + `statusMeta` |
| `pages/dashboard/pipeline-analysis.tsx` | `STAGE_LABELS` + `colorMap` |
| `pages/tasks/hierarchy-review.tsx` | `statusMeta` |
| `pages/tasks/entrypoint-review.tsx` | `statusMeta` |

---

## 九、文件清单

### 后端改动文件

| # | 文件 | 改动 |
|---|---|---|
| 1 | `TaskStatus.java` | 新增 `BASELINE_DOC_INHERIT` 枚举值 |
| 2 | `TaskStateMachineServiceImpl.java` | 合法转换 + 进度 85% |
| 3 | `BaselineInheritanceService.java` | 重写 `inheritDrafts`（纯 release 方案）+ `parseModuleMapYaml` + `ModuleMapEntry` record |
| 4 | `DecompileTaskServiceImpl.java` | `runBaselineDocInheritAndGenerateDoc` + `retryBaselineInherit` + `impactCache` + 调用点传 `repositoryId` |
| 5 | `DecompileTaskService.java` | 接口加 `retryBaselineInherit` |
| 6 | `DecompileTaskController.java` | `POST /{id}/retry-baseline-inherit` |
| 7 | `AiSummaryServiceImpl.java` | `generateDraftDocument` 构建 `bfsHitClassNames` + `entryModifiedClassNames`；`functionTouchedByIncremental` 三条件判定；移除 `baselineWorkspaceId` 设置 |
| 8 | `DraftTreeDiffDto.java` | 加 `modifiedRows` 字段 |
| 9 | `DocumentDiffDto.java` | v2 — `baselineContent` 内联返回，移除 `baselineContentUri` / `baselineDraftId` |
| 10 | `DraftServiceImpl.java` | `getWorkspaceTreeDiff` 从 releases 读基线模块名 + `getDocumentDiff` 从 releases 读基线正文 + `parseModuleMapNames` + `findModuleFileNameInYaml` |
| 11 | `DraftService.java` | 接口加 `getDocumentDiff` |
| 12 | `DraftController.java` | `GET /{id}/content-diff` |

### 前端改动文件

| # | 文件 | 改动 |
|---|---|---|
| 13 | `api/draft.ts` | `DocumentDiffDto` 接口更新 + `DraftTreeDiffDto` 加 `modifiedRows` + `getDocumentDiff` |
| 14 | `api/task.ts` | `retryBaselineInherit` + `getModuleHierarchyDiff` 导入（workspace.tsx 使用） |
| 15 | `pages/drafts/workspace.tsx` | DIFF/全量 tab 语义改造（DIFF 默认页只展示变更文档）+ 变更类型 Badge（树+标题）+ 模块层级 DIFF（`getModuleHierarchyDiff` + 追加删除节点）+ `effectiveHierarchyTree` 过滤 + `draftDiffTypeMap` / `hierarchyDiffTypeMap` + `DiffEditor` + 选中失效自动重选 |
| 16 | `utils/draftHierarchyTree.ts` | `DraftHierarchyTreeNode` 新增 `diffType` 字段 |
| 17 | `utils/draftHierarchyTreeUi.tsx` | `buildHierarchyAntTreeNodes` 渲染变更类型 Badge（新增/修改/继承/删除）+ 删除节点删除线样式 + 不可选中 |
| 18 | `eslint.config.js` | 禁用 `react-hooks/immutability` 规则 |
| 19 | `pages/tasks/detail.tsx` | `statusMeta` + `flowStepItems`（**全量隐藏「基线复制」** + `flowCurrentAdjusted`）+ 「重新继承基线文档」按钮 |
| 20 | `pages/tasks/TaskListTab.tsx` | `statusMeta` |
| 21 | `pages/dashboard/index.tsx` | `runningStatuses` + `statusMeta` |
| 22 | `pages/dashboard/pipeline-analysis.tsx` | `STAGE_LABELS` + `colorMap` |
| 23 | `pages/tasks/hierarchy-review.tsx` | `statusMeta` |
| 24 | `pages/tasks/entrypoint-review.tsx` | `statusMeta` |

---

## 十、风险与注意

| 风险 | 说明 | 应对 |
|---|---|---|
| release 目录被删 | `inheritDrafts` 找不到 release 目录 | Fail-fast 抛异常，任务 FAILED，提示基线数据损坏 |
| `module-map.yaml` 格式变更 | 手动解析器可能不兼容 | 格式由 `KnowledgeServiceImpl.createVersion` 固定生成（`- name:` + `path:` 两行一组），不会随意变更 |
| `contentUri` 构造错误 | `buildDraftUri` 的 `fileName` 参数传错导致 `resolveDraftUri` 解析到错误路径 | 必须传 `relativeDocPath`（含 `task_{id}/` 前缀），见 §4.3 注释 |
| `getEntrypointDiff` 性能 | 重生成判定需查入口 DIFF | `generateDraftDocument` 内只查一次，缓存结果传给功能循环 |
| 反向 BFS 深度限制 | 默认深度 15，超深依赖链不命中 | 已有 `DEGRADED_CLASS_PATH` 降级兜底 |
| `changedFqSet` 与反向 BFS 重复命中 | 两个条件可能同时命中同一功能 | `touched` 布尔短路，不重复生成 |
| 复制失败后文件残留 | `@Transactional` 回滚 DB 但文件已复制 | catch 块清理 `copiedFiles` |
| 重跑幂等 | 用户多次点「重新继承基线文档」 | `existingNames` 检查，跳过已复制的 |
| INITIAL 任务不受影响 | 新节点仅 INCREMENTAL | `continueAfterEntrypointReview` / `resumeAfterHierarchyReview` 中 `task.type` 判定 |
| `deletedRows` 虚拟节点 ID | 从 `module-map.yaml` 构造的删除节点没有真实 draft ID | 使用合成负 ID（-1, -2, ...），前端不依赖 ID 查询内容 |
| `getWorkspaceTree` 旧合并逻辑 | `baselineWorkspaceId` 不再设置，合并分支不执行 | 保留旧代码但 `baselineWorkspaceId == null` 时自动跳过，无害 |
| 知识发布回滚 | 回滚只切换 `last_published_version_id` 指针，不删除 release 目录 | 回滚后 `inheritDrafts` 会读到回滚前的版本（正确行为） |
| 继承文档 `CONFIRMED` 状态影响任务整体通过 | 继承文档默认 `CONFIRMED`，`areAllDraftLeavesConfirmed` 检查会自动通过 | 设计预期：继承文档不需要人工再确认，只检查修改/新增文档是否逐篇通过 |
| DIFF tab 切换时选中失效 | 切换到 DIFF 模式后当前选中的可能是 inherited 文档，不在过滤后的树中 | `useEffect` 监听 `effectiveHierarchyTree` 变化，自动重选第一个有效文档 |
| `getModuleHierarchyDiff` 失败 | 层级 DIFF 接口异常时模块小窗无 Badge | `.catch(() => null)` 静默降级，树仍可正常展示（无 Badge） |
| `react-hooks/immutability` lint 规则 | `effectiveHierarchyTree` 浅拷贝返回触发此规则 | 已在 `eslint.config.js` 中禁用此规则（与 `exhaustive-deps` 同级处理） |
| **TDZ：`effectiveHierarchyTree` before initialization** | `draftDiffTypeMap → effectiveHierarchyTree → treeNodes → documentLeaves` 整段定义在约 1041 行，但 `documentLeaves` / `useEffect` 在约 367 行就引用了它 | **已修复**：将整段 useMemo 挪到 `isEditorReadOnly` 之后、`flatLeaves` / `currentLeafIndex` / `useEffect` 之前（约 376–516 行）。设计文档 §8.3.8 已标注声明顺序约束 |
| **DIFF 视图文档错误标记为"继承"** | `inheritDrafts` 用扁平 `filePath` 创建继承草稿，AI 用嵌套 `filePath` 查询 → 可能建第二份；或 AI 覆盖后 `setBaselineTaskId(null)` 因 MyBatis-Plus 默认忽略 null **未落库** → `getWorkspaceTreeDiff` 仍进 inherited | **已修复（三端）**：①`KnowledgeDraft.baselineTaskId` 加 `updateStrategy=FieldStrategy.ALWAYS`；②`getWorkspaceTreeDiff` 仅 CONFIRMED/PUSHED+baseline 进 inherited，AI_GENERATED 等重生成态归 modified；③前端 `indexDrafts` 同名优先非继承；④工具栏 modified 展示可点「比对」 |

---

## 十一、与已有方案的关系

| 已有方案/代码 | 本方案是否修改 | 交叉点 |
|---|---|---|
| `module-hierarchy-id-reuse-fix.md` | 不改 | 模块层级 DIFF 独立运行，本方案消费其结果但不改其逻辑 |
| `entrypoint-review-diff-design.md` | 不改 | 本方案消费 `getEntrypointDiff` 的 modified 结果，不改入口 DIFF 逻辑 |
| `IncrementalImpactAnalyzer` | 不改 | 本方案消费 `hierarchyRetargetEntries`，不改反向 BFS 计算 |
| `ci_knowledge_draft` 表结构 | 不改 | 用现有字段（hash、module_name、content_uri、baseline_task_id） |
| `module_doc_prompt.md` | 不改 | 决策 5 = 不动提示词 |
| `analyze_prompt.md` | 不改 | 模块层级归纳提示词，与文档 DIFF 无关 |
| `BaselineInheritanceService` | **改** — 重写 `inheritDrafts` 方法 | 新增 `CodeRepositoryMapper` / `KnowledgeVersionMapper` 依赖 |
| `KnowledgeServiceImpl.createVersion` | 不改（自动修复） | workspace 自包含后，现有查询逻辑自然读到完整文档 |
| `NasPushStrategy` | 不改（自动修复） | 同上 |
| `DraftServiceImpl` | **改** — `getWorkspaceTreeDiff` + `getDocumentDiff` 从 releases 读取 | 新增 `KnowledgeVersionMapper` 依赖 |
| `AiSummaryServiceImpl` | **改** — 移除 `baselineWorkspaceId` 设置 + 接入 BFS/entryModified | `generateDraftDocument` + `functionTouchedByIncremental` |
| `TaskStatus` / 状态机 | **改** — 新增 `BASELINE_DOC_INHERIT` 状态 | 仅 INCREMENTAL 任务经过 |
| `EnvStorageResolver.releaseDir` | 不改 | 已有方法，签名 `(systemId, repositoryId, versionNum) → Path` |
| `DraftFileUtil.buildDraftUri` | 不改 | 已有方法，注意 `fileName` 参数需含 `task_{id}/` 前缀 |
| `getModuleHierarchyDiff` API | 不改（消费方） | 本方案前端消费其 4 桶（new/modified/inherited/deleted hierarchy），用于模块目录小窗 Badge |
| `DraftModuleDirectory` 组件 | 不改 | 树数据由 `buildHierarchyAntTreeNodes` 生成，Badge 在节点 title 中渲染 |
| `eslint.config.js` | **改** — 禁用 `react-hooks/immutability` | `effectiveHierarchyTree` 浅拷贝触发此规则 |
