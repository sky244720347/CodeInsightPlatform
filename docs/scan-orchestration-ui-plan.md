# 任务编排页（Commit 探测）· UI 方案

> **状态：已实施**（2026-08-05）  
> **关联**：[scheduled-commit-poll-scan-plan.md](./scheduled-commit-poll-scan-plan.md)  
> **目标**：新「任务编排」页展示日探测进度与流水记录，并配置调度 cron；旧编排页（扫描窗口列表/热力图）废弃但保留文件。

---

## 〇、目标一句话

**菜单仍进「任务编排」，打开的是新页：顶部调 cron / 启停，中部看今日已探测/需探测总数，下部翻页看每次探测流水；旧窗口编排页文件保留、路由不再挂载。**

---

## 〇.1 「扫描窗口」是什么（对应你问的第 4 点）

平台里其实有**两套选仓方式**（由配置 `global-poll-enabled` 切换）：

| 模式 | 配置 | 探哪些仓 |
|---|---|---|
| **全局轮询** | `global-poll-enabled=true`（你 1000 仓日扫要用的） | 所有「需要探测」的远程仓，按日覆盖 |
| **扫描窗口** | `global-poll-enabled=false` | 只探在 `ci_scan_window` 里配了「周几+几点」且**当前时刻命中**的仓 |

旧「任务编排」页干的就是：**给每个仓配扫描窗口 + 热力图**，和现在的 commit 日探测不是一回事。

**废弃旧页之后**：

- 新编排页**不再展示/编辑扫描窗口**（避免和日探测混在一起）
- 仓库抽屉里若仍留着「扫描窗口」按钮（`ScanWindowModal`），那是给「关全局轮询、改回窗口模式」时用的备用入口
- 你若一直开着全局轮询，**可以完全不理扫描窗口**；旧页文件只是留着别删代码

若你希望连抽屉里的窗口入口也去掉，可另说，本方案默认：**抽屉保留、编排页不管**。

---

## 一、现状缺口

| 已有 | 缺口 |
|---|---|
| Redis `scan:probe:done:{day}` 仅仓 id 集合 | 无「探测记录」明细流水 |
| `/scan-windows/scheduler/cron\|enabled` | 无 coverage / records API |
| `pages/basic/orchestration.tsx` | 扫描窗口 CRUD + 热力图，与 commit 轮询脱节 |

因此：**必须先落探测流水表**；done Set 继续作调度加速，与流水**追加写入**（非覆盖）。

---

## 二、已确认决策

| # | 项 | 结论 |
|---|---|---|
| U1 | 路由 | 仍用 `/basic/orchestration`；新组件替换挂载；旧页改名为 `orchestration-legacy.tsx`，不再 import |
| U2 | 扫描窗口 | 新编排页不管；抽屉 Modal 暂留作窗口模式备用（见 §〇.1） |
| U3 | 可配项 | 页面仅：**cron** + **调度 enabled**；其余只读展示配置文件/阿波罗值 |
| U4 | 记录范围 | 默认**今天**；可选日期；分页 |
| U5 | 记录粒度 | **每次探测尝试都留流水**（insert，不按日覆盖） |
| U5b | 总数口径 | **需要探测的总量**（见 §3.2），不是 `ci_repository` 裸总数 |
| U6 | 刷新 | 进入页拉取 + 手动刷新；summary 可 30s 轮询 |
| U7 | 集群 | 任意节点提供只读 API；写记录仅 Leader 调度路径 |
| U8 | API 前缀 | **新前缀** `/scan/orchestration` |

---

## 三、数据模型

### 3.1 新表 `ci_scan_probe_record`（流水，每次 insert）

