# 存储与集群模式方案（确认稿）

> 状态：**已确认并实施**  
> 日期：2026-07-14（实施：2026-07-15；修订：删除 `CLUSTER_ENABLED`；再修订 2026-07-15：合并 `data-root` + `workspace-root` 为 `runtime-root`，**BREAKING**）  
> 替代此前讨论稿中的「standalone/cluster 两套 path 键」思路，改为 **dev / 非 dev** 二分。  
> 详细合并方案：[cluster-storage-runtime-root-plan.md](./cluster-storage-runtime-root-plan.md)

---

## 一、目标

1. **开发简单**：dev 环境路径写死本机，无需配置存储路径。
2. **非开发安全**：路径全部显式配置、无默认；**始终**走集群调度逻辑（单节点也可）。
3. **推送与运行时分离**：已发布知识（知识查看）路径独立配置。
4. **一个环境开关主导全部默认行为**；删除 `storage.mode` / `base-path` / **`CLUSTER_ENABLED`** 双轨。

---

## 二、核心口径（一句话）

> **`env=dev`：单机 + 写死本机路径。**  
> **`env≠dev`：集群调度 + 全部路径走配置（含独立 releases），未配则启动失败。**

集群是否开启 **只由 `CODE_INSIGHT_ENV` 推导**，不再提供独立开关。

---

## 三、开关与默认

### 3.1 环境开关（唯一主导）

```yaml
code-insight:
  env: ${CODE_INSIGHT_ENV:dev}   # dev | prod（或 staging 等非 dev 值）
```

| `env` | 集群调度 | 路径策略 |
|---|---|---|
| `dev` | **单机**（内部 `cluster.enabled=false`） | **写死本机路径**，不读存储路径配置 |
| 非 `dev`（如 `prod`） | **集群**（内部 `cluster.enabled=true`） | **只读配置**，两根路径均无默认、必填 |

说明：

- 非 dev 单节点开集群**可行**（一人集群：自己选 Leader、Redis 限流），故非 dev 一律集群可接受。
- **已删除** `CLUSTER_ENABLED` / `code-insight.cluster.enabled` 外部配置；运行时 `ClusterProperties.enabled` 仅由 `env` 在启动时推导，业务代码仍读该字段。

### 3.2 删除的旧配置

| 删除项 | 原因 |
|---|---|
| `storage.mode` / `STORAGE_MODE` | 与 env/集群语义重叠 |
| `storage.base-path` | shared 半成品 |
| `storage.drafts-root` 可配 | 相对名写死即可 |
| 业务代码直注 `local-path` | 统一走路径门面 |
| **`CLUSTER_ENABLED` / `cluster.enabled` 可配** | 与 env 重复；非 dev 单机也走集群逻辑无功能阻碍 |

---

## 四、路径模型

### 4.1 两个逻辑根

| 逻辑根 | 含义 |
|---|---|
| **runtimeRoot** | 运行期合并根：内含 `data/`（草稿/AI 日志/增量影响）与 `workspaces/`（源码 + 推送前 docs 组装）两个子目录 |
| **releasesRoot** | 推送后正式知识 = 知识查看数据源（独立卷，可分 NAS） |

> 早期版本曾拆 `dataRoot` + `workspaceRoot` 两个根，但同性质、同故障域，合并为 `runtimeRoot` 减少运维心智。详见 [cluster-storage-runtime-root-plan.md](./cluster-storage-runtime-root-plan.md)。

### 4.2 根如何取值

#### A. `env=dev`（写死，不配）

| 逻辑根 | 写死值 |
|---|---|
| runtimeRoot | `{user.dir}/storage` 或约定相对 `./storage`（启动 resolve 为绝对路径） |
| releasesRoot | `./storage/releases`（本地 mock；开发也走同一套拼接规则） |

由 `runtimeRoot` 派生的子目录（dev / 非 dev 一致）：

- `dataRoot = {runtimeRoot}` — 业务沿用 `getActiveDataRoot()` 读取
- `workspaceRoot = {runtimeRoot}/workspaces` — 业务沿用 `getActiveWorkspaceRoot()` 读取

dev **不要求**、也**不读取** `STORAGE_*` 路径环境变量（读到可 warn「dev 已忽略」）。

> **dev 行为变更**：旧的 `./temp_repos` 工作区被 `./storage/workspaces` 取代。开发者首次切换可 `rm -rf ./temp_repos`。

#### B. `env≠dev`（全配置，无默认）

