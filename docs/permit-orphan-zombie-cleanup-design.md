# 任务 / AI 并发许可：孤儿与僵尸统一清理方案

> **管什么**：集群模式下 `ci:permits:task:*` 与 `ci:permits:ai:*` 的残留清理，避免「任务数很少 / AI 并发配了 10」仍报满或排队卡死。  
> **不管什么**：改 `ai.concurrency` / `task.concurrency` 业务语义；模块层级串行；LLM 限流本身。  
> **状态**：已实施（按 2026-07-24 确认项）。  
> **前置**：`docs/task-permit-reconcile-on-restart-design.md`（A+B+C 已落地，但仍有缺口）。  
> **确认（2026-07-24）**：① Task T3 做；② 不加 `CODE_INSIGHT_INSTANCE_ID`（复用已有 `ClusterInstanceId`，重启即变）；③ 流量管控页补 `task.concurrency` + 两按钮清空 Task/AI Redis 许可；④ Lua 原子 acquire 做。

---

## 〇、现象与缺口

| 现象 | 可能根因 |
|---|---|
| 仅 3～4 个任务在跑，`ai.concurrency=10` 仍报「AI 调用并发已达上限」 | Redis Set 成员数 ≥10，其中大量非本轮真实占用 |
| 任务全局并发有空位，PENDING 长期不调度 | `ci:permits:task:global` 中僵尸 `task:{id}` 占坑 |
| 任意活任务续租会刷新**整 key TTL** | 僵尸成员不会因 TTL 自然掉光 |

### 当前能力 vs 缺口

| 池 | 已有 | 缺口 |
|---|---|---|
| **Task** | 按 DB `status ∈ 应占许可` 对账；畸形 holder 删除；TTL+续租；优雅停机 | ① status 仍为 `GENERATING_DOC` 等但进程已死（lease 过期）时**仍保留**许可；② 系统池里孤立成员未从「扫 sys pool」主动发现；③ `tryAcquire` check-then-SADD 非原子，边界可超卖 |
| **AI** | holder=`ai:{instanceId}:{uuid}`；只清**本节点前缀且不在 localHeld**；TTL+续租；优雅停机 | ① 旧格式 `ai:{uuid}` **永不清理**；② **其它已死节点**前缀 holder 清不掉；③ 无 DB 真相，不能按 task 对账 |

---

## 一、目标

1. **Task**：终态 / 不存在 / **租约失效的假运行** → 许可可在一分钟内清掉。  
2. **AI**：本机孤儿 + **已下线实例**残留 + **旧格式**残留 → 均可清理；在飞调用不误伤。  
3. 两边共用同一调度器节奏（启动 + 周期），配置可运维。  
4. `dev`（Semaphore）不变。

---

## 二、统一模型

```text
                    ┌─────────────────────────────┐
                    │ PermitReconcileScheduler    │
                    │ Ready + fixedDelay(60s)     │
                    └─────────────┬───────────────┘
                                  │
              ┌───────────────────┴───────────────────┐
              ▼                                       ▼
   TaskConcurrencyLimiter                  AiConcurrencyService
   reconcileTaskPermits()                  reconcileAiPermits()
              │                                       │
              ▼                                       ▼
        Redis Set                                  Redis Set
   ci:permits:task:global                    ci:permits:ai:global
   ci:permits:task:sys:{id}
```

另增：**实例心跳登记**（AI 跨节点清理的依据；Task 加强「假运行」时也可复用）。

```text
SET ci:permits:instance:{instanceId} = 1  EX 90s
周期续租（与 permit renew 同调度）
```

`instanceId` 与 AI holder 前缀一致（`HOSTNAME` / `COMPUTERNAME` 规范化）。

---

## 三、Task 清理规则（加强）

### 3.1 合法 holder

格式：`task:{taskId}`（其它一律僵尸，直接 SREM global + 各 sys）。

### 3.2 判定「应保留」