```sql
CREATE TABLE IF NOT EXISTS ci_scan_probe_record (
  id              BIGSERIAL PRIMARY KEY,
  probe_date      DATE NOT NULL,              -- 自然日（便于按日筛）
  repository_id   BIGINT NOT NULL,
  system_id       BIGINT,
  attempt_no      INT,                         -- 可选：同仓同日第几次（调度内填充）
  status          VARCHAR(32) NOT NULL,      -- SUCCESS / FAILED / SKIPPED_LOCAL / INCONCLUSIVE / DISPATCH_FAILED
  remote_head     VARCHAR(64),
  baseline_commit VARCHAR(64),
  dispatch_action VARCHAR(32),               -- INITIAL / INCREMENTAL / NONE / null
  task_id         BIGINT,
  message         VARCHAR(512),
  probed_at       TIMESTAMP NOT NULL,
  created_date    TIMESTAMP,
  updated_date    TIMESTAMP,
  is_deleted      SMALLINT DEFAULT 0 NOT NULL,
  created_by      VARCHAR(100) DEFAULT 'sys' NOT NULL,
  updated_by      VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_scan_probe_date_probed
  ON ci_scan_probe_record (probe_date, probed_at DESC);
CREATE INDEX IF NOT EXISTS idx_scan_probe_date_repo
  ON ci_scan_probe_record (probe_date, repository_id, probed_at DESC);
```

**无唯一约束**：同仓同日可多行（超时重试、多次 tick 都会追加）。

**写入时机**（`processOne` 每次有结论都 `INSERT`）：

| 结果 | status | Redis done | 流水 |
|---|---|---|---|
| 拿到 HEAD | SUCCESS | ✅ | insert |
| 本地路径 / 空 URL | SKIPPED_LOCAL | ✅（不计入需探测总量，见下） | insert（审计用，可选；见总量口径） |
| 明确失败 | FAILED | ✅ | insert |
| 超时 inconclusive | INCONCLUSIVE | ❌ | **insert**（流水要留；不推进 done） |
| 下发成功 | SUCCESS + action + task_id | ✅ | insert |
| 探测成功但下发暂不可行（技术栈未配置/不支持、Git 未连通等） | **DEFERRED_DISPATCH** | ❌ | insert；后续 tick 重试 |

列表默认按 `probed_at DESC`。保留策略：可后续加「只留近 N 天流水」清理任务（本期可不做）。

### 3.2 Summary 口径（已确认）

**需要探测的总量 `probeTargetTotal`**（全局轮询模式）：

- 统计 `ci_repository` 中 **非本地路径** 且 **gitUrl 非空** 的仓数  
- 本地路径 / 空 URL **不计入分母**（它们不是「需要探测」的对象）  
- 若写了 SKIPPED_LOCAL 流水，仅作审计，**不增加** `probedCount` 分子

**已探测 `probedDistinctCount`**（进度分子）：

- 当日至少有一条 **了结结论** 的去重仓数：  
  `status IN ('SUCCESS','FAILED')`  
- **不含** `INCONCLUSIVE` / `DEFERRED_DISPATCH`（还要重试，不算探完）  
- **不含** `SKIPPED_LOCAL`（不在目标总量里）

| 字段 | 含义 |
|---|---|
| `probeDate` | 统计日 |
| `probeTargetTotal` | 需探测总量（远程+有 URL） |
| `probedDistinctCount` | 当日已探完去重仓数 |
| `pendingProbeCount` | `probeTargetTotal - probedDistinctCount` |
| `attemptCount` | 当日流水总行数（含 inconclusive） |
| `successCount` / `failedCount` / `inconclusiveCount` | 流水按 status 计数（行数，非去重） |
| `dispatchedCount` | 当日流水中已下发 INITIAL/INCREMENTAL 的行数（或去重仓数，实施时取「行数」并在 UI 注明） |
| `schedulerEnabled` / `cron` / `nextRuns` | 调度器 |
| `globalPollEnabled` 等 | 只读配置回显 |

进度条：`probedDistinctCount / probeTargetTotal`。  
与 Redis done Set：done 成员应对齐「已探完」仓；API **以 DB 去重为准**展示进度。

---

