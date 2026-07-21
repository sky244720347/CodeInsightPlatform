# 「系统与仓库」新增「组件」维度方案

> **管什么**：在「系统与仓库」下为业务系统增加 **组件** 维度；以「系统 + 组件」作为业务身份做查重；对外隔离键仍是 **单个 `system_id`**。  
> **不管什么**：新建 `ci_component` 表 / `component_id` FK；跨组件共享业务知识、并发配额、统一父级分组树；仓库侧（`ci_repository`）结构变更。  
> **状态**：实施中（2026-07-21：schema / 查重接口 / 「系统与仓库」页面表单与列表已落地；其它页系统下拉文案未改）。  
> **设计原则**：最小改动 —— 把「系统+组件」编码成一条 `ci_system`，沿用现有 `system_id` 贯穿任务 / 并发 / 业务知识 / 前端筛选。

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

**展示文案（建议统一）：**

- 有组件：`{name} / {component}`（中文可用 `nameCn`，旁注组件）
- 无组件：仍显示 `{name}`（兼容存量）

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
| 列表 / 详情响应 | 带回 `component`；可选派生 `displayLabel`（也可前端拼） |

---

## 三、后端行为

### 3.1 查重（新建 + 更新必做）

在 `SystemApplicationServiceImpl`：

1. `normalizeComponent(raw)`：`null` / blank → `""`；trim。
2. `assertUniqueNameComponent(name, component, excludeId)`：查 `is_deleted=0` 且 `(name, component)` 命中且 `id != excludeId` → `BusinessException`（文案明确：系统+组件已存在）。
3. `createSystemDraft`：校验 `name`/`owner` 后 → normalize → assertUnique → insert。
4. `updateSystem`：同路径；允许改 `component`，但撞唯一则失败。

DB 唯一索引作最终兜底；服务层校验给前端可读错误。

### 3.2 明确不改

| 模块 | 原因 |
|---|---|
| `TaskConcurrencyLimiter` | 仍按 `system_id` → `maxConcurrentTasks` |
| 任务创建 / 队列 / 知识 / 推送 / 日志筛选 | 仍传 `systemId` |
| `ci_repository` 创建逻辑 | 仍挂在所选系统（即某一「系统+组件」行）下 |
| 存储路径 `releases/{systemId}/...` | 不变 |

---

## 四、前端改动细节（已落地）

> API 层仍走现有 `createSystem` / `updateSystem` / `listSystems`（`frontend/src/api/system.ts` 方法签名未改）；类型与页面消费 `component`。  
> 查重错误由后端 `BusinessException` 经 `request` 拦截器抛出，前端用既有 `message` / 控制台处理，**未单独做前端预查重**。

### 4.1 类型

| 文件 | 改动 |
|---|---|
| `frontend/src/types/index.ts` | `System` 增加可选字段 `component?: string`（注释：与 `name` 联合唯一；空表示无组件） |

`api/system.ts` 使用 `Partial<System>`，无需改方法签名；创建/更新请求体自然带上 `component`。

### 4.2 新建向导 `SystemWizardModal.tsx`

| 点 | 细节 |
|---|---|
| 表单类型 | 本地 `SystemFormValues` 增加 `component?: string` |
| Step 1 UI | 「系统名称」下方增加「组件」`Form.Item`：可选、`allowClear`；`extra` 文案说明与系统名联合唯一、重复无法创建 |
| 提交归一 | `handleStep1Submit`：`component: values.component?.trim() \|\| ''` 后再 `createSystem` / `updateSystem` |
| 展示名 | 保存成功后 `setSystemName`：有组件用 `{name} / {component}`，无组件仍用 `name`（供后续步骤命名前缀） |
| 查重失败 | 依赖后端报错（如「系统+组件已存在」）；向导不本地拦截 |

### 4.3 编辑弹窗 `SystemFormModal.tsx` + `index.tsx`

| 点 | 细节 |
|---|---|
| 表单 | 「系统名称」旁增加「组件」输入；`extra`：与系统名称联合唯一；可不填 |
| 回填 | `handleEdit`：`component: record.component \|\| undefined`（空串不占位） |
| 提交 | `handleEditSubmit`：同样 `trim() \|\| ''` 后 `updateSystem(id, payload)` |
| 错误 | 重复时后端拒绝，现有 catch 打日志；表单校验错误（`errorFields`）不弹额外提示 |

### 4.4 列表 `columns.tsx`

| 列 / 交互 | 行为 |
|---|---|
| 「系统」列 | 有 `component` 时链接文案为 `` `${name} / ${component}` ``，否则仅 `name` |
| 新增「组件」列 | 有值 → `<Tag>`；无值 → 次要色 `—` |
| 删除确认 | Popconfirm 标题用「系统 / 组件」或仅系统名，与列表一致 |

### 4.5 展示约定（与方案 §一 对齐）

- 有组件：`{name} / {component}`
- 无组件：`{name}`
- **value / 路由 / 业务请求仍只用 `systemId`**，不做「先选系统再选组件」两级联动

### 4.6 本期未改（明确）

| 项 | 说明 |
|---|---|
| `SystemFilterBar` | 仍只按系统名 / 负责人筛；未加组件关键字 |
| 任务 / 知识 / 草稿等页的系统下拉 | 仍可能只显示 `name`；同名多组件时文案可能撞车——**后续按需改文案层** |
| `RepositoryDrawer` 等仓库侧 | 仍挂当前选中行的 `systemId`，逻辑不变 |
| 前端本地查重 | 不做；以接口为准 |

---

## 五、改动清单（实施用）

| # | 文件 / 区域 | 内容 | 状态 |
|---|---|---|---|
| A1 | `schema.sql` / `schema-fresh.sql` | `component` 列 + 部分唯一索引 | 已做 |
| A2 | `SystemApplication.java` | 字段 `component` | 已做 |
| A3 | `SystemApplicationServiceImpl` + Controller | normalize + 创建/更新查重 | 已做 |
| A4 | `SystemApplicationNameComponentUniqueTest` | 重复拒绝 / 空串归一 / 更新撞车 | 已做 |
| B1 | `frontend/src/types/index.ts` | `System.component?` | 已做 |
| B2 | `SystemFormModal` / `SystemWizardModal` / `index.tsx` | 组件输入 + 提交 trim | 已做 |
| B3 | `columns.tsx` | 系统列拼接 + 组件列 + 删除文案 | 已做 |
| B4 | 任务 / 知识等 `systemId` 选择器文案 | 同步 `name / component` | **未做（建议后续）** |
| B5 | `SystemFilterBar` 按组件过滤 | 可选增强 | **未做** |

---

## 六、验收

- [x] 新建：`(name=A, component=B)` 成功；再新建同对 → 业务错误，不落库（单测 + 接口）
- [x] 新建：`(A, B)` 与 `(A, C)`、`(A, '')` 可并存（后端逻辑；空串归一单测）
- [x] 更新：把组件改成已占用对 → 失败（单测）
- [ ] 软删后再建同名同组件 → 成功（依赖集成环境）
- [x] 存量系统（`component=''`）列表无组件时展示与改前一致（仅 `name`）
- [x] 该系统下建仓库、跑任务、业务知识仍只依赖其 `system_id`，无需新参数
- [x] 向导 / 编辑页可填组件；列表可见「系统 / 组件」

---

## 七、后续可选（不在本期）

1. 真实 `ci_component` + 父级 `system_id`，仓库挂组件  
2. 列表按 `name` 分组折叠多个组件  
3. 跨组件共享业务知识 / 统一并发池  

以上任一项都会动到隔离边界，需单独方案，**禁止**在本期顺手做。