同时满足才保留：

| # | 条件 |
|---|---|
| T1 | `ci_task` 存在 |
| T2 | `status ∈ PERMIT_HOLDING_STATUSES`（与现表一致） |
| T3（新增） | **租约仍有效**：`lease_until != null && lease_until > now()` **或** 本节点 `localHeldTasks` 含该 taskId |

说明：

- 流水线正常跑时，认领/执行侧应保证 `lease_until` 被续；若现状未续 lease，本期一并：**持有许可期间续 lease**，或对账时对「本节点 localHeld」豁免 T3。  
- 人工断点态本就不在 T2，继续清掉误残留。

不满足 → 记入 stale，从 `task:global` + **所有** `task:sys:*`（及 `sys:unknown`）SREM。

### 3.3 系统池双向扫（新增）

除「以 global 为准删 sys」外，对每个 `task:sys:{id}`：

- 成员不在 global → 删（孤立）  
- 或按同一 T1–T3 判僵尸 → 删  

避免 sys 池假满导致系统级并发误拒。

### 3.4 原子 acquire（建议同期）

`RedisDistributedPermits.tryAcquire` 改为 **Lua**：`SCARD < max` 才 `SADD`，否则失败；成功后 `EXPIRE`。消除 check-then-act 超卖。

---

## 四、AI 清理规则（加强）

### 4.1 Holder 格式

| 格式 | 含义 | 处理 |
|---|---|---|
| `ai:{instanceId}:{uuid}` | 现行 | 按实例存活 + 本机 held 判断 |
| `ai:{uuid}`（无第二段实例） | 历史 | **一律视为僵尸删除** |
| 其它 | 非法 | 删除 |

### 4.2 判定「应保留」

对 `ai:{instanceId}:{uuid}`：

| # | 条件 |
|---|---|
| A1 | Redis 存在心跳 key `ci:permits:instance:{instanceId}`（未过期） |
| A2 | 若 `instanceId == 本机`：还必须在 `localHeldHolders` 中 |

因此：

- **本机崩溃残留**：重启后无 localHeld → 清本机前缀（现有逻辑保留）。  
- **它机崩溃**：心跳过期 → 清该前缀全部 holder（新增）。  
- **它机仍活且持有**：心跳在 + 不碰其 holder（多节点安全）。  
- **旧格式**：无实例段 → 直接删。

### 4.3 心跳

| 项 | 建议默认 |
|---|---|
| key | `ci:permits:instance:{instanceId}` |
| TTL | **90s**（`instance-heartbeat-ttl-seconds`） |
| 续租 | 与 permit reconcile 同周期（60s），或独立 30s |
| 写入时机 | `ApplicationReady` + 每次 scheduled reconcile/renew |

停机 `@PreDestroy`：可 DEL 本机心跳（加速它机清理）；杀 -9 则等 TTL。

### 4.4 为何不用「整池 DEL」

多节点时误删在飞 AI 调用 → 超卖或逻辑错乱。心跳方案可精确按实例裁剪。

---

## 五、配置（共用 / 可加）

沿用现有：

| Key | 默认 | 用途 |
|---|---|---|
| `code-insight.cluster.task-permit-ttl-seconds` | 300 | task/ai Set key TTL |
| `code-insight.cluster.task-permit-renew-seconds` | 60 | 续租间隔（语义可升为 permit-renew） |
| `code-insight.cluster.task-permit-reconcile-interval-ms` | 60000 | 对账周期 |

新增：

| Key | 默认 | 用途 |
|---|---|---|
| `code-insight.cluster.instance-heartbeat-ttl-seconds` | 90 | 实例存活心跳 TTL |
| （可选）`code-insight.cluster.permit-reconcile-on-ready` | true | 启动对账开关 |

业务上限仍走系统配置：`task.concurrency` / `ai.concurrency`（与清理无关）。

---

## 六、调度与接口

