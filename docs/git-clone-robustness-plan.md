# Git Clone 健壮性方案（去 Mock + 定向重试）

> 状态：已实施  

> 范围：运行期远程 Git clone；**不含试跑并发改造**（试跑另议）  
> 日期：2026-07-31

## 1. 背景

批量 INITIAL 任务出现 `source_commit = MOCK_COMMIT_*`、扫描文件仅 4 个。根因是 `CodeScannerServiceImpl.pullAndScan` 在 JGit clone 失败后静默降级为内置 Mock 仓库（恰好 4 个 Java 文件）。

线上日志典型错误：

- `Stale file handle`（NAS/NFS）
- `No such file or directory`
- `Missing unknown <hash>`（clone 半截 / 对象缺失）

这些不是「超时专门走 Mock」，而是 **任意 clone 异常**（除目录未清空外）都会进 Mock。

## 2. 目标

1. 禁止假成功：远程 clone 失败 → 任务/试跑失败，绝不生成 Mock  
2. 对 NAS 瞬时 IO 定向重试：最多 3 次 attempt（首次 + 2 次），退避 2s → 5s  
3. 失败可追查：应用日志（含 taskId）+ `ci_operation_log`（`GIT_CLONE_FAILED`）+ 正式任务经流水线写入 `pipeline.log` / `error_message`  
4. 不做本机常驻 clone（避免压垮应用机磁盘）  
5. **本轮不改试跑并发**（试跑仍不占 `pull.concurrency`）

## 3. 非目标

- 试跑等待任务 pull 槽（另案）  
- 本机临时盘 clone 再拷 NAS  
- 新建/调整 `pull.concurrency` 默认值（已有本机闸，默认 1）  
- 集群全局 pull 闸

## 4. 设计

### 4.1 删除 Mock

- 删除 `generateMockRepositoryFiles` 及调用  
- 去掉默认 `MOCK_COMMIT_*`  
- 远程 clone 最终失败抛 `BusinessException(ErrorCode.GIT_CLONE_FAILED, …)`

### 4.2 定向重试

| 项 | 约定 |
|---|---|
| 最大次数 | 3（`GitCloneRetrySupport.MAX_ATTEMPTS`） |
| 可重试 | `Stale file handle` / `No such file or directory` / `Missing unknown`；以及 `NoSuchFileException` / `FileSystemException` |
| 不可重试 | 认证失败、仓库/分支不存在、权限拒绝；目录未清空（`already exists`）直接失败 |
| 重试前 | `prepareEmptyCloneDirectory` 再清工作区 |
| 退避 | attempt1 失败后 2s；attempt2 失败后 5s |

### 4.3 失败落点

```
operationLogService.logOperation(
    repo.getSystemId(), taskId, "GIT_CLONE_FAILED", detail, exceptionMsg, false)
→ throw BusinessException(ErrorCode.GIT_CLONE_FAILED, …)
```

正式任务：`runPipeline` 已有 `execLog.logException` + `failUnlessCancelled`。  
试跑：共用 `pullAndScan`，失败进现有 trial `FAILED` 路径（不再假成功）。

### 4.4 错误码

| Code | 含义 |
|---|---|
| `GIT_UNREACHABLE(2103)` | 创建/下发前 ls-remote 门禁（已有） |
| `GIT_CLONE_FAILED(2104)` | 运行期 clone 失败（新增） |

## 5. 改动文件

| 文件 | 变更 |
|---|---|
| `docs/git-clone-robustness-plan.md` | 本方案 |
| `ErrorCode.java` | 新增 `GIT_CLONE_FAILED(2104)` |
| `GitCloneRetrySupport.java` | 重试判定 + 退避常量 |
| `CodeScannerServiceImpl.java` | 去 Mock、重试循环、操作日志 |
| `CodeScannerServiceTest.java` | 无效 URL 期望抛错 |
| `GitCloneRetrySupportTest.java` | 纯单元测试判定逻辑 |

## 6. 验收

1. 无效 Git URL → 任务 `FAILED`，无 `MOCK_COMMIT_*`，有 `GIT_CLONE_FAILED` 操作日志  
2. 试跑同样失败（无 Demo Controller）  
3. `Stale file handle` 类错误日志出现 attempt 重试；耗尽仍 FAILED  
4. 认证类错误不重试  
5. 正常 clone / 本地路径模式行为不变  

## 7. 运维建议（代码外）

- 保持 `pull.concurrency=1`（多节点时每节点仍可能各 1 路写 NAS）  
- NAS 持续抖动时优先降并行任务量，而非加大重试次数  
