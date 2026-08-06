# 文档源码可达性保证方案

> 状态：**已实施**（2026-08-03）  
> 关联：[doc-generation-guarantee-plan.md](./doc-generation-guarantee-plan.md)、[draft-source-bundle-regenerate-plan.md](./draft-source-bundle-regenerate-plan.md)

---

## 1. 目标

在文档生成前消掉「代码不可达」风险；单篇 AI 失败仍可降级，但**降级稿也必须带可定位的代码来源**，便于用户「重跑此篇」再喂 AI。单篇失败不拖垮整任务。

| # | 约束 | 态度 |
|---|---|---|
| 1 | 保留单篇 TEMPLATE 降级 | **保留**（仅表示 AI/内容失败，不表示没源码） |
| 2 | 可接受单篇报错/降级，但必须有代码源 | Bundle/`refs` 必达；重跑可再读源码 |
| 3 | 单独失败不导致整任务失败 | 按功能 try/catch 隔离（已有，保持） |
| 4 | 文档前代码一定可达 | 落表重试/回填 + 取源多级定位，消灭主路径 `source_unreachable` |

---

## 2. 成功标准

对每个 FUNCTION 草稿（含降级稿）：

1. `FunctionSourceBundle.promptText` 非空，或至少 `refs[]` 非空且 `file_path` 在 workspace **exists**
2. `ci_draft_source_reference` 已写入
3. 「重跑此篇」共用同一取源链路，能再喂 AI
4. 单篇异常只计入 failed/degraded，任务继续

主路径上应**不再出现**「清单有类 + `reason=source_unreachable` + refs 空」。

---

## 3. 两段保证 + 一段降级

```text
① 落表可达性（hierarchy）
   每个 FUNCTION → ci_method_function_binding ≥ 1 行
   写库失败 → try-catch 有限重试 + 回读
   逻辑空（白名单剔光 / 无 method_signatures）→ source=BACKFILL 回填
   落表时尽量固化 file_path（entrypoint / method_call / 物理查找，且 exists）

② 文档取源（GENERATING_DOC）
   collectFunctionSourceBundle：表驱动 + 多级路径 + 整文件兜底
   空 Bundle → 文档侧即时回填再取一次
   出口：promptText 非空且 refs 可定位

③ 单篇降级（保留）
   Bundle 已达 → 调 AI
     成功 → AI_GENERATED + refs
     失败 → TEMPLATE + 同一 Bundle 的 refs（可重跑）
```

---

## 4. 数据契约

```text
ci_method_function_binding
  class_name + method_signature (+ 可选 file_path)
        ↓
ci_method_call.file_path / ci_entrypoint.file_path / workspace 物理文件
        ↓
FunctionSourceBundle.promptText + refs[] → ci_draft_source_reference
```

- `source` 枚举扩展：`AI` / `USER` / `MIGRATED` / **`BACKFILL`**
- 新增列：`file_path VARCHAR(500)`（可空；落表时尽量填）

---

## 5. 落表策略（约束：表里一定有数据）

### 5.1 物理失败

`batchInsertBindings`：最多 3 次；退避；成功后按 `function_node_id` 回读 count≥1。

### 5.2 逻辑空 → 回填

对 `class_paths` 非空但笛卡尔积/白名单后零行的 function：

1. 从该类在 `ci_method_call` / 入口方法视图取签名  
2. 仍无 → 占位根签名 + 固化 `file_path`（类级锚点，文档侧整文件读）  
3. `source=BACKFILL` 写入并回读  

### 5.3 路径固化顺序

`entrypoint.file_path` → `method_call.file_path` → ScanScope 下找 `**/SimpleName.java`（必须 exists）

---

## 6. 文档取源策略（约束：有表必达源码）

1. `selectByTaskAndFunction`；空则文档侧 BACKFILL 一次再查  
2. BFS 截方法；截不到则 ROOT 类整文件  
3. 路径：`binding.file_path` → method_call → entrypoint → 物理查找；**禁止**未 exists 的瞎拼路径当成功  
4. AI 失败 → 现有 TEMPLATE；**必须**已有 Bundle/refs  
5. 极端仍不可达 → `[DOC-SOURCE-REPAIR-FAIL]` + 降级壳 + failed++，**不** FAILED 整任务

---

## 7. 明确不做

- 因单篇取源/AI 失败而整任务 `FAILED`  
- 删掉单篇 TEMPLATE 降级  
- 无限死循环重试（上限 3）  
- 确认清盘后仍要求可重跑（沿用 NAS 清盘约定）

---

## 8. 落地清单

| 项 | 说明 |
|---|---|
| `docs/doc-source-reachability-plan.md` | 本方案 |
| `schema.sql` + Entity/Mapper | `file_path`、`BACKFILL` |
| `ModuleHierarchyServiceImpl` | 落表重试 + 空表回填 + 路径 |
| `AiSummaryServiceImpl` / `SourceFileLocator` | 必达取源；降级仍落 refs |
| 单测 | 路径定位；回填语义（可纯单测 / 集成） |

---

## 9. 验收

1. INSERT 首次失败后重试成功 → 文档有源码  
2. 白名单剔光 → BACKFILL 仍有行 → 文档有源码 / refs  
3. AI 超时 → TEMPLATE，但代码来源非空，重跑可升档  
4. 单功能极端失败 → 仅该篇，同任务其它功能完成  
5. 主路径不再出现「有 Controller 清单 + source_unreachable + 空 refs」
