# 知识版本列表 releaseDirExists 下沉方案

## 目标

让 `listVersions` 一次返回 `releaseDirExists`，前端不再依赖 `listRepositoryPublishSnapshots` 就能决定回滚按钮是否可用。选了 / 没选仓库行为统一。

## 背景

当前「知识推送」页面的回滚按钮在不同状态下行为不一致：

- **未选仓库**：仅调 `GET /api/knowledge/page`，返回的 `KnowledgeVersion` 缺 `releaseDirExists`，旧版本按钮可点（点了报错）。
- **选了仓库**：额外调 `GET /api/repositories/{id}/publish/snapshots`，拿到 `releaseDirExists`，旧版本按钮置灰。

两条路径返回字段不同导致 UX 不一致。本次方案把 `releaseDirExists` 下沉到 `listVersions` 接口，统一行为。

---

## 改动 1：实体加 transient 字段

**文件**：[`backend/src/main/java/com/company/codeinsight/modules/knowledge/entity/KnowledgeVersion.java`](backend/src/main/java/com/company/codeinsight/modules/knowledge/entity/KnowledgeVersion.java)

### 1a. 新增 import

```java
import com.baomidou.mybatisplus.annotation.TableField;
```

（`TableField` 已有 `activePublished` 字段用到，确认包路径一致）

### 1b. 新增字段

在 `@TableField(exist = false) private Boolean activePublished;` 紧邻位置加：

```java
/** NAS releases 目录是否存在（仅 PUSHED 版本有实际意义；API 计算字段，非表列） */
@TableField(exist = false)
private Boolean releaseDirExists;
```

---

## 改动 2：Service 层做 enrich

**文件**：[`backend/src/main/java/com/company/codeinsight/modules/knowledge/service/impl/KnowledgeServiceImpl.java`](backend/src/main/java/com/company/codeinsight/modules/knowledge/service/impl/KnowledgeServiceImpl.java)

### 2a. 新增 import

```java
import java.nio.file.Files;
import java.nio.file.Path;
```

### 2b. 注入 `StorageProperties`

类里 `taskWorkspacePaths` 附近加：

```java
@Autowired
private com.company.codeinsight.common.storage.StorageProperties storageProperties;
```

> 如果类已经使用 Lombok `@RequiredArgsConstructor`，改为把 `StorageProperties storageProperties` 加到 final 字段列表。

### 2c. 修改 `listVersionsPage`

原：

```java
public Page<KnowledgeVersion> listVersionsPage(int current, int size, Long systemId, Long repositoryId) {
    Page<KnowledgeVersion> page = new Page<>(current, size);
    LambdaQueryWrapper<KnowledgeVersion> qw = new LambdaQueryWrapper<>();
    qw.eq(systemId != null, KnowledgeVersion::getSystemId, systemId)
      .eq(repositoryId != null, KnowledgeVersion::getRepositoryId, repositoryId)
      .orderByDesc(KnowledgeVersion::getCreatedAt);
    Page<KnowledgeVersion> result = versionMapper.selectPage(page, qw);
    enrichActivePublished(result.getRecords());
    return page;
}
```

改为：

```java
public Page<KnowledgeVersion> listVersionsPage(int current, int size, Long systemId, Long repositoryId) {
    Page<KnowledgeVersion> page = new Page<>(current, size);
    LambdaQueryWrapper<KnowledgeVersion> qw = new LambdaQueryWrapper<>();
    qw.eq(systemId != null, KnowledgeVersion::getSystemId, systemId)
      .eq(repositoryId != null, KnowledgeVersion::getRepositoryId, repositoryId)
      .orderByDesc(KnowledgeVersion::getCreatedAt);
    Page<KnowledgeVersion> result = versionMapper.selectPage(page, qw);
    enrichActivePublished(result.getRecords());
    enrichReleaseDirExists(result.getRecords());
    return page;
}
```

