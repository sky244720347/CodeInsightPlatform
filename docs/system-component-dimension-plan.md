# 「系统与仓库」新增「组件」维度方案

> **管什么**：在「系统与仓库」下为业务系统增加 **组件** 维度；以「系统 + 组件」作为业务身份做查重；对外隔离键仍是 **单个 `system_id`**。  
> **不管什么**：新建 `ci_component` 表 / `component_id` FK；跨组件共享业务知识、并发配额、统一父级分组树；仓库侧（`ci_repository`）结构变更。  
> **状态**：已落地（2026-07-22：schema / 查重 / 「系统与仓库」表单列表 / 其它页分字段展示与筛选 / DTO 独立 `component`）。  
> **设计原则**：最小改动 —— 把「系统+组件」编码成一条 `ci_system`，沿用现有 `system_id` 贯穿任务 / 并发 / 业务知识 / 前端筛选。API 与列表分字段展示；仅强校验提示语拼接。

---

## 〇、背景与结论

### 0.1 现状

| 项 | 现状 |
|---|---|
| 层级 | `ci_system` 1 → N `ci_repository`，无中间层 |
| 隔离根 | `system_id`（任务、并发闸门、业务知识、发布路径、多页筛选均以此为准） |
| 系统查重 | **无**（`createSystemDraft` 只校验 `name`/`owner` 非空） |
| 组件实体 | **无**（与模块层级 / Spring `@Component` 无关） |

### 0.2 选定方案（最小改动）

**「系统 + 组件」= 一条 `ci_system` = 一个 `system_id`。**

- 组件不是独立表，而是 `ci_system` 上的属性（新列 `component`）。
- 业务身份唯一键：`(name, component)`（仅未删除行）。
- 仓库、任务、队列、知识等 **继续只认 `system_id`**，不引入第二套外键。

### 0.3 明确代价（可接受）

每个「系统+组件」是独立隔离单元，天然：

- 各自一套 `maxConcurrentTasks`
- 各自一份业务知识（`ci_business_knowledge` 现 1:1 `system_id`）
- 列表里是多行，不能「点开一个系统看其下全部组件」的树形父级（本期不做）

若日后需要「一系统多组件共享策略 / 树形分组」，再单独立项做真实组件表 —— **本期不做**。

---

## 一、身份与展示约定

| 字段 | 含义 | 约束 |
|---|---|---|
| `name` | 系统标识（如 `order`） | 必填；建议英文/短码 |
| `component` | 组件标识（如 `billing`） | **可空**；空 =「无组件 / 默认组件」；有值时与 `name` 联合唯一 |
| `nameCn` | 展示用中文名 | 可选；可不参与查重 |
| `id` / `system_id` | 平台隔离主键 | 不变 |

**展示约定：**

- 列表 / 详情：**系统**与**组件**分字段 / 分列展示；组件空显示 `—`
- 下拉：`value` 仍为 `systemId`；选项用 `optionRender` 分开展示系统名与组件 Tag
- API：**禁止**返回 `name / component` 拼接串；`systemName` 仅为纯名称
- 强校验提示语（查重等）才拼接：`{name} / {component}`

**查重规则：**

- 同一 `(name, component)` 在 `is_deleted = 0` 下禁止重复创建 / 更新撞车。
- `component` 为空时归一为 `''`（空串），避免 `NULL` 在唯一索引上的多行歧义。
- 软删后再建同名同组件：**允许**（与现有软删语义一致）。

---

## 二、数据模型

### 2.1 Schema（幂等，改 `schema.sql`）

```sql
ALTER TABLE ci_system ADD COLUMN IF NOT EXISTS component VARCHAR(100) NOT NULL DEFAULT '';
COMMENT ON COLUMN ci_system.component IS '组件标识；与 name 联合构成业务身份；空串表示无组件';

-- 未删除行上 (name, component) 唯一
CREATE UNIQUE INDEX IF NOT EXISTS uk_system_name_component_active
    ON ci_system (name, component)
    WHERE is_deleted = 0;
```

说明：

- 存量行 `DEFAULT ''` 自动填空串，行为与「只有系统、无组件」一致。
- 不改 `ci_repository` / `ci_task` 等任何带 `system_id` 的表。

### 2.2 Entity / API

| 层 | 改动 |
|---|---|
| `SystemApplication` | 新增 `component` 字段 |
| 创建 / 更新 DTO 或实体入参 | 接收 `component`；空 / blank → 归一 `''` |
| 列表 / 详情 / 聚合响应 | 带回独立 `component`；**不**派生 `displayLabel` / 拼接 `systemName` |

---

## 三、后端行为

### 3.1 查重（新建 + 更新必做）

