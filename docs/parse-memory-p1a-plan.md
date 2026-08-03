# 解析内存 / 调用链落库 P1-A 方案

> 状态：已实施  
> 日期：2026-07-31  
> 约束：**软删不可改为物理删**（公司要求）；本轮只做批次 A；B1/B2/P2 作为后续增强。

## 背景

STG 监控与日志显示：

1. `MethodCallServiceImpl.persistAstForTask` 单次约 **794s**，伴随 2GB 堆打满与 Full GC。
2. MyBatis-Plus 分页 count 优化出现 `TimeoutException` WARN（GC STW 副作用，非 SQL 语法错误）。
3. `TaskQueueClaimService.reserveNextPending` avg 正常，max 尖刺约 8s（轮询间隔 + GC/连接池等待）。

根因主路径：全量 AST + SymbolSolver/subtype 缓存峰值 + 外层长事务占连接 + 入口发现 `listByTaskId` 二次顶堆；增量路径存在 pipeline / persist **双 inherit**。

## 本轮范围（批次 A）

| 编号 | 内容 | 不做 |
|------|------|------|
| A2 | 去掉 `persistAstForTask` 内二次 `inheritMethodCalls` | — |
| A1 | 去掉 `persistAstForTask` 外层长 `@Transactional`；batch 失败上抛 | 不改软删 |
| A3 | 入口发现改为分页/轻量列读取调用边，禁止全量 `List<MethodCall>` | — |

**明确不做**：C1 物理删 / tombstone purge；B1 写完即清文件 cache；B2 subtype 降级；P0 扩堆；P2 列表 count WARN。

## 执行顺序

```text
1. A2 去双 inherit
2. A1 拆长事务 + batch 失败上抛
3. A3 入口发现轻量/分页读边
4. 编译 + 相关单测
```

## 明细

### A2 — 去双 inherit

- **改**：`MethodCallServiceImpl` 增量分支删除 `baselineInheritanceService.inheritMethodCalls`。
- **保留**：pipeline（`DecompileTaskServiceImpl`）在 PARSING 前的唯一 inherit。
- **persist 增量职责**：按 deleted/changed 软删 → `walkAndPersist` 重插。
- **验收**：INCREMENTAL 不再近乎双倍边；变更覆盖正确。

### A1 — 拆长事务

- **改**：去掉 `persistAstForTask` 两重载上的 `@Transactional`。
- **软删**：仍走 MyBatis-Plus `@TableLogic`；单条 DELETE SQL 原子即可，不包长事务。
- **batch**：每 500 条独立 `SqlSession` commit；失败抛 `BusinessException`，禁止静默丢边。
- **幂等**：全量软删本 task 边 + 重插；增量软删变更路径 + 重插。
- **验收**：落表语义不变；解析期不再长时间占 Spring 连接；batch 失败任务 FAILED。

### A3 — 入口发现轻量读边

- **新增**：按 `task_id` + `id` 游标分页查询轻量列（`class_name`, `dependency_name`, `file_path`），`is_deleted = 0`。
- **改**：`EntryPointDiscoveryServiceImpl.discoverEntriesInternal` / `collectReachableSourceInternal` 不再 `listByTaskId` 全量进堆。
- **discover**：优先用已解析 `ParsedClassInfo` 建路径索引，DB 分页补洞。
- **collectReachable**：分页组装 BFS 邻接与路径映射。
- **验收**：同任务入口结果一致；入口阶段堆峰值下降。

## 后续增强（不在本轮）

- **B1**：persist walk 写完后按文件 evict `parseCache`（需开关；与入口发现缓存复用有权衡）。
- **B2**：subtype / SymbolSolver 降峰值。
- **P2**：任务列表 `optimizeCountSql(false)` 等噪音治理。
- **运维 P0**：扩堆、守住 `parse.concurrency=1`。

## 涉及文件（预期）

- `docs/parse-memory-p1a-plan.md`（本文）
- `MethodCallService.java` / `MethodCallServiceImpl.java` / `MethodCallMapper.java`
- 轻量 DTO（如 `MethodCallEdgeLite`）
- `EntryPointDiscoveryServiceImpl.java`
- 相关单测
