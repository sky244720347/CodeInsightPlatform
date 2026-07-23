# 增量任务 · 删除继承文档 + 知识推送方案

> **管什么**：INCREMENTAL 任务在知识生成阶段把工作区收敛为「与当前模块层级一致的最终文档集」（含删除已失效的继承草稿）；以及推送/建版本时按该最终集做全量快照发布。  
> **不管什么**：入口 / 模块层级 DIFF 分桶本身（见既有方案）；正文 Monaco 比对 UI（见 [knowledge-document-diff-design.md](./knowledge-document-diff-design.md)）。  
> **关联**：[knowledge-document-diff-design.md](./knowledge-document-diff-design.md)、[incremental-baseline-design.md](./incremental-baseline-design.md)、[module-hierarchy-id-reuse-fix.md](./module-hierarchy-id-reuse-fix.md)。  
> **状态：已实施**（2026-07）。

---

## 〇、目标一句话

**生成结束时 workspace = 即将推送的最终知识文件集**（当前 hierarchy 下每个功能一份文档，不多不少）；推送只是把该集合写成新 release 版本并切换指针，不再在推送期补删或补拷。

---

## 一、现状与缺口

### 1.1 已具备（不必重做）

| 能力 | 位置 | 说明 |
|---|---|---|
| 基线文档整包继承 | `BASELINE_DOC_INHERIT` → `BaselineInheritanceService.inheritDrafts` | 从 `releases/.../modules` + `module-map.yaml` 复制到本次 workspace |
| 变更篇重生成 | `AiSummaryServiceImpl.generateDraftDocument*` | 仅 touched 功能 upsert；清 `baselineTaskId`（需 `FieldStrategy.ALWAYS`） |
| 推送读当前 workspace | `KnowledgeServiceImpl.createVersion` / `NasPushStrategy` / `GitPushStrategy` | **无**「只推 DIFF」分支；按 workspace 全量写新版本 |
| 发布指针切换 | `RepositoryPublishServiceImpl` | `last_published_version_id` → 新版本（全量快照语义） |

### 1.2 缺口（本方案要修）

| # | 缺口 | 后果 |
|---|---|---|
| A | `inheritDrafts` 复制 **release 全量**；`GENERATING_DOC` **不按当前 hierarchy 删除**已不存在功能的继承草稿 | 层级已删功能的文档仍以 `CONFIRMED` 留在 workspace，推送后「死文档」重回正式知识 |
| B | DIFF `deletedRows` =「基线 map 有 ∩ 本次 workspace 无」的**虚拟节点** | 若孤儿继承草稿仍在 workspace → `deletedRows` 为空，复核人看不到「应删」 |
| C | 推送门禁 `validateDraftsReady` 要求 workspace **每一篇**都是 CONFIRMED/PUSHED | 孤儿继承虽「看起来已通过」，却污染最终发布集 |
| D | Git 推送以 `git add` 目录为主 | 即使 NAS 新 version 目录正确，Git 侧旧路径文件未必物理删除（次要；本方案以 NAS release 新目录 + workspace 收敛为主） |

### 1.3 实测（task #6，2026-07）

- 模块层级 DIFF：`deletedHierarchy` 含已删业务（如订单报价相关）  
- workspace 仍残留对应继承草稿（`baseline_task_id != null` + `CONFIRMED`）  
- `GET .../tree/diff` → `deletedRows = 0`（因草稿仍在）  
- 推送若执行，该篇会进入新 `versionNum` 的 `modules/`

---

## 二、核心原则

1. **Workspace 最终集 = 当前任务 `ModuleHierarchy` 的功能全集**  
   - 功能粒度：`module_name = "{模块} / {子模块} / {功能}"`（与 `upsertFunctionDraft` 一致）  
   - 模块粒度（若配置 `doc-generation.granularity=module`）：以模块名对齐，规则见 §4.3  
2. **删除发生在生成流水线内，不发生在推送策略里**  
   - 推送代码保持「读 workspace → 写新版本」；不做 INCREMENTAL 特判  
3. **先继承、再生成、再裁剪（或生成末尾统一裁剪）**  
   - 裁剪依据是 **本任务已落库的 hierarchy**，不是 git diff 文件列表  
4. **被删文档对复核可见**  
   - 物理/DB 删除后，`deletedRows` 自然出现；DIFF 树继续用 hierarchy `deletedHierarchy` + 文档 `deletedRows` 双通道（文档侧变为真删除）  
5. **INITIAL 任务不走本裁剪**（无 `BASELINE_DOC_INHERIT`，无基线孤儿问题）

---

## 三、目标流水线

