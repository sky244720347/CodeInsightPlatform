# 任务并发许可：重启后死锁 / 排队卡死修复方案

> **问题**：服务异常退出或重启后，后续任务长期 `PENDING` 排队。  
> **根因**：集群模式下 Redis `ci:permits:task:*` 中的 holder（`task:{id}`）在崩溃时未 `release`，占用名额；当前仅靠 Set key **24 小时** TTL 整 key 过期。  
> **状态**：已实施（任务 + AI）。  
> **范围**：任务并发闸门 `ci:permits:task:*`；AI 并发 `ci:permits:ai:*` 同构清理。

---

## 〇、现象与分环境

| 环境 | `code-insight.env` | 许可存放 | 重启后行为 |
|---|---|---|---|
| 本地开发 | `dev` | JVM `Semaphore` | **进程没了许可也没了**，一般不会因「残留许可」卡队列 |
| 非 dev / 集群 | 非 `dev` | Redis Set | **重启不清理 Set** → 旧 `task:{id}` 仍占坑 → `availablePermits=0` → 一直不调度 |

本地若也出现「重启后一直排队」，优先查：调度器是否起来、任务是否真 `PENDING`、系统/全局上限是否为 0；**不是** Redis 许可 TTL 问题。

本方案主要针对 **Redis 许可残留**。

---

## 一、现状

```text
tryAcquire → SADD ci:permits:task:global  task:{id}
           → SADD ci:permits:task:sys:{sysId}  task:{id}
           → EXPIRE key 24h（每次 acquire 刷新整 key）

runPipeline finally → SREM 两边（正常路径）

崩溃 / kill -9 / OOM → finally 不跑 → 成员残留
```

`RedisDistributedPermits.reconcileStale` 已存在，但**没有任何启动/周期任务调用**清理任务许可。

仅靠缩短 24h TTL **不够**：

- TTL 挂在 **整个 Set key** 上，不是单成员；
- 只要有任意任务仍在跑并 `acquire`，会反复 `EXPIRE 24h`，僵尸成员可能长期不掉；
- 若改成极短 TTL 且无续租，正常长任务也会误释放，造成超卖。

---

## 二、目标

1. **重启后短时间内**（秒～分钟级）恢复可调度，不靠等 24h。  
2. 不超卖：仍在跑的真实流水线任务继续占许可。  
3. TTL / 续租可配置，运维可调。  
4. 本地 `dev` 行为不变（仍走 Semaphore）。

---

## 三、推荐方案（组合）

### 策略 A（主）：启动 + 周期「按 DB 对账」清僵尸（必做）

**规则**：Redis 中 `task:{id}` 合法，当且仅当 `ci_task` 对应行处于「仍应占许可」的状态。

建议「应占许可」状态（与调度拉起～流水线 finally 之间一致）：

```text
PULLING_CODE, PARSING_CODE, SPLITTING_TASK,
AI_ANALYZING, MODULE_HIERARCHY, BASELINE_DOC_INHERIT,
GENERATING_DOC, PUSHING
```

**不占许可**（应对齐删除 Redis 成员）：

```text
PENDING, DRAFT,
ENTRYPOINT_REVIEW, MODULE_HIERARCHY_REVIEW,   -- 现实现 finally 会 release
PENDING_REVIEW, REVIEWING, CONFIRMED, PUSHED,
FAILED, CANCELLED, ARCHIVED
```

对账逻辑：

```text
holders = SMEMBERS ci:permits:task:global
for each task:{id}:
  if 任务不存在 OR status ∉ 应占许可集合:
    SREM global + 各 sys pool 中的该 holder
```

触发：

| 时机 | 说明 |
|---|---|
| `@PostConstruct` / `ApplicationReadyEvent` | 节点启动立即对账一次 |
| `@Scheduled` 固定间隔 | 建议默认 **60s**（可配），防漏网 |
| 可选管理接口 | `POST /api/admin/task-permits/reconcile` 运维手动触发 |

实现落点建议：

- `TaskConcurrencyLimiter.reconcileWithDatabase()`  
- 内部调 `RedisDistributedPermits.reconcileStale(...)`  
- 仅 `cluster.enabled=true` 时执行  

**效果**：重启后第一次对账即可清掉已死任务的 holder，队列恢复，**不依赖改短 TTL**。

