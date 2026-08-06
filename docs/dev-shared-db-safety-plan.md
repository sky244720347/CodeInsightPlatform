# Dev 防污染 STG 方案（无孤儿 + 只跑本机 IP 任务 + 修复操作日志 IP）

> 状态：已实施  
> 日期：2026-08-03（实施 2026-08-04）  
> 背景：本地 `CODE_INSIGHT_ENV=dev` 误连测试库时，单机孤儿逻辑以 `CLAIMED_BY_OTHER_PROCESS` 抢走 STG Pod 认领的任务；同时 `ci_operation_log.ip_address` 恒为 `127.0.0.1`，无法用日志区分来源。  
> 约束：不降低正式集群（非 dev）行为与任务产出质量。

---

## 0. 结论先讲

| 诉求 | 结论 |
|------|------|
| 1. dev 不要孤儿 | **做**：dev 下整段关闭孤儿接管（含启动扫与周期扫） |
| 2. dev 只执行本机 IP 任务 | **做**：任务创建时写入真实客户端 IP；dev 自动派发/续跑只认「创建 IP ∈ 本机网卡地址（含 127.0.0.1）」的任务 |
| 3. operation_log IP 全是 127.0.0.1 | **是问题**：代码写死 Mock，**一并修**；调度类无 HTTP 上下文时再标 `0.0.0.0` / `scheduler` 等，禁止假 127.0.0.1 |

任务环境亲和的**精确定义**：

```text
任务字段 is_dev（新建）= 创建时后端 CodeInsightEnvProperties.isDev()
dev 可执行 ⟺ is_dev = true

机器区分走 ci_operation_log.ip_address（loopback 回落本机网卡 IP）
```

- STG 后端创建 → `is_dev=false` → **本机 dev 不派发**  
- 本机 `CODE_INSIGHT_ENV=dev` 创建 → `is_dev=true` → **可派发**  
- 历史任务 `is_dev` 默认 false / null → **dev 不碰**

---

## 1. 问题拆解

### 1.1 孤儿误抢（已证实）

dev 下 `cluster.enabled=false`，`TaskOrphanReclaimScheduler.isOrphan`：

```text
claimed_by 非空 且 ≠ 本进程 instanceId → 立刻 orphan（CLAIMED_BY_OTHER_PROCESS）
```

误连 STG 库时会抢走 `ph-qtcp-...` 等 Pod 的在飞任务。

### 1.2 自动派发也会抢 STG

即便关掉孤儿，`TaskQueueDispatcher` 仍用 `FOR UPDATE SKIP LOCKED` 抢任意 `PENDING` / `RESUME_QUEUED`，dev 一样会把 STG 队列任务拉到本机跑。

### 1.3 `ip_address` 恒为 127.0.0.1 — **确认是缺陷**

```java
// OperationLogServiceImpl.logOperation
log.setUserId(1L);           // Mock
log.setUsername("Owner");    // Mock
log.setIpAddress("127.0.0.1"); // 写死，与真实请求无关
```

因此日志里无法区分本机调度 vs 远程用户；**不能**用现有错误的 `ip_address` 做亲和，必须先修写入；任务亲和改用 `is_dev`。

---

## 2. 目标行为矩阵

| 场景 | 孤儿接管 | 自动派发 PENDING | 手动启动任务 |
|------|----------|------------------|--------------|
| **dev** + `is_dev=true` | 否 | **是** | 是 |
| **dev** + `is_dev=false` / null（含 STG 历史） | 否 | **否** | 否 |
| **非 dev（STG/prod 集群）** | 保持现网（租约宽限+心跳） | 保持现网（不过滤 is_dev） | 保持现网 |

---

## 3. 方案明细

### P0-A — dev 关闭孤儿接管

**文件**：`TaskOrphanReclaimScheduler`

- 注入 `CodeInsightEnvProperties`
- `onReady` / `scheduledReclaim`（及草稿 REGENERATING 清理可选同步跳过或保留仅本机草稿——本轮草稿清理若可能改 STG 行，建议 **一并跳过**）入口：

```text
if (env.isDev()) {
  log.debug/skip once: "dev 禁用孤儿接管";
  return;
}
```

- **不改**非 dev 的 `isOrphan` 判定。

**验收**：dev 连任意库，不再出现本机写入的 `TASK_ORPHAN_RECLAIM` / `CLAIMED_BY_OTHER_PROCESS`。

---

### P0-B — 任务落 `is_dev`；dev 按 `is_dev` 派发

**Schema**（幂等）：

