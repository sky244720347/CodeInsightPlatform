# 任务流程修复 + NAS 磁盘回收方案（定稿）

> 状态：已实施（2026-07-28）  
> 范围：知识确认后清源码/drafts、手动下发自动启动、知识复核断点、自动建版 NAS 推送、推送记录页、删除 Git 推送。

---

## 1. 目标闭环

```text
手动下发（UI 默认三断点全跳过）
  → 创建成功即 start → PENDING → 流水线
  → 入口/层级按开关可停
  → GENERATING_DOC 完成
       ├─ requireKnowledgeReview=true  → PENDING_REVIEW → 人工 confirmTask
       └─ false（常见）                 → 不进 PENDING_REVIEW，服务端自动确认
            ↓
  → onKnowledgeConfirmed（KnowledgePublishFacade，事务提交后异步）
       1) 组装 docs/code-insight（无源码类 index）
       2) 建版（versionNum = v{N+1}）
       3) 删源码工作区（留 docs）+ 删 drafts 磁盘
       4) enqueuePush(NAS)
            ↓
  NAS 成功 → PUSHED → 清该任务全部 runtime（含剩余 docs / ai_logs / task_*）
```

`releases/{sys}/{repo}/{ver}/` 正式知识不回收。

---

## 2. 拍板结论

| # | 结论 |
|---|------|
| 1 | 三断点：**库默认 `true`**；**下发页 UI 默认 `false`**；创建以请求体为准（null 才落库默认） |
| 2 | 推送**固定 NAS**；**删除 Git / S3 推送策略** |
| 3 | 版本号**只认 `v1,v2,v3…`**（`^v(\d+)$`） |
| 4 | 跳过知识复核时**可不进 `PENDING_REVIEW`** |
| 5 | **仅手动下发**创建后自动 `startTask`（调度/纠错创建不套用） |
| 6 | 「知识推送」改名为**「推送记录」**：只读列表，禁止新建版本/发起推送 |

---

## 3. drafts / staging / releases

| 名称 | 实际路径 | 角色 |
|------|----------|------|
| drafts | `{runtimeRoot}/drafts/task_{id}/` | 编辑中的稿（AI 生成 / 人工复核） |
| staging（发布包） | `workspaces/task_{id}/docs/code-insight/` | 确认后、推送前的定稿包（非新存储根） |
| releases | `{releasesRoot}/{sys}/{repo}/{ver}/` | 推送后的正式知识 |

**改前：** `NasPushStrategy` 主要从 drafts 读文件 → releases，故确认后不能先删 drafts。  
**改后：** 确认时先把 drafts 拷进 docs → 再删 drafts/源码 → 推送只读 docs → releases。

组装顺序：

```text
drafts（复核/生成结果）
  → 写入 docs/code-insight/modules + meta
  → createVersion（写 meta/version）
  → 删 drafts + 删源码（保留 docs）
  → NAS 只读 docs/code-insight → releases
  → 推送成功后再删整个 task 的 runtime（含 docs）
```

增量继承基线文档：从 **`releases/…/modules`** 拷，不依赖旧任务 drafts。

---

## 4. 索引与源码回收

**停写（不再扫 `.java`）：**

- `api-index.md`
- `database-index.md`
- `dependency-index.md`
- 硬编码 `frontend-overview.md` / `backend-overview.md`

**仍写（不读源码）：**

- `modules/*.md`
- `meta/module-map.yaml`、`meta/document-index.md`、`knowledge-version.json` 等
- `index.md` / `module-index.md` / `architecture-overview.md` / `pending-confirmation.md`

**删源码时机：** 知识确认完成（人工 `confirmTask` 或跳过路径的自动确认），在建版之后、推送入队之前。

---

## 5. 断点开关