---

### 策略 B（辅）：优雅停机主动 release（建议做）

`@PreDestroy` / Spring `DisposableBean`：

- 对本节点已知 in-flight（可维护 `ConcurrentHashMap<Long, Long> heldTaskIds`，acquire 登记、release 移除）逐个 `release`；  
- 杀不干净的仍靠策略 A。

对 `kill -9` 无效，故 A 仍是主路径。

---

### 策略 C（辅）：缩短 key TTL + 运行中续租（可选，改「过期时间」）

若仍希望残留有上限：

| 配置项（建议） | 默认建议 | 含义 |
|---|---|---|
| `code-insight.cluster.task-permit-ttl-seconds` | **300**（5 分钟） | Set key 空闲/无续租后的最长残留 |
| `code-insight.cluster.task-permit-renew-seconds` | **60** | 流水线进行中周期 `EXPIRE` 续租 |

约束：`renew < ttl`，且长任务必须续租，否则误释放导致超卖。

实现要点：

- `tryAcquire` 成功后 `EXPIRE ttl`（替换写死 24h）；  
- 流水线线程或调度旁路每 `renew` 秒对持有任务的 pool key 续期；  
- **不能只改短 TTL 不做续租**。

与策略 A 叠加：即使 TTL 较长，重启对账仍立刻恢复。

---

### 策略 D（dev 防护，可选）

本地若误配非 `dev` 却单机跑：启动日志明确打印 `cluster.enabled` 与 permits 后端（Redis vs Semaphore）。  
可选：dev 启动时若发现 `ci:permits:task:*` 非空，打 warn 或自动 reconcile（防环境串用同一 Redis）。

---

## 四、不推荐

| 做法 | 原因 |
|---|---|
| 仅把 24h 改成 5 分钟且无续租 | 长任务中途丢许可 → 超卖 |
| 重启时 `DEL ci:permits:task:*` 一刀切 | 多节点时误删仍在跑节点的合法 holder |
| 只改表把任务 CANCELLED | Redis holder 仍在，队列依旧不动（集群） |
| 依赖人工清 Redis | 易忘，且无审计 |

---

## 五、改动清单（确认后实施）

| # | 项 | 内容 |
|---|---|---|
| 1 | `TaskConcurrencyLimiter` | `reconcileWithDatabase()`；持有集合登记；可选续租 |
| 2 | `TaskPermitReconcileScheduler` | Ready 时对账 + 周期对账（仅 cluster） |
| 3 | `RedisDistributedPermits` | TTL 可配；提供 `expire(pool, ttl)` / `members(pool)` |
| 4 | `application.yml` | `task-permit-ttl-seconds` / `renew-seconds` / `reconcile-interval-ms` |
| 5 | 优雅停机 | `@PreDestroy` release 本节点 held |
| 6 | 单测 | 僵尸 holder + DB 已终态 → reconcile 后 available > 0；在跑状态不删 |
| 7 | 文档 | 更新本文件状态为已落地；运维手册补「卡队列先 reconcile」 |

**默认建议落地范围**：策略 **A + B**；策略 **C** 把 TTL 从 24h 改为 5min+续租作为增强（可一并做）。

---

## 六、验收

1. 集群模式：人为 `SADD ci:permits:task:global task:999`（DB 无此任务或已 CANCELLED）→ 启动或等一轮 reconcile → `SCARD` 降下来，PENDING 任务被调度。  
2. 真实 `GENERATING_DOC` 任务 holder 不被误删。  
3. 正常跑完 finally 仍正确 release。  
4. `dev` 模式不走 Redis 对账，行为与今一致。  
5. （若做 C）长任务 > TTL 仍不丢许可（续租生效）。

---

## 七、已确认与跟进

1. **本期范围**：A+B+C（对账 + 短 TTL + 续租）已落地。  
2. **TTL 默认**：`task-permit-ttl-seconds=300`，续租/对账周期见 `ClusterProperties`。  
3. **人工断点状态**：不占许可，对账时清掉误残留。  
4. **AI 并发** `ci:permits:ai:*`：已同期落地——holder 形如 `ai:{instanceId}:{uuid}`；启动/周期清本节点孤儿；TTL/续租与任务共用集群配置；`@PreDestroy` 释放本节点持有。