```text
INCREMENTAL：
  … → MODULE_HIERARCHY(_REVIEW)
    → BASELINE_DOC_INHERIT     // inheritDrafts：release → workspace
    → GENERATING_DOC           // AI upsert 变更/新增
    → PRUNE_STALE_DRAFTS       // ★ 本方案新增步骤（可内嵌 GENERATING_DOC 末尾，不必新 TaskStatus）
    → PENDING_REVIEW
    → … 复核通过 → createVersion → enqueuePush → NAS/Git
         // 推送读到的 workspace 已是最终集
```

**推荐落地**：不新增任务状态枚举；在 `runBaselineDocInheritAndGenerateDoc` 中于 `generateDraftDocument` **成功返回后**、`transitTo(PENDING_REVIEW)` **之前**调用 `pruneStaleDrafts(taskId, workspaceId)`。  
`retryBaselineInherit` 走同一编排，必须同样 prune。

```text
INITIAL：
  … → GENERATING_DOC → PENDING_REVIEW → …
  // 不调用 prune（或调用也为 no-op：无 baseline 继承孤儿）
```

---

## 四、删除继承文档（PRUNE）设计

### 4.1 合法文档名集合 `expectedNames`

从本任务 `ModuleHierarchyService.loadByTaskId(taskId)` 收集：

```text
function 粒度：
  expectedNames = {
    m.moduleName + " / " + sm.subModuleName + " / " + fn.functionName
    | ∀ module m, sub sm, function fn
  }

module 粒度（granularity=module）：
  expectedNames = { m.moduleName | ∀ module m }
```

粒度读取与 `AiSummaryServiceImpl` / `code-insight.doc-generation.granularity` 一致，避免 function 任务误删模块名草稿或相反。

### 4.2 删除条件（须全部满足）

对 workspace 内每条 `KnowledgeDraft d`：

| 条件 | 说明 |
|---|---|
| `d.moduleName ∉ expectedNames` | 当前层级已无此功能/模块 |
| 且（推荐）`d.baselineTaskId != null` **或** 强制删除所有不在 expected 的草稿 | 见决策 §5 |

**推荐默认（决策 R1）**：凡 `moduleName ∉ expectedNames` 一律删除（含误生成的多余 AI 草稿），保证「最终集 = hierarchy」。  
仅删继承（`baselineTaskId != null`）会留下「层级已删但仍是 AI_GENERATED」的脏行，不满足「最终文件」目标。

### 4.3 删除动作（事务 + 文件）

对每个命中草稿：

1. 删 `ci_draft_source_reference`（`draftId`）  
2. 删相关 revision / comment 若有外键约束（按现有级联或显式删）  
3. 删 `ci_knowledge_draft` 行  
4. 删物理文件：`DraftFileUtil.resolve(contentUri)` → `Files.deleteIfExists`；若曾扁平/嵌套双路径，按 `filePath` 再尝试一次  
5. `ci_operation_log` / `pipeline.log` 记：`PRUNE_STALE_DRAFT moduleName=… draftId=…`

失败策略：**单篇失败记 warn 并计入失败计数；任一篇 DB 删失败则整次 prune 抛错让任务 FAILED**（避免半删导致推送集不确定）。文件删失败可 warn 不阻断（DB 已无行则推送读不到）。

### 4.4 幂等

- 已删则无行；重跑 `retryBaselineInherit`：先 inherit（跳过已有同名）→ generate → prune 再次扫，仍安全  
- prune **不得**删除 `expectedNames` 内文档

### 4.5 与 DIFF 的关系

| 时机 | `inheritedRows` | `modified/new` | `deletedRows` |
|---|---|---|---|
| prune 前 | 含层级已删的孤儿 | 正常 | 常为 0（孤儿仍在） |
| prune 后 | 仅「层级仍在且未重跑」 | 正常 | 基线 map 有、workspace 无 → **真删除**虚拟节点 |

前端已有「删除」Tag / 删除线；prune 后文档桶与层级删除对齐，无需大改 UI。  
可选增强：DIFF 删除节点 Tooltip 标明「已从工作区移除，推送不会带上」。

### 4.6 放置位置（代码）

| 组件 | 职责 |
|---|---|
| `BaselineInheritanceService.pruneStaleDrafts(taskId, workspaceId)` 或独立 `DraftWorkspacePruneService` | 读 hierarchy + 扫 drafts + 删 DB/文件 |
| `DecompileTaskServiceImpl.runBaselineDocInheritAndGenerateDoc` | generate 成功后调用 |
| `DecompileTaskServiceImpl.retryBaselineInherit` | 同样调用 |
| `AiSummaryServiceImpl` | **不**在单篇 upsert 里 prune（避免半生成状态误删） |

纠错任务 `RESUME_GENERATING_DOC`：若只重跑生成，结束时也应 prune 一次（hierarchy 可能已变）。

---

## 五、决策记录

