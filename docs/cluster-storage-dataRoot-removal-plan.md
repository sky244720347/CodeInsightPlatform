# dataRoot 抽象层删除方案

> 状态：**已确认并实施**（日期 2026-07-15）  
> 修订对象：[cluster-storage-runtime-root-plan.md](./cluster-storage-runtime-root-plan.md) 的 API 进一步精简  
> 性质：纯内部重构，公开 API 仅移除 `getActiveDataRoot()` / `dataRootString()`，物理路径不变

---

## 一、目标

`EnvStorageResolver` 中 `dataRoot` 这层抽象物理上 100% 等于 `runtimeRoot`，是冗余别名。在已经合并为两根（`runtime-root` + `releases-root`）的基础上，进一步删除 `dataRoot` 这层语义抽象，让"根"的概念与配置保持一致。

---

## 二、删除范围

### 2.1 `EnvStorageResolver` 中删除

- 字段 `activeDataRoot`（改为内部直接用 `activeRuntimeRoot`）
- 公开方法 `getActiveDataRoot()`
- 公开方法 `dataRootString()`（**无外部调用方**）

### 2.2 `TaskWorkspacePaths` 中删除

- 公开方法 `dataRoot()`（仅 TaskWorkspacePaths 内部使用）
- `@Deprecated` 方法 `storageLocalPath()`（**无外部调用方**）

### 2.3 内部调用方替换

- [DataUriUtil.java:69](backend/src/main/java/com/company/codeinsight/common/util/DataUriUtil.java#L69) `getActiveDataRoot()` → `getActiveRuntimeRoot()`
- [DraftFileUtil.java:55](backend/src/main/java/com/company/codeinsight/common/util/DraftFileUtil.java#L55) `getActiveDataRoot()` → `getActiveRuntimeRoot()`
- [DraftFileUtil.java:30](backend/src/main/java/com/company/codeinsight/common/util/DraftFileUtil.java#L30) `@Deprecated resolveDraftPath(uri, storageLocalPath)` 方法删除
- [DraftFileUtil.java:43](backend/src/main/java/com/company/codeinsight/common/util/DraftFileUtil.java#L43) `@Deprecated resolve(uri, StorageProperties)` 方法删除（只抛异常）
- [AiSummaryServiceImpl.java:1244-1245](backend/src/main/java/com/company/codeinsight/modules/ai/service/impl/AiSummaryServiceImpl.java#L1244-L1245) 硬编码 `.resolve("ai_logs")` → 改用 `storageResolver.aiLogDir(taskId).resolve(fileName)`

### 2.4 保留

- `getActiveRuntimeRoot()` / `runtimeRootString()` — 物理根（与配置一一对应）
- `getActiveWorkspaceRoot()` / `workspaceRootString()` — 派生虚拟根（`runtimeRoot/workspaces`），通过 `TaskWorkspacePaths` 包装被 11 个文件使用
- `getActiveReleasesRoot()` — 物理根
- 所有后缀辅助方法（`draftsRoot` / `draftFilePath` / `taskDataDir` / `aiLogDir` / `taskWorkspaceDir` / `releaseDir`）— 签名不变

---

## 三、改动清单（5 个文件）

| 文件 | 改动 |
|---|---|
| [EnvStorageResolver.java](backend/src/main/java/com/company/codeinsight/common/storage/EnvStorageResolver.java) | 删 `activeDataRoot` 字段 / `getActiveDataRoot()` / `dataRootString()`；内部全部改用 `activeRuntimeRoot` |
| [TaskWorkspacePaths.java](backend/src/main/java/com/company/codeinsight/common/storage/TaskWorkspacePaths.java) | 删 `dataRoot()` 方法；删 `@Deprecated storageLocalPath()` |
| [DataUriUtil.java](backend/src/main/java/com/company/codeinsight/common/util/DataUriUtil.java) | 内部 `getActiveDataRoot()` → `getActiveRuntimeRoot()`；Javadoc 同步 |
| [DraftFileUtil.java](backend/src/main/java/com/company/codeinsight/common/util/DraftFileUtil.java) | 删 2 个 `@Deprecated` 方法；`getActiveDataRoot()` → `getActiveRuntimeRoot()` |
| [AiSummaryServiceImpl.java](backend/src/main/java/com/company/codeinsight/modules/ai/service/impl/AiSummaryServiceImpl.java) | 行 1244-1245 硬编码改用 `aiLogDir(taskId)` |

---

## 四、效果

- `EnvStorageResolver` 公开方法数：**11 → 8**
- 物理根 getter：**4 → 3**（`runtimeRoot` / `workspaceRoot` / `releasesRoot`）
- 死代码清理：3 处
- 硬编码后缀收编：1 处

---

## 五、兼容性

- **无 BREAKING**（物理路径不变；公开 API 仅删除冗余 getter）
- 业务侧 28 个文件零改动（仅内部 3 处直接调用方需要调整）
- 现有 URI scheme（`draft:` / `release:` / `prompt:` / `business-knowledge:` / `release-edit:` / `trial:` / `snapshot:`）不变

---

## 六、验证

```bash
cd backend
mvn -DskipTests compile    # 必须零错误
mvn test-compile           # 测试代码也要编过
```

期望：编译通过，0 警告新增。