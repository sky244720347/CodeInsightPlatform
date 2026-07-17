# Phase 4 UI 重新设计：INITIAL 全量 / INCREMENTAL diff 切换

> 适用范围：3 个复核断点（ENTRYPOINT_REVIEW / MODULE_HIERARCHY_REVIEW / PENDING_REVIEW）
> 依赖：[incremental-baseline-design.md](./incremental-baseline-design.md)（数据层） + [module-hierarchy-design.md](./module-hierarchy-design.md)（AI 提取准确性）

---

## 一、设计目标

### 用户需求
- **INITIAL 任务**：效果不改动（保持全量展示，不分新/旧）
- **INCREMENTAL 任务**：展示成 git diff 效果——区分 4 种类型
  - 🟦 **本次新增**（`+`）
  - 🟩 **基线继承**（`=`）
  - 🟨 **AI 重提炼**（`~`，疑似修改）
  - 🟥 **本次删除**（`-`）

### 与两个上游方案的关系

| 上游方案 | 提供的"基础" |
|---|---|
| [incremental-baseline-design.md](./incremental-baseline-design.md) | 数据层字段：`baselineTaskId` / `sourceEntryClass` / `baseline_workspace_id` + 基线继承 Service + persistIncremental 落表 |
| [module-hierarchy-design.md](./module-hierarchy-design.md) | AI 提取串行 + 共享上下文，确保"基线继承"vs"AI 重生成"在数据层正确标识 |

**本方案不重新实现数据层**，只在前端展示层做差异化渲染。

### 设计原则
1. **INITIAL 任务零侵入**——完全保留现状
2. **后端驱动 diff**——DIFF 字段已存在（incremental-baseline-design.md Phase 1）
3. **前端 3 个 Workspace 改**——加 Segmented 切换 + diff Tag 渲染
4. **4 种 diff 分类**——3 种靠 DTO 字段，1 种（本次删除）靠新增 `/diff` 端点

---

## 二、4 种 diff 分类与识别

| 分类 | 标识 | 颜色（antd） | 识别方法 | 数据来源 |
|---|---|---|---|---|
| **本次新增** | `+` | geekblue | `baselineTaskId == null` | 已有字段 |
| **基线继承** | `=` | green | `baselineTaskId != null` | 已有字段 |
| **AI 重提炼** | `~` | orange | `sourceEntryClass != null`（含 retarget 入口的节点） | 已有字段 |
| **本次删除** | `-` | red | 基线表有 + 本次表无 | **新增 B4 端点** |

### 4 种分类的覆盖关系

```
INCREMENTAL 任务下，每个数据行属于且仅属于一种分类：
  本次新增 (baselineTaskId == null)
    └─ 本次 AI 识别后新增的入口/模块/草稿
  基线继承 (baselineTaskId != null && !AI 重提炼)
    └─ 从基线任务复制，本任务未重提炼
  AI 重提炼 (sourceEntryClass != null)
    └─ INCREMENTAL 任务的 retarget 入口对应的节点（继承自基线，被 AI 重生成）
  本次删除 (基线有 + 本次无)
    └─ INCREMENTAL 任务的 retarget 集合包含了，但本次没生成的节点
       实际场景：基线有某入口，retarget 列表中未出现（罕见）
```

**说明**：
- "AI 重提炼"和"基线继承"在数据层都是 `baselineTaskId != null`，**但**：
  - 基线继承的节点 `sourceEntryClass = null`（基线节点是整树继承的，没有 source_entry_class 标记）
  - AI 重提炼的节点 `sourceEntryClass = retarget entry className`（本次 AI 生成的）
- 这是 `sourceEntryClass` 字段的设计意图

---

## 三、后端最小改动

### B1: `EntrypointReviewView` 加 `baselineTaskId`

**文件**：[`backend/src/main/java/com/company/codeinsight/modules/entrypoint/model/EntrypointReviewView.java`](backend/src/main/java/com/company/codeinsight/modules/entrypoint/model/EntrypointReviewView.java)

```java
@Data
public class EntrypointReviewView {
    // ... 现有字段 ...
    /** INCREMENTAL 任务的基线继承标识（NULL=本次新增；非空=从该基线任务继承） */
    private Long baselineTaskId;
}
```

