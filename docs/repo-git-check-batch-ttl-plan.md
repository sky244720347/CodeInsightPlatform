# Git 连通性扫描优化（分批 + 分层 TTL）

> 状态：**已实施**（2026-08-03）  
> 前置：[repo-git-connectivity-design.md](./repo-git-connectivity-design.md)

## 问题

定时全库复查 + 已连通也扫；千仓时单轮墙钟远超 `git-check-interval-ms`，造成重叠、Leader 锁久占、Git/网络压力。

## 目标

1. **单轮有界**：每 tick 只处理一批 due 仓，墙钟 ≤ `max-sweep-seconds`
2. **已连通降频**：按 TTL；未检测优先、不通次之
3. **防重入**：上轮未完成则跳过本 tick
4. **游标**：跨 tick 扫全库，尾部不饿死
5. 不改任务门禁；手动测 / 按系统批量仍全量测该范围

## 策略

| 状态 | 定时复查 |
|---|---|
| `git_reachable IS NULL` | 每轮优先 |
| `= 0` 不通 | `checked_at` 早于 now − unreachable_ttl |
| `= 1` 已连通 | `checked_at` 早于 now − reachable_ttl |

默认：`reachable_ttl = 6h`，`unreachable_ttl = 30min`，`batch_size = 40`，`max_sweep_seconds = 120`。

## 配置

```yaml
code-insight.repo:
  git-check-batch-size: 40
  git-check-max-sweep-seconds: 120
  git-check-reachable-ttl-ms: 21600000
  git-check-unreachable-ttl-ms: 1800000
```

## 验收

1. 已连通且 `checked_at` 新鲜 → 不进本轮  
2. 未检测优先于已连通  
3. 并发 sweep 第二次跳过  
4. 单轮不超过 max-sweep（提交波次受墙钟约束）  