| # | 决策 | 选项 | 选择 | 理由 |
|---|---|---|---|---|
| R1 | 删谁 | 仅 `baselineTaskId!=null` / **凡不在 expectedNames** | **凡不在 expectedNames** | 保证最终集=hierarchy |
| R2 | 何时删 | inherit 后立刻 / **generate 后** / 推送前 | **generate 后** | 生成可能改名/新路径；末态一次收敛 |
| R3 | 新 TaskStatus | 增加 `PRUNE_STALE_DRAFTS` / **内嵌 GENERATING_DOC** | **内嵌** | 减少状态机面；日志用 `PRUNE_STALE_DRAFT` 即可 |
| R4 | 推送是否特判 INCREMENTAL | 推送侧过滤删除 / **不特判** | **不特判** | workspace 已最终集；保持 createVersion/NAS/Git 简单 |
| R5 | NAS 旧 version 目录 | 删旧目录文件 / **新 versionNum 整目录** | **新目录**（已有） | 旧 release 保留可回滚；指针切换即「覆盖」 |
| R6 | Git 删远程旧 md | 本阶段强制 `git rm` / **本阶段不强制** | **本阶段不强制** | 与 NAS 权威分离；若产品要求 Git 也无残留，单开后续项 |
| R7 | 缺文档（hierarchy 有、draft 无） | prune 顺带补生成 / **仅告警** | **仅 pipeline 告警** | 补生成属生成缺陷；推送门禁已要求篇篇 CONFIRMED，缺篇会在复核/门禁暴露 |

---

## 六、增量任务知识推送（确认 + 门禁补强）

### 6.1 语义（已成立，本方案书面钉死）

```text
INCREMENTAL 推送 = 全量快照发布
  输入：本次 task workspace 内全部 CONFIRMED/PUSHED 草稿（= 裁剪后的最终集）
  输出：releases/{sys}/{repo}/{versionNum}/ 完整 modules + meta
  生效：last_published_version_id := 新 versionId
```

不存在「只推 modifiedRows」；与 INITIAL 推送路径相同。

### 6.2 推送链路（保持）

```text
任务 CONFIRMED
  → KnowledgeServiceImpl.createVersion(taskId, …)
       // 读 workspace 全部 CONFIRMED|PUSHED → 写 docs/code-insight
  → PushServiceImpl.enqueuePush(versionId, NAS|GIT)
       // validateDraftsReady(workspace)
  → NasPushStrategy / GitPushStrategy
       // 拷贝/提交 workspace（或 version 已写内容）到目标
  → RepositoryPublishService 更新仓库发布快照与指针
```

### 6.3 门禁补强（建议，小改）

在 `assertTaskReadyForKnowledgePublish` 或 `validateDraftsReady` 增加 **INCREMENTAL 可选校验**（配置开关默认开）：

```text
expectedNames = from hierarchy(taskId)
draftNames    = workspace 全部 moduleName
若 draftNames ≠ expectedNames：
  - 多余 → BusinessException（提示先检查 prune 是否执行）
  - 缺失 → BusinessException（提示缺文档未生成/未确认）
```

避免 prune 被跳过或半失败时把脏集推进正式库。

### 6.4 人工复核与推送的关系

| 文档类型 | 生成后状态 | 复核 | 推送 |
|---|---|---|---|
| 继承未改 | `CONFIRMED`（inherit 时） | 全量视图可见；DIFF 默认隐藏 | 直接计入 |
| 修改/新增 | `AI_GENERATED` → 人审 → `CONFIRMED` | DIFF 默认展示；可「比对」 | 须全部确认 |
| 已删（prune 后） | 行与文件已不存在 | DIFF `deletedRows` + 层级删除 | 不出现在版本中 |

---

## 七、实现清单

### 7.1 后端

| # | 文件 | 改动 |
|---|---|---|
| 1 | 新建 `DraftWorkspacePruneService`（或挂 `BaselineInheritanceService`） | `pruneStaleDrafts(taskId, workspaceId)`：load hierarchy → expectedNames → 删多余 draft+引用+文件+日志 |
| 2 | `DecompileTaskServiceImpl` | `runBaselineDocInheritAndGenerateDoc` / `retryBaselineInherit` / 纠错 `RESUME_GENERATING_DOC` 路径在 generate 后调用 prune |
| 3 | `DraftServiceImpl`（可选） | `assertWorkspaceMatchesHierarchy(taskId)` 供推送门禁 |
| 4 | `PushServiceImpl.validateDraftsReady` 或 `assertTaskReadyForKnowledgePublish` | INCREMENTAL 时调用上述 assert |
| 5 | 测试 | `DraftWorkspacePruneServiceTest`：孤儿继承被删；expected 内保留；幂等；文件清理 |

### 7.2 前端

