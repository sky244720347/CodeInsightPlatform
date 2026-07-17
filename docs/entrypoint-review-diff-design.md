# 入口复核 DIFF 视图方案

> 范围：只做 ENTRYPOINT_REVIEW 页面的 4 桶 git 风格 DIFF。  
> **同签名「内容变更」（bodyHash / `~`）** 已拆独立方案：[entrypoint-method-content-diff-design.md](./entrypoint-method-content-diff-design.md)。  
> 前置依赖：① INCREMENTAL 方案（数据层）+ ② Phase 4 方案（后端 diff 端点 + 前端基础）。

---

## 一、展示效果

### 展示顺序（有改动在前，基线折叠在后）

```
Segmented: [全量视图] [DIFF 视图]

diff 统计条：+2新增  ~1变更  -1删除  =47基线

━━━ 本次新增 (2) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
┃ blue │ [+] UserController
┃      │   ├─   GET /users                         ← 新增类，方法不加前缀
┃      │   └─   POST /users

━━━ 本次变更 - 类不变但方法有变化 (1) ━━━━━━━━━━━━━━━
┃orange│ [~] OrderController
┃      │   ├─   GET /orders                        ← 不变方法，无标识
┃      │   ├─ + PUT /orders/{id}                   ← 新增方法，蓝色 + 前缀
┃      │   └─ - DELETE /orders/{id}                ← 删除方法，红色 - 前缀 + 删除线

━━━ 本次删除 (1) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
┃ red  │ [-] OldController                          ← 删除类 + 所有方法划删除线
┃      │   ├─ - GET /old
┃      │   └─ - POST /old

━━━ 基线继承 (47) ▸ 展开 ━━━━━━━━━━━━━━━━━━━━━━━━━
┃ gray │   ProductController                        ← 全不变
```

### 行级颜色

| diff 类型 | 左边框 | 背景色 | Badge | 类名样式 |
|---|---|---|---|---|
| **新增** | 3px solid #1890ff | #e6f7ff | `[+]` 蓝色 | 正常 |
| **变更** | 3px solid #fa8c16 | #fff7e6 | `[~]` 橙色 | 正常 |
| **删除** | 3px solid #ff4d4f | #fff1f0 | `[-]` 红色 | 删除线 |
| **继承** | 3px solid #52c41a | #f6ffed | 无 | 正常 |

### 方法级前缀

| 方法 diff | 前缀 | 样式 |
|---|---|---|
| 新增 | `+` | 蓝色，粗体 |
| **内容变更**（同签名、方法体变） | `~` | 橙色 |
| 不变 | 无 | 正常 |
| 删除 | `-` | 红色，删除线 |

### 折叠行为

- **DIFF 视图默认**：新增 / 变更 / 删除 3 组展开；基线继承组折叠
- **全量视图**：全部展开，灰色统一背景，无 diff 样式

---

## 二、依赖的 bug 修复

| # | bug | 原因 | 修复 | 文件 |
|---|---|---|---|---|
| 1 | DIFF 数据永远不加载 | `useEffect` 依赖 `[taskId]`，判断 `task?.type` 时 task 是 null | 加 `task?.type` 到依赖数组 | `EntrypointReviewWorkspace.tsx` |
| 4 | **方法级 diffStatus 不生效**（new / unchanged / deleted 全部不渲染） | `getEntrypointDiff` 里 `currentMethods` 和 `view.getMethods()` 是**两份反序列化结果**——`setDiffStatus` 设到 currentMethods，view 里的另一份仍是 null | 改用 `currentMethods` 作为 view.methods；`toReviewView` 调用移到方法 diff 设置**之前**或绕开 `toReviewView` 内部 `deserializeMethods`，直接构造 view | `EntrypointReviewServiceImpl.getEntrypointDiff` |
| 2 | `getEntrypointDiff` 动态 import 不可读 | `import('../../api/task').then(...)` | 改为静态 import | 同上 |
| 3 | **re-discover 的入口全被标为"新增"** | `discoverAndPersist` INCREMENTAL 分支里 `row.setBaselineTaskId(null)`——基线已有的类被重新识别后 baselineTaskId 丢了 | 插入前查基线 entrypoint 表：className 在基线里出现过 → 设 `baselineTaskId = baselineTaskId` | `EntrypointReviewServiceImpl.discoverAndPersist` |

