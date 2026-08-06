# 知识 Release 保留近 N 版清理（异步删盘 + 软删打标）

> 状态：已实施  
> 日期：2026-08-04  
> 确认：N=3；先软删再异步删盘；小时对账；脏目录不动；publish-snapshots 一并删；软删后列表不可见。

---

## 0. 结论先讲

| 项 | 决定 |
|----|------|
| 保留单位 | 按 `repositoryId` 的 `ci_knowledge_version`（`PUSHED`） |
| 保留数量 | 默认 3（可配置） |
| 「打标已删除」 | 复用已有 `is_deleted=1`（`@TableLogic`），**不加字段** |
| 物理删除 | `{releasesRoot}/{sys}/{repo}/{versionNum}/`；可选清 runtime `publish-snapshots/{repoId}/{versionId}/` |
| 触发 | 推送 **SUCCESS** 且 `applyRepositoryPublish` 完成后 **异步** 入队 |
| 删盘重试 | 每个待删目录最多 **3** 次 attempt（含首次），线性/固定退避 |
| 推送本身 | 清理全程 best-effort：异常只打日志 / 操作日志，**不**改 push 任务为 FAILED |
| 生效版本 | **永不**清理 `ci_repository.last_published_version_id` 对应版本 |

UI「推送记录」列表走 `listVersions`（`is_deleted=0`），软删后默认不可见 =「已删除」。

---

## 1. 背景与现状

```text
推送成功路径（PushServiceImpl.executePushTask）
  → NasPushStrategy 写出 release 目录
  → applyRepositoryPublish（指针 + snapshot）
  → pushTask=SUCCESS / version=PUSHED
  → cleanupAfterPush（仅清 task runtime，不清历史 release）
```

- Release 布局：`{releasesRoot}/{systemId}/{repositoryId}/{versionNum}/`
- 版本号：`v1`、`v2`…（`nextSimpleVersionNum`）
- **无** release 保留策略；历史目录会一直堆积
- `KnowledgeVersion` / `PushTask` / `RepositoryPublishSnapshot` 均已有 `is_deleted`

相关实体关系：

```text
KnowledgeVersion (1) ──< (N) PushTask
KnowledgeVersion (1) ─── (1) RepositoryPublishSnapshot
CodeRepository.last_published_version_id → KnowledgeVersion.id
```

---

## 2. 目标行为

对每个仓库，在一次成功推送后：

1. 选出应保留的最近 N 个 `PUSHED` 且未软删的版本（按 `pushed_at DESC, id DESC`）
2. 其余版本：
   - DB：version + 其下 push_task + snapshot → `is_deleted=1`
   - 磁盘：异步删除对应 release（及可选 snapshot 目录），失败重试至多 3 次
3. 列表/回滚/ZIP：软删版本不可见；其 release 若仍残留也不再被正常路径依赖

### 2.1 明确不做

| 项 | 原因 |
|----|------|
| 新增 `pruned`/`archived` 列或 status 值 | 用户要求不加字段；status 造假更差 |
| 清理失败导致推送 FAILED | 推送已成功落 NAS，清理是附属 |
| 硬删 DB 行 | 审计与排障需要留痕 |
| 全局跨仓统一保留 | 应按仓库独立计数 |
| 同步阻塞删大目录 | 推送线程/调度线程不应被 NAS IO 拖死 |

---

## 3. 详细设计

### 3.1 配置

```yaml
code-insight:
  publish:
    release-keep-count: 3              # 每仓保留最近 N 个 PUSHED 版本
    release-prune-max-attempts: 3      # 单个目录删盘最大 attempt（含首次）
    release-prune-backoff-ms: 2000     # 删盘重试退避基数；等待 = backoff × attempt
    release-prune-reconcile-enabled: true
    release-prune-reconcile-interval-ms: 3600000  # 兜底对账（默认 1h）
```

环境变量可选映射（与项目习惯一致即可）：`PUBLISH_RELEASE_KEEP_COUNT` 等。

### 3.2 组件划分

| 组件 | 职责 |
|------|------|
| `ReleaseRetentionProperties` | 上述配置 |
| `ReleaseRetentionService` | 算候选、软删 DB、提交异步删盘、对账入口 |
| `ReleasePruneExecutor`（或 service 内方法） | 在独立线程池执行删盘 + 重试 |
| 触发点 | `PushServiceImpl` 成功路径末尾 `submitPrune(repositoryId)` |
| 兜底 | `@Scheduled` 对账：扫盘孤儿 / 扫「已软删但仍有目录」 |

线程池：新增 `AsyncExecutorConfig.RELEASE_PRUNE_EXECUTOR`（建议 core=1、max=2、queue 较大、前缀 `release-prune-`），**不要**占用 `knowledgePublishExecutor`（避免与确认/组包抢池）。

### 3.3 触发时序（推荐）