```yaml
code-insight:
  env: prod
  # cluster.enabled 不配：启动时由 env 推导为 true
  storage:
    runtime-root: ${STORAGE_RUNTIME_ROOT:}     # 必填，无默认
    releases-root: ${STORAGE_RELEASES_ROOT:}   # 必填，无默认
```

任一项为空、非绝对路径、或不可读写 → **启动失败**。

非 dev 下 **只有这一套路径键**；调度始终为集群逻辑，路径始终是「配置根」。

### 4.3 业务拼接后缀（dev / 非 dev 相同）

配置或写死的只是「根」；业务在根下拼固定相对路径：

```text
{runtimeRoot}/
  drafts/task_{taskId}/.../*.md          ← data 区域
  ai_logs/task_{taskId}/...              ← data 区域
  task_{taskId}/pipeline.log             ← data 区域
  task_{taskId}/incremental-impact.json  ← data 区域
  workspaces/                            ← workspace 区域（runtimeRoot 派生子目录）
    task_{taskId}/
      …源码…
      docs/code-insight/                 # 确认后、推送前

{releasesRoot}/                          ← 独立根
  {systemId}/{repositoryId}/{versionNum}/
    modules/
    index/
    meta/knowledge-version.json
```

- 不按模式改变相对布局。
- 废弃旧 shared 的 `{base}/drafts/{sys}/{repo}/task_*`。

---

## 五、落盘与业务场景

| 产出 | 落点 | dev | 非 dev |
|---|---|---|---|
| 任务/入口/层级/调用链等元数据 | PostgreSQL | 同左 | 同左 |
| 草稿自动保存 / 编辑锁 | Redis | 同左 | 同左（集群必达） |
| 草稿 Markdown、pipeline/ai 日志、增量影响 | runtimeRoot（data 区） | 本机写死 | 配置的共享/NAS |
| 源码工作区、推送前 docs 组装 | runtimeRoot（workspaces 区） | 本机写死 | 同一共享 NAS（与 data 同卷） |
| **推送后**正式知识（NAS） | releasesRoot | 本机 mock 目录 | **独立配置的 NAS**（与 runtime 分卷） |
| 知识版本指针 | PostgreSQL | 同左 | 同左 |
| Git 推送 | 业务仓库 | 不变 | 不变 |

整体模型：

```text
dev：
  中间产出 + 推送前 → 本机（runtimeRoot = ./storage，含 ./storage/workspaces）
  推送后 → 本机 mock releases（./storage/releases）

非 dev：
  中间产出 + 推送前 → 配置的 runtimeRoot（通常共享 NAS）
  推送后 → 配置的独立 releases NAS
  默认开集群调度（单节点也可）
```

---

## 六、知识推送与知识查看

1. **NAS 推送**目标唯一：`{releasesRoot}/{sys}/{repo}/{ver}/`。
2. **知识查看（已发布）**：只读 `releasesRoot` + `last_published_version_id`；**不再**依赖 `storage.mode=shared`。
3. **未推送预览**：允许回退读 `{workspaceRoot}/task_{id}/docs/code-insight/`。
4. 入口 / 层级元数据来自 DB；文档正文来自 releases（或上述回退）。

---

## 七、集群行为（非 dev 默认）

与现网集群能力一致，本方案不改语义，只保证文件可见：

| 能力 | 说明 |
|---|---|
| Leader | `ci:leader:task-dispatcher` |
| 任务认领 | SKIP LOCKED + `claimed_by` / `lease_until` |
| 并发 | Redis `ci:permits:task:*` / `ci:permits:ai:*` |
| 配置广播 | `ci:config:refresh` |
| 断点亲和 | 工作区目录在共享盘上则任意节点可续跑 |

单节点非 dev：上述逻辑仍跑，无功能阻碍，仅多依赖 Redis。

dev：强制不走上述分布式路径（本地 Semaphore + 各节点可调度的旧单机逻辑）。

---

## 八、启动校验

### 8.1 `env=dev`

- 使用写死根（`./storage` + `./storage/releases`）；resolve 后确保可创建/可写。
- 内部 `cluster.enabled=false`（由 env 推导）。
- Redis：按现有开发习惯（建议可达，草稿自动保存仍可能依赖）。

### 8.2 `env≠dev`

1. `STORAGE_RUNTIME_ROOT` / `STORAGE_RELEASES_ROOT` 均非空。
2. 二者均为绝对路径，可读写。
3. Redis 不可达 → **启动失败**（非 dev 一律集群，依赖 Redis）。
4. 内部 `cluster.enabled=true`（由 env 推导，无外部关闭开关）。
5. `runtime-root` 与 `releases-root` 解析后相同 → **warn**（不推荐，不阻断）。