### 2d. 新增私有方法

紧跟 `enrichActivePublished` 后：

```java
/**
 * 标注每条版本的 NAS 发布目录是否存在（仅 PUSHED 状态有实际意义）。
 * 用于前端「知识版本列表」的回滚按钮置灰判断，不再依赖 listRepositoryPublishSnapshots 兜底。
 */
private void enrichReleaseDirExists(List<KnowledgeVersion> records) {
    if (records == null || records.isEmpty()) {
        return;
    }
    for (KnowledgeVersion v : records) {
        if (!"PUSHED".equals(v.getStatus())) {
            v.setReleaseDirExists(null);
            continue;
        }
        Path releaseDir = storageProperties.releaseDir(
                v.getSystemId(), v.getRepositoryId(), v.getVersionNum());
        v.setReleaseDirExists(Files.isDirectory(releaseDir));
    }
}
```

---

## 改动 3：前端类型补字段

**文件**：[`frontend/src/api/knowledge.ts`](frontend/src/api/knowledge.ts)

在 `KnowledgeVersion` interface 里 `activePublished` 下方加：

```typescript
export interface KnowledgeVersion {
  // ...原有字段保持...
  createdAt: string;
  /** 是否为仓库当前生效的已发布版本 */
  activePublished?: boolean;
  /** NAS releases 目录是否存在（仅 PUSHED 有意义） */
  releaseDirExists?: boolean | null;
}
```

---

## 改动 4：前端按钮禁用逻辑改造

**文件**：[`frontend/src/pages/push/index.tsx`](frontend/src/pages/push/index.tsx)

### 4a. 删除快照接口相关状态和副作用

删除整段：

```typescript
useEffect(() => {
  if (selectedRepositoryId == null) {
    setPublishSnapshots([]);
    return;
  }
  listRepositoryPublishSnapshots(selectedRepositoryId)
    .then(setPublishSnapshots)
    .catch(() => setPublishSnapshots([]));
}, [selectedRepositoryId]);

const snapshotByVersionId = React.useMemo(() => {
  const map = new Map<number, RepositoryPublishSnapshotView>();
  publishSnapshots.forEach((s) => map.set(s.versionId, s));
  return map;
}, [publishSnapshots]);
```

### 4b. 改造 `rollbackDisabledReason`

原：

```typescript
const rollbackDisabledReason = (record: KnowledgeVersion): string | null => {
  if (record.status !== 'PUSHED') return null;
  if (record.activePublished) return '已是当前生效版本';
  const snap = snapshotByVersionId.get(record.id);
  if (selectedRepositoryId != null) {
    if (!snap) return '未找到该版本的发布快照';
    if (!snap.releaseDirExists) return 'NAS 发布产物目录缺失，无法回滚';
  }
  return null;
};
```

改为：

```typescript
const rollbackDisabledReason = (record: KnowledgeVersion): string | null => {
  if (record.status !== 'PUSHED') return null;
  if (record.activePublished) return '已是当前生效版本';
  if (record.releaseDirExists === false) return 'NAS 发布产物目录缺失，无法回滚';
  return null;
};
```

### 4c. 删除回滚成功后刷新快照列表的代码

在 `handleRollbackRepository` 的 `onOk` 里，删掉：

```typescript
if (selectedRepositoryId != null) {
  listRepositoryPublishSnapshots(selectedRepositoryId).then(setPublishSnapshots).catch(() => undefined);
}
```

只保留 `fetchVersions()`。

### 4d. 清理无用 import

如果不再用，删除文件顶部相关 import：

```typescript
import {
  rollbackRepositoryPublish,
  listRepositoryPublishSnapshots,
  // ...
} from '../../api/knowledge';
import type { RepositoryPublishSnapshotView } from '../../api/knowledge';
```

以及相关 state：

```typescript
const [publishSnapshots, setPublishSnapshots] = useState<RepositoryPublishSnapshotView[]>([]);
```

