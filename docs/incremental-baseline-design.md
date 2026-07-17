# INCREMENTAL 任务重新设计：基线 + 增量

> 实施版本：v1  
> 配套设计稿：`C:\Users\sky\.claude\plans\woolly-herding-whale.md`  
> 配套门禁文档：[incremental-task-strict-gate.md](./incremental-task-strict-gate.md)

---

## 一、设计目标

**增量任务只分析变动项，并修改/新增/删除变动的知识。**

### 两个核心结论

1. **对比基准** = 仓库最新 PUSHED 的 commit（`ci_repository.last_commit_id`）
2. **数据复制源** = 仓库最新 PUSHED 的任务（`ci_repository.last_published_task_id`）

两者同源（仓库维度），不与任何非 PUSHED 任务耦合。

### 核心思想：基线快照 + 增量叠加

```
T0（PUSHED 基线）──→ git diff C0..HEAD ──→ T1（INCREMENTAL 任务）
                       changedPaths / deletedPaths
                              ↓
T1 数据 = T0 任务数据复制（继承自基线）+ T1 增量（本次新增/修改/删除）
```

---

## 二、当前实现问题（为什么要重新设计）

| 阶段 | 现状 | 问题 |
|---|---|---|
| **PARSING_CODE** | `walkAndPersist` 按 `pathFilter` 过滤（已增量） | 无 |
| **ENTRYPOINT_DISCOVERY** | `EntryPointDiscoveryServiceImpl.java:75` 调 `discoverEntriesWithMethods` → `parseDirectory` 扫**全项目**；落表 `deleteByTaskId + 全量 insert` | INCREMENTAL 也要全量扫全项目找入口 |
| **AI_ANALYZING** | 反向 BFS + `docRetargetModuleIds` 过滤（已增量） | 无 |
| **MODULE_HIERARCHY** | AI 调用是增量的，但 `persistAll` 走 `deleteByTaskId + batchInsert`（`ModuleHierarchyServiceImpl.java:232`） | 增量跳过 AI 但仍**全表重写** |
| **GENERATING_DOC** | `moduleTouchedByChange` 过滤生成，workspace 物理隔离（已增量） | 无 |
| **PUSHING** | 全量导出 artifacts | 合理（推送是终态快照） |

**核心症结**：入口识别阶段是**唯一**真正的"自上而下"全量残留；模块层级是"增量 AI 调用 + 全量写表"的混合模型。

---

## 三、数据模型改造（schema 变更）

所有 DDL 用 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 幂等迁移，写入 `backend/src/main/resources/db/schema.sql`。

### 3.1 表字段新增

| 表 | 新增字段 | 含义 | 索引 |
|---|---|---|---|
| `ci_draft_workspace` | `baseline_workspace_id BIGINT` | 本任务 workspace 引用的基线 workspace | `idx_draft_workspace_baseline (baseline_workspace_id)` |
| `ci_knowledge_draft` | `baseline_task_id BIGINT` | NULL=本次新增；非空=从该基线任务继承 | `idx_draft_workspace_baseline (workspace_id, baseline_task_id)` |
| `ci_entrypoint` | `baseline_task_id BIGINT` | 同上 | `idx_entrypoint_baseline_task (task_id, baseline_task_id)` |
| `ci_method_call` | `baseline_task_id BIGINT` | 同上 | `idx_method_call_baseline_task (task_id, baseline_task_id)` |
| `ci_module_hierarchy` | `source_entry_class VARCHAR(500)` | FUNCTION 节点关联的入口类全限定名 | `idx_module_hierarchy_source_entry (task_id, source_entry_class)` |

**索引列名约定**：
- `ci_entrypoint` / `ci_method_call` / `ci_module_hierarchy` 都有 `task_id` 列，索引首列用 `task_id`
- `ci_knowledge_draft` **没有 `task_id` 列**（task 信息在 `ci_draft_workspace` 表里），索引首列必须用 `workspace_id`
- `ci_draft_workspace` 的 `baseline_workspace_id` 是单列索引（不是 task 维度的）

### 3.2 新建表 `ci_incremental_scan`