在 `SystemApplicationServiceImpl`：

1. `normalizeComponent(raw)`：`null` / blank → `""`；trim。
2. `assertUniqueNameComponent(name, component, excludeId)`：查 `is_deleted=0` 且 `(name, component)` 命中且 `id != excludeId` → `BusinessException`（文案可拼接：`name / component`）。
3. `createSystemDraft`：校验 `name`/`owner` 后 → normalize → assertUnique → insert。
4. `updateSystemBasicInfo`：同路径；允许改 `component`，但撞唯一则失败。

DB 唯一索引作最终兜底；服务层校验给前端可读错误。

### 3.2 列表筛选

`listSystemsPage` / `listSystemsWithSummary` 支持可选 `component` 模糊过滤（与 `name` 模糊、`owner` 精确并列）。

### 3.3 明确不改

| 模块 | 原因 |
|---|---|
| `TaskConcurrencyLimiter` | 仍按 `system_id` → `maxConcurrentTasks` |
| 任务创建 / 队列 / 知识 / 推送 / 日志筛选 | 仍传 `systemId`（不新增 component 筛选参数） |
| `ci_repository` 创建逻辑 | 仍挂在所选系统（即某一「系统+组件」行）下 |
| 存储路径 `releases/{systemId}/...` | 不变 |

---

## 四、前端改动细节（已落地）

> 查重错误由后端 `BusinessException` 经 `request` 拦截器抛出，前端用既有 `message` / 控制台处理，**未单独做前端预查重**。  
> 列表与下拉：**系统**、**组件**分字段展示；仅删除确认等强提示可拼接。

### 4.1 类型与 API 包装

| 文件 | 改动 |
|---|---|
| `frontend/src/types/index.ts` | `System.component?`；`KnowledgeBrowseItem.component?`；`KnowledgeBrowseTreeResult.component?` |
| `frontend/src/api/system.ts` | `listSystems` 增加可选 `component?: string` |
| `frontend/src/api/draft.ts` | `PreviewSystemDto.component?` |
| `frontend/src/api/dashboard.ts` | `SystemCoverageItem.component?` |
| `frontend/src/api/knowledge-query.ts` | `KnowledgeContextView.component?` |
| `frontend/src/api/mock/drafts.mock.ts` | mock `PreviewSystemDto` 补 `component` |

### 4.2 「系统与仓库」页

| 文件 | 改动 |
|---|---|
| `frontend/src/pages/systems/SystemWizardModal.tsx` | Step1「组件」表单项；提交 `trim\|\|''`；`setSystemName` 仅纯 `name` |
| `frontend/src/pages/systems/SystemFormModal.tsx` | 编辑「组件」表单项 |
| `frontend/src/pages/systems/index.tsx` | 编辑回填 / 提交带 `component`；FilterBar 透传 `searchComponent` |
| `frontend/src/pages/systems/columns.tsx` | 「系统」列仅 `name`；「组件」列 Tag；删除确认可拼接 |
| `frontend/src/pages/systems/SystemFilterBar.tsx` | 增加「搜索组件」输入 |
| `frontend/src/pages/systems/hooks.ts` | `searchComponent` 状态并传给 `listSystems` |

### 4.3 共享下拉 / 单元格

| 文件 | 改动 |
|---|---|
| `frontend/src/utils/systemSelect.tsx` | **新增**：`toSystemSelectOptions` / `renderSystemSelectOption` / `renderSystemSelectLabel` / `filterSystemSelectOption` / `renderComponentCell` |

### 4.4 其它页：系统下拉 + 列表「组件」列

| 文件 | 改动 |
|---|---|
| `frontend/src/pages/tasks/TaskListTab.tsx` | 系统 Select 走 helper；列表增「组件」列 |
| `frontend/src/pages/tasks/queue.tsx` | 同上 |
| `frontend/src/pages/tasks/dispatch.tsx` | 系统 Select 走 helper |
| `frontend/src/pages/tasks/entrypoint-review.tsx` | Select + 列表「组件」列 |
| `frontend/src/pages/tasks/hierarchy-review.tsx` | Select + 列表「组件」列 |
| `frontend/src/pages/drafts/index.tsx` | Select + 列表「组件」列 |
| `frontend/src/pages/push/index.tsx` | 系统 Select 走 helper |
| `frontend/src/pages/knowledge/KnowledgeContextBar.tsx` | 系统 Select 走 helper（入口/层级页共用） |
| `frontend/src/pages/knowledge/index.tsx` | 系统 Select；列表「组件」列；树预览带 `component` |
| `frontend/src/pages/logs/index.tsx` | 系统 Select 走 helper |
| `frontend/src/pages/token-audit/index.tsx` | 系统 Select 走 helper |
| `frontend/src/pages/dashboard/ai-usage.tsx` | 系统 Select 走 helper |
| `frontend/src/pages/dashboard/system-coverage.tsx` | 明细表增「组件」列 |
| `frontend/src/pages/basic/orchestration.tsx` | `sysMap`/`componentMap` 按 repositoryId；列表增系统/组件列 |
| `frontend/src/pages/basic/ScanWindowHeatmap.tsx` | 抽屉表增「组件」列；`componentMap` prop |

