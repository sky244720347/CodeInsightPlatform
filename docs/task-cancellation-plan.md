# 任务手动终止协作取消方案

> 状态：已实施（M1–M3）  
> 日期：2026-07-30  
> 背景：手动终止仅改 DB 为 `CANCELLED` 并清资源，流水线 `CompletableFuture` 不检查取消；层级/文档长循环与 AI HTTP 会继续跑，任务槽亦延迟释放。

---

## 1. 目标

| 目标 | 说明 |
|------|------|
| 立刻放槽 | terminate 同步 `TaskConcurrencyLimiter.release` + `clearReservation` |
| 停开新活 | 层级入口循环、文档功能 submit/job 开头检查取消 |
| 掐在飞 AI | HTTP 改为 `sendAsync`，terminate 时 `cancel(true)` |
| 保持 CANCELLED | 取消路径禁止 `transitTo(FAILED)` |
| 正常路径无感 | 未终止时行为与现网一致 |

---

## 2. 架构

```text
terminateTask
  → CANCELLED
  → TaskCancellationRegistry.requestCancel (flag + cancel Futures)
  → release 任务槽 + clearReservation
  → 清运行态内存 / 磁盘

协作检查点：阶段边界 / 入口前 / 功能 job 前 / AI attempt 前 / 等槽循环
  → TaskCancelledException
  → 上层 catch 识别后静默退出（不写 FAILED）
```

核心组件：

- `TaskCancellationRegistry`：取消 flag、HTTP/Work/Pipeline Future 登记
- `TaskCancelledException`：取消专用异常（继承 `BusinessException`）

---

## 3. 分期

| 期 | 内容 |
|----|------|
| **M1** | Registry + terminate 放槽/清认领 + 阶段/入口/功能检查点 + 取消不覆盖 FAILED |
| **M2** | AI `sendAsync` + registerHttp + terminate 掐 HTTP；`PipelineAiCaller` 取消禁重试；等槽可取消 |
| **M3** | 文档 Future 登记/cancel；停 submit；修正 `isPipelineThreadActive`；retry/新跑 clear flag |
| **M4**（后续） | 取消后 purge 策略细化；解析文件循环检查 |

本期实施 **M1–M3**。层级半成品策略：**A（不落半棵树）**——取消则抛异常，跳过 `persistAll`。

---

## 4. 改动清单

| 类 | 动作 |
|----|------|
| `TaskCancellationRegistry` | 新建 |
| `TaskCancelledException` | 新建 |
| `DecompileTaskServiceImpl` | terminate / catch / 阶段检查 / pipeline Future / isPipelineThreadActive |
| `ModuleHierarchyServiceImpl` | 入口循环检查；取消不落库 |
| `AiSummaryServiceImpl` | sendAsync；文档 Future；等槽检查 |
| `PipelineAiCaller` | attempt 前检查；取消禁重试 |

---

## 5. 验收

1. 层级第 3/100 入口 terminate → 最多再处理/打断当前 1 次 AI，状态保持 CANCELLED  
2. 文档已 submit 多 job 时 terminate → 未开工不调 AI；在飞 HTTP 被 cancel；槽立即可用  
3. 未点终止的流水线回归：层级/文档/mock/重试行为不变  
4. 单测：Registry；PipelineAiCaller 取消不重试；文档 submit 遇取消停止  

---

## 6. 明确不做（本期）

- `Thread.stop` / 盲目 interrupt commonPool  
- 依赖删 workspace 制造异常当取消  
- 层级半棵树续跑（M4-B）