### Bug 3 详情

```
T1 INCREMENTAL 流程（ProductController 文件被改了）：
  1) inheritFromBaseline → ProductController 从 T0 继承（baseline_task_id = T0_id）
  2) delete by file_path IN changedPaths → ProductController 被删
  3) discoverEntriesInFiles(changedPaths) → 重新识别
  4) INSERT row.setBaselineTaskId(null) ← BUG！
  5) getEntrypointDiff 看到 baseline_task_id == null → "新增"（蓝色）
```

**修复**：步骤 4 插入前查基线 entrypoint 表——className 在基线里出现过 → 设 `baselineTaskId = effective.getBaselineTaskId()`，否则 null。

**修复后效果**：
- ProductController 的 `baseline_task_id = T0_id`（非 null）
- `getEntrypointDiff` 进入方法对比分支
- 方法有变化 → "变更"（橙色）；方法没变 → "基线继承"（绿色）

---

## 三、后端改动

### 3.1 `EntrypointDiffDto` 加 `modifiedRows`

**文件**：`backend/src/main/java/com/company/codeinsight/modules/entrypoint/dto/EntrypointDiffDto.java`

```java
@Data
public class EntrypointDiffDto {
    private List<EntrypointReviewView> newRows;        // 本次新增（类级）
    private List<EntrypointReviewView> modifiedRows;   // 类不变 + 方法有变化 ← 新增
    private List<EntrypointReviewView> inheritedRows;  // 类+方法全不变
    private List<EntrypointReviewView> deletedRows;    // 本次删除（类级）
}
```

### 3.2 `EntrypointMethodView` 加 `diffStatus`

**文件**：`backend/src/main/java/com/company/codeinsight/modules/entrypoint/model/EntrypointMethodView.java`

```java
@Data
public class EntrypointMethodView {
    // ... 现有字段 ...
    /** v1 方法级 diff（仅 INCREMENTAL 任务有值）：new / unchanged / deleted */
    private String diffStatus;
}
```

### 3.3 `getEntrypointDiff` 加方法对比

**文件**：`EntrypointReviewServiceImpl.java`

```java
// 在 getEntrypointDiff() 中对 baselineTaskId != null 的行拆分为 modified / inherited：

Map<String, EntrypointEntity> baselineByName = baselineRows.stream()
    .collect(Collectors.toMap(EntrypointEntity::getClassName, r -> r));

for (EntrypointEntity row : currentRows) {
    if (row.getBaselineTaskId() == null) {
        newRows.add(toView(row));
        continue;
    }
    // 类不变，对比方法列表
    EntrypointEntity baselineRow = baselineByName.get(row.getClassName());
    List<EntrypointMethodView> currentMethods = deserializeMethods(row.getMethodsJson());
    List<EntrypointMethodView> baselineMethods = baselineRow != null
        ? deserializeMethods(baselineRow.getMethodsJson()) : List.of();
    
    Set<String> baselineSigs = baselineMethods.stream()
        .map(m -> m.getMethodSignature()).filter(Objects::nonNull).collect(toSet());
    Set<String> currentSigs = currentMethods.stream()
        .map(m -> m.getMethodSignature()).filter(Objects::nonNull).collect(toSet());
    
    boolean hasChanges = false;
    for (EntrypointMethodView m : currentMethods) {
        if (m.getMethodSignature() != null && !baselineSigs.contains(m.getMethodSignature())) {
            m.setDiffStatus("new"); hasChanges = true;
        } else {
            m.setDiffStatus("unchanged");
        }
    }
    // 基线有 + 本次无 = deleted（从 baselineMethods 中找）
    EntrypointReviewView view = toView(row);
    if (baselineRow != null) {
        List<EntrypointMethodView> deletedMethods = baselineMethods.stream()
            .filter(m -> !currentSigs.contains(m.getMethodSignature()))
            .peek(m -> m.setDiffStatus("deleted"))
            .collect(toList());
        view.getMethods().addAll(deletedMethods);  // deleted 方法也加入方法列表
    }
    if (hasChanges || !deletedMethods.isEmpty()) {
        modifiedRows.add(view);
    } else {
        inheritedRows.add(view);
    }
}
```

**工作量**：~60 行。

---

## 四、前端改动

**只改 1 个文件**：[`EntrypointReviewWorkspace.tsx`](frontend/src/pages/tasks/EntrypointReviewWorkspace.tsx)

