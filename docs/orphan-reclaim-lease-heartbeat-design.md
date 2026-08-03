# 孤儿接管优化方案（租约 + 宽限 + 心跳）

> 状态：已确认并实施  
> 背景：活任务因「仅租约过期」被误抢，导致双跑、非法状态流转、`PUSHED` 后自动确认失败。

## 1. 目标

- 兜底：进程重启 / 机器真挂后可接管续跑
- 避免：任务仍在执行时因续租抖动被误抢
- 文档收尾重复进入自动确认时，对已发布任务幂等成功

## 2. 统一孤儿判定（一套逻辑）

可接管当且仅当：

| 条件 | 含义 |
|---|---|
| **A. NO_CLAIM** | `claimed_by` 空 **且** `lease_until` 空 |
| **B. 确认死亡** | **`now > lease_until + grace`** **且** **认领方心跳已死** |

否则不抢。

补充：

- 集群：禁止「仅租约过期就抢」
- `heartbeat.isAlive` 查询异常 → **视为存活**（防 Redis 抖动误杀）
- 本机 `isHeldLocally` / `isPipelineThreadActive` 为真 → 仍跳过
- 单机 `dev`：不写 Redis 心跳；`claimed_by != 本进程` 立刻可接管（热重启）；本进程认领则仅「过宽限」可接管（活任务仍由 `isHeldLocally` / `isPipelineThreadActive` 挡住）

**不做**：同节点接管前 `requestCancel`；不用启动时间 / `updated_date` 做集群主条件。

## 3. 参数

| 配置 | 值 |
|---|---|
| `code-insight.cluster.task-lease-minutes` | **30** |
| `code-insight.cluster.task-lease-grace-minutes`（新增） | **10** |
| `task-lease-renew-interval-ms` | 120000（不变） |
| `instance-heartbeat-ttl-seconds` | 180（不变） |
| 心跳续期 | 随对账约 **30s** 一次（不变） |
| `orphan-reclaim-interval-ms` | 30000（不变） |

真挂机后大约 **≤ 40 分钟**（30+10）可被接管。

## 4. `PUSHING` 孤儿接管（不变）

```text
PUSHING + 判定 B
  → FAILED
  → 提示：请在推送页对 DRAFT/FAILED 版本重新入队
```

与 `autoConfirmAndPublish` 无关。

## 5. `autoConfirmAndPublish` 幂等

| 状态 | 行为 |
|---|---|
| `PUSHING` / `PUSHED` | **直接 return**（记日志，不抛错） |
| `GENERATING_DOC` | `→ CONFIRMED`，再调度发布 |
| `CONFIRMED` | 不重复流转，只调度发布 |
| 其它 | 仍抛业务异常 |

说明：`PUSHING` return 只防「文档收尾重复自动确认」，不替代 §4 孤儿接管。  
`onKnowledgeConfirmed` 已有 `PUSHING`/`PUSHED` skip，保留第二道。

## 6. 改动文件

- `ClusterProperties` + `application.yml`
- `TaskOrphanReclaimScheduler`
- `KnowledgePublishFacade#autoConfirmAndPublish`
- 对应单测

## 7. 验收

- 续租失败、心跳仍在 → 不接管
- 租约刚过期、宽限内 → 不接管
- 进程/Pod 真死 → 约 40 分钟内可接管
- 重复自动确认且已是 `PUSHED`/`PUSHING` → 成功跳过，不翻 `FAILED`
- `PUSHING` 真挂 → 仍由孤儿路径 FAILED + 提示重推