## 四、后端 API（已确认：新前缀）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/scan/orchestration/summary?date=` | 进度 + 只读配置 + cron/enabled |
| GET | `/scan/orchestration/records?date=&status=&keyword=&current=&size=` | **流水**分页；keyword 匹配仓 gitUrl / id |
| PUT | `/scan/orchestration/cron` | `{ cron }` → `ScanWindowScheduler.updateCron` |
| PUT | `/scan/orchestration/enabled` | `{ enabled }` → `setEnabled` |

旧 `/scan-windows/scheduler/*` 保留兼容；**新页只调** `/scan/orchestration/*`。

记录 DTO：`id`、`repositoryId`、`systemId`、`gitUrl`、`status`、`remoteHead`、`baselineCommit`、`dispatchAction`、`taskId`、`message`、`probedAt`、`attemptNo`（可选）。

---

## 五、前端页面

### 5.1 文件与路由

| 动作 | 路径 |
|---|---|
| 新页 | `frontend/src/pages/basic/orchestration-scan.tsx`（名可再定） |
| 旧页 | `orchestration.tsx` → 重命名 `orchestration-legacy.tsx`，**不再注册路由** |
| 路由 | `router`：`orchestration` → 新页 |
| 菜单 | `BasicLayout` 文案仍「任务编排」，无需新菜单项 |
| API | `frontend/src/api/scan-orchestration.ts` |

### 5.2 页面结构（一屏三段）

```text
┌─────────────────────────────────────────────────────────┐
│ 任务编排                                                  │
│ 调度：●运行中  [开关]   cron: 0 */5 * * * * [编辑]       │
│ 下 5 次：…                    [刷新]                      │
│ 只读：全局轮询=开  日覆盖=开  验证全量=关（配置文件）      │
├─────────────────────────────────────────────────────────┤
│ 今日探测  已探测 320 / 需探测 980   进度条                  │
│ 流水尝试 410 · 成功行… · 失败… · 超时… · 已下发 42 · 待探测 660 │
│ 日期：[今天 ▾]                                            │
├─────────────────────────────────────────────────────────┤
│ 探测流水表（分页，每次尝试一行）                              │
│ 时间 | 仓库 | 系统 | 状态 | HEAD | 基线 | 下发 | 任务 | 说明   │
└─────────────────────────────────────────────────────────┘
```

交互要点：

- cron 编辑沿用 Modal + 校验失败 toast
- 状态 Tag：SUCCESS 绿 / FAILED 红 / INCONCLUSIVE 橙 / DISPATCH_FAILED 橙红
- `taskId` 可链到任务详情（若已有路由）
- 筛选：状态、关键字；默认 `probed_at DESC`（同仓多次尝试都会出现）

### 5.3 不做（本方案）

- 页面改 `global-poll` / `force-full` / batch（仍阿波罗）
- 新编排页内的扫描窗口/热力图（旧文件保留；抽屉可继续配窗口）

---

## 六、与调度写入

```text
processOne 每次得出结论（含超时）
  → INSERT ci_scan_probe_record（流水）
  → 若终态成功/明确失败：coverageStore.markDone(repoId)
  → 若需下发：create+start，同条或紧随流水带上 task_id
```

记录写入失败打 error，**不阻断**探测主路径。

---

## 七、实施步骤

1. schema + entity/mapper + `ScanProbeRecordService#insert` + summary 去重统计  
2. 调度器接入流水；`/scan/orchestration/*` API  
3. 前端新页 + 路由切换；旧页改名 `orchestration-legacy.tsx`  
4. 单测：流水插入、需探测总量、去重已探测；前端类型对齐  
5. CHANGELOG；本方案标为已实施  

---

## 八、确认摘要（2026-08-05）

| # | 结论 |
|---|---|
| 1 | 每次探测都留流水（insert） |
| 2 | 总数 = **需要探测的总量**（非本地、有 URL） |
| 3 | API = `/scan/orchestration` |
| 4 | 扫描窗口 = 旧页那套「周几几点扫谁」；新页不管；全局轮询下可忽略；抽屉暂留备用 |

回复「按方案实施」即可开工。