```sql
CREATE TABLE IF NOT EXISTS ci_incremental_scan (
    task_id BIGINT PRIMARY KEY,
    system_id BIGINT NOT NULL,
    repository_id BIGINT NOT NULL,
    baseline_task_id BIGINT,                    -- 数据复制源
    baseline_commit_id VARCHAR(100),            -- 对比基准 commit
    head_commit_id VARCHAR(100),                -- 本次 HEAD commit
    scan_mode VARCHAR(20) NOT NULL,             -- INITIAL / INCREMENTAL
    changed_paths JSONB NOT NULL DEFAULT '[]',
    deleted_paths JSONB NOT NULL DEFAULT '[]',
    inherited_count INT DEFAULT 0,              -- 从基线继承的入口数
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_incremental_scan_repo ON ci_incremental_scan (repository_id, scan_mode);
```

**作用**：PULLING_CODE 完成后落盘，后续 5 阶段都从这里读 changedPaths / deletedPaths / baselineTaskId（不再依赖内存 `IncrementalContext`）。

---

## 四、5 阶段 + 推送 详细设计

### 阶段 1：PULLING_CODE（拉取 + git diff）

**保持现状** + **新增落表**：
- `CodeScannerService.pullAndScan` 走 `git diff <lastCommitId>..HEAD` 算 changedPaths / deletedPaths
- **新增**：`IncrementalScanPersistenceService` 在 PULLING_CODE 末尾落 `ci_incremental_scan` 表

```java
// CodeScannerServiceImpl.pullAndScan 末尾
incrementalScanPersistenceService.persist(taskId, repo.getId(),
    repo.getLastPublishedTaskId(),  // baselineTaskId
    repo.getLastCommitId(),          // baselineCommitId
    commitId,                        // headCommitId
    changedPaths, deletedPaths);
```

### 阶段 2：PARSING_CODE（AST + 调用链）

**改造为基线 + 增量**：

```java
// MethodCallServiceImpl.persistAstForTask 改造
public int persistAstForTask(Long taskId, File projectDir, IncrementalContext ctx) {
    IncrementalContext effective = ctx == null ? IncrementalContext.fullScan() : ctx;
    
    if (!effective.isIncremental()) {
        // INITIAL: 全量（保持原逻辑）
        deleteByTaskId(taskId);
        return walkAndPersist(projectDir, projectDir, taskId, null);
    }
    
    // INCREMENTAL: 基线 + 增量
    inheritFromBaseline(taskId, effective.getChangedPaths(), effective.getDeletedPaths());
    methodCallMapper.delete(...file_path IN deletedPaths);
    methodCallMapper.delete(...file_path IN changedPaths);
    return walkAndPersist(projectDir, projectDir, taskId, effective.getChangedPaths());
}
```

**新增 SQL** `methodCallMapper.inheritFromBaseline`：

```sql
INSERT INTO ci_method_call (task_id, ..., baseline_task_id)
SELECT ?, ..., baseline_task_id
FROM ci_method_call
WHERE task_id = ?  -- baselineTaskId
  AND file_path NOT IN (?, ?, ...);  -- changedPaths ∪ deletedPaths
```

### 阶段 3：ENTRYPOINT_DISCOVERY（入口识别）

**最大改造点**——从全量扫改为基线 + 增量。

#### 3.1 新增 `discoverEntriesInFiles`（单文件识别）

**文件**：`EntryPointDiscoveryService.java` 新增重载：

```java
List<DiscoveredEntrypoint> discoverEntriesInFiles(
        Long taskId, File projectDir, EntryPointConfig config, 
        Set<String> relativePaths);
```

**实现要点**：
- 复用 `JavaParserService.parseFile(File)` 单文件解析（已存在）
- 抽取"判定单个 ParsedClassInfo 是否为入口"的私有方法（与全量方法共享）
- `EntryPointConfig` 从任务快照读取
- **判定逻辑在单文件内**：只看类自身注解（`@RestController` / `@Scheduled` / `@RabbitListener` / OTHER）

#### 3.2 改造 `discoverAndPersist`（增量分支）

```java
// EntrypointReviewServiceImpl
public List<DiscoveredEntrypoint> discoverAndPersist(
        Long taskId, File projectDir, EntryPointConfig config, IncrementalContext ctx) {
    
    IncrementalContext effective = ctx == null ? IncrementalContext.fullScan() : ctx;
    
    if (!effective.isIncremental()) {
        // INITIAL: 全量扫全项目
        return discoverAndPersistFull(taskId, projectDir, config);
    }
    
    // INCREMENTAL: 基线 + 增量
    inheritEntrypointsFromBaseline(taskId, effective.getChangedPaths(), effective.getDeletedPaths());
    entrypointMapper.delete(...file_path IN deletedPaths);
    entrypointMapper.delete(...file_path IN changedPaths);
    List<DiscoveredEntrypoint> discovered = 
        entryPointDiscoveryService.discoverEntriesInFiles(
            taskId, projectDir, config, effective.getChangedPaths());
    for (DiscoveredEntrypoint dep : discovered) {
        EntrypointEntity row = toEntity(dep, taskId, /*baselineTaskId*/ null);
        entrypointMapper.insert(row);
    }
    return discovered;
}
```

