# 「系统与仓库」新增「组件」维度方案

> **管什么**：在「系统与仓库」下为业务系统增加 **组件** 维度；以「系统 + 组件」作为业务身份做查重；对外隔离键仍是 **单个 `system_id`**。  
> **不管什么**：新建 `ci_component` 表 / `component_id` FK；跨组件共享业务知识、并发配额、统一父级分组树；仓库侧（`ci_repository`）结构变更。  
> **状态**：待实施。  
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

## 四、前端（「系统与仓库」为主）

### 4.1 必改

| 位置 | 改动 |
|---|---|
| `api/system.ts`（或等价类型） | `System` 增加 `component?: string` |
| `SystemFormModal` / `SystemWizardModal` | 增加「组件」表单项（可选）；提交前 trim |
| `columns.tsx` / 列表 | 展示 `name` + `component`（或统一 `displayLabel`） |
| `SystemFilterBar` | 支持按系统名 / 组件关键字过滤（可先简单 `includes`） |

### 4.2 展示策略

- 下拉选系统（任务、知识、草稿等页）：选项文案改为「系统 / 组件」，**value 仍是 `systemId`**。
- 本期可不做「先选系统再选组件」两级联动（因为组件不是子表，每一行已是完整身份）。

### 4.3 明确可不改（本期）

- 仓库抽屉内部逻辑（仍是某 `systemId` 下的仓库列表）
- 任务流水线、知识发布路径

若其它页系统下拉仍只显示 `name`，会出现「同系统不同组件看起来像重复」—— **建议同步改展示文案**（改动面是文案层，不是数据模型层）。

---

## 五、改动清单（实施用）

| # | 文件 / 区域 | 内容 |
|---|---|---|
| A1 | `backend/.../db/schema.sql` | `component` 列 + 部分唯一索引 |
| A2 | `SystemApplication.java` | 字段 `component` |
| A3 | `SystemApplicationServiceImpl` | normalize + 创建/更新查重 |
| A4 | 单测 | 同名同组件拒绝；同名不同组件允许；空组件互斥；软删后可重建 |
| B1 | `frontend/src/api/system.ts` | 类型字段 |
| B2 | `SystemFormModal` / `SystemWizardModal` | 组件输入 |
| B3 | `columns.tsx` + 系统下拉展示 | `name / component` |
| B4 | （建议）任务 / 知识等 `systemId` 选择器 | 同步展示文案 |

---

## 六、验收

- [ ] 新建：`(name=A, component=B)` 成功；再新建同对 → 业务错误，不落库
- [ ] 新建：`(A, B)` 与 `(A, C)`、`(A, '')` 可并存，三条不同 `system_id`
- [ ] 更新：把组件改成已占用对 → 失败；改成空闲对 → 成功
- [ ] 软删后再建同名同组件 → 成功
- [ ] 存量系统（`component=''`）列表 / 下拉行为与改前一致
- [ ] 该系统下建仓库、跑任务、业务知识仍只依赖其 `system_id`，无需新参数

---

## 七、后续可选（不在本期）

1. 真实 `ci_component` + 父级 `system_id`，仓库挂组件  
2. 列表按 `name` 分组折叠多个组件  
3. 跨组件共享业务知识 / 统一并发池  

以上任一项都会动到隔离边界，需单独方案，**禁止**在本期顺手做。