| 开关 | 库默认 | 下发页 UI 默认 | 停在 |
|------|--------|----------------|------|
| `requireEntrypointReview` | true | false | `ENTRYPOINT_REVIEW` |
| `requireHierarchyReview` | true | false | `MODULE_HIERARCHY_REVIEW` |
| **`requireKnowledgeReview`（新）** | true | false | `PENDING_REVIEW`（跳过则不进入） |

Schema：

```sql
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS require_knowledge_review BOOLEAN DEFAULT TRUE NOT NULL;
```

状态机补充：`GENERATING_DOC → CONFIRMED`（跳过知识复核时允许）。

---

## 6. 统一编排钩子

`KnowledgePublishFacade`：

- `autoConfirmAndPublish(taskId)`：草稿 CONFIRMED + `GENERATING_DOC→CONFIRMED` + **异步** `schedulePublishAfterConfirmed` → `onKnowledgeConfirmed`
- `onKnowledgeConfirmed(taskId, confirmedBy)`：组装 → 建版 → 清源码/drafts → `enqueuePush(NAS)`
- `schedulePublishAfterConfirmed`：事务 `afterCommit` 后投递 `knowledgePublishExecutor`（人工 confirm 与跳过复核共用）

调用方：

- 流水线 `finishAfterDocGenerated`（`requireKnowledgeReview=false`）→ `autoConfirmAndPublish`
- `DraftService.confirmTask` 末尾（人工复核通过）→ 仅 CONFIRMED，再 `schedulePublishAfterConfirmed`

推送成功（`PushServiceImpl` SUCCESS）：`TaskDiskCleanupService.cleanupAfterPush(taskId)`。

版本号：`KnowledgeService.nextSimpleVersionNum(repositoryId)` —— 扫描该仓库 `^v(\d+)$` 取 max+1，无则 `v1`，冲突递增重试。

---

## 7. NAS 回收对照

| 时机 | 清什么 |
|------|--------|
| 知识确认完成 | 源码工作区（保留 `docs/code-insight`）；`drafts/task_{id}/` |
| 推送成功 | 该任务全部 runtime：`workspaces` / `drafts` / `task_{id}` / `ai_logs` |
| 始终保留 | 当前及历史 `releases`；PG 元数据（`PUSHED` 任务仍禁止删除） |

纠错：基线 `workspaces` 可能已回收 → `TaskArtifactCloneService.cloneTaskArtifacts(base, new, repoId)` 在缺失时对 newTask `pullAndScan`。

---

## 8. 前端

| 页面 | 改动 |
|------|------|
| 任务下发 | 增「知识复核」开关，三开关默认关；创建成功提示含自动启动 |
| 推送页 | 改名「推送记录」；只读版本/推送列表 + 回滚生效；去掉新建版本/入队推送 |
| 知识复核 | 整体通过后提示自动建版推送 |
| 导航/仪表盘 | 「知识推送」文案改为「推送记录」 |

---

## 9. 关键代码落点

| 模块 | 说明 |
|------|------|
| `TaskDiskCleanupService` | 确认后 strip 源码；推送后整包清 |
| `KnowledgePublishFacade` | 确认→建版→推送编排 |
| `KnowledgeService.assemblePublishPackage` / `nextSimpleVersionNum` | 组包与 vN |
| `NasPushStrategy` | 只读 `docs/code-insight` |
| `PushMethod` | 仅保留 `NAS`；删除 `GitPushStrategy` / `S3PushStrategy` |
| `PushController` | 仅 `GET …/tasks` 查询 |
| `DecompileTaskController` | 手动创建后写知识复核开关并 `startTask` |
| `schema.sql` | `require_knowledge_review` |

---

## 10. 验证建议

1. 下发 INITIAL，三断点全跳过 → 任务自动跑到 `PUSHED`，`releases` 出现 `vN`，`workspaces/task_*` 被清空。  
2. 仅开知识复核 → 停在 `PENDING_REVIEW`，人工整体通过后自动建版推送。  
3. 推送记录页可查版本与推送状态，无「新建版本」入口。  
4. 纠错在基线工作区已回收时仍能 `pullAndScan` 启动。