**新增 SQL** `entrypointMapper.inheritFromBaseline`：

```sql
INSERT INTO ci_entrypoint (task_id, class_name, file_path, ..., baseline_task_id)
SELECT ?, class_name, file_path, ..., baseline_task_id
FROM ci_entrypoint
WHERE task_id = ?  -- baselineTaskId
  AND file_path NOT IN (?, ?, ...);  -- changedPaths ∪ deletedPaths
```

#### 3.3 改 `listByTaskId` / `loadEnabledEntries`

**不变**——`selectByTaskId(taskId)` 已返回 taskId 下所有行（含 `baseline_task_id != NULL` 的继承行），符合"基线 + 本次"语义。

### 阶段 4：AI_ANALYZING（影响分析）

**保持现状**（已经是增量的）：
- 反向 BFS 起点：changed FQ（来自增量 ci_method_call）
- 入口集合：`loadEnabledEntries(taskId)` 返回**继承 + 本次新增**的入口
- 产出：`hierarchyRetargetEntries` + `docRetargetModuleIds` + `traces`

**无改动**。

### 阶段 5：MODULE_HIERARCHY（模块层级）

**改造为按 entry 维度 merge**：

```java
// ModuleHierarchyServiceImpl.buildAndPersist 改造
public ModuleHierarchy buildAndPersist(Long taskId, File projectDir, IncrementalContext ctx, IncrementalImpact impact) {
    IncrementalContext effective = ctx == null ? IncrementalContext.fullScan() : ctx;
    
    if (!effective.isIncremental()) {
        // INITIAL: 全量
        return buildAndPersistFull(taskId, projectDir, ctx, impact);
    }
    
    // INCREMENTAL: 基线 + 增量
    inheritHierarchyFromBaseline(taskId);
    ModuleHierarchy hierarchy = loadByTaskId(taskId);
    
    // AI 重提炼只对 retarget 入口
    List<EntryPoint> toProcess = entries.stream()
        .filter(impact::isHierarchyRetarget)
        .toList();
    for (EntryPoint entry : toProcess) {
        JsonNode inc = callAiForEntry(task, entry, ...);
        // 先删后插：retarget 入口的旧节点删除
        nodeMapper.deleteByTaskIdAndSourceEntryClass(taskId, entry.getClassName());
        mergeEntryResult(hierarchy, entry, inc, methodsByClass);
    }
    
    // persist 改用增量 upsert
    persistIncremental(taskId, task.getSystemId(), hierarchy);
    
    // deleted 文件清理
    purgeDeletedClassPaths(hierarchy, effective.getDeletedPaths());
    return hierarchy;
}
```

#### 5.0 与 [module-hierarchy-design.md](./module-hierarchy-design.md) 的协同

**关键：两个方案在同一位置（`buildAndPersist`），代码已自然衔接。**

| 阶段 | 行为 |
|---|---|
| 1. 整树继承 | `inheritModuleHierarchy` → DB 已有基线节点 |
| 2. 删 retarget 旧节点 | `deleteByTaskIdAndSourceEntryClass`（INCREMENTAL 模式） |
| 3. 加载 DTO | `loadByTaskId`（含基线继承 - retarget 旧节点） |
| 4. **串行 AI（共享 hierarchy）** | `for entry in toProcess: callAiForEntry(..., hierarchy); mergeIncrement(hierarchy, inc)` |
| 5. 落表 | `persistIncremental`（基线节点不动，新节点 insert） |

**协同效果**（INCREMENTAL 任务）：
- AI 看到 hierarchy = 基线继承的完整树 - retarget 入口的旧节点
- AI 复用基线 ID（共享上下文方案 + 精简版 hierarchy）
- 串行调用确保每次 AI 都看到最新 hierarchy
- 立即合并，下一个 AI 看到"已经合并了上一个 AI 输出"的最新树

