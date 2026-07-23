# 同 Git 多组件隔离：扫描根 / 排除规则生效方案

> **目标**：同一 `gitUrl` 下存在多个业务组件时，通过仓库配置的 **扫描根目录 / 排除目录 / 排除文件类型** 把各系统（组件）的扫描、入口识别、调用链、文档生成边界隔开。  
> **不管什么**：Git 凭证拆分、推送目标隔离、前端「系统+组件」维度本身（已见 [system-component-dimension-plan.md](./system-component-dimension-plan.md)）。  
> **状态**：待确认（未实施）。  
> **现状**：`scanRoot` 落库但未读；`excludeDirs` / `excludeFileTypes` 仅影响 `ci_code_file_snapshot`，入口 / 调用链仍扫整仓工作区。

---

## 〇、业务模型（约定）

推荐配置方式（与现有表结构兼容）：

| 层级 | 含义 | 示例 |
|---|---|---|
| `ci_system` | 一个隔离单元（可已含「系统+组件」） | `warehouse` + 组件 `rms` |
| `ci_repository` | 挂在该系统下；**可与兄弟系统共用同一 `gitUrl`/`branch`** | `https://gitee.com/.../warehouseManager` |
| `scan_root` | **本系统在仓内的业务根**（相对仓库根） | `/rms-service` 或 `services/rms` |
| `exclude_dirs` | 在扫描根之下再排除的目录名/路径片段 | `target,test,.git` |
| `exclude_file_types` | 排除后缀 | `.md,.xml,.json`（通常不影响 `.java`） |

**隔离键仍是 `system_id` + `repository_id`**：任务、入口、层级、草稿、配额不变；只把「看见的代码范围」收窄到本组件。

```text
同一 Git 仓
├── rms-service/          ← 系统 A 的 scan_root
├── order-service/        ← 系统 B 的 scan_root
└── common/               ← 可被 A/B 各自 include，或写入对方 exclude
```

---

## 一、设计原则

