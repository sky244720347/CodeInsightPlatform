# 代码来源全量落库 + 单篇重跑方案（方案 C 定稿）

> 状态：已实施（2026-07-28）  
> 前置已落地：接口→Impl `dependency_candidates` 修复；文档 BFS 保序；同类/`this` 调用入图。  
> 关联定稿：[task-flow-nas-cleanup-plan.md](./task-flow-nas-cleanup-plan.md)  
> 用户拍板：① 统一 SourceBundle + 全量 refs + 补 regenerate；② **方案 C**（`function_node_id` + `ref_kind` + `bfs_order`）；③ 重跑覆盖正文（确认弹窗 + 修订记录）。

---

## 1. 问题与目标

| 现象 | 根因 |
|------|------|
| 「代码来源」只有 Controller 入口行 | `insertDraftSourceReferences` 只写 binding 根，不写喂 AI 的 BFS 可达集 |
| 「重跑此篇」404 `No static resource drafts/{id}/regenerate` | 前端已调 `POST /api/drafts/{id}/regenerate`，后端无路由 |

**目标：**

```text
生成 / 重跑共用 SourceBundle：
  binding 根 → 正向 BFS（含同类边、保序）
    → promptText（喂 AI）
    → refs[]（ROOT + REACHABLE，带 bfs_order）落 ci_draft_source_reference
重跑：按 function_node_id 定位功能 → 再跑同一 Bundle → 覆盖正文 → 刷新来源 → 写修订
```

---

## 2. 与 NAS 清盘方案的关联影响（必读）

[task-flow-nas-cleanup-plan.md](./task-flow-nas-cleanup-plan.md) 已改变「源码 / drafts 何时还在」，直接约束本方案的重跑窗口与门禁。

### 2.1 生命周期对照

```text
GENERATING_DOC
  →（requireKnowledgeReview=true）PENDING_REVIEW ← 【可重跑窗口】源码 + drafts 均在
  → 人工 confirmTask / 或跳过复核自动确认
       ↓
  onKnowledgeConfirmed
       组装 docs ← drafts
       建版
       删源码工作区（留 docs）+ 删 drafts/task_*     ← 【此后无法按仓现读源码重跑】
       enqueuePush(NAS)
       ↓
  PUSHED → cleanupAfterPush：清整个 task runtime
```

| 任务阶段 | 源码 workspace | drafts | 单篇 AI 重跑 |
|----------|----------------|--------|--------------|
| `GENERATING_DOC` / `PENDING_REVIEW` / `REVIEWING` | 有 | 有 | **允许** |
| 已 `CONFIRMED`（确认钩子已跑） | **无**（已 strip） | **无**（已拷进 docs 后删） | **禁止**（明确错误码） |
| `PUSHING` / `PUSHED` | 无 | 无 | **禁止**（与现 `assertNotPushed` 一致并加强） |

### 2.2 对本方案的硬约束

1. **重跑必须现读源码重算 Bundle**（不存整段源码快照）→ 只能在「知识确认前」执行。  
2. **跳过知识复核**（下发页默认关）时任务可能很快进确认清盘 → 复核页几乎无停留；重跑按钮在无 `PENDING_REVIEW` 时本就不可达，属预期。  
3. **开启知识复核**时，用户在 `PENDING_REVIEW` 内可多次「重跑此篇」；整体通过后不可再重跑。  
4. NAS 方案已**停写**依赖扫 `.java` 的 `api-index` / `database-index` / `dependency-index` → 扩大 refs **不再拖累**发布包索引；refs 主要服务复核 UI + 重跑追溯。  
5. 确认后 PG 里 `ci_draft_source_reference` / `ci_knowledge_draft` **元数据仍在**，但 `content_uri` 指向的 drafts 文件已删；正式正文在 `releases`。重跑不以 DB refs 当源码缓存。  
6. 纠错新任务：基线 workspace 可能已回收，靠 `pullAndScan` 重建；新任务生成走同一 SourceBundle，与本方案兼容。

### 2.3 非冲突项（可并行）

- 推送固定 NAS、版本号 `vN`、推送记录只读：与本方案无关。  
- `requireKnowledgeReview` 开关：只影响「有没有复核窗口」，不改 Bundle 算法。  
- TEMP `[TEMP_FULL_PROMPT]` 日志：验收后删除，与本方案独立。