```text
executePushTask 成功分支：
  strategy.execute
  applyRepositoryPublish
  pushTask → SUCCESS
  task → PUSHED（如适用）
  cleanupAfterPush(taskId)          # 现有：清本次 task runtime
  releaseRetentionService
      .submitAfterPushSuccess(repositoryId)   # 仅入队，立刻返回
```

`submitAfterPushSuccess`：

1. 取仓库级短锁（见 3.6），避免同仓并发清理交错
2. **同步、快速**：计算 prune 列表 + **DB 软删**（打标）
3. **异步**：对每个待删 `versionNum` 提交删盘任务（带 attempt 计数）
4. 释放锁（或锁只包住「算候选 + 软删」；删盘异步不再持仓锁，避免长占）

**为何先软删再异步删盘**

- 「推送记录已删除」立即生效，不依赖 NAS IO
- 删盘可失败重试；即便进程崩溃，兜底对账仍能按「已软删版本」继续删目录
- 推送路径不被大目录删除阻塞

### 3.4 候选计算

输入：`repositoryId`，`keep = release-keep-count`。

```text
versions = SELECT * FROM ci_knowledge_version
           WHERE repository_id=? AND status='PUSHED' AND is_deleted=0
           ORDER BY pushed_at DESC NULLS LAST, id DESC

keepSet = 前 keep 条的 id
activeId = repo.last_published_version_id
若 activeId 非空且不在 keepSet → 强制加入 keepSet（安全兜底）

pruneList = versions 中 id ∉ keepSet
```

对 `pruneList` 每条：

1. Soft-delete `RepositoryPublishSnapshot`（by version_id）
2. Soft-delete `PushTask`（by version_id）
3. Soft-delete `KnowledgeVersion`（by id）  
   （顺序：先子后父，避免短暂列表不一致；均用 MyBatis-Plus `remove`/`delete` 走 `@TableLogic`）

然后对每条 enqueue：`pruneDisk(systemId, repositoryId, versionId, versionNum, attempt=1)`。

### 3.5 异步删盘 + 重试 3 次

单个目录一次 attempt：

```text
path = storageResolver.releaseDir(systemId, repositoryId, versionNum)
若 !exists → 视为成功（幂等）
否则 DirectoryCleanupUtil / Files.walk 递归删除
可选：删 runtimeRoot/publish-snapshots/{repositoryId}/{versionId}/
成功 → 打 INFO + 可选 operation_log RELEASE_PRUNE_OK
失败 → catch：
  if attempt < maxAttempts:
    sleep(backoffMs * attempt)
    再提交同参数 attempt+1（同线程内循环即可，不必再入队）
  else:
    ERROR 日志 + operation_log RELEASE_PRUNE_FAILED（detail 含 path、attempt、异常摘要）
```

**重试语义（与「3 次」对齐）**

| attempt | 含义 |
|---------|------|
| 1 | 首次删除 |
| 2 | 第一次失败后重试 |
| 3 | 第二次失败后重试 |
| 3 次后仍失败 | 放弃本轮，留给对账调度 |

实现上推荐 **同异步任务内 for 循环 1..maxAttempts**，避免线程池被大量重试任务刷爆；退避在循环内 `Thread.sleep`。

**尽量确保删除的手段（多层）**

1. 推送后异步：最多 3 attempt  
2. 定时对账（3.7）：扫出「DB 已软删 / 或不在 keep 集合」但仍存在的目录，再次走同一删盘逻辑（仍限 3 attempt）  
3. 幂等：目录不存在 = 成功  

不引入新表存「待删队列」；对账以 **文件系统 + 现有 is_deleted/keep 规则** 为准，满足「不加字段」。

### 3.6 并发与幂等

| 场景 | 处理 |
|------|------|
| 同仓短时间两次推送成功 | `ci:lock:release-prune:{repositoryId}`（Redis，TTL 如 5–10min）包住「算候选+软删」；拿不到锁则跳过本次（下一次推送或对账会补） |
| 版本已 is_deleted | 候选查询自然排除；对账仍可按「软删但仍有目录」删盘 |
| 目录已删 | 幂等成功 |
| 集群多节点 | Redis 锁 + 共享 releasesRoot；删盘在任一节点执行均可 |

### 3.7 定时对账（强烈建议，保证「尽量腾空间」）

`ReleaseRetentionReconcileScheduler`（可用现有 `@Scheduled` 风格）：

1. 分页扫描有 release 的仓库，或扫 `releasesRoot` 一级 `systemId/repositoryId`  
2. 对每个仓：  
   - 按 3.4 重算 keepSet（仅未软删 PUSHED）  
   - 列出 `{releasesRoot}/{sys}/{repo}/` 下子目录名  
   - 若子目录名 ∉ keep 的 versionNum → 提交删盘（3 attempt）  
   - 额外：查该仓 `is_deleted=1` 的 version（需 bypass `@TableLogic` 的自定义查询或 `select` 带 deleted）若目录仍在 → 删盘  

注意：MyBatis-Plus 默认查不到已软删行，对账「已软删仍占盘」需要：