---

## 五、改动文件清单（完整）

> 路径相对仓库根；状态均为 **已做**。不含 `backend/target/**` 等构建产物。

### 5.1 文档

| 文件 | 内容 |
|---|---|
| `docs/system-component-dimension-plan.md` | 本方案（含完整文件清单与展示约定修订） |

### 5.2 后端 · Schema

| 文件 | 内容 |
|---|---|
| `backend/src/main/resources/db/schema.sql` | `ci_system.component` 列 + 部分唯一索引 `uk_system_name_component_active` |
| `backend/src/main/resources/db/schema-fresh.sql` | 同上（全新库建表定义） |

### 5.3 后端 · 系统模块（字段 / 查重 / 列表筛选）

| 文件 | 内容 |
|---|---|
| `backend/src/main/java/com/company/codeinsight/modules/system/entity/SystemApplication.java` | 实体字段 `component` |
| `backend/src/main/java/com/company/codeinsight/modules/system/service/SystemApplicationService.java` | `listSystemsPage(..., component, ...)`；创建/更新查重契约注释 |
| `backend/src/main/java/com/company/codeinsight/modules/system/service/impl/SystemApplicationServiceImpl.java` | normalize + assertUnique；列表传 `component`；查重错误文案可拼接 |
| `backend/src/main/java/com/company/codeinsight/modules/system/controller/SystemApplicationController.java` | `GET /systems` 增加 `component` 请求参数 |
| `backend/src/main/java/com/company/codeinsight/modules/system/mapper/SystemApplicationMapper.java` | `listSystemsWithSummary` 增加 `@Param("component")` |
| `backend/src/main/resources/mapper/SystemApplicationMapper.xml` | SQL：`s.component LIKE` 模糊条件 |
| `backend/src/main/java/com/company/codeinsight/modules/system/vo/SystemSummaryVO.java` | **未改文件**：继承 `SystemApplication`，列表响应 naturally 含 `component` |

### 5.4 后端 · 其它模块 DTO / 赋值（分字段，不拼接）

| 文件 | 内容 |
|---|---|
| `backend/src/main/java/com/company/codeinsight/modules/draft/dto/PreviewSystemDto.java` | 新增 `component` |
| `backend/src/main/java/com/company/codeinsight/modules/draft/service/impl/DraftServiceImpl.java` | `setComponent(sys.getComponent())`；`systemName` 仍为纯 `name` |
| `backend/src/main/java/com/company/codeinsight/modules/knowledge/browse/dto/KnowledgeBrowseItem.java` | 新增 `component` |
| `backend/src/main/java/com/company/codeinsight/modules/knowledge/browse/dto/KnowledgeBrowseTreeResult.java` | 新增 `component` |
| `backend/src/main/java/com/company/codeinsight/modules/knowledge/browse/KnowledgeBrowseServiceImpl.java` | 填充 `component`；keyword 可匹配 component |
| `backend/src/main/java/com/company/codeinsight/modules/knowledge/browse/KnowledgeBrowseTreeService.java` | `setComponent`；`formatSystemName` 仍只返回 name/nameCn |
| `backend/src/main/java/com/company/codeinsight/modules/knowledge/query/dto/KnowledgeContextView.java` | 新增 `component` |
| `backend/src/main/java/com/company/codeinsight/modules/knowledge/query/service/KnowledgeQueryServiceImpl.java` | `setComponent`；`formatSystemName` 不拼接 |
| `backend/src/main/java/com/company/codeinsight/modules/dashboard/DashboardServiceImpl.java` | 覆盖率条目 `entry.put("component", ...)` |

### 5.5 后端 · 测试

| 文件 | 内容 |
|---|---|
| `backend/src/test/java/com/company/codeinsight/modules/system/SystemApplicationNameComponentUniqueTest.java` | name+component 查重 / 空串归一 / 更新撞车 |
| `backend/src/test/java/com/company/codeinsight/modules/system/SystemApplicationServiceTests.java` | `listSystemsPage` 签名对齐（增加 `component` 参数位） |

### 5.6 前端 · 类型 / API / 工具

