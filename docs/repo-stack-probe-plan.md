# 仓库类型 / 技术栈探测（Leader 串行）

> **状态：已实施**（2026-08-06 定稿：Leader + COUNT 调度冷却 + 整轮 NAS 统一清理）

---

## 已确认决策

| # | 决策 | 结论 |
|---|---|---|
| A | 候选 | 真空 ∧（`git_reachable=1` ∨ URL `*_db`/`*-db`） |
| B | 多机 | **仅 Leader**（无整轮任务锁）；**每批**续租/校验，丢主跳过本批；**串行** |
| C | 调度冷却 | COUNT=0 → Redis `ci:stack-probe:next-run-at` 阶梯退避（**不是**每仓冷却） |
| D | 有活批间 | **同一轮锁内多批**直到墙钟/列表空 |
| E | NAS | `stack_probe_run_{短runId}/{repoId}/`（毫秒 base36 + 随机）；**整轮 finally 统一删** |
| F | 唤醒 | 新建真空 / 连通变 1 → 清 next-run-at；**不**并行 clone |

---

## 流程

```text
@Scheduled(fixedDelay≈20s) 醒来
  → 非 Leader → return
  → now < next-run-at → return
  → COUNT 待探
       =0 → SET next-run-at += idle backoff，return
       >0 → mkdir NAS run 目录
            → 串行：每批前续租 Leader（降 Redis）；TTL≥整批最坏耗时
            → finally 删整个 run 目录
            → SET next-run-at = now + active-delay
```

Redis 键：

- `ci:leader:repo-stack-probe`（TTL 默认 ≥1800s 或 batch×tree-timeout+120，每批续租一次）
- `ci:stack-probe:next-run-at`

**无** 整轮任务锁 / `cooldown:{repoId}` / 按仓锁。

---

## 配置

```yaml
code-insight:
  repo:
    stack-probe-enabled: true
    stack-probe-interval-ms: 20000
    stack-probe-initial-delay-ms: 30000
    stack-probe-batch-size: 30
    stack-probe-tree-timeout-ms: 45000
    stack-probe-max-sweep-seconds: 1800
    stack-probe-leader-lock-ttl-seconds: 1800
    stack-probe-active-delay-ms: 20000
    stack-probe-idle-backoff-ms: "60000,300000,900000"
    stack-probe-on-create: true
    stack-probe-orphan-max-age-hours: 2
```

---

## 代码

| 类 | 职责 |
|---|---|
| `RepoStackProbeScheduler` | Leader + tick |
| `RepoStackProbeService#runScheduledSweep` | COUNT / 整轮锁 / 多批 / 统一清理 |
| `RepoStackTreeFetcher` | 浅克隆；`deleteWorkDirAfter=false` |
| `RepoStackUrlRules` / `RepoStackTreeClassifier` | 识别规则 |