---

## 3. 方案 C 数据模型

### 3.1 `ci_knowledge_draft`

```sql
ALTER TABLE ci_knowledge_draft
  ADD COLUMN IF NOT EXISTS function_node_id VARCHAR(16);

COMMENT ON COLUMN ci_knowledge_draft.function_node_id IS
  '功能节点 ID（f 前缀），与 ci_method_function_binding.function_node_id 对齐；单篇重跑定位用';

CREATE INDEX IF NOT EXISTS idx_draft_function_node
  ON ci_knowledge_draft (workspace_id, function_node_id)
  WHERE function_node_id IS NOT NULL AND is_deleted = 0;
```

- function 粒度草稿：生成时写入 `fn.getId()`。  
- module 粒度草稿：可空（整模块重跑另议；MVP 以 function 粒度为主，与 `doc-generation.granularity=function` 一致）。  
- 旧稿 NULL：重跑时兼容解析 `moduleName` / `filePath`，成功后**回填** `function_node_id`。

### 3.2 `ci_draft_source_reference`

```sql
ALTER TABLE ci_draft_source_reference
  ADD COLUMN IF NOT EXISTS ref_kind VARCHAR(16) DEFAULT 'REACHABLE' NOT NULL;
ALTER TABLE ci_draft_source_reference
  ADD COLUMN IF NOT EXISTS bfs_order INT DEFAULT 0 NOT NULL;

COMMENT ON COLUMN ci_draft_source_reference.ref_kind IS
  'ROOT=binding 入口；REACHABLE=BFS 下游（含同类助手）';
COMMENT ON COLUMN ci_draft_source_reference.bfs_order IS
  'BFS 发现序（从 0 起），代码来源列表/树排序用';
```

| 字段 | 含义 |
|------|------|
| 既有 `file_path` / `class_name` / `method_signature` / 行号 | 不变 |
| `ref_kind=ROOT` | `ci_method_function_binding` 根方法 |
| `ref_kind=REACHABLE` | 正向 BFS 可达且非根（或根已用 ROOT 标过则不再重复） |
| `bfs_order` | 与 prompt 类/方法拼装序一致 |

**不去重掉 Service：** 同一文件多个方法多行 ref；去重键建议 `filePath|className|methodSignature`。

---

## 4. 统一 SourceBundle

### 4.1 结构（逻辑）

```text
SourceBundle
  promptText          // 已有 collectFunctionSourceCode 输出（保序）
  refs: List<RefItem> // 与 prompt 同源
    - filePath, className, methodSignature, startLine, endLine
    - refKind: ROOT | REACHABLE
    - bfsOrder: int
  rootSignatures      // 调试/日志
  functionNodeId
```

### 4.2 收集算法

1. `loadFunctionRootSignatures` → roots（记为 ROOT，bfsOrder 按入队序）。  
2. `methodCallGraphService.resolveReachableMethods`（LinkedHashSet 发现序；含同类边、深度帽）。  
3. 对每个可达签名：解析 filePath + 行号 → RefItem；已在 roots 中的标 ROOT，其余 REACHABLE。  
4. `promptText`：沿用现有按类保序 + 方法保序拼装（与 refs 的 bfs_order 对齐）。  
5. 生成与重跑**只调这一入口**，禁止再走「只写 binding」的旧 `insertDraftSourceReferences` 主路径。

### 4.3 落库

- `upsertFunctionDraft` 时：写 `function_node_id`；`replace` 该 draft 全部 refs = bundle.refs。  
- module 粒度若仍存在：可对模块内每个 function 各写一套 refs，或 MVP 仅保证 function 路径。

---

## 5. 重跑 API 与门禁

### 5.1 接口

```http
POST /api/drafts/{id}/regenerate
Body: { "author"?: string, "remark"?: string }  // remark 默认「AI 重跑」
```

响应建议：

```json
{
  "draftId": 250,
  "status": "AI_GENERATED",
  "contentUri": "...",
  "functionNodeId": "f5G6H",
  "referenceCount": 4
}
```

### 5.2 编排（`DraftService.regenerateDraft` → `AiSummaryService`）