---

## 九、配置示例

### 9.1 本地开发

```yaml
code-insight:
  env: dev
  # storage.* 路径无需配置
  # 集群强制单机（无 CLUSTER_ENABLED）
```

### 9.2 预发 / 生产（可单节点）

```yaml
code-insight:
  env: prod
  storage:
    runtime-root: /mnt/nas
    releases-root: /mnt/nas-publish/releases
```

> `runtimeRoot = /mnt/nas` 内部展开为 `/mnt/nas/drafts/`、`/mnt/nas/ai_logs/`、`/mnt/nas/task_{id}/`、`/mnt/nas/workspaces/task_{id}/`。

### 9.3 环境变量

| 变量 | 说明 |
|---|---|
| `CODE_INSIGHT_ENV` | `dev` / `prod` / …（唯一决定单机 vs 集群） |
| `STORAGE_RUNTIME_ROOT` | 仅非 dev，必填绝对路径（运行期合并根） |
| `STORAGE_RELEASES_ROOT` | 仅非 dev，必填绝对路径（已发布知识，独立卷） |

已删除：`CLUSTER_ENABLED`、`STORAGE_MODE`、`STORAGE_LOCAL_PATH`、`STORAGE_DATA_ROOT`、`STORAGE_WORKSPACE_ROOT`。

---

## 十、实现要点（确认后开发）

1. **`EnvStorageResolver`（或并入 `StorageProperties`）**  
   按 `env` 解析出 `activeRuntimeRoot` + `activeReleasesRoot`；内部派生 `activeDataRoot = runtimeRoot` 与 `activeWorkspaceRoot = runtimeRoot/workspaces`，业务侧 `getActiveDataRoot()` / `getActiveWorkspaceRoot()` 公开 API 不变。
2. **路径门面**  
   所有读写（AI 草稿、日志、增量影响、DraftFileUtil、NasPush、知识浏览）只经门面，禁止 `@Value(local-path)`。
3. **删除 `StorageMode`**；知识浏览与 mode 解耦，发布态读 releasesRoot。
4. **`StorageBootstrapValidator`** 按 第八节校验。
5. **`ClusterEnvAligner`**：`enabled = !env.isDev()`；忽略一切外部 `CLUSTER_ENABLED`。
6. **文档**：更新 README / CLAUDE；以本文为存储与集群口径。

---

## 十一、与现网差异摘要

| 点 | 现网 | 本方案 |
|---|---|---|
| 主导开关 | `cluster` + `storage.mode` | **仅 `env`**（集群由其推导） |
| 开发路径 | 配 `local-path` 等 | **写死，免配** |
| 非开发路径 | 易默认落到 `./storage` | **两根必配，无默认** |
| 推送路径 | 常挂在 storage 下相对 `releases` | **独立 `releases-root`（非 dev 必配）** |
| 非开发默认 | 集群默认 false / 可关 | **一律集群，无独立开关** |
| shared 目录分层 | 有半成品 | **废弃**，相对路径统一 |
| runtime 拆分 | `dataRoot` + `workspaceRoot` 两根 | **合并为 `runtimeRoot`（dev/非 dev 一致）** |

---

## 十二、边界

- 仍用共享文件系统，本阶段不上 S3。
- 不改任务状态机 / Leader / permits 业务语义。
- Git 推送策略不变。
- 兼容期：可选一个版本 warn 旧键 `STORAGE_LOCAL_PATH` / `STORAGE_MODE` / `CLUSTER_ENABLED`，**不**再改变行为。

---

## 十三、确认结论

- [x] **1.** 采用 `CODE_INSIGHT_ENV=dev|prod`，dev 写死路径 + 单机；非 dev 集群 + 两根必配。
- [x] **2.** **删除** `CLUSTER_ENABLED`；非 dev 一律集群（含单节点）。
- [x] **3.** 未推送知识查看允许回退 workspace docs。
- [x] **4.** runtime 与 releases 同路径仅 warn、不禁止。
- [x] **5.** dev 写死 releases 为 `./storage/releases`（本地 mock）可接受。
- [x] **6.** 合并 `dataRoot` + `workspaceRoot` 为 `runtimeRoot`（2026-07-15；详见 [cluster-storage-runtime-root-plan.md](./cluster-storage-runtime-root-plan.md)）。
