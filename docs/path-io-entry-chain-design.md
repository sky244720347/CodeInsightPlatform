# NAS 路径读写入口链路设计

> 状态：**已实施**（2026-07-15）  
> 修订来源：[cluster-storage-runtime-root-plan.md](./cluster-storage-runtime-root-plan.md) + [cluster-storage-dataRoot-removal-plan.md](./cluster-storage-dataRoot-removal-plan.md) 的最终落地形态  
> 配套规范：[cluster-shared-storage-design.md](./cluster-shared-storage-design.md)

---

## 一、设计目标

1. **单一入口**：所有 NAS 路径解析只走一个门面，业务代码禁止硬编码或直注
2. **dev / 非 dev 判断一次**：启动时确定根目录，运行期不再分支
3. **后缀布局一致**：dev 与非 dev 的相对路径完全相同，业务代码无感
4. **可测试**：单测可注入任意根，绕过 env 分支

---

## 二、三层调用架构

```text
┌──────────────────────────────────────────────────────────────────┐
│  L3  业务调用方（28 个文件）                                       │
│      scanner / parser / callchain / entrypoint / hierarchy /     │
│      ai / draft / push / knowledge / task / repository ...        │
└────────────────────┬─────────────────────────────────────────────┘
                     │ 调 draftsRoot() / taskWorkspaceDir(id) 等
                     ▼
┌──────────────────────────────────────────────────────────────────┐
│  L2  路径门面（唯一根入口）                                        │
│                                                                  │
│  • EnvStorageResolver           ★ 核心：根解析 + 后缀辅助         │
│  • TaskWorkspacePaths             薄包装（11 个文件直接用）        │
└────────────────────┬─────────────────────────────────────────────┘
                     │ 注入 StorageProperties / CodeInsightEnvProperties
                     ▼
┌──────────────────────────────────────────────────────────────────┐
│  L1  启动配置（仅 EnvStorageResolver 注入）                        │
│                                                                  │
│  • CodeInsightEnvProperties    ★ 唯一 dev 判断源                 │
│  • StorageProperties             非 dev 才读                     │
└──────────────────────────────────────────────────────────────────┘
                     │
                     ▼
                文件系统
```

### 2.1 高级 URI 工具（横切）

部分业务有"逻辑 URI → 物理路径"的语义转换需求，封装在工具类里，**内部仍走 L2 门面**：

| 工具 | URI scheme | 解析到 |
|---|---|---|
| `DraftFileUtil` | `draft:{sys}:{repo}:task_{id}/...` | `{runtimeRoot}/drafts/...` |
| `DraftFileUtil` | `release:{sys}:{repo}:v1.0.0/relPath` | `{releasesRoot}/{sys}/{repo}/v1.0.0/relPath` |
| `DataUriUtil` | `prompt:{id}/content.md` | `{runtimeRoot}/prompts/{id}/content.md` |
| `DataUriUtil` | `business-knowledge:{systemId}/content.md` | `{runtimeRoot}/business-knowledge/{systemId}/content.md` |
| `DataUriUtil` | `release-edit:{id}/content.md` | `{runtimeRoot}/release-edits/{id}/content.md` |
| `DataUriUtil` | `trial:{trialId}/result.json` | `{runtimeRoot}/trials/{trialId}/result.json` |
| `DataUriUtil` | `snapshot:{repoId}:{versionId}/{file}.json` | `{runtimeRoot}/publish-snapshots/{repoId}/{versionId}/{file}.json` |
| `DraftFileUtil` | `file:///absolute/path` | 绝对路径（兼容旧数据） |

---

## 三、dev / 非 dev 判断链路

### 3.1 配置来源链路

```text
环境变量 CODE_INSIGHT_ENV
        │
        ▼
application.yml / application-local.yml
  code-insight:
    env: ${CODE_INSIGHT_ENV:dev}            ← 默认 dev
        │
        ▼
CodeInsightEnvProperties.env 字段
        │
        ▼
CodeInsightEnvProperties.isDev()  ← 唯一判断点
        │
        ▼
EnvStorageResolver.resolve() @PostConstruct 选分支
        │
        ▼
activeRuntimeRoot / activeReleasesRoot 字段
        │
        ▼
运行期所有调用读字段，不再判断
```

