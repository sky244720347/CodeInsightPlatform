# 文档生成时间（generated_at）方案

> 状态：**已实施**（2026-08-03）

---

## 1. 目标

每个知识文档有独立的「生成时间」，语义为：**正文最后一次由 AI/流水线写出的时间**。

| 要求 | 行为 |
|---|---|
| 增量**直接继承**的文档 | 保留基线原文生成时间，**不**写成继承时刻 |
| **手动重跑**成功 | 刷新为当前时间 |
| 人工编辑 / 确认 / 状态变更 | **不**刷新生成时间 |

---

## 2. 为何不用 `updated_date`

`updated_date` 会被编辑、确认、继承落库、重跑失败回滚等污染。故新增业务字段 `generated_at`。

---

## 3. 数据模型

```sql
ALTER TABLE ci_knowledge_draft
  ADD COLUMN IF NOT EXISTS generated_at TIMESTAMP;

COMMENT ON COLUMN ci_knowledge_draft.generated_at IS
  '正文最后一次 AI/流水线生成时间；继承保留原文；人工编辑不刷新';
```

发布 `meta/module-map.yaml` 每条模块增加：

```yaml
modules:
  - name: "模块 / 子模块 / 功能"
    path: "docs/code-insight/modules/xxx.md"
    generatedAt: "2026-08-03T17:00:00"
```

继承时从 map 读回；旧 release 无该字段时回退 `KnowledgeVersion.pushedAt` / `createdDate`。

---

## 4. 写入规则

| 场景 | `generated_at` |
|---|---|
| `upsertFunctionDraft` / `upsertModuleDraft` | `now` |
| `regenerateFunctionDocument`（经 upsert） | `now` |
| `inheritDrafts` | map / 版本时间回退，**禁止**无脑 `now` |
| 人工 save / confirm | 不动 |
| 重跑失败恢复 status | 不动 |

---

## 5. API / UI

- `DraftTreeNode.generatedAt`、`KnowledgeDraft.generatedAt` 透出  
- 知识复核详情标题区展示「生成时间」

---

## 6. 验收

1. 继承文档：`generated_at` = 基线 map 时间（或版本回退），≠ 继承任务开始时刻  
2. 重跑成功：`generated_at` 更新  
3. 人工保存后：`generated_at` 不变，`updated_date` 可变  