| # | 文件 | 改动 |
|---|---|---|
| 6 | 一般无需改 | prune 后现有 DIFF 删除展示即正确 |
| 7 | （可选）`workspace.tsx` | 删除篇 Tooltip：「已移出工作区，推送不会包含」 |

### 7.3 文档

| # | 文件 | 改动 |
|---|---|---|
| 8 | 本文档 | 实施后改状态为「已实施」 |
| 9 | `knowledge-document-diff-design.md` | §〇 增补原则：「生成末尾 prune，workspace=最终集」；风险表链到本文 |

---

## 八、伪代码

```java
@Transactional(rollbackFor = Exception.class)
public int pruneStaleDrafts(Long taskId, Long workspaceId) {
    ModuleHierarchy hierarchy = moduleHierarchyService.loadByTaskId(taskId);
    Set<String> expected = collectExpectedModuleNames(hierarchy, granularity());
    List<KnowledgeDraft> drafts = draftMapper.selectList(
        eq(KnowledgeDraft::getWorkspaceId, workspaceId));

    int removed = 0;
    for (KnowledgeDraft d : drafts) {
        if (d.getModuleName() == null || expected.contains(d.getModuleName())) {
            continue;
        }
        // 1) refs / comments / revisions
        // 2) delete draft row
        // 3) deleteIfExists(resolve(contentUri)); optional filePath fallback
        // 4) execLog / operationLog
        removed++;
    }
    log.info("PRUNE_STALE_DRAFTS taskId={} removed={} kept={}",
        taskId, removed, drafts.size() - removed);
    return removed;
}
```

编排：

```java
inheritDrafts(...);
generateDraftDocument(...);
int pruned = draftWorkspacePruneService.pruneStaleDrafts(taskId, ws.getId());
execLog.log(taskId, "  裁剪失效草稿 " + pruned + " 份");
transitTo(PENDING_REVIEW);
```

---

## 九、验收标准

### 9.1 删除 / 最终集

- [x] 层级删除某功能后，生成结束 workspace **无**该 `module_name` 草稿与对应 md（单测覆盖裁剪逻辑）  
- [x] 未删功能的继承稿仍在（单测 keep）  
- [x] 修改/新增稿不受 prune 误伤（expected 内不删）  
- [x] `retryBaselineInherit` / 纠错续跑路径已接入 prune  
- [ ] 端到端：DIFF `deletedRows` 可见 + 推送 release 无该篇（需本地跑通增量任务）

### 9.2 推送

- [x] 门禁：`assertTaskReadyForKnowledgePublish` 对 INCREMENTAL 调用 `assertWorkspaceMatchesHierarchy`  
- [ ] 端到端推送验收（见上）

### 9.3 回归

- [ ] INITIAL 任务：无 prune 副作用（或不改变结果）  
- [ ] 无删除、仅修改的增量：pruned=0，推送集完整  

---

## 十、风险

| 风险 | 应对 |
|---|---|
| hierarchy 与 `module_name` 字符串不一致（空格/全角） | 与 upsert 使用同一拼接函数；单测锁死格式 |
| 模块粒度与功能粒度混用 | prune 读同一 granularity 配置 |
| 生成失败半截就 prune | 仅 generate **正常返回**后调用；异常走 FAILED 不 prune |
| 误删人工新建草稿 | 产品上不允许脱离 hierarchy 的草稿；R1 已定最终集=hierarchy |
| Git 远程残留旧 md | R6 本阶段接受；NAS/知识查看以 release 为准 |
| 历史已污染的已发布版本 | 不自动改旧 release；下次增量 prune 后推送新版本覆盖指针 |

---

## 十一、与已有方案关系

| 方案 | 关系 |
|---|---|
| `knowledge-document-diff-design.md` | 本方案补其「workspace 自包含」在**删除维**的空洞；推送仍不改查询逻辑 |
| `incremental-baseline-design.md` | 继承语义不变；增加继承后的收敛步骤 |
| `module-hierarchy-*-diff` | 只消费 `loadByTaskId` / deletedHierarchy；不改层级 DIFF 算法 |

---

## 十二、实施顺序建议

1. 实现 `pruneStaleDrafts` + 单测（可用 task #6 数据形态：孤儿订单文档）  
2. 接入 `runBaselineDocInheritAndGenerateDoc` / retry / 纠错续跑  
3. （可选）推送门禁 `assertWorkspaceMatchesHierarchy`  
4. 本地跑通：增量删除功能 → 生成 → DIFF 见删除 → 确认 → 推送 → 新 release 无该篇  
5. 回写本文档状态为「已实施」，并在知识 DIFF 方案加交叉链接  

---

## 十三、总结

> **生成末尾按当前模块层级裁掉 workspace 中多余草稿（含失效继承），使工作区文件即最终发布集；增量推送继续走全量快照（新 version + 指针切换），推送层不做增量补丁。**