### 6.1 `PermitReconcileScheduler`（现 `TaskPermitReconcileScheduler` 可改名）

每轮顺序：

1. `touchInstanceHeartbeat()`  
2. `taskLimiter.reconcileTaskPermits()`（含 T3 + sys 双向）  
3. `aiConcurrency.reconcileAiPermits()`（旧格式 + 死实例 + 本机孤儿）  
4. `taskLimiter.renewLocalHeldPermits()`  
5. `aiConcurrency.renewLocalHeldPermits()`  

`ApplicationReady`：先 heartbeat，再 2+3（可不 renew）。

### 6.2 运维接口（建议）

`POST /api/admin/permits/reconcile`  

- 返回：`taskRemoved` / `aiRemoved` / `aiMembersBefore` / `taskMembersBefore`  
- 权限：与现有 admin 一致（MVP 可先内网）

可选只读：`GET /api/admin/permits/stats` → SCARD + 抽样 members + 配置 max。

---

## 七、改动清单

| # | 项 | 内容 |
|---|---|---|
| 1 | `ClusterProperties` | 增加 `instanceHeartbeatTtlSeconds` |
| 2 | `InstanceHeartbeat`（小组件）或塞进 Scheduler | SET/续租/列举存活 instanceId |
| 3 | `TaskConcurrencyLimiter` | T3 lease/localHeld；sys 池双向扫；命名 `reconcileTaskPermits` |
| 4 | 任务 lease 续租 | 持有许可期间保证 `lease_until` 有效（或对账豁免 localHeld） |
| 5 | `AiConcurrencyService` | 清旧格式；按心跳清死实例；保留本机孤儿逻辑 |
| 6 | `RedisDistributedPermits` | Lua 原子 tryAcquire |
| 7 | Scheduler | 统一编排 + Ready 顺序 |
| 8 | Admin API（可选） | reconcile / stats |
| 9 | 单测 | 旧 `ai:uuid` 删除；死实例前缀删除；活实例保留；task lease 过期删除；holding+lease 有效保留 |
| 10 | 文档 | 本文落地；更新旧 reconcile 文档缺口说明 |

---

## 八、风险与边界

| 风险 | 缓解 |
|---|---|
| lease 未续导致误删仍在跑任务的许可 | localHeld 豁免；或先修 lease 续租再启用 T3 |
| 时钟 skew / 心跳抖动 | heartbeat TTL(90) > reconcile(60)；续租失败打 warn |
| 实例 ID 冲突（同 HOSTNAME 多进程） | 文档要求容器设唯一 `HOSTNAME`/`CODE_INSIGHT_INSTANCE_ID`；可选配置强制唯一 ID |
| Lua 引入后行为变严 | 单测 + 灰度看 SCARD |

---

## 九、验收

1. `SADD ... ai:dead-uuid` 与 `ai:oldstyle-only` → 一轮 reconcile 后消失。  
2. `SADD ... ai:deadHost:xxx`，无对应 heartbeat → 被删；`ai:{本机活}:{uuid}` 且在 localHeld → 保留。  
3. `SADD task:999` 且 DB 无任务 / 终态 → 删；`GENERATING_DOC` 且 lease 过期且非本机 held → 删；lease 有效 → 保留。  
4. `ai.concurrency=10`，无僵尸时 4 个任务跑 AI 不再误报上限。  
5. `dev` 不写 Redis 许可、不对账。

---

## 十、已确认

1. **Task T3**：做。保留条件 = `localHeld` **或**（status 应占许可 **且** lease 未过期 **且** `claimed_by` 对应实例心跳仍在）。  
2. **实例 ID**：不做新 env；统一用现有 `ClusterInstanceId`（`host:pid:uuid`，重启变化符合单机预期）。  
3. **页面**：流量管控补 `task.concurrency`；两按钮「清空任务 Redis 并发」「清空 AI Redis 并发」（整池 DEL，确认后执行）。  
4. **Lua 原子 acquire**：同期做。