1. 加载 draft + workspace → `taskId`。  
2. **门禁（相对 NAS 清盘加强）：**  
   - 任务状态 ∈ {`PENDING_REVIEW`, `REVIEWING`}（或仍处于生成后、确认前的等价态）；  
   - **禁止** `CONFIRMED` / `PUSHING` / `PUSHED` / `CANCELLED` / `FAILED`（按产品可对 FAILED 另开「整任务重试」）；  
   - `projectDir` 存在且可解析到源码（`TaskWorkspacePaths`）；不存在 → 业务错误：`SOURCE_WORKSPACE_GONE`（文案：知识已确认或源码已回收，无法重跑）。  
3. 解析 `functionNodeId`（列优先；否则解析 moduleName；成功则回填列）。  
4. 加载 hierarchy 中对应 `FunctionDto` + 文档提示词快照。  
5. `collectSourceBundle` → AI（复用 `generateFunctionDraft` 核心）→ 覆盖正文 URI/hash。  
6. 替换 refs；状态 → `AI_GENERATED`（或项目既有「待复核」草稿态）。  
7. 写 `ci_draft_revision`（remark=「AI 重跑」或请求 remark）。  
8. 返回计数；**前端必须重新拉 content + references**（禁止只本地改 `EDITING`）。

### 5.3 覆盖策略（已拍板）

- 覆盖当前正文；前端二次确认：「将覆盖当前正文并重新调用 AI」。  
- 修订记录保留历史版本 URI（与手动保存同一套 revision 机制）。

---

## 6. 前端

| 项 | 改动 |
|----|------|
| `regenerateDraft` | 使用新响应；成功后 `getDraftContent` + `getReferences` + 刷新树状态 |
| 确认 Modal | 覆盖前提示 |
| 代码来源 UI | 列表按 `bfsOrder`；Tag 区分「入口 / 调用链」；条数应为 BFS 规模 |
| 按钮禁用 | 任务已确认/推送中/已推送，或接口返回 workspace gone |

---

## 7. 代码落点

| 模块 | 改动 |
|------|------|
| `schema.sql` / `schema-fresh.sql` | 三列 + 注释 + 索引 |
| `KnowledgeDraft` / `DraftSourceReference` | 字段 |
| `AiSummaryService` (+ Impl) | `SourceBundle`；`collectSourceBundle`；生成落全量 refs；`regenerateFunctionDraft` |
| `DraftService` (+ Impl) / `DraftController` | `POST /{id}/regenerate` + 门禁 |
| `frontend/api/draft.ts` / `workspace.tsx` | API、确认框、刷新、来源展示 |
| 单测 | Bundle 含 Service/requireProduct；regenerate 门禁；旧稿无 function_node_id 兼容 |

---

## 8. 验收清单

1. 新任务生成后，「代码来源」含 Controller（ROOT）+ Service/助手（REACHABLE），顺序入口在前。  
2. `POST /drafts/{id}/regenerate` 200，正文更新，refs 刷新，修订多一条。  
3. 任务 `confirmTask` / 自动确认之后再重跑 → 明确业务错误（非静态资源 404）。  
4. `PUSHED` 后 runtime 已清，重跑仍禁止。  
5. 开启知识复核的任务：在 `PENDING_REVIEW` 内重跑成功；跳过复核直推的任务不依赖单篇重跑。  
6. 删除或保留 `[TEMP_FULL_PROMPT]` 与验收无关，验收后建议删。

---

## 9. 明确不做（本轮）

- 不把整段 `promptText` 落 NAS 快照（确认后也无法「离线重放」；要重跑必须源码还在）。  
- 不做确认后「从 releases 反推源码」的伪重跑。  
- 不恢复已删除的源码类 index 文件。  
- 不改推送记录页 / 版本号策略。

---

## 10. 与既有改动的衔接

| 已做 | 本方案用法 |
|------|------------|
| `dependency_candidates` + Maven 源根 | Bundle BFS 能到 Impl |
| BFS LinkedHashSet + groupByClass 保序 | `bfs_order` / prompt 序一致 |
| 同类/`this` 边 + 深度帽 | REACHABLE 含 `requireProduct` 等 |

旧任务草稿：来源仍可能只有入口；**对该篇执行一次重跑**（须仍在复核窗口且源码未清）即可升为全量 refs。