### 4.1 修复 useEffect + 静态 import

```tsx
// 静态 import（替代动态 import）
import { getEntrypointDiff, getEntrypointReview, ... } from '../../api/task';

// useEffect 加 task?.type 依赖
useEffect(() => {
    ...
}, [taskId, task?.type]);  // ← 修复：task 加载后重新判定 INCREMENTAL
```

### 4.2 diff 分类 useMemo

```tsx
const diffClassified = useMemo(() => {
    if (!diffData || displayMode !== 'full' /* 仅 DIFF 模式生效 */) return null;
    return {
        newItems: diffData.newRows || [],
        modifiedItems: diffData.modifiedRows || [],
        deletedItems: diffData.deletedRows || [],
        inheritedItems: diffData.inheritedRows || [],
    };
}, [diffData, displayMode]);
```

### 4.3 DIFF 模式渲染

替换现有 `{visibleItems.map(...)}` 中的灰色块渲染，DIFF 模式下走 git 风格：

```tsx
const diffColorMap = {
    new:      { border: '3px solid #1890ff', bg: '#e6f7ff', badge: '[+]', badgeColor: 'blue' },
    modified: { border: '3px solid #fa8c16', bg: '#fff7e6', badge: '[~]', badgeColor: 'orange' },
    deleted:  { border: '3px solid #ff4d4f', bg: '#fff1f0', badge: '[-]', badgeColor: 'red' },
    inherited:{ border: '3px solid #52c41a', bg: '#f6ffed', badge: null },
};

// DIFF 模式：分组渲染
{displayMode === 'diff' && diffClassified ? (
    <>
        {['new','modified','deleted','inherited'].map(group => {
            const items = diffClassified[group + 'Items'];
            if (!items.length) return null;
            const collapsed = group === 'inherited';  // 基线默认折叠
            const style = diffColorMap[group];
            return (
                <CollapseSection key={group}
                    title={`${GROUP_LABELS[group]} (${items.length})`}
                    defaultCollapsed={collapsed}>
                    {items.map(it => (
                        <div style={{ borderLeft: style.border, background: style.bg, ... }}>
                            {style.badge && <Tag color={style.badgeColor}>{style.badge}</Tag>}
                            <Text strong delete={group==='deleted'}>{it.className}</Text>
                            {/* 方法列表：diff 前缀 + 颜色 */}
                            {it.methods?.map(m => (
                                <div style={methodDiffStyle(m.diffStatus)}>
                                    {m.diffStatus === 'new' && <Text type="success">+ </Text>}
                                    {m.diffStatus === 'deleted' && <Text type="danger" delete>- </Text>}
                                    {m.methodName}
                                </div>
                            ))}
                        </div>
                    ))}
                </CollapseSection>
            );
        })}
    </>
) : (
    /* 全量视图：原样渲染，不加任何 diff 样式 */
    visibleItems.map(it => (
        <div style={{ border: '1px solid #f0f0f0', background: '#fafafa', ... }}>
            {/* 与今天完全一致 */}
        </div>
    ))
)}
```

**工作量**：~90 行（列表视图）。

### 4.4 树形视图 diff 渲染

**问题**：树形视图（`viewMode === 'tree'`）不区分全量/diff，统一灰色 Tree，无增删改标记。

**修复**：新增 `diffTreeData` useMemo——DIFF 模式下按 4 类重构树：

```
本次新增 (2)
  └─ Controller (1)
       └─ + UserController              ← 类级蓝色 [+] Badge
本次变更 (1)  
  └─ Controller (1)
       └─ ~ ProductController            ← 类级橙色 [~] Badge
            ├─     GET /products          ← 不变方法
            ├─ +   PUT /products/{id}     ← 新增方法蓝色 + 前缀
            └─ -   DELETE /products/{id}  ← 删除方法红色 - 前缀 + 删除线
本次删除 (1)
  └─ Controller (1)
       └─ - OldController                ← 类级红色 [-] Badge + 删除线
基线继承 (47) ▸ 折叠
  └─ Controller (45)
       └─ ProductController               ← 无标记
```

**代码**（新增 `diffTreeData` useMemo + 修改 Tree 渲染分支）：

