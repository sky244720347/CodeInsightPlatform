# 知识文档生成保证方案（A 实施 + B 后续）

> 状态：**方案 A 已实施**（2026-07-29）；方案 B 列为后续优化项。

---

## 1. 问题

| 现象 | 根因 |
|------|------|
| UI「无文档」 | `collectFunctionSourceBundle` 空 → `generateFunctionDraft` **软跳过**不写 `ci_knowledge_draft` |
| 占位/假文档 | AI 超时 / HTTP 失败 / 上下文过大 → 返回 `{}` → `buildPlaceholderDoc`（几乎无源码信息） |
| 重试无效 | 同质重试不缩小 prompt；固定 HTTP 45s 易超时 |

---

## 2. 保证等级

| 等级 | 含义 | 本轮 |
|------|------|------|
| **G1** 结构保证 | 每个功能节点必有一篇 draft | ✅ |
| **G2** 可用保证 | 失败时至少结构化降级稿（含层级/来源摘要） | ✅ |
| **G3** 质量保证 | 每篇完整真 AI 六章 | 尽量（A 裁剪），不硬保证 |

**不加表字段**（第一期）：过程看 `pipeline.log`；结果看 `status` + 正文顶部提示行。  
若后续要在草稿树按档位筛选，再考虑 `generation_tier` / `generation_note`（见 §6）。

---

## 3. 方案 A（本轮实施）：单次调用内预算裁剪

### 3.1 流程

```text
collect SourceBundle
  ├─ 无 promptText → 写 TEMPLATE 降级稿 + [DOC-DEGRADE]（禁止软跳过）
  └─ 有源码
       → 首调不预裁，完整 {java.code} 调 AI
       → callWithRetry
            ├─ HTTP/超时等失败 → 抛 BusinessException，pipeline.log 记真实 reason（非 empty response）
            ├─ 上下文超限 → [AI-SHRINK] 裁剪后重试
            ├─ 纯超时 → 原样重试（不因超时裁剪）
            ├─ 结构不合规 → 追加系统提示
            └─ 全失败 → TEMPLATE 降级稿 + [AI-FALLBACK]
```

### 3.2 裁剪策略

1. **首调不限制**字符；`max-prompt-chars` 仅作「超限后再裁」的目标上限。  
2. 按 `// === Class: … ===` 块保序保留（BFS 发现序，入口类靠前）。  
3. 单块超过 `max-method-body-chars` → 截断并标注 `// ... truncated ...`。  
4. 累计超目标 → 丢弃靠后的 class 块。  
5. 文末追加：`[已截断：classes=k/n，chars=from→to]`。  
6. **不改** DOCUMENT_GENERATION 主提示词；校验仍要求「一、～六、」。

### 3.2.1 真实异常透传

`summarizeWithPrompt` 在 HTTP 非 200 / 网络超时 / 额度阻断时 **抛 `BusinessException(真实文案)`**，不再吞成 `"{}"`。  
`PipelineAiCaller` 的 `[AI-RETRY]` / `[AI-FAIL]` 因此能看到如 `HTTP 400: ... context_length_exceeded ...`。  
仅 Mock 模式仍返回 `"{}"`（预期）。

### 3.3 配置（`code-insight.ai.doc`）

| 键 | 默认 | 说明 |
|----|------|------|
| `max-prompt-chars` | `100000` | 超限后再裁的目标上限（首调不预裁） |
| `max-method-body-chars` | `12000` | 单 class 块方法体上限 |
| `http-timeout-seconds` | `120` | 文档 AI HTTP 超时 |
| `parallelism` | `4` | **本机**文档固定线程池大小；超额功能排队 |
| `acquire-wait-seconds` | `1800` | 文档与模块层级阻塞等集群 `ai.concurrency` 槽；应 ≫ HTTP 超时 |
| `acquire-poll-interval-ms` | `5000` | 等槽轮询间隔；过短只会空转抢锁 |

文档 / `MODULE_HIERARCHY`：应用层对 `tryAcquire` 排队等待（不改 `AiConcurrencyService`）。层级等槽超时 → 放弃当前入口、阶段继续；文档超时走失败/降级路径。

### 3.4 pipeline.log 标签

| 标签 | 含义 |
|------|------|
| `[AI-RETRY]` / `[AI-OK]` / `[AI-FAIL]` / `[AI-BLOCK]` | 已有 |
| `[AI-SHRINK]` | 因预算或失败原因裁剪源码 |
| `[AI-FALLBACK]` | 真 AI 失败后写 TEMPLATE 稿 |
| `[DOC-DEGRADE]` | 源码不可达等，仍写降级稿（替代原 `[skip]`） |

### 3.5 代码落点

| 模块 | 改动 |
|------|------|
| `docs/doc-generation-guarantee-plan.md` | 本方案 |
| `AiDocBudgetProperties` + `application.yml` | 预算/超时配置；retry 拆为 hierarchy/doc |
| `AiRetryProperties` | `hierarchy-max-attempts` / `doc-max-attempts` |
| `DocSourceBudgetShrinker` | 可单测的裁剪算法 |
| `PipelineAiCaller` | `isContextOrTimeoutFailure` 辅助；失败分类不变 |
| `AiSummaryServiceImpl` | 禁软跳过；预裁剪；重试 mutator；降级稿；日志 |
| 单测 | Shrinker +（可选）Caller 分类 |

---

## 4. 方案 B（后续优化项，本轮不做）

**触发条件**：裁剪到仅剩 ROOT（或 `max-prompt-chars` 下限）仍 context/超时失败。

**做法概要**：

1. 片 0：ROOT + 前 K 个 REACHABLE → 完整六章骨架（现有主模板）。  
2. 片 1..N：后续 REACHABLE → 程序追加「分片模式」指令，只输出方法细则增量（**主模板不入库改版**）。  
3. 程序合并为一篇 md（骨架 + 方法块按 `bfs_order` 插入）；`[AI-SHARD]` / `[AI-MERGE]`。  
4. 不推荐「按六章各调一次」。

详见历史讨论；落地前另开实施单并补验收用例。

---

## 5. 明确不做（本轮）

- 真分片（方案 B）  
- `ci_knowledge_draft` 新列  
- 修改库内 DOCUMENT_GENERATION 提示词正文  
- 确认后离线重跑（仍受 NAS 清盘约束）

---

## 6. 可选后续（观测）

```sql
ALTER TABLE ci_knowledge_draft
  ADD COLUMN IF NOT EXISTS generation_tier VARCHAR(16);
ALTER TABLE ci_knowledge_draft
  ADD COLUMN IF NOT EXISTS generation_note VARCHAR(255);
```

`FULL` / `TRUNCATED` / `TEMPLATE`；前端角标。非 G1/G2 前置条件。

---

## 7. 验收

1. BFS/源码不可达：仍有 draft，`pipeline.log` 含 `[DOC-DEGRADE]`，无静默 skip。  
2. 人为超大 Bundle：出现 `[AI-SHRINK]`，尽量产出 `AI_GENERATED`；否则 `[AI-FALLBACK]` + 结构化降级稿。  
3. 任务详情页「执行日志」可看到上述标签。  
4. 单篇重跑：源码不可达时返回明确业务错误或已写出的降级稿（不再「跳过导致无文档」）。