---

## 改动 5：保留后端 `listRepositoryPublishSnapshots` 接口

> **本次不动**。该接口另有用途——`RepositoryPublishSnapshotView` 包含发布元数据（提示词 ID、模型名、发布人、发布时间等），与 `KnowledgeVersion` 是不同维度的视图。未来"发布历史"页面 / 报表 / 对比功能可能复用。
>
> 本次仅移除前端调用，**后端能力保留**。

---

## 验证清单

按下面顺序验证：

| # | 验证项 | 怎么验证 | 期望结果 |
| --- | --- | --- | --- |
| 1 | 后端编译通过 | `cd backend && mvn -DskipTests compile` | BUILD SUCCESS |
| 2 | `KnowledgeVersion.releaseDirExists` 字段序列化 | 调 `GET /api/knowledge/page` 看返回 JSON | 每条记录多 `releaseDirExists` 字段 |
| 3 | 未选仓库时按钮置灰 | 不选仓库，系统下造一条 PUSHED + releaseDir 缺失的版本 | 按钮置灰，悬浮提示 "NAS 发布产物目录缺失，无法回滚" |
| 4 | 选了仓库时按钮置灰 | 选仓库，看到同一条版本 | 表现跟未选仓库一致 |
| 5 | 正常 PUSHED 版本可点 | 一条 releaseDir 正常的 PUSHED 版本（非 active） | 按钮可点，回滚成功 |
| 6 | 当前生效版本按钮置灰 | activePublished=true 的版本 | 提示 "已是当前生效版本" |
| 7 | 非 PUSHED 版本 | DRAFT / PUSHING / FAILED 状态 | 按钮可点（releaseDirExists=null 不触发 false 分支）；回滚时由后端 `rollbackToVersion` 兜底报错 |
| 8 | 网络请求数 | DevTools Network 面板 | `listRepositoryPublishSnapshots` 请求消失，只剩 `listVersions` |

---

## 边界与取舍说明

1. **`releaseDirExists` 给非 PUSHED 状态填 `null` 而不是 `false`**
   - 避免前端误判，把 DRAFT 等"还没发布"的版本也置灰
   - 前端用 `=== false` 严格比较，规避 null/undefined 误触发

2. **每次列表请求做 N 次 `Files.isDirectory`**
   - stat 系统调用，单次微秒级，页大小 10–20 条可接受
   - 如果未来分页很大（>100），可以加本地缓存（按 path → exists），30s TTL

3. **`listRepositoryPublishSnapshots` 接口保留**
   - 不删，未来"发布历史"页、报表、对比功能可能还要用
   - 移除前端调用 ≠ 删除后端能力

4. **没动浏览页**
   - 浏览页 `require()` 那条 releaseDirExists 校验独立保留，逻辑不动
   - 列表侧和浏览侧各自管各自的 releaseDir 检查

---

## 不在本方案里的事（后续可考虑）

- `enrichReleaseDirExists` 在 shared 模式（集群 + NAS）下行为正确；local 模式下 `releaseDir()` 走 `./storage/releases/...`，逻辑也一致
- 没改 `applyFromTask` / `rollbackToVersion` 的 releaseDirExists 处理流程
- 没改前端"知识版本"以外的其他页面对 `KnowledgeVersion` 的使用
- 没动 `RepositoryPublishSnapshotView` 接口

---

## 执行顺序建议

1. 后端：改 `KnowledgeVersion.java` + `KnowledgeServiceImpl.java` → 跑 `mvn -DskipTests compile`
2. 启动后端，手动 curl 一次 `GET /api/knowledge/page`，确认 JSON 多 `releaseDirExists` 字段
3. 前端：改 `knowledge.ts` 类型 → `push/index.tsx` 改造
4. 启动前端，按"验证清单"逐项过

## 改动量预估

- 后端：~25 行
- 前端：~15 行（包含删除）