**代码已合并实施**（[ModuleHierarchyServiceImpl.java:208-240](backend/src/main/java/com/company/codeinsight/modules/hierarchy/service/impl/ModuleHierarchyServiceImpl.java#L208-L240)），无需再调整。

**新增 mapper 方法** `ModuleHierarchyNodeMapper`：

- `inheritFromBaseline(taskId, baselineTaskId)`：从基线任务继承所有节点
- `deleteByTaskIdAndSourceEntryClass(taskId, sourceEntryClass)`：按入口类删除
- `batchUpsertByEntry(taskId, systemId, hierarchy)`：upsert 增量节点

**upsert SQL**：

```sql
INSERT INTO ci_module_hierarchy (...)
VALUES (...)
ON CONFLICT (task_id, node_id) DO UPDATE SET
  name = EXCLUDED.name,
  keywords = EXCLUDED.keywords,
  class_paths = EXCLUDED.class_paths,
  method_signatures = EXCLUDED.method_signatures,
  source_entry_class = EXCLUDED.source_entry_class,
  updated_at = CURRENT_TIMESTAMP;
```

**关键替换规则**：
- **修改**：upsert 按 `(task_id, node_id)` 更新
- **删除**：retarget 入口的旧节点 `deleteByTaskIdAndSourceEntryClass`；deleted 文件 → `purgeDeletedClassPaths` 清空 `class_paths`
- **新增**：upsert 自动处理

### 阶段 6：GENERATING_DOC（知识草稿）

**改造为基线 + 增量**：

```java
// AiSummaryServiceImpl.generateDraftDocument 改造
public void generateDraftDocument(Long taskId, String promptContent, IncrementalContext ctx, IncrementalImpact impact) {
    IncrementalContext effective = ctx == null ? IncrementalContext.fullScan() : ctx;
    
    DraftWorkspace ws = workspaceService.getOrCreate(taskId);
    
    if (!effective.isIncremental()) {
        // INITIAL: 全量
        return generateDraftDocumentFull(taskId, ws, promptContent);
    }
    
    // INCREMENTAL: workspace 引用基线
    Long baselineWorkspaceId = lookupBaselineWorkspaceId(taskId, effective);
    ws.setBaselineWorkspaceId(baselineWorkspaceId);
    workspaceMapper.updateById(ws);
    
    // 只对 retarget 模块生成草稿
    for (ModuleDto module : hierarchy.getModules().values()) {
        if (!impact.getDocRetargetModuleIds().contains(module.getId())) {
            continue;  // 跳过：从基线继承
        }
        generateModuleDraft(task, ws, module, hierarchy, projectDir);
    }
}
```

**草稿查询合并** `DraftService.getWorkspaceTree(workspaceId)`：

```sql
-- 合并基线 workspace 草稿 + 本任务 workspace 草稿（按 module_id 去重）
SELECT * FROM ci_knowledge_draft
WHERE workspace_id = ?  -- baselineWorkspaceId
   OR workspace_id = ?; -- currentWorkspaceId
```

### 阶段 7：PUSHING（推送）

**保持现状**（推送是终态快照，必须全量导出）：
- `applySnapshotToRepository` 把 `lastPublishedTaskId` 推进
- `exportArtifactsToRelease` 导出**完整**知识到 `/docs/code-insight`
- 数据来源：本任务全量数据（基线复制 + 本次新增）—— 已经是"完整知识"

**无改动**。

---

## 五、调用链总览

```
DecompileTaskServiceImpl.runPipeline
  │
  ├─→ PULLING_CODE
  │     ├─→ CodeScannerService.pullAndScan (git diff → changed/deleted)
  │     ├─→ IncrementalScanPersistenceService.persist (落 ci_incremental_scan)
  │     ├─→ BaselineInheritanceService.inheritEntrypoints (基线 → ci_entrypoint)
  │     ├─→ BaselineInheritanceService.inheritMethodCalls (基线 → ci_method_call)
  │     ├─→ MethodCallService.persistAstForTask (changed .java 重解析)
  │     └─→ EntrypointReviewService.discoverAndPersist (changed 重识别)
  │
  ├─→ ENTRYPOINT_DISCOVERY (已完成)
  │
  ├─→ ENTRYPOINT_REVIEW (断点：用户确认/驳回)
  │
  ├─→ AI_ANALYZING
  │     └─→ IncrementalImpactAnalyzer.analyze (基于增量入口 + changed FQ)
  │
  ├─→ MODULE_HIERARCHY
  │     ├─→ BaselineInheritanceService.inheritModuleHierarchy (基线 → ci_module_hierarchy)
  │     └─→ ModuleHierarchyService.buildAndPersist (retarget 入口 AI + upsert)
  │
  ├─→ MODULE_HIERARCHY_REVIEW (断点)
  │
  ├─→ GENERATING_DOC
  │     ├─→ DraftWorkspace.setBaselineWorkspaceId (引用基线 workspace)
  │     └─→ AiSummaryService.generateDraftDocument (retarget 模块生成草稿)
  │
  ├─→ PENDING_REVIEW (断点)
  │
  └─→ PUSHING
        ├─→ RepositoryPublishServiceImpl.applyFromTask (全量导出)
        └─→ Repository.last_published_task_id = 本任务 ID
```

---

## 六、关键文件清单

### 后端新增

| 文件 | 用途 |
|---|---|
| `BaselineInheritanceService.java` | 基线数据复制工具（5 个表的 inherit 方法） |
| `IncrementalScanPersistenceService.java` | `ci_incremental_scan` 落表 |
| `IncrementalScan.java` / `IncrementalScanMapper.java` | 新表的 entity + mapper |
| `DiscoverEntriesInFilesTest.java` | 入口识别单文件测试 |
| `BaselineInheritanceServiceTest.java` | 基线复制单元测试 |

### 后端修改

| 文件 | 改动 |
|---|---|
| `backend/src/main/resources/db/schema.sql` | 5 处 `ALTER TABLE` + 1 张新表 |
| `MethodCallServiceImpl.java` | `persistAstForTask` 增量分支 |
| `MethodCallMapper.java` | 新增 `inheritFromBaseline` |
| `EntryPointDiscoveryService.java` | 新增 `discoverEntriesInFiles` 重载 |
| `EntryPointDiscoveryServiceImpl.java` | 抽取"单文件判定"私有方法 |
| `EntrypointReviewService.java` | `discoverAndPersist` 接收 `IncrementalContext` |
| `EntrypointReviewServiceImpl.java` | 增量分支实现 |
| `EntrypointMapper.java` | 新增 `inheritFromBaseline` |
| `ModuleHierarchyServiceImpl.java` | `persistAll` 拆 full/inc；`buildAndPersist` 增量分支 |
| `ModuleHierarchyNodeMapper.java` | 新增 `inheritFromBaseline` / `deleteByTaskIdAndSourceEntryClass` / `batchUpsertByEntry` |
| `AiSummaryServiceImpl.java` | `generateDraftDocument` 增量分支 |
| `DraftWorkspaceService.java` | `getOrCreate` 支持 baseline_workspace_id |
| `DraftService.java` | `getWorkspaceTree` 合并基线 + 本次 |
| `CodeScannerServiceImpl.java` | 末尾调用 `IncrementalScanPersistenceService` |
| `DecompileTaskServiceImpl.java` | PULLING_CODE 阶段调 `BaselineInheritanceService` |
| `RepositoryPublishServiceImpl.java` | **无改动**（推送仍是全量导出） |

### 前端修改（Phase 4，可选）

- 3 个复核页加"基线继承 N / 本次新增 M"标识
- 入口复核：基线继承入口降色
- 模块层级：基线节点折叠
- 知识复核：基线草稿与本次草稿同树

---

## 七、复用与新增的函数/工具

### 复用现有函数

| 函数 | 路径 | 用途 |
|---|---|---|
| `JavaParserService.parseFile(File)` | `scanner/service/JavaParserService.java` | 单文件解析（已存在） |
| `MethodCallServiceImpl.walkAndPersist` | `callchain/service/impl/MethodCallServiceImpl.java` | 已支持 `pathFilter` 增量过滤 |
| `IncrementalImpactAnalyzer.analyze` | `callchain/service/impl/IncrementalImpactAnalyzerImpl.java` | 反向 BFS 完整逻辑 |
| `IncrementalImpactSupport.moduleTouchedByChange` | `callchain/support/IncrementalImpactSupport.java` | changedFqSet 过滤 |
| `ModuleHierarchyServiceImpl.deriveFqcnFromPath` | `hierarchy/service/impl/ModuleHierarchyServiceImpl.java` | 路径→FQCN 工具 |

### 新增的私有方法

| 方法 | 路径 |
|---|---|
| `BaselineInheritanceService.inheritEntrypoints` | 新 Service |
| `BaselineInheritanceService.inheritMethodCalls` | 新 Service |
| `BaselineInheritanceService.inheritModuleHierarchy` | 新 Service |
| `BaselineInheritanceService.lookupBaselineWorkspaceId` | 新 Service |
| `EntrypointMapper.inheritFromBaseline` | 新 SQL |
| `MethodCallMapper.inheritFromBaseline` | 新 SQL |
| `ModuleHierarchyNodeMapper.inheritFromBaseline` | 新 SQL |
| `ModuleHierarchyNodeMapper.deleteByTaskIdAndSourceEntryClass` | 新 SQL |
| `ModuleHierarchyNodeMapper.batchUpsertByEntry` | 新 SQL |
| `EntryPointDiscoveryService.discoverEntriesInFiles` | 新重载 |

---

## 八、验证

### 单元测试

- `BaselineInheritanceServiceTest`：
  - 基线有 N 个入口，本任务有 M 个变更 → 复制 N-变更命中数 个
  - 变更文件不复制
  - deleted 文件不复制
- `EntrypointDiscoveryServiceTest`：
  - `discoverEntriesInFiles(changedPaths)` 只识别指定文件
  - 4 类入口规则正确判定
- `ModuleHierarchyServiceTest`：
  - `persistIncremental` 按 entry upsert
  - retarget 入口的旧节点被删除
  - 非 retarget 入口的节点原样保留
- `DraftServiceTest`：
  - `getWorkspaceTree` 合并基线 + 本次草稿

### 端到端验证

| 场景 | 预期 |
|---|---|
| 1. 全新仓库跑 INITIAL | 全量发现入口、全量生成模块层级、全量草稿（行为不变） |
| 2. 已 PUSHED 仓库改 3 个文件跑 INCREMENTAL | 入口：3 个 changed + 继承自基线 N-3 个；模块层级：只重提炼 retarget 入口；草稿：只生成 retarget 模块 |
| 3. 已 PUSHED 仓库删 1 个文件跑 INCREMENTAL | 入口：删除对应行；模块层级：从 class_paths 移除；草稿：相关模块重新生成 |
| 4. 多次 INCREMENTAL 未 PUSHED 连续跑 | 每次基线都是 T0（PUSHED），重复处理未 PUSHED 任务处理过的代码（预期） |
| 5. PUSHED 后立刻 INCREMENTAL | 基线更新为新 commit；changedPaths 反映新变更 |

### 性能对比（关键指标）

- **入口识别**：INCREMENTAL 任务应**只扫 N 个变更文件**（N 通常 < 50），不再扫全项目
- **模块层级 AI**：INCREMENTAL 任务应**只调 retarget 入口数 × 1 次 AI**，不再调全量
- **模块层级落表**：INCREMENTAL 任务应**只 upsert retarget 节点**，不再 deleteByTaskId + 全量 insert
- **草稿生成**：INCREMENTAL 任务应**只调 retarget 模块 × 1 次 AI**，workspace 物理隔离保留基线

---

## 九、实施阶段与工作量

| Phase | 内容 | 工作量 |
|---|---|---|
| **Phase 1** | Schema 改造（5 处 ALTER + 1 张新表） | 2 小时 |
| **Phase 2** | 基线继承工具（BaselineInheritanceService + 5 个 mapper 方法） | 1 天 |
| **Phase 3** | 5 阶段改造（PARSING + ENTRYPOINT + MODULE_HIERARCHY + GENERATING_DOC） | 2-3 天 |
| **Phase 4** | UI 呈现（3 个复核页加"基线/新增"标识） | 1 天（可选） |
| **Phase 5** | 端到端验证 + 性能对比 | 1 天 |
| **总计** | 约 1 周（1 人） | |

---

## 十、关键风险与应对

| 风险 | 应对 |
|---|---|
| Schema 变更影响已有数据 | 字段全部 NULL 允许，老数据 `baseline_task_id = NULL` 仍能正常工作 |
| 入口识别单文件判定漏判 | 严格按现有 4 类规则（@RestController / @Scheduled / @RabbitListener / OTHER），与全量识别结果对比测试 |
| 模块层级 upsert 性能 | `ON CONFLICT` 命中率高时性能优于 delete+insert；预创建 `(task_id, node_id)` 唯一索引 |
| 草稿合并去重 | 按 `module_id` 去重（基线 workspace 与本任务 workspace 不重叠） |
| PUSHED 推进后基线变更 | PUSHED 后基线 = 上一 PUSHED 任务；新 INCREMENTAL 从新基线复制（不影响已有任务） |
| 多任务并发 | 任务间通过 `task_id` 完全隔离；同一仓库串行 PUSH |
| **基线任务被误删** | **见下方"方案1：PUSHED 任务强保护"** |

### 关键风险补充：基线任务保护（方案1）

#### 风险本质

本次设计的核心假设是"基线任务不被删"：
- PUSHED 任务的 `ci_entrypoint` / `ci_method_call` / `ci_module_hierarchy_node` / `ci_draft_workspace` / `ci_knowledge_draft` 表数据是后续 INCREMENTAL 任务的基线复制源
- 一旦 PUSHED 任务被删除，对应 `baseline_task_id` 指向不存在任务 → `inheritFromBaseline` 失败 → INCREMENTAL 任务跑不起来

#### 现状分析

`DecompileTaskServiceImpl.deleteTask` 已有两道保护：

| 保护 | 机制 | 是否能挡住"删 PUSHED 任务" |
|---|---|---|
| 状态门禁 | `DELETABLE_STATUSES = {DRAFT, PENDING, FAILED, CANCELLED, ARCHIVED}` 不含 PUSHED | ✅ 正常调用能挡住 |
| `assertDeletableArtifacts` | 检查 `knowledgeVersion` + `repo.lastPublishedTaskId` | ✅ 当前基线能挡住 |

但**两道保护都是应用层**的，存在以下漏洞：

| 漏洞场景 | 是否能删 |
|---|---|
| 用户点"删除"按钮删 PUSHED 任务 | ✅ 被状态门禁拦截 |
| 有人手动把 task.status 改回 DRAFT/FAILED 再删 | ❌ **可被删**（绕过保护 + knowledgeVersion 为空 + 不是 lastPublishedTaskId） |
| 运维 UPDATE 数据库手动删 task | ❌ **可被删**（绕过应用层） |
| 历史 PUSHED 任务（非最近一次 PUSHED） | ❌ **可被删**（`lastPublishedTaskId` 只指向最新一次） |
| `task.status = ARCHIVED` 的 PUSHED 任务（理论上不会，但 schema 允许） | ❌ **可被删**（ARCHIVED 在 DELETABLE_STATUSES 中） |

#### 方案1：应用层强保护（已采用）

**核心改动**：在 `deleteTask` 的状态门禁之前，加一道 PUSHED 状态强拦截（**永远不能删**），让"绕过 DELETABLE_STATUSES 也无法删 PUSHED 任务"。

**代码**（`DecompileTaskServiceImpl.deleteTask`）：

```java
public void deleteTask(Long id) {
    DecompileTask task = this.getById(id);
    if (task == null) {
        throw new BusinessException("任务不存在");
    }
    // === 方案1：PUSHED 状态永远不能删除 ===
    if (TaskStatus.PUSHED.name().equals(task.getStatus())) {
        throw new BusinessException("PUSHED 状态任务不能删除；PUSHED 是知识确认的硬边界，" +
                "其数据是后续 INCREMENTAL 任务的基线复制源。如确需清理，请联系管理员手动处理。");
    }
    // 现有逻辑保持不变
    if (!DELETABLE_STATUSES.contains(task.getStatus())) { ... }
    if (taskCache.containsKey(id)) { ... }
    assertDeletableArtifacts(id, task);
    ...
}
```

**为什么这样选**：
- 加 5 行代码，简单直接
- 拦截 99% 误操作（前端"删除"按钮、状态被改回 DRAFT 等）
- 不改 schema、不改 PUSH 流程、不影响已有 PUSHED 任务的展示
- 保留管理员手动 UPDATE 数据库的应急通道（极端情况 DBA 可绕过）

#### 备选方案（未采用）

| 备选 | 工作量 | 评估 |
|---|---|---|
| 方案2：schema 强约束（`ci_task` 加 `is_published` 字段 + 应用层校验） | 中 | 更安全，但需要数据库迁移 + 改 PUSH 流程；与软删除有冲突 |
| 方案3：数据库 trigger 物理不可删 | 中-大 | 最强约束，但连 DBA 都不能直接删；应急清理困难 |

**当前选方案1的理由**：最小改动、最大 ROI、与现有架构最兼容。如果未来需要更强的约束，可升级到方案2。

---

## 十一、取舍决策记录

| 决策点 | 选择 | 理由 |
|---|---|---|
| 对比基准 | 仓库最新 PUSHED 的 commit | PUSHED 是知识确认的硬边界 |
| 数据复制源 | 仓库最新 PUSHED 的任务 | 与对比基准同源 |
| 物理复制 vs 逻辑引用 | **物理复制** | 数据强隔离，存储可接受 |
| 单层 vs 多层基线 | **单层** | 简化设计，引用最近 PUSHED |
| 复制时机 | **任务启动时一次性** | 快照语义，便于流水线 |
| 复制范围 | **全表** | 简化设计，存储可接受 |
| UI 呈现 | **后端优先，前端可后续** | 核心是资源效率，UI 是锦上添花 |

---

## 十二、一句话总结

> **INCREMENTAL 任务 = 从最近 PUSHED 任务继承基线数据 + 仅分析 changed paths + 修改/新增/删除对应知识。**
>
> 对比基准与数据复制源都是仓库最近 PUSHED 的任务，二者同源；不与任何非 PUSHED 任务耦合。

---

## 十三、前端展示层协作

**本方案是后端数据层 + Service 层的改造。前端展示层由 [phase4-ui-redesign.md](./phase4-ui-redesign.md) 负责。**

### 两个方案的层次关系

| 方案 | 层次 | 范围 | 状态 |
|---|---|---|---|
| **本方案（incremental-baseline-design.md）** | 后端数据层 + Service 层 | 5 阶段流水线 + 数据库字段 + Service | ✅ 已完成大部分 |
| **[phase4-ui-redesign.md](./phase4-ui-redesign.md)** | 前端展示层 | 3 个 Workspace + DTO 字段透传 | ⏳ 待实施 |

### 强依赖

```
本方案（数据层）
  ├─ 数据库加字段：ci_entrypoint.baseline_task_id / ci_module_hierarchy.source_entry_class
  │                 / ci_knowledge_draft.baseline_task_id / ci_draft_workspace.baseline_workspace_id
  ├─ Service 层透传到 DTO
  ├─ 3 个模块（scan / entrypoint / moduleHierarchy / draft）的 inherit/deleteBySourceEntryClass 方法
  └─ IncrementalContext.baselineTaskId
                ↓
phase4-ui-redesign.md（前端展示）
  ├─ INITIAL 任务：全量视图（与今天一致）
  ├─ INCREMENTAL 任务：4 种 diff 分类（本次新增 / 基线继承 / AI 重提炼 / 本次删除）
  └─ 通过 task.type 字段判断 + Segmented 切换
```

**没有本方案的数据层，phase4-ui-redesign.md 就没有数据可展示——两者是**强依赖**。**

### 本方案为 phase4-ui-redesign.md 提供的数据

| 数据字段 | 表 | 用途 |
|---|---|---|
| `baseline_task_id` | `ci_entrypoint` | 区分"本次新增"（NULL）vs "基线继承"（非空） |
| `source_entry_class` | `ci_module_hierarchy` | 区分"基线继承"（NULL）vs "AI 重提炼"（非空） |
| `baseline_task_id` | `ci_knowledge_draft` | 区分草稿"本次新增"（NULL）vs "基线继承"（非空） |
| `baseline_workspace_id` | `ci_draft_workspace` | 草稿合并查询（已实现） |
| `IncrementalContext.baselineTaskId` | 内存 | "本次删除"diff 计算（后端 /diff 端点） |
| `lastPublishedTaskId`（`ci_repository`） | 仓库表 | "本次删除"diff 计算（基线数据） |

### 实施顺序建议

1. ✅ **本方案（incremental-baseline-design.md）**——已完成大部分（Phase 1-3d）
2. ⏳ **[phase4-ui-redesign.md](./phase4-ui-redesign.md)**——3-4 天工作量

### 独立 vs 协同

| 维度 | 本方案 | phase4-ui-redesign.md |
|---|---|---|
| 是否依赖对方 | 否（独立后端） | **强依赖**本方案的数据层 |
| 是否可独立实施 | ✅ 是 | ❌ 否（必须先有数据层） |
| INITIAL 任务支持 | ✅ | ✅（保持现状） |
| INCREMENTAL 任务支持 | ✅（数据正确） | ✅（视觉增强） |
| 性能 | 不影响 INITIAL；INCREMENTAL 任务更高效 | 加载时多 1 次 `/diff` 查询（毫秒级） |

### 文档维护约定

- 本方案（incremental-baseline-design.md）**只关注后端**
- phase4-ui-redesign.md **只关注前端**
- 任何一方的字段 / 端点变更，**必须同步更新另一方**的引用说明
