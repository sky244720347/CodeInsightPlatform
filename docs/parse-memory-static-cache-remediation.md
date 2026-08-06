# 解析静态缓存残留治理方案（质量优先 / 不降速）

> 状态：批次 A 已实施  
  
> 日期：2026-08-03  
> 证据：STG 日志 `AstJavaParser static caches growing: symbolSolver=18 subtypeIndex=18`（`doc-ai-*` 线程）  
> 约束：
>
> 1. 会使用入口试跑  
> 2. **不得降低任务产出质量**（喂给 AI 的解析/调用链/源码切片语义变差则不做）  
> 3. **不得为省内存而拉长任务耗时**（加闸排队、强制重解析整仓等不做）  
> 4. 无需复核路径为主；复核断点相关项排除  
> 5. **不做运维 HTTP 接口**（stats / clear-all）

---

## 1. 问题定性

### 1.1 已证实

静态 Map 在膨胀，不是单纯「RSS 不归还 OS」：

```text
AstJavaParserService.SYMBOL_SOLVER_CACHE   → symbolSolver=18
AstJavaParserService.SUBTYPE_INDEX_CACHE → subtypeIndex=18
```

告警阈值是 `>= 8`（见 `acquireProjectContext`）。`18` 表示 **Map 里同时存在 18 套 ProjectContext**。

### 1.2 根因（两层叠加）

| 层 | 机制 | 结果 |
|----|------|------|
| A. 多模块根分裂 | `discoverSourceRoot` 向上遇**第一个** `pom.xml` / `build.gradle` 就返回，多模块仓每个子模块各建一套 SymbolSolver + subtype | 单任务即可到十几（与日志 `18` 吻合） |
| B. 驱逐缺口 | 入口试跑 `TrialRunServiceImpl` **从不** `TaskParseMemoryService.evict`；历史任务/试跑残留会叠在 static Map 上 | idle 后仍可能 >0 |

文档生成阶段（`doc-ai-*`）会再次 `parseFile` → `acquireProjectContext`，因此告警常出现在 AI 文档线程——这与「解析段已 evict、AI 段重建」的现有流水线一致。

### 1.3 无需复核路径下的缓存生命周期（现状）

```text
PARSING + 入口发现
  → releaseParsePermitAndEvict          （清一次）
AI / 模块层级 / GENERATING_DOC（doc-ai 再 parse）
  → 重建 SymbolSolver / subtype / parseCache
流水线 finally
  → taskParseMemoryService.evict + clearRuntimeCaches
```

**正常正式任务终态应清空该 taskId 条目。**  
idle 仍高 / 告警长期停在 18，优先查：**试跑未清** + **多任务/多试跑残留叠加** + **evict 是否漏删绝对路径 key**。

---

## 2. 约束下的取舍原则

| 原则 | 做法 |
|------|------|
| 产出质量不变 | 只做「用完释放 / 修好驱逐」；不改解析语义、不关 subtype、不改 AI 用的轻量解析 |
| 不降速 | 不把 AI/文档再挂回 `parse.concurrency`；不做机械 B1 |
| 无运维接口 | 不暴露 stats / clear-all HTTP；靠代码路径保证驱逐 + 日志 WARN |

---

## 3. 方案清单

### P0-1 入口试跑 finally 驱逐 — **必须修（本轮实施）**

| 项 | 内容 |
|----|------|
| **怎么修** | `TrialRunServiceImpl` 注入 `TaskParseMemoryService`；`executeAsyncInternal` 的 `finally` 中调用 `evict(trialId)`（成功/失败/取消/早退都清）。 |
| **验收** | 单测断言 finally 调用 `evict`；连续试跑后 static 缓存不随次数线性涨。 |

---

### P0-2 驱逐硬化 + 日志可观测 — **必须修（本轮实施）**

| 项 | 内容 |
|----|------|
| **怎么修** | 1）`evictTaskCaches`：parseCache 按 `task_{id}/` 前缀 **或** `matchesTaskWorkspacePath` 删除（覆盖绝对路径 key）。<br>2）SymbolSolver/subtype 继续按路径匹配删。<br>3）evict 后若仍有属于本 taskId 的 key，打 **WARN**（附最多 5 个样例）。 |
| **不做** | 运维 HTTP 接口。 |
| **验收** | 单测：相对路径 + 绝对路径 key 均可按 taskId 删净。 |

---

### P0-3 流水线终态回归 — **必须修（本轮实施）**

| 项 | 内容 |
|----|------|
| **怎么修** | 确认无需复核主路径 finally 已调用 `taskParseMemoryService.evict`；补单测锁住试跑 / 驱逐契约。正式任务三 Map 清理已有 `TaskRuntimeCacheClearTest`。 |
| **验收** | 相关单测通过。 |

---

### 明确不做

| 项 | 原因 |
|----|------|
| 运维 stats / clear-all 接口 | 产品要求不做 |
| 自动空闲 clearAll | 可能冷启动变慢 |
| `.git` 优先合并模块根 | 可能改变解析产出 |
| lite parse / 关 subtype / AI 加 parse 闸 / 机械 B1 | 质量变差或变慢 |
| 复核断点相关 | 不走复核 |

---

## 4. 实施批次

```text
批次 A（本轮）
  1. P0-1 试跑 finally evict
  2. P0-2 驱逐硬化 + incomplete WARN（无运维接口）
  3. P0-3 单测锁住

批次 B（仅当 idle 仍稳定残留）
  → 查 evict WARN 样例 key
  → 不进入模块根合并，除非接受产出差异
```

---

## 5. STG 验证

1. 部署后跑 1 次入口试跑，查日志 `evictTaskCaches` / 无持续 `static caches growing` 叠高。  
2. 跑无需复核正式任务至 `PENDING_REVIEW`，确认无该 task 残留告警。  
3. 并发任务全部结束后，不应再出现随历史试跑次数线性上涨的 `symbolSolver`。  
4. 若**单个**大仓文档生成中途仍刷到 `=18`：属多模块峰值，本约束下接受。

---

## 6. 结论表

| 编号 | 项 | 本轮 |
|------|----|------|
| P0-1 | 试跑 finally evict | **实施** |
| P0-2 | 驱逐硬化 + WARN | **实施**（无运维接口） |
| P0-3 | 终态 / 试跑单测 | **实施** |
| — | 运维 HTTP | **不做** |
| P1-2 等 | 改解析根 / lite parse 等 | **不做** |