1. **拉取仍可整仓 clone**（共享 Git、共享 commit 基线）；隔离发生在 **「有效扫描根」** 之后，不按组件做 sparse checkout（一期）。  
2. **所有读源码的流水线阶段统一走「有效项目目录 / 路径过滤器」**，禁止再对整仓 `projectDir` 裸扫。  
3. **路径一律相对仓库根归一化**（`/`、`\`、前后空白）；`scan_root=/` 表示整仓（兼容现状）。  
4. **入口复核的「业务排除」仍用 EntryPointConfig**；仓库三配置管的是「物理可见范围」，两者叠加：先物理范围，再入口规则。

---

## 二、核心概念：ScanScope

新增程序内不可变对象（不必新表）：

```text
ScanScope {
  repoRoot: File          // clone 后的仓库根 = 今日 projectDir
  scanRootRel: String     // 归一化后的 scan_root，如 "rms-service"
  effectiveRoot: File     // repoRoot/scanRootRel（不存在则任务 FAIL）
  excludeDirs: List<String>
  excludeFileTypes: List<String>  // 不含点或统一去点
}
```

工具方法：

- `boolean accepts(String relativeToRepoRoot)`  
- `boolean acceptsAbsolute(File f)`  
- `File effectiveRoot()`  
- 从 `CodeRepository` 工厂构建：`ScanScope.from(repo, repoRoot)`

**相对路径约定（全流水线统一）**：

- 快照 / `ci_method_call.file_path` / 入口 `filePath`：**相对仓库根**（与今日一致，便于增量 git diff 对齐）。  
- 物理读写：一律 `new File(repoRoot, relativePath)`，并先过 `ScanScope.accepts`。

---

## 三、流水线改造点

| 阶段 | 今日 | 改造后 |
|---|---|---|
| **PULLING** | 整仓 clone 到 `workspaces/task_{id}` | **不变**（仍整仓）；校验 `effectiveRoot` 存在 |
| **快照 scanDirectory** | 从仓根扫；已用 exclude | 从 **`effectiveRoot` 起扫**；exclude 相对仓根匹配；路径仍记相对仓根 |
| **PARSING / 调用链** | `persistAstForTask(projectDir)` 整树 + 硬编码 SKIP_DIRS | walk 时叠加 **ScanScope**（跳过根外 + exclude）；硬编码 SKIP 保留 |
| **入口识别** | `parseDirectory(projectDir)` 整仓 | 只解析 **ScanScope 内** `.java`；增量 `discoverEntriesInFiles` 对范围外路径直接跳过 |
| **层级 / 文档源码收集** | 按 binding 读文件 | 读文件前 `accepts`；范围外视为不可达（打 warn，不跨组件污染） |
| **增量 diff** | git diff 全仓路径 | diff 结果先 **filter 进 ScanScope**，再进入 snapshot / AST / 入口增量 |

入口复核页展示的列表 = 识别结果；因识别已受限，**自然只含本组件入口**，无需单独改前端复核 UI。

---

## 四、配置语义细则

### 4.1 `scan_root`

| 输入 | 归一化 | 行为 |
|---|---|---|
| 空 / `/` / `.` | `""` | 有效根 = 仓库根 |
| `/rms-service`、`rms-service/`、`\rms-service` | `rms-service` | 有效根 = `repoRoot/rms-service` |
| 指向不存在目录 | — | **任务 FAIL**，明确错误：`扫描根不存在: …` |

禁止：`..` 跳出仓库根（normalize 后必须仍在 `repoRoot` 下）。

### 4.2 `exclude_dirs`

- 逗号分隔，trim；匹配规则建议升级为：  
  - **路径段匹配**（推荐）：相对仓根的路径按 `/` 分段，任一段等于排除名，或相对路径以 `排除名/` 开头。  
  - 替代今日宽松的 `contains`（易误伤，如排除 `test` 误伤 `contest`）。  
- 默认仍强制排除：`.` 开头目录、`target`、`node_modules`（与今日一致）。

### 4.3 `exclude_file_types`

- 去点、大小写不敏感；对非 `.java` 的排除主要影响快照统计。  
- **解析 / 入口只关心 `.java`**：若用户把 `.java` 配进排除，视为配置错误并 FAIL 或忽略并 warn（建议：**warn + 忽略对 .java 的排除**，避免误配扫空）。

---

## 五、同 URL 多仓库共存

允许：

```text
系统 A / 仓库 1：gitUrl=U, branch=master, scan_root=rms-service
系统 B / 仓库 2：gitUrl=U, branch=master, scan_root=order-service
```

约束与提示（产品层）：

- 创建/编辑仓库时：若同 `system_id` 下已有相同 `gitUrl+branch+scan_root`，提示重复。  
- **不强制**跨系统唯一；跨系统同 URL 不同 scan_root 是推荐用法。  
- 增量基线：`last_commit_id` **按 repository 行**独立；同 URL 两仓库可各自推进发布基线（互不影响）。

---

## 六、不在一期做的

| 项 | 原因 |
|---|---|
| Git sparse-checkout / partial clone | 复杂度高；整仓磁盘可接受 |
| 把 common 模块自动共享给多组件 | 需显式「附加 include 根」模型，二期再议 |
| 用排除规则替代入口 EntryPointConfig | 职责不同，叠加即可 |
| 改 DB schema 新表 | 现有三列足够 |

二期可选：`include_roots` 多根（主 scan_root + 额外 common/）；或 sparse checkout 省盘。

---

## 七、改动清单（确认后实施）

| # | 位置 | 内容 |
|---|---|---|
| D1 | 新建 `ScanScope` + `ScanScopeResolver` | 归一化、accepts、effectiveRoot 校验 |
| D2 | `CodeScannerServiceImpl` | 从 effectiveRoot 扫描；exclude 按段匹配；增量 diff 先 filter |
| D3 | `MethodCallServiceImpl` | walk 叠加 ScanScope |
| D4 | `EntryPointDiscoveryServiceImpl` / Fallback parseDirectory 调用方 | 传入 scope 或只扫 effectiveRoot |
| D5 | `AstJavaParserService` / 文档源码收集 | 越界路径拒绝 |
| D6 | 任务启动 / pullAndScan | effectiveRoot 不存在 → FAIL + 操作日志 |
| D7 | 前端文案 | 扫描根 / 排除说明改为「组件隔离：相对仓库根；入口与解析均受此范围限制」 |
| D8 | 单测 | 同仓两 scan_root 互不可见；exclude 不误伤；`/` 兼容整仓 |

---

## 八、验收场景

1. 同 URL 建两仓库，`scan_root` 分别为 `mod-a`、`mod-b`：A 任务入口不得出现 B 包下 Controller。  
2. `scan_root=/`：行为与改造前整仓扫描一致（回归）。  
3. `scan_root` 配错不存在路径：任务失败且日志可读。  
4. 排除 `target` + 自定义 `generated`：调用链与入口均不进入。  
5. 增量：仅 mod-a 下文件变更时，A 仓库增量命中；B 仓库同 commit 无 diff 命中（filter 后空可按现有增量语义处理）。

---

## 九、请确认的决策点

请回复确认或改选项：

1. **拉取策略**：一期维持「整仓 clone + 逻辑隔离」（推荐） vs 上 sparse-checkout。  
2. **exclude_dirs 匹配**：路径段精确匹配（推荐） vs 保留今日 `contains`。  
3. **误排除 `.java`**：warn 并忽略（推荐） vs 直接 FAIL。  
4. **common 代码**：一期不支持多根；跨组件依赖靠「把 common 放进各自 scan_root 能扫到的相对位置」或二期 `include_roots`。  
5. **文档/层级读源码越界**：跳过并 warn（推荐） vs 任务 FAIL。

确认后按第七节落地。