| 文件 | 内容 |
|---|---|
| `frontend/src/types/index.ts` | `System` / `KnowledgeBrowseItem` / `KnowledgeBrowseTreeResult` 的 `component?` |
| `frontend/src/api/system.ts` | `listSystems` 参数 `component?` |
| `frontend/src/api/draft.ts` | `PreviewSystemDto.component?` |
| `frontend/src/api/dashboard.ts` | `SystemCoverageItem.component?` |
| `frontend/src/api/knowledge-query.ts` | `KnowledgeContextView.component?` |
| `frontend/src/api/mock/drafts.mock.ts` | mock 预览系统补 `component` |
| `frontend/src/utils/systemSelect.tsx` | **新增**共享系统 Select / 组件单元格工具 |

### 5.7 前端 · 「系统与仓库」

| 文件 | 内容 |
|---|---|
| `frontend/src/pages/systems/SystemWizardModal.tsx` | 组件表单项 + 提交归一；命名前缀用纯 name |
| `frontend/src/pages/systems/SystemFormModal.tsx` | 组件表单项 |
| `frontend/src/pages/systems/index.tsx` | 回填/提交/`SystemFilterBar` 透传 |
| `frontend/src/pages/systems/columns.tsx` | 系统列纯 name；组件列；删除确认可拼接 |
| `frontend/src/pages/systems/SystemFilterBar.tsx` | 「搜索组件」 |
| `frontend/src/pages/systems/hooks.ts` | `searchComponent` → `listSystems` |

### 5.8 前端 · 任务 / 草稿 / 推送 / 知识 / 审计 / 仪表盘 / 编排

| 文件 | 内容 |
|---|---|
| `frontend/src/pages/tasks/TaskListTab.tsx` | Select helper + 「组件」列 |
| `frontend/src/pages/tasks/queue.tsx` | Select helper + 「组件」列 |
| `frontend/src/pages/tasks/dispatch.tsx` | Select helper |
| `frontend/src/pages/tasks/entrypoint-review.tsx` | Select helper + 「组件」列 |
| `frontend/src/pages/tasks/hierarchy-review.tsx` | Select helper + 「组件」列 |
| `frontend/src/pages/drafts/index.tsx` | Select helper + 「组件」列 |
| `frontend/src/pages/push/index.tsx` | Select helper |
| `frontend/src/pages/knowledge/KnowledgeContextBar.tsx` | Select helper（入口/层级共享） |
| `frontend/src/pages/knowledge/index.tsx` | Select helper + 列表「组件」列 + 树预览 `component` |
| `frontend/src/pages/logs/index.tsx` | Select helper |
| `frontend/src/pages/token-audit/index.tsx` | Select helper |
| `frontend/src/pages/dashboard/ai-usage.tsx` | Select helper |
| `frontend/src/pages/dashboard/system-coverage.tsx` | 明细「组件」列 |
| `frontend/src/pages/basic/orchestration.tsx` | repo→系统名/组件 map；列表系统+组件列 |
| `frontend/src/pages/basic/ScanWindowHeatmap.tsx` | `componentMap`；抽屉「组件」列 |

### 5.9 文件计数（便于核对）

| 分区 | 数量 |
|---|---|
| 文档 | 1 |
| 后端 Schema | 2 |
| 后端系统模块（实际改动） | 6（不含仅继承、未改文件的 `SystemSummaryVO`） |
| 后端其它 DTO/Service | 9 |
| 后端测试 | 2 |
| 前端类型/API/工具 | 7（含新增 `systemSelect.tsx`） |
| 前端系统页 | 6 |
| 前端其它页 | 15 |
| **合计（实际改动路径）** | **48** |

---

## 六、验收

- [x] 新建：`(name=A, component=B)` 成功；再新建同对 → 业务错误，不落库（单测 + 接口）
- [x] 新建：`(A, B)` 与 `(A, C)`、`(A, '')` 可并存（后端逻辑；空串归一单测）
- [x] 更新：把组件改成已占用对 → 失败（单测）
- [ ] 软删后再建同名同组件 → 成功（依赖集成环境）
- [x] 存量系统（`component=''`）列表无组件时展示与改前一致（仅 `name`）
- [x] 该系统下建仓库、跑任务、业务知识仍只依赖其 `system_id`，无需新参数
- [x] 向导 / 编辑页可填组件；列表可见独立「系统」「组件」列
- [x] 其它页系统下拉 / 列表分字段展示组件；API 不返回拼接串

---

## 七、后续可选（不在本期）

1. 真实 `ci_component` + 父级 `system_id`，仓库挂组件  
2. 列表按 `name` 分组折叠多个组件  
3. 跨组件共享业务知识 / 统一并发池  

以上任一项都会动到隔离边界，需单独方案，**禁止**在本期顺手做。