```sql
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS is_dev BOOLEAN DEFAULT FALSE;
ALTER TABLE ci_task DROP COLUMN IF EXISTS client_ip;
```

实体 / 创建路径：

- `DecompileTask.isDev = envProperties.isDev()`（INITIAL / INCREMENTAL / 纠错 / 定时均如此）

**TaskQueueClaimService**（仅 `env.isDev()`）：

```text
AND is_dev = TRUE
```

历史 `is_dev` 默认 false：dev **不自动跑**。

**手动 start / retry**（dev）：`is_dev != true` → 拒绝。

**非 dev**：不过滤 `is_dev`。

---

### P0-C — 修复 `ci_operation_log.ip_address`（及操作人 Mock）

**文件**：`OperationLogServiceImpl`

| 字段 | 现状 | 改为 |
|------|------|------|
| `ip_address` | 写死 `127.0.0.1` | `ClientIpResolver.resolveOr("scheduler")` |
| `user_id` / `username` | Mock Owner | `OperatorContext.getUserId()` / `get()`（无上下文用 `sys`） |

调度线程（孤儿、dispatcher、定时）无 HTTP：

- IP 记 `scheduler` 或 `0.0.0.0`（**不要**再写假 `127.0.0.1`）  
- 用户名记 `sys`

可选：`OperatorContext` 旁增加 `RequestIpContext`（Filter 写入 ThreadLocal，finally 清理），供 Service 层无 HttpServletRequest 时读取。

**验收**：浏览器触发的 `TASK_TRANSIT` 的 IP 为真实客户端；调度触发的为 `scheduler`；不再「全家 127.0.0.1」。

---

### P1 — 启动告警

全部行为仅由 `CODE_INSIGHT_ENV` / `env.isDev()` 决定，不另加开关。

启动 `ApplicationReadyEvent`：

```text
if (isDev()) {
  WARN: "DEV 安全模式：禁用孤儿接管；仅执行 is_dev=true 的任务；请确认未误连共享库"
  INFO: jdbcUrl masked + localIps
}
```

`.env.example` 补充说明：误连 STG 时靠上述默认兜底，仍应优先用本地库。

---

## 4. 明确不做

| 项 | 原因 |
|----|------|
| 让本地也 `cluster=true` 连 STG Redis | 许可/心跳互相污染 |
| 用现有错误的 log.ip 做亲和 | 全是假 127.0.0.1 |
| 仅禁孤儿、不改派发 | 仍会抢 STG PENDING |
| 按 hostname 模糊匹配 claimed_by 当「本机任务」 | PENDING 尚无 claimed_by；且与「IP」诉求不符 |
| 非 dev 改变孤儿/派发语义 | 避免回归 STG |

---

## 5. 实施顺序

```text
1. ClientIpResolver + RequestIp Filter/Context
2. 修 OperationLogServiceImpl（IP + OperatorContext）
3. schema + DecompileTask.isDev + 创建路径赋值
4. TaskOrphanReclaimScheduler：isDev → return
5. TaskQueueClaimService：dev 仅认领 is_dev=true
6. 手动 start 校验 + 启动 WARN
7. 单测 + 文档（本文件状态改为已实施；CHANGELOG / README / .env.example）
```

---

## 6. 测试要点

| 用例 | 期望 |
|------|------|
| dev + 孤儿调度 | 不写 `TASK_ORPHAN_RECLAIM` |
| 本地创建任务 `is_dev=true` | 可被 dispatcher 抢到 |
| STG/历史任务 `is_dev=false`/null | dispatcher 跳过；手动 start 拒绝 |
| HTTP 写操作日志 | loopback 时为网卡 IP，非回环 peer 为真实客户端 |
| 调度写操作日志 | `ip_address`=本机网卡可辨识 IP，username=`system` |
| `CODE_INSIGHT_ENV=stg` | 孤儿/派发行为与改前一致（回归） |

---

## 7. 风险与注意

- **本机前端走 Vite 代理**：操作日志 peer 为 loopback 时回落网卡 IP，便于区分机器。  
- **IPv6 / 多网卡**：`preferredMachineIp` 优先非回环 IPv4。  
- **历史任务无 `is_dev`**：默认 false，dev 不碰。  
- **纠错任务**：`is_dev` 写当前后端 env，不用 base 任务的值。

---

## 8. 一句话

**dev：绝不孤儿；只执行创建时 `is_dev=true` 的任务；操作日志写本机可辨识 IP（区分不同开发机）。** 这样即便 JDBC 误指 STG，本机进程默认也不会再抢集群在飞任务或 STG 用户队列。
