# 拉代码 / 解析并发与排队态改造方案

> 状态：已确认并实施  
> 日期：2026-07-31

## 背景

1. 高并发下任务长时间停在「拉取代码」却不失败：拉完后在 `PULLING_CODE` 上阻塞等 `parse.concurrency`，UI 误导。  
2. `parse.concurrency` 实现上误延伸到 AI 模块层级提炼；本意只护本机 JavaParser 堆。  
3. 拉代码无独立闸，大仓并行 clone 易打满磁盘/带宽。  
4. 任务并发应尽量高（AI/文档可并行），拉/析另限。

## 三闸独立（默认 4 + 1 + 1）

| 配置 | 默认 | 维度 | 含义 |
|------|------|------|------|
| `task.concurrency` | **4** | 本机 | 同时持有「任务执行槽」的流水线数 |
| `pull.concurrency` | **1** | 本机（新） | 同时 `pullAndScan` 数 |
| `parse.concurrency` | **1** | 本机 | 同时本地重解析（AST + 入口发现）数 |
| `ai.concurrency` | （不变） | 集群 | LLM 调用总闸 |

互不合并。流量管控三项分列。

## 任务槽持有

- **一旦占到 `task.concurrency`，中途不因等拉/析而释放**；跑到本段流水线结束或人工断点再放。  
- `PULL_QUEUED` / `PARSE_QUEUED` **仍占任务槽**，避免 PENDING 插队抢拉导致队列雪崩。  
- 仍释放任务槽的时机：流水线结束 / 失败 / 终止；人工断点 `ENTRYPOINT_REVIEW` / `MODULE_HIERARCHY_REVIEW`（其后 `RESUME_QUEUED` 再抢槽）。

## 新状态

| 状态 | 含义 | 任务槽 | 拉/析槽 |
|------|------|--------|---------|
| `PULL_QUEUED` | 已占任务槽，等拉代码槽 | 是 | 否 |
| `PARSE_QUEUED` | 已占任务槽，等解析槽 | 是 | 否 |

流转：

```text
PENDING ──占 task──► PULL_QUEUED ──占 pull──► PULLING_CODE
                                              │ 释 pull
                                              ▼
                                         PARSE_QUEUED ──占 parse──► PARSING_CODE → 入口发现
                                              │ 释 parse + 清解析缓存
                                              ▼
                                         AI_ANALYZING → MODULE_HIERARCHY → …（不占 parse）
```

实现：等闸前先 `transit` 到排队态，再阻塞 `acquire`；禁止在 `PULLING_CODE` 上静默等解析。

## parse 闸范围（收窄）

**占 parse**：`PARSING_CODE` → 入口发现。  

**释 parse**：进入 `AI_ANALYZING` / 模块层级提炼之前；入口复核暂停前也释放。  

**不占 parse**：增量影响（图/DB）、AI 分析、模块层级提炼、文档生成——仅受 `task.concurrency` + `ai.concurrency`。

> 修正历史实现：不再把 parse 许可拿到 AI 层级结束。

## 前端

- 详情 Steps：对齐 `RESUME_QUEUED`——`PULL_QUEUED` 停「拉取代码」+「排队中」；`PARSE_QUEUED` 停「静态解析」+「排队中」。  
- 列表：主标签「排队拉取」/「排队解析」，下标 `(拉取代码)` / `(静态解析)`；计入进行中。

## 非目标

- 不改集群 AI 闸语义。  
- 不把拉/析闸做成 Redis 集群闸（本机护资源即可）。  
- 不在资源排队时释放任务槽。