### 3.2 `isDev()` 实现

[CodeInsightEnvProperties.java:19-21](backend/src/main/java/com/company/codeinsight/common/config/CodeInsightEnvProperties.java#L19-L21)

```java
public boolean isDev() {
    return env == null || env.isBlank() || "dev".equalsIgnoreCase(env.trim());
}
```

dev 触发条件（任一满足）：
- `env` 为 `null`
- `env` 为空白字符串
- `env` 大小写不敏感等于 `"dev"`

非 dev：`prod` / `staging` / 其他任何值。

### 3.3 `EnvStorageResolver.resolve()` 分支

[EnvStorageResolver.java:42-55](backend/src/main/java/com/company/codeinsight/common/storage/EnvStorageResolver.java#L42-L55)

```java
@PostConstruct
void resolve() {
    if (envProperties.isDev()) {
        // dev: 写死本机
        activeRuntimeRoot = Paths.get("./storage").toAbsolutePath().normalize();
        activeReleasesRoot = Paths.get("./storage/releases").toAbsolutePath().normalize();
        if (hasConfiguredStoragePaths()) {
            log.warn("dev 已忽略 STORAGE_RUNTIME_ROOT / RELEASES_ROOT 配置");
        }
    } else {
        // 非 dev: 必填绝对路径
        activeRuntimeRoot = requireAbsoluteConfigured("runtime-root", storageProperties.getRuntimeRoot());
        activeReleasesRoot = requireAbsoluteConfigured("releases-root", storageProperties.getReleasesRoot());
    }
    // 派生：dev / 非 dev 一致
    activeWorkspaceRoot = activeRuntimeRoot.resolve("workspaces").normalize();
}
```

### 3.4 关键特性

| 特性 | 说明 |
|---|---|
| **判断时机** | 仅 `@PostConstruct` 启动时一次 |
| **判断后存储** | 结果存到 `activeRuntimeRoot` / `activeReleasesRoot` 字段 |
| **运行期调用** | 28 个调用方**完全不知道**是 dev 还是非 dev，统一从 `activeRuntimeRoot` 派生路径 |
| **后缀一致** | dev/非 dev 的 `drafts/` / `ai_logs/` / `workspaces/` 等后缀**完全相同** |
| **重启即切换** | 切换 `CODE_INSIGHT_ENV` 必须重启进程（无热切换） |

---

## 四、目录布局（dev / 非 dev 一致）

```text
{runtimeRoot}/                          ← STORAGE_RUNTIME_ROOT（dev 写死 ./storage）
├── drafts/                             ← EnvStorageResolver.draftsRoot()
│   └── task_{id}/.../*.md              ← EnvStorageResolver.draftFilePath(rel)
├── ai_logs/                            ← EnvStorageResolver.aiLogDir(id) 之父
│   └── task_{id}/                      ← EnvStorageResolver.aiLogDir(id)
├── task_{id}/                          ← EnvStorageResolver.taskDataDir(id)
│   ├── pipeline.log
│   └── incremental-impact.json
├── workspaces/                         ← EnvStorageResolver.taskWorkspaceDir(id) 之父
│   └── task_{id}/                      ← EnvStorageResolver.taskWorkspaceDir(id)
│       ├── ...源码...
│       └── docs/code-insight/          ← TaskWorkspacePaths.taskDocsCodeInsight(id)
├── prompts/                            ← DataUriUtil:prompt:
├── business-knowledge/                 ← DataUriUtil:business-knowledge:
├── release-edits/                      ← DataUriUtil:release-edit:
├── trials/                             ← DataUriUtil:trial:
└── publish-snapshots/                  ← DataUriUtil:snapshot:

{releasesRoot}/                         ← STORAGE_RELEASES_ROOT（dev 写死 ./storage/releases）
└── {sys}/{repo}/{ver}/                 ← EnvStorageResolver.releaseDir(sys, repo, ver)
    ├── modules/
    ├── index/
    └── meta/
```

---

## 五、`EnvStorageResolver` 公开 API

| 类别 | 方法 | 路径形态 |
|---|---|---|
| 根 getter | `getActiveRuntimeRoot()` | `{runtimeRoot}` |
| | `getActiveWorkspaceRoot()` | `{runtimeRoot}/workspaces`（派生） |
| | `getActiveReleasesRoot()` | `{releasesRoot}` |
| 根 String | `runtimeRootString()` | `{runtimeRoot}` 字符串 |
| | `workspaceRootString()` | `{runtimeRoot}/workspaces` 字符串 |
| 后缀辅助 | `draftsRoot()` | `{runtimeRoot}/drafts` |
| | `draftFilePath(rel)` | `{runtimeRoot}/drafts/{rel}` |
| | `taskDataDir(id)` | `{runtimeRoot}/task_{id}` |
| | `aiLogDir(id)` | `{runtimeRoot}/ai_logs/task_{id}` |
| | `taskWorkspaceDir(id)` | `{runtimeRoot}/workspaces/task_{id}` |
| | `taskWorkspaceDirString(id)` | 同上字符串 |
| | `releaseDir(sys, repo, ver)` | `{releasesRoot}/{sys}/{repo}/{ver}` |
| 测试 | `overrideRootsForTest(runtimeRoot, workspaceRoot, releasesRoot)` | 单测注入，绕过 env |

**`TaskWorkspacePaths` 薄包装**：

| 方法 | 路径形态 |
|---|---|
| `taskProjectDir(id)` | `taskWorkspaceDir(id).toFile()` |
| `taskProjectPath(id)` | `taskWorkspaceDir(id)` |
| `taskDocsCodeInsight(id)` | `taskWorkspaceDir(id)/docs/code-insight` |
| `workspaceRoot()` | `workspaceRootString()` |
| `releasesRoot()` | `getActiveReleasesRoot()` |

---

## 六、真正的文件 I/O 在哪发生？

`EnvStorageResolver` 和 `TaskWorkspacePaths` **只返回 Path 对象**，**不直接读写文件**。真正的 I/O 分散在调用方：

| 文件 | 操作 | 路径来源 |
|---|---|---|
| [DataUriUtil.java:96-98](backend/src/main/java/com/company/codeinsight/common/util/DataUriUtil.java#L96-L98) | `Files.write/readString` | `resolve(uri, resolver)` |
| [TaskExecutionLogger.java:40,62,96](backend/src/main/java/com/company/codeinsight/modules/task/service/TaskExecutionLogger.java) | `pipeline.log` | `taskDataDir(id)/pipeline.log` |
| [IncrementalImpactPersistenceImpl.java:86](backend/src/main/java/com/company/codeinsight/modules/callchain/service/impl/IncrementalImpactPersistenceImpl.java#L86) | `incremental-impact.json` | `taskDataDir(id)/incremental-impact.json` |
| [AiSummaryServiceImpl.java:1247-1249](backend/src/main/java/com/company/codeinsight/modules/ai/service/impl/AiSummaryServiceImpl.java#L1247-L1249) | AI req/resp 日志 | `aiLogDir(id)/...` |
| 各种 draft 写盘 | 草稿正文 | `draftsRoot()/draftFilePath()` |
| 推送策略 | 已发布知识 | `releaseDir()` |

**所有写文件的代码都先经过 `EnvStorageResolver.xxx()` 拿到 Path，再 `Files.write`**。dev/非 dev 自动得到正确路径，调用方无感。

---

## 七、约束（防止回退）

### 7.1 禁止事项

| ❌ 禁止 | ✅ 替代 |
|---|---|
| `Paths.get("./storage")` 业务代码 | `resolver.getActiveRuntimeRoot()` |
| `Paths.get("./temp_repos")` 业务代码 | `resolver.taskWorkspaceDir(id)` |
| `@Value("${code-insight.storage.*}")` 业务代码 | 注入 `EnvStorageResolver` |
| 业务代码读 `STORAGE_LOCAL_PATH` 环境变量 | 不读，走门面 |
| 业务代码调 `envProperties.isDev()` 判断路径 | 路径无 dev/非 dev 之分 |
| `new File("./...")` 业务代码 | `resolver.xxx().toFile()` |

### 7.2 唯一允许 `isDev()` 判断路径的位置

`EnvStorageResolver.resolve()` 是**唯一**做"根解析"分支判断的地方。其他地方**不要再加** `isDev()` 路径分支。

### 7.3 唯一允许硬编码 `./storage` 的位置

[EnvStorageResolver.java:42-43](backend/src/main/java/com/company/codeinsight/common/storage/EnvStorageResolver.java#L42-L43) — dev 写死。其他位置 0 容忍。

---

## 八、测试隔离

测试代码要避免污染本机 `./storage`：

```java
// 推荐：用临时目录
Path tempRuntime = Files.createTempDirectory("ci-runtime-");
Path tempWorkspace = Files.createTempDirectory(tempRuntime, "workspaces");
Path tempReleases = Files.createTempDirectory("ci-releases-");
resolver.overrideRootsForTest(tempRuntime, tempWorkspace, tempReleases);

// 不推荐：依赖 dev 写死（本机真实 ./storage）
EnvStorageResolver resolver = new EnvStorageResolver(
    new CodeInsightEnvProperties(),  // env="dev"
    new StorageProperties()         // 空配置
);
```

---

## 九、运行时序图

```text
Spring 启动
    │
    ▼
CodeInsightEnvProperties 注入 env 字段
StorageProperties 注入 runtimeRoot / releasesRoot
    │
    ▼
EnvStorageResolver @PostConstruct resolve()
    │
    ├── envProperties.isDev() ?──yes──► 写死 ./storage + ./storage/releases
    │                                    │
    │                                    └──► 派生 workspaceRoot = ./storage/workspaces
    │
    └── envProperties.isDev() ?──no───► 读 StorageProperties 必填校验
                                         │
                                         └──► 派生 workspaceRoot = runtimeRoot/workspaces
    │
    ▼
StorageBootstrapValidator @Order(0) ApplicationRunner.run()
    │
    ├── ensureWritable(runtimeRoot)        ← dev + 非 dev
    ├── ensureWritable(releasesRoot)       ← dev + 非 dev
    ├── warn if runtimeRoot == releasesRoot ← dev + 非 dev
    │
    └── if !isDev() → pingRedisOrFail()    ← 仅非 dev
    │
    ▼
业务请求到达
    │
    ▼
业务代码调 resolver.taskWorkspaceDir(id) / resolver.draftsRoot() 等
    │
    ▼
返回 Path，调用方 Files.write / Files.readString
    │
    ▼
文件系统（dev 写 ./storage，非 dev 写 STORAGE_RUNTIME_ROOT）
```

---

## 十、未来扩展指引

### 10.1 想新增一类存储后缀？

❌ 错误做法：在业务代码里 `runtimeRoot.resolve("xxx")`

✅ 正确做法：
1. 在 `EnvStorageResolver` 加一个后缀辅助方法（如 `promptsRoot()` / `businessKnowledgeRoot()` 等）
2. 业务代码调 `resolver.xxxRoot()`
3. 这样将来要换后缀名只需要改 1 处

### 10.2 想加一个独立物理卷（如 `snapshotsRoot`）？

1. `StorageProperties` 加字段
2. `application*.yml` 加配置
3. `EnvStorageResolver` 加 getter + 后缀辅助
4. `.env.example` 加环境变量
5. `StorageBootstrapValidator` 加 `ensureWritable`
6. **不**改业务代码直注路径

### 10.3 想支持 S3 / OSS 等对象存储？

把 `EnvStorageResolver` 抽象成接口 `StorageBackend`：
- `LocalStorageBackend`（当前实现）
- `S3StorageBackend`（未来）
- 通过 `code-insight.storage.backend` 配置切换

`Path` 类型可换为 `StorageLocation`（path-like 抽象）。

---

## 十一、相关文档

- [cluster-shared-storage-design.md](./cluster-shared-storage-design.md) — 集群与存储模式确认稿
- [cluster-storage-runtime-root-plan.md](./cluster-storage-runtime-root-plan.md) — 三根 → 两根合并
- [cluster-storage-dataRoot-removal-plan.md](./cluster-storage-dataRoot-removal-plan.md) — dataRoot 抽象层删除
- [schema-text-field-remediation.md](./schema-text-field-remediation.md) — C 组 URI 整改
- [incremental-task-strict-gate.md](./incremental-task-strict-gate.md) — 增量任务的存储门禁