**`EntrypointReviewServiceImpl.listByTaskId` 映射**（[line 131-152](backend/src/main/java/com/company/codeinsight/modules/entrypoint/service/impl/EntrypointReviewServiceImpl.java#L131-L152)）：

```java
v.setId(row.getId());
v.setTaskId(row.getTaskId());
// ... 现有字段 ...
v.setBaselineTaskId(row.getBaselineTaskId());  // ← 新增 1 行
v.setMethods(deserializeMethods(row.getMethodsJson()));
```

### B2: `ModuleDto` / `FunctionDto` 加 `sourceEntryClass`

**文件**：[`ModuleDto.java`](backend/src/main/java/com/company/codeinsight/modules/hierarchy/model/ModuleDto.java) + [`FunctionDto.java`](backend/src/main/java/com/company/codeinsight/modules/hierarchy/model/FunctionDto.java)

```java
// ModuleDto.java
/** MODULE 级节点的来源入口类（INCREMENTAL 任务的"AI 重提炼"节点才有值） */
private String sourceEntryClass;

// FunctionDto.java
/** FUNCTION 级节点的来源入口类（用于 diff 视图"AI 重提炼"分类） */
private String sourceEntryClass;
```

**`ModuleHierarchyServiceImpl.loadByTaskId` 映射**（[line 485+](backend/src/main/java/com/company/codeinsight/modules/hierarchy/service/impl/ModuleHierarchyServiceImpl.java#L485-L)）：

```java
// 加载 FUNCTION 节点时
for (ModuleHierarchyNode n : functionNodes) {
    FunctionDto fn = new FunctionDto();
    fn.setId(n.getNodeId());
    fn.setFunctionName(n.getName());
    fn.setClassPaths(parseClassPaths(n.getClassPaths()));
    fn.setMethodSignatures(parseMethodSignatures(n.getMethodSignatures()));
    fn.setSourceEntryClass(n.getSourceEntryClass());  // ← 新增
    // ... 挂树 ...
}
```

**MODULE / SUB_MODULE 级也透传**（如果需要更精细的展示）：

```java
// MODULE 级
mod.setSourceEntryClass(node.getSourceEntryClass());
// SUB_MODULE 级
sub.setSourceEntryClass(node.getSourceEntryClass());
```

### B3: `DraftTreeNode` 加 `baselineTaskId`

**文件**：[`DraftTreeNode.java`](backend/src/main/java/com/company/codeinsight/modules/draft/dto/DraftTreeNode.java)

```java
@Data
public class DraftTreeNode {
    // ... 现有字段 ...
    /** INCREMENTAL 任务的基线继承标识（NULL=本次新增；非空=从该基线任务继承） */
    private Long baselineTaskId;
}
```

**`DraftTreeNode.fromDraft()` 传递**：

```java
public static DraftTreeNode fromDraft(KnowledgeDraft d) {
    DraftTreeNode n = new DraftTreeNode();
    // ... 现有字段 ...
    n.setBaselineTaskId(d.getBaselineTaskId());  // ← 新增
    return n;
}
```

### B4: 3 个 `/diff` 端点（含本次删除）

#### B4.1 `GET /tasks/{id}/entrypoints/diff`

**Controller**：

```java
@GetMapping("/{id}/entrypoints/diff")
public ApiResponse<EntrypointDiffDto> getEntrypointDiff(@PathVariable Long id) {
    return ApiResponse.success(
        entrypointReviewService.getEntrypointDiff(id));
}
```

**Service 实现**：

```java
// EntrypointReviewServiceImpl
public EntrypointDiffDto getEntrypointDiff(Long taskId) {
    DecompileTask task = taskMapper.selectById(taskId);
    if (task == null) {
        throw new BusinessException("任务不存在");
    }
    EntrypointDiffDto result = new EntrypointDiffDto();

    if (!"INCREMENTAL".equals(task.getType()) || task.getRepositoryId() == null) {
        // INITIAL 任务 / 无仓库：返回空 diff
        return result;
    }
    CodeRepository repo = codeRepositoryService.getById(task.getRepositoryId());
    Long baselineTaskId = repo == null ? null : repo.getLastPublishedTaskId();
    if (baselineTaskId == null) {
        return result;
    }

    // 本次入口（基线继承 + 本次新增）
    List<EntrypointEntity> currentRows = entrypointMapper.selectByTaskId(taskId);
    Set<String> currentClassNames = currentRows.stream()
            .map(EntrypointEntity::getClassName)
            .collect(Collectors.toSet());

    // 基线入口
    List<EntrypointEntity> baselineRows = entrypointMapper.selectByTaskId(baselineTaskId);

    result.setNewRows(currentRows.stream()
            .filter(r -> r.getBaselineTaskId() == null)
            .map(this::toView)
            .collect(Collectors.toList()));
    result.setInheritedRows(currentRows.stream()
            .filter(r -> r.getBaselineTaskId() != null)
            .map(this::toView)
            .collect(Collectors.toList()));
    result.setDeletedRows(baselineRows.stream()
            .filter(r -> !currentClassNames.contains(r.getClassName()))
            .map(this::toView)
            .collect(Collectors.toList()));
    return result;
}
```

**DTO** `EntrypointDiffDto`：

```java
@Data
public class EntrypointDiffDto {
    private List<EntrypointReviewView> newRows;        // 本次新增
    private List<EntrypointReviewView> inheritedRows;   // 基线继承
    private List<EntrypointReviewView> deletedRows;     // 本次删除
}
```

#### B4.2 `GET /tasks/{id}/module-hierarchy/diff`

**结构**与 B4.1 相同：

```java
// Controller
@GetMapping("/{id}/module-hierarchy/diff")
public ApiResponse<ModuleHierarchyDiffDto> getHierarchyDiff(@PathVariable Long id) {
    return ApiResponse.success(
        moduleHierarchyService.getHierarchyDiff(id));
}

// Service
public ModuleHierarchyDiffDto getHierarchyDiff(Long taskId) {
    // 1. 加载本任务 hierarchy（含继承 + 本次）
    ModuleHierarchy current = loadByTaskId(taskId);
    // 2. 加载基线任务 hierarchy
    ModuleHierarchy baseline = loadByTaskId(baselineTaskId);
    // 3. 按 FUNCTION 节点 classPaths 对比：
    //    - current 有 + baseline 无 = 本次新增
    //    - current 有 + sourceEntryClass 标记 = AI 重提炼
    //    - baseline 有 + current 无 = 本次删除
    //    - baseline 有 + current 有 + sourceEntryClass == null = 基线继承
    // ...
}
```

#### B4.3 `GET /drafts/workspace/{id}/tree/diff`

**结构**与 B4.1 相同：

```java
// Controller
@GetMapping("/workspace/{id}/tree/diff")
public ApiResponse<DraftTreeDiffDto> getWorkspaceTreeDiff(@PathVariable Long id) {
    return ApiResponse.success(draftService.getWorkspaceTreeDiff(id));
}

// Service
public DraftTreeDiffDto getWorkspaceTreeDiff(Long workspaceId) {
    // 1. 加载本任务 workspace + 草稿
    DraftWorkspace ws = workspaceMapper.selectById(workspaceId);
    // 2. 如果有 baseline_workspace_id，加载基线草稿
    // 3. 按 moduleName 对比：
    //    - current 有 + baseline 无 = 本次新增
    //    - current 有 + baseline 有 = 基线继承（或本次修改）
    //    - baseline 有 + current 无 = 本次删除
    // ...
}
```

---

## 四、前端最小改动

### F1: 三个 Workspace 加 `task.type` 判断 + Segmented 切换

**通用模板**（所有 3 个 Workspace 通用）：

```tsx
// 在 useEffect 加载 task 后
const isIncremental = task?.type === 'INCREMENTAL';

const [viewMode, setViewMode] = useState<'full' | 'diff'>(
    isIncremental ? 'diff' : 'full'  // INITIAL 任务默认全量；INCREMENTAL 任务默认 diff
);
```

### F2: 顶部加 Segmented 切换控件

**通用组件**（在 3 个页面都加）：

```tsx
import { Segmented, Tag, Space } from 'antd';

<Space style={{ marginBottom: 16 }}>
    <Segmented
        options={[
            { label: '全量视图', value: 'full' },
            { label: 'DIFF 视图', value: 'diff', disabled: !isIncremental },
        ]}
        value={viewMode}
        onChange={setViewMode}
    />
    {isIncremental && diffSummary && (
        <Space>
            <Tag color="geekblue">+{diffSummary.newCount} 新增</Tag>
            <Tag color="green">={diffSummary.inheritedCount} 继承</Tag>
            <Tag color="orange">~{diffSummary.modifiedCount} 重提炼</Tag>
            <Tag color="red">-{diffSummary.deletedCount} 删除</Tag>
        </Space>
    )}
    {!isIncremental && <Tag color="default">INITIAL 任务仅支持全量视图</Tag>}
</Space>
```

### F3: 入口复核页（`EntrypointReviewWorkspace.tsx`）

**类型扩展**（[`frontend/src/types/index.ts`](frontend/src/types/index.ts)）：

```typescript
interface EntrypointReviewItem {
    // ... 现有字段 ...
    baselineTaskId?: number;  // ← 新增
}
```

**渲染逻辑**：

```tsx
// viewMode === 'full'：原样展示
// viewMode === 'diff'：按 4 种分类分组

const diffGrouped = useMemo(() => {
    if (viewMode === 'full' || !isIncremental || !diffData) {
        return null;
    }
    return {
        new: diffData.newRows || [],
        inherited: diffData.inheritedRows || [],
        deleted: diffData.deletedRows || [],
    };
}, [viewMode, isIncremental, diffData]);

return (
    <Card>
        <Space>
            <Segmented {...} />
            {diffSummary && <DiffSummaryTag summary={diffSummary} />}
        </Space>
        {diffGrouped ? (
            <Tabs items={[
                { label: `本次新增 (${diffGrouped.new.length})`, key: 'new', children: <List items={diffGrouped.new} /> },
                { label: `基线继承 (${diffGrouped.inherited.length})`, key: 'inherited', children: <List items={diffGrouped.inherited} /> },
                { label: `本次删除 (${diffGrouped.deleted.length})`, key: 'deleted', children: <List items={diffGrouped.deleted} /> },
            ]} />
        ) : (
            <List items={items} />  // INITIAL 或全量视图：原样展示
        )}
    </Card>
);
```

**数据获取**（`useEffect` 中）：

```tsx
useEffect(() => {
    if (isIncremental) {
        // INCREMENTAL 任务加载 diff
        getEntrypointDiff(taskId).then(setDiffData);
    } else {
        // INITIAL 任务加载全量
        getEntrypointReview(taskId).then(setItems);
    }
}, [taskId, isIncremental]);
```

### F4: 模块层级复核页（`HierarchyReviewWorkspace.tsx`）

**类型扩展**：

```typescript
interface ModuleNode {
    // ... 现有字段 ...
    sourceEntryClass?: string;
}
interface FunctionNode {
    // ... 现有字段 ...
    sourceEntryClass?: string;
}
```

**`ModuleHierarchyEditor` 组件内渲染 diff Tag**（注意：Workspace 把渲染委托给了 `ModuleHierarchyEditor`）：

```tsx
const renderFunctionNode = (fn: FunctionNode) => {
    const diffTag = !isIncremental ? null
        : fn.sourceEntryClass ? { color: 'orange', label: 'AI 重提炼' }
        : { color: 'green', label: '基线继承' };
    return (
        <div>
            {diffTag && <Tag color={diffTag.color}>{diffTag.label}</Tag>}
            {fn.functionName}
        </div>
    );
};
```

**MODULE / SUB_MODULE 级也类似**：

```tsx
const renderModuleNode = (mod: ModuleNode) => {
    const diffTag = !isIncremental ? null
        : mod.sourceEntryClass ? { color: 'orange', label: 'AI 重提炼' }
        : { color: 'green', label: '基线继承' };
    return (
        <div>
            {diffTag && <Tag color={diffTag.color}>{diffTag.label}</Tag>}
            {mod.moduleName}
        </div>
    );
};
```

### F5: 知识复核页（`workspace.tsx`）

**类型扩展**（[`frontend/src/api/draft.ts`](frontend/src/api/draft.ts)）：

```typescript
interface DraftTreeNode {
    // ... 现有字段 ...
    baselineTaskId?: number;
}
```

**左侧目录树 diff Tag 渲染**：

```tsx
const renderTreeNode = (node: DraftTreeNode) => {
    const diffTag = !isIncremental ? null
        : node.baselineTaskId ? { color: 'green', label: '基线继承' }
        : { color: 'geekblue', label: '本次新增' };
    return (
        <TreeNode title={
            <Space>
                {diffTag && <Tag color={diffTag.color}>{diffTag.label}</Tag>}
                {node.moduleName}
            </Space>
        }>
            {/* children */}
        </TreeNode>
    );
};
```

### F6: "本次删除" Tab（在 3 个页面复用 B4 数据）

如果 B4 端点返回了 `deletedRows`，前端用 antd `Tabs` 组件展示：

```tsx
<Tabs
    items={[
        { label: <><Tag color="geekblue">+{diff.new.length}</Tag>本次新增</>, key: 'new' },
        { label: <><Tag color="green">={diff.inherited.length}</Tag>基线继承</>, key: 'inherited' },
        { label: <><Tag color="red">-{diff.deleted.length}</Tag>本次删除</>, key: 'deleted' },
    ]}
/>
```

---

## 五、INITIAL 任务 vs INCREMENTAL 任务的 UI 行为对比

| 场景 | INITIAL 任务 | INCREMENTAL 任务 |
|---|---|---|
| 顶部 Segmented | "全量视图" 可用，"DIFF 视图" **禁用** | "全量视图" + "DIFF 视图" 都可用 |
| 默认视图 | 全量 | **DIFF** |
| 入口列表 | 全量展示，无 Tag | 标 "本次新增 / 基线继承 / 本次删除" Tag |
| 模块树 | 全量展示，无 Tag | 节点标 "基线继承 / AI 重提炼" Tag |
| 草稿树 | 全量展示，无 Tag | 草稿标 "本次新增 / 基线继承" Tag |
| "本次删除" Tab | 无 | 有（依赖 B4 端点） |
| 统计信息 | 总数 | "+N 新增 / =M 继承 / ~K 重提炼 / -J 删除" |

---

## 六、关键文件清单

### 后端修改

| 文件 | 改动 | 行数 |
|---|---|---|
| `EntrypointReviewView.java` | 加 `baselineTaskId` 字段 | +5 |
| `EntrypointReviewServiceImpl.java` | `listByTaskId` 映射 `baselineTaskId`；新增 `getEntrypointDiff` | +50 |
| `EntrypointDiffDto.java` | 新增 DTO（newRows / inheritedRows / deletedRows） | +20 |
| `ModuleDto.java` | 加 `sourceEntryClass` 字段 | +5 |
| `FunctionDto.java` | 加 `sourceEntryClass` 字段 | +5 |
| `ModuleHierarchyServiceImpl.java` | `loadByTaskId` 映射 `sourceEntryClass`；新增 `getHierarchyDiff` | +80 |
| `ModuleHierarchyDiffDto.java` | 新增 DTO | +20 |
| `DraftTreeNode.java` | 加 `baselineTaskId` 字段 + `fromDraft` 传递 | +5 |
| `DraftServiceImpl.java` | `getWorkspaceTree` 映射 `baselineTaskId`；新增 `getWorkspaceTreeDiff` | +60 |
| `DraftTreeDiffDto.java` | 新增 DTO | +20 |
| `DecompileTaskController.java` | 新增 `/entrypoints/diff` 端点 | +10 |
| `ModuleHierarchyService` 接口 | 新增 `getHierarchyDiff` | +3 |
| `EntrypointReviewService` 接口 | 新增 `getEntrypointDiff` | +3 |
| `DraftService` 接口 | 新增 `getWorkspaceTreeDiff` | +3 |
| **后端合计** | | **~289 行** |

### 前端修改

| 文件 | 改动 | 行数 |
|---|---|---|
| `types/index.ts` | `EntrypointReviewItem.baselineTaskId` + `FunctionNode.sourceEntryClass` | +3 |
| `api/draft.ts` | `DraftTreeNode.baselineTaskId` | +1 |
| `api/task.ts` | `getEntrypointDiff` / `getModuleHierarchyDiff` / `getWorkspaceTreeDiff` | +30 |
| `EntrypointReviewWorkspace.tsx` | Segmented + diff Tag 渲染 + diff Tab | ~80 |
| `HierarchyReviewWorkspace.tsx` | Segmented + diff Tag（在 ModuleHierarchyEditor 内） | ~50 |
| `workspace.tsx`（草稿） | Segmented + 草稿 diff Tag | ~50 |
| **前端合计** | | **~214 行** |

### 总量

| 项 | 行数 |
|---|---|
| 后端（含 B1+B2+B3+B4） | ~289 行 |
| 前端 | ~214 行 |
| **合计** | **~503 行** |

---

## 七、复用与新增

### 复用现有（来自 INCREMENTAL 方案）

| 现有字段 / 功能 | 用途 |
|---|---|
| `ci_entrypoint.baseline_task_id` | 区分"本次新增" / "基线继承" |
| `ci_module_hierarchy.source_entry_class` | 区分"AI 重提炼" / "基线继承" |
| `ci_knowledge_draft.baseline_task_id` | 区分草稿"本次新增" / "基线继承" |
| `IncrementalContext.baselineTaskId` | 用于"本次删除"diff 计算 |
| `BaselineInheritanceService` | 复用基线数据访问（getWorkspaceTree 等） |

### 新增

| 项 | 用途 |
|---|---|
| B1-B3: 3 个 DTO 加字段 | 透传已有数据到前端 |
| B4: 3 个 `/diff` 端点 | 提供"本次删除"数据 |
| 3 个前端 Segmented | 切换全量/diff 视图 |
| 4 种 diff Tag 颜色 | 可视化分类 |

---

## 八、关键依赖关系

```
本方案强依赖：
  ├─ incremental-baseline-design.md（数据层字段 + Service）
  │   ├─ Phase 1: Schema 改造（已完成）
  │   ├─ Phase 2: 基线继承工具（已完成）
  │   ├─ Phase 3: 5 阶段改造（已完成大部分）
  │   └─ Phase 5b: 端到端验证（待本地 PG）
  │
  └─ module-hierarchy-design.md（AI 提取准确性）
      └─ 已实施（Phase 1+2+3 完成）
```

**实施顺序**：INCREMENTAL 方案数据层（已完成）→ 本方案 B1-B3（DTO 透传）→ B4（diff 端点）→ 前端展示。

---

## 九、风险与缓解

| 风险 | 缓解 |
|---|---|
| INITIAL 任务被错误展示为 diff | 默认 `viewMode='full'`（INITIAL）+ Segmented 禁用 diff 选项 + Tag 渲染条件判断（`isIncremental` 守卫） |
| DTO 字段缺失导致前端报错 | 后端 DTO 加字段向后兼容；前端 TS 类型加可选字段 |
| "修改"识别不精确 | 仅用 `sourceEntryClass` 标识"AI 重提炼过"；Tag 文字用 "AI 重提炼" 而非 "修改"；不假装精确 diff |
| B4 端点性能 | 3 张表按 `task_id` 索引；差集计算 O(N) 单次 SQL；INCREMENTAL 任务下 N 较小（< 100） |
| 模块层级页面委托给 `ModuleHierarchyEditor` | 需在 `ModuleHierarchyEditor` 组件内实现 diff Tag，而不是 Workspace 组件 |
| 草稿页面的 `getWorkspaceTree` 已合并基线 + 本次 | 新增 `getWorkspaceTreeDiff` 端点返回"基线 - 本次"差集，不破坏现有合并逻辑 |

---

## 十、实施步骤

| Phase | 内容 | 工作量 |
|---|---|---|
| **1** | 后端 B1+B2+B3（3 个 DTO 加字段 + 3 个 service 映射） | 半天 |
| **2** | 后端 B4（3 个 `/diff` 端点 + 3 个 service diff 计算） | 1-2 天 |
| **3** | 前端 3 个类型扩展 + 3 个 Workspace 加 Segmented + diff Tag 渲染 + 4 种分类 Tab | 1-2 天 |
| **4** | 编译验证 + 端到端测试（INITIAL 任务 / INCREMENTAL 任务各 1 跑） | 半天 |
| **总计** | | **3-4 天** |

---

## 十一、验证

### 编译

```bash
cd backend && mvn -DskipTests compile
cd ../frontend && npm run build
```

### 端到端验证

| 场景 | 预期 |
|---|---|
| 1. INITIAL 任务入口复核 | 全量视图可用，DIFF 视图禁用，无 Tag |
| 2. INCREMENTAL 任务入口复核 | DIFF 视图默认开启，3 个 Tab：本次新增 / 基线继承 / 本次删除 |
| 3. INCREMENTAL 任务模块层级 | FUNCTION 节点标 "基线继承" / "AI 重提炼" |
| 4. INCREMENTAL 任务知识复核 | 草稿标 "本次新增" / "基线继承"；基线继承的草稿内容来自基线 workspace |
| 5. Segmented 切换 | INITIAL 任务切换不响应；INCREMENTAL 任务切换流畅 |
| 6. 删除项展示 | INCREMENTAL 任务的"本次删除"Tab 显示基线有 + 本次无的入口 |

---

## 十二、一句话总结

> **INITIAL 任务保持现状（Segmented 禁用 diff）；INCREMENTAL 任务展示为 diff 视图（4 种分类：本次新增 / 基线继承 / AI 重提炼 / 本次删除）。后端透传 DTO 字段 + 新增 3 个 `/diff` 端点；前端加 Segmented + diff Tag 渲染。强依赖 INCREMENTAL 方案的数据层基础。**