```tsx
const diffTreeData = useMemo<DataNode[]>(() => {
    if (!diffData) return [];
    const isDiff = displayMode === 'diff';
    if (!isDiff) return [];
    return DIFF_GROUPS.filter(g => {
        const rows = (diffData as any)[g.key] as EntrypointReviewItem[];
        return rows && rows.length > 0;
    }).map(g => {
        const rows = (diffData as any)[g.key] as EntrypointReviewItem[];
        return {
            key: `diff-${g.key}`,
            title: <Space><Text strong style={{ color: g.color }}>{g.badge || ''} {g.label} ({rows.length})</Text></Space>,
            selectable: false,
            children: rows.map(cls => ({
                key: `diff-${g.key}-class-${cls.id}`,
                title: <Space size={4}>
                    {g.badge && <Tag color={...}>{g.badge}</Tag>}
                    <Text strong delete={g.key === 'deletedRows'}>{shortClassName(cls.className)}</Text>
                </Space>,
                children: (cls.methods || []).map((m, mi) => ({
                    key: `diff-${g.key}-m-${cls.id}-${mi}`, isLeaf: true,
                    title: <Space size={4}>
                        {m.diffStatus === 'new' && <Tag color="blue">+</Tag>}
                        {m.diffStatus === 'deleted' && <Tag color="red">-</Tag>}
                        <Text delete={m.diffStatus === 'deleted'} style={{ color: m.diffStatus === 'new' ? '#1890ff' : m.diffStatus === 'deleted' ? '#ff4d4f' : undefined, fontSize: 12 }}>
                            {m.methodSignature || m.methodName}
                        </Text>
                    </Space>,
                })),
            })),
        };
    });
}, [diffData, displayMode, canReview]);
```

**渲染分支改为**：

```tsx
viewMode === 'tree' ? (
    displayMode === 'diff' && diffData ? (
        <Tree treeData={diffTreeData} defaultExpandAll showLine={{ showLeafIcon: false }} blockNode />
    ) : (
        <Tree treeData={treeData} defaultExpandAll showLine={{ showLeafIcon: false }} blockNode />
    )
) : ...
```

**工作量**：~50 行。

---

## 五、全量视图（不改变）

```
━ 所有入口 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
┃ gray │ [Controller] UserController
┃ gray │ [Controller] OrderController
┃ gray │ [Controller] ProductController
... 全部平铺，无 diff Tag，灰色统一背景
```

**今天的全量视图完全不变**——用户切到 Segmented "全量视图"就是今天的样子。

---

## 六、改动清单

| 层 | 文件 | 改动 | 行数 |
|---|---|---|---|
| 后端 | `EntrypointDiffDto.java` | 加 `modifiedRows` 字段 | +5 |
| 后端 | `EntrypointMethodView.java` | 加 `diffStatus` 字段 | +5 |
| 后端 | `EntrypointReviewServiceImpl.java` | 改 `getEntrypointDiff` 加方法对比 | +60 |
| 后端 | `DecompileTaskController.java` | 无改动（端点已有） | 0 |
| 前端 | `EntrypointReviewWorkspace.tsx` | 修复 useEffect + 静态 import + git 渲染 | +120 |

---

## 七、验证

```bash
# 编译
cd backend && mvn -DskipTests compile
cd frontend && npm run build

# 端到端（需要本地 PG + Redis）
# 1. 跑一条 INCREMENTAL 任务
# 2. 入口复核页切到 DIFF 视图
# 3. 验证：新增/变更/删除/继承 4 组颜色区分
# 4. 验证：方法级 + / - 前缀
# 5. 验证：基线组默认折叠
```

---

## 八、一句话（初版）

> **INCREMENTAL 任务入口复核 DIFF 视图 = 类级 git 风格（彩色左边框 + 背景）+ 方法级 diff 前缀（+/-）+ 有改动在前 + 基线折叠。INITIAL 任务全量视图不变。**

---

## 九、方法「内容变更」（同签名、方法体变了）

> **已拆出独立方案**，细节与落地清单见：  
> **[entrypoint-method-content-diff-design.md](./entrypoint-method-content-diff-design.md)**  
> 下游模块合并如何消费 `modified`：见 [module-hierarchy-id-reuse-fix.md §七](./module-hierarchy-id-reuse-fix.md)。

摘要：`methods_json.bodyHash` + DIFF 时同签名 hash 不同 → `diffStatus=modified` → UI `~`；无 hash 降级 `unchanged`。
