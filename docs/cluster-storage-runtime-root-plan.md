# 存储路径合并：3 根 → 2 根（runtime-root）

> 状态：**已确认并实施**（日期 2026-07-15）  
> 修订对象：[cluster-shared-storage-design.md](./cluster-shared-storage-design.md) §4 / §8 / §9 / §11  
> 性质：**BREAKING** — 删除 `STORAGE_DATA_ROOT` / `STORAGE_WORKSPACE_ROOT`，新增 `STORAGE_RUNTIME_ROOT`

---

## 一、目标

将原"dataRoot + workspaceRoot + releasesRoot"三根合并为"runtimeRoot + releasesRoot"两根：

- **runtimeRoot**：运行期合并根，承载 `data/`（草稿/AI 日志/增量影响）与 `workspaces/`（源码 + 推送前 docs 组装）两个子目录
- **releasesRoot**：已发布知识根，**保持独立**（不同故障域，需独立备份/快照）

减少运维心智：少一个必填环境变量、少一个 NAS 挂载点。

---

## 二、目录布局（dev / 非 dev 一致）

```text
{runtimeRoot}/
├── drafts/                    ← dataRoot 后缀
├── ai_logs/                   ← dataRoot 后缀
├── task_{id}/                 ← dataRoot 后缀（pipeline.log / incremental-impact.json）
└── workspaces/                ← 派生 workspaceRoot
    └── task_{id}/
        ├── ...源码...
        └── docs/code-insight/ ← 推送前组装

{releasesRoot}/               ← 独立卷
└── {sys}/{repo}/{verNum}/
    ├── modules/
    ├── index/
    └── meta/
```

dev 写死：`runtimeRoot = ./storage` → `dataRoot = ./storage`、`workspaceRoot = ./storage/workspaces`、`releasesRoot = ./storage/releases`。

> **dev 行为变更**：旧的 `./temp_repos` 工作区被 `./storage/workspaces` 取代；首次切换请 `rm -rf ./temp_repos`（无功能损失，仅占盘）。

---

## 三、配置变更

### 删除的环境变量（无 fallback）

- `STORAGE_DATA_ROOT`
- `STORAGE_WORKSPACE_ROOT`

### 新增的环境变量

- `STORAGE_RUNTIME_ROOT` — 非 dev 必填绝对路径（dev 写死 `./storage`）

### 保留的环境变量

- `STORAGE_RELEASES_ROOT` — 非 dev 必填绝对路径（dev 写死 `./storage/releases`）

### `application.yml` / `application-local.yml` / `application-test.yml`

```yaml
storage:
  runtime-root: ${STORAGE_RUNTIME_ROOT:}
  releases-root: ${STORAGE_RELEASES_ROOT:}
```

---

## 四、代码层改动

### 4.1 `StorageProperties.java`

- 删除字段 `dataRoot`、`workspaceRoot`
- 新增字段 `runtimeRoot`
- 保留字段 `releasesRoot`

### 4.2 `EnvStorageResolver.java`

公开 API **零改动**，业务侧 28 个调用文件无需调整：

- `getActiveDataRoot()` / `getActiveWorkspaceRoot()` / `getActiveReleasesRoot()` 签名保持
- `draftsRoot()` / `draftFilePath()` / `taskDataDir()` / `aiLogDir()` / `taskWorkspaceDir()` 内部拼接后缀不变
- `@PostConstruct resolve()` 改为：先解析 `activeRuntimeRoot`，再派生 `activeDataRoot = activeRuntimeRoot`、`activeWorkspaceRoot = activeRuntimeRoot.resolve("workspaces")`
- 新增 `@Getter activeRuntimeRoot` + `runtimeRootString()` 辅助方法
- `hasConfiguredStoragePaths()` 改为检查 `runtimeRoot` 与 `releasesRoot`

### 4.3 `StorageBootstrapValidator.java`

- `ensureWritable` 校验对象：`activeRuntimeRoot` + `activeReleasesRoot`（合并前的三个减为两个）
- `runtimeRoot == releasesRoot` 时输出 warn（不阻断）

---

## 五、兼容性

- **不读旧 `STORAGE_DATA_ROOT` / `STORAGE_WORKSPACE_ROOT`**：干净切换优于保留兼容键，避免静默错配。
- **运维迁移**：现有非 dev 部署需同步更新环境变量；启动日志会清晰报错指出 `runtime-root` 必填。
- **回滚**：本次改动不涉及持久数据结构变更，`git revert` 即可回到三根版本。

---

## 六、验证清单

1. `mvn -DskipTests compile` + `mvn test-compile` 零错误
2. dev 模式启动：日志 `存储根已解析 env=dev dataRoot=.../storage workspaceRoot=.../storage/workspaces releasesRoot=.../storage/releases`
3. 非 dev 启动校验：未配 `STORAGE_*` 时启动失败并提示 `runtime-root` / `releases-root` 必填；配齐后启动成功
4. 路径冒烟（dev 模式）：INITIAL / INCREMENTAL / AI 归纳 / 知识推送四个产物路径全部写入预期目录
5. 单测：`RepositoryPublishServiceRollbackTest`、`RepositoryActiveKnowledgeResolverTest`、`TaskRetryCleanupTest` 全部通过
6. 兼容性回归：旧 `STORAGE_DATA_ROOT` / `STORAGE_WORKSPACE_ROOT` 不再被读取，非 dev 启动报 `runtime-root` 缺失

---

## 七、风险

| 风险 | 缓解 |
|---|---|
| dev 模式 `./temp_repos` 历史残留被忽略 | PR 描述里提醒开发者 `rm -rf ./temp_repos`（仅占盘） |
| 现有非 dev 部署仍用旧 `STORAGE_*` | CHANGELOG 标 BREAKING；启动校验立即报错，不会静默错配 |
| `EnvStorageResolver` 公开 API 变动 | 不动公开 API，内部派生即可 |
| `AiSummaryServiceImpl.java:1244-1245` 仍硬编码 `.resolve("ai_logs")` | 物理路径不变，影响放后续清理 PR |

---

## 八、改动文件总览

| 类别 | 文件 | 性质 |
|---|---|---|
| 代码 | `backend/src/main/java/com/company/codeinsight/common/storage/StorageProperties.java` | 字段重构 |
| 代码 | `backend/src/main/java/com/company/codeinsight/common/storage/EnvStorageResolver.java` | 派生逻辑 |
| 代码 | `backend/src/main/java/com/company/codeinsight/common/storage/StorageBootstrapValidator.java` | 校验对象 |
| 配置 | `backend/src/main/resources/application.yml` | 键名替换 |
| 配置 | `backend/src/main/resources/application-local.yml` | 键名替换 |
| 配置 | `backend/src/test/resources/application-test.yml` | 键名替换 |
| 配置 | `.env.example` | 模板更新 |
| 文档 | `docs/cluster-shared-storage-design.md` | 主要修订 |
| 文档 | `docs/schema-text-field-remediation.md` | 措辞同步 |
| 文档 | `CLAUDE.md` | 共享存储描述 |
| 文档 | `CHANGELOG.md` | BREAKING 条目 |
| SQL 注释 | `backend/src/main/resources/db/schema.sql` | 2 处行内注释 |

**合计：12 个文件**。