- Mapper 方法：`SELECT … WHERE repository_id=? AND is_deleted=1 AND status='PUSHED' ORDER BY id DESC LIMIT 100`（显式写 SQL，绕过逻辑删除拦截），或  
- 仅靠「扫磁盘 ∉ keepSet」——**更简单且足够**，推荐作为主对账策略（不必读软删行）。

主对账策略（推荐唯一实现）：

```text
keepNums = 未软删 PUSHED 最近 N 的 version_num（含 active 兜底）
diskNums = 目录子项
toDelete = diskNums - keepNums
→ 异步/同步（调度线程内）删盘 + 3 次重试
```

这样即使某次软删后删盘失败、甚至软删漏了，只要目录多于 keep，对账也会清掉多余目录。  
若目录对应版本 **尚未软删** 但不在 keep（异常窗口）：对账应 **先软删再删盘**（复用 3.4），避免「盘没了但列表还在、ZIP 报错」。

### 3.8 操作日志

| action_type | 何时 |
|-------------|------|
| `RELEASE_PRUNE_MARKED` | 软删一批 version（detail: repoId, versionIds/versionNums） |
| `RELEASE_PRUNE_OK` | 某 versionNum 目录删除成功 |
| `RELEASE_PRUNE_FAILED` | 3 次后仍失败 |
| `RELEASE_PRUNE_RECONCILE` | 对账触发的清理汇总（可选） |

IP/操作人：调度线程走现有 `ClientIpContext` 本机 IP + `system`。

### 3.9 对现有功能的影响

| 功能 | 影响 |
|------|------|
| 推送记录列表 | 被裁版本消失（软删） |
| 回滚到旧版本 | 仅保留的 N 个（且目录仍在）可回滚；更早版本不可用——符合产品预期 |
| ZIP 导出 | 同上 |
| `nextSimpleVersionNum` | 只看未软删行，编号可跳号（可接受） |
| 知识查看生效版 | 不受影响（指针保护） |
| 增量继承读 baseline release | 读的是生效版目录，只要 active 在 keep 内即安全 |

---

## 4. 失败与边界

| 情况 | 行为 |
|------|------|
| 软删部分成功、进程崩溃 | 下次推送或对账：未删目录会被扫出；已软删行不再进列表 |
| 删盘 3 次失败 | 行已软删；目录残留；对账周期继续打 |
| NAS 只读/权限 | 打 FAILED 日志；不回滚软删 |
| keep=3 但仓内不足 3 个版本 | 不删 |
| version_num 非 `vN` 的脏目录 | 对账：若不在 keepNums → 删除（或仅删匹配 `^v\d+$`，脏目录另打 WARN 人工处理——**建议只删 `^v\d+$`**，更安全） |
| active 不在「最近 3」 | 强制保留 active + 最近 3 中与 active 不同的，实际上 keep 可能变成 3（active 替换最老）——实现写明：**keep 集合 = topN ∪ {active}，再 prune 其余**；若 active 已在 topN，则恰好 N |

---

## 5. 实施顺序

```text
1. ReleaseRetentionProperties + application.yml
2. AsyncExecutorConfig 增加 releasePruneExecutor
3. ReleaseRetentionService：selectKeep / softDelete / deleteDirWithRetry
4. PushServiceImpl 成功路径 submitAfterPushSuccess
5. ReleaseRetentionReconcileScheduler（扫盘 ∉ keep）
6. 操作日志埋点
7. 单测：候选计算、active 保护、删盘重试 3 次、目录不存在幂等、推送不被清理异常影响
8. 文档：本文件状态 → 已实施；CHANGELOG 一条
```

---

## 6. 测试要点

| 用例 | 期望 |
|------|------|
| 同仓 4 个 PUSHED，再推第 5 个成功 | 列表剩 3；最老软删；其 release 目录最终不存在 |
| active 被强制保护 | 即使排序异常也不删 active 目录 |
| 删盘前 2 次抛错第 3 次成功 | 目录最终删除；无 PRUNE_FAILED |
| 删盘 3 次全失败 | 有 PRUNE_FAILED；行已 is_deleted；对账再次尝试 |
| submit 抛错 | pushTask 仍为 SUCCESS |
| 对账：盘上有 v1 且不在 keep | v1 被删（若对应行未软删则先软删） |
| 非 `v\d+` 脏目录 | 不删或仅 WARN（按最终确认） |

---

## 7. 待你确认的点

1. **保留数 N=3**、**删盘 maxAttempts=3** 是否就按本文默认？  
2. **先软删再异步删盘** 是否接受？（推荐）  
3. **小时级扫盘对账** 是否要做？（强烈建议，否则「尽量腾空间」只靠推送触发）  
4. 脏目录（非 `v\d+`）是否动？建议 **不动**。  
5. runtime `publish-snapshots/{repo}/{versionId}` 是否一并删？建议 **一并删**（与 release 同生命周期）。  
6. 软删后列表完全不可见是否 OK？还是要「可见但标删除」（后者要改 API/UI，违背当前不加字段+默认 TableLogic 方案）？

确认后按第 5 节落地实现。
