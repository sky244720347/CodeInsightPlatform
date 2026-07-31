# 仓库 Git 连通性检测方案

> 状态：已确认并实施  
> 日期：2026-07-30

## 目标

1. 定时 + 打开抽屉触发 Git 连通性检测（等同「测试 Git」），结果落库。
2. 仓库抽屉列表展示连通状态：绿「连通」/ 红「不通」/ 灰「未检测」。
3. 为后续「通/不通数量」监控预留字段与 summary 接口；本期不做大盘。
4. 下发任务（INITIAL / INCREMENTAL / 批量 / 定时 / 纠错）必须 `git_reachable=1`，否则拒绝。

## 触发模型（方案 C）

| 触发 | 行为 |
|---|---|
| 后台定时 | Leader 节点每 **3 分钟**全库探测，有限并发 2～3 |
| 打开仓库抽屉 | `POST /repositories/batch-test-connection?systemId=` 异步测该系统仓库 |
| 手动「测试 Git」 | 测完写库，与列表一致 |

前端不周期调用 test-connection，仅读列表字段；抽屉打开期间可轻量轮询列表。

## 多机

- Redis Leader 锁：`ci:leader:repo-git-check`
- 仅 Leader 跑全库轮询；其它节点跳过
- 按系统批量检测：任意节点可接，异步写库（最后写库结果为准）
- `dev` 单机：本机直接跑

## 数据模型

```sql
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS git_reachable SMALLINT;
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS git_checked_at TIMESTAMP;
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS git_check_msg VARCHAR(255);
-- NULL=未检测 / 1=连通 / 0=不通
```

## 默认状态

- 新列 / 未测仓库：`git_reachable = NULL` → UI「未检测」（灰）
- **超时 / 中断**：不落库为不通，保留原状态（避免启动抖动误标红）
- 明确失败（鉴权失败、无 refs 等）才写 `0`「不通」
- 调度：`initialDelay` 默认与 interval 相同，启动后不立刻全库扫

## 配置

```yaml
code-insight:
  repo:
    git-check-interval-ms: 180000
    git-check-initial-delay-ms: 180000
    git-check-timeout-seconds: 15
    git-check-concurrency: 3
```

## API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/repositories/test-connection` | 未保存测通；有 id 时测完落库 |
| POST | `/repositories/{id}/test-connection` | 已保存测通并落库 |
| POST | `/repositories/batch-test-connection?systemId=` | 异步批量；立即返回 accepted |
| GET | `/repositories/git-connectivity-summary` | 监控预留：total/reachable/unreachable/unchecked |
| GET | `/repositories` | 响应含 `gitReachable` / `gitCheckedAt` / `gitCheckMsg` |

## 任务门禁

`validateTaskSource`（及纠错 `createRemediationShell`）：

- `git_reachable == null` → 未检测，拒绝
- `git_reachable == 0` → 不通，拒绝
- 错误码 `GIT_UNREACHABLE(2103)`

## UI（仅仓库抽屉）

- 列「Git 连通」：绿/红/灰 + Tooltip 最近检测时间
- 系统主表本期不加汇总

## 本期不做

连通率大盘、告警、系统主表汇总列。
