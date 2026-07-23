# 跨组件同包同名 · 类身份（moduleKey）方案

> **管什么**：同一仓库多 Maven/Gradle 模块（组件）下「同包同类、各有一份源码」时，从扫描 → 调用链 → 入口 → 正向/反向 BFS → 层级 AI 落表 → binding → 文档生成 → 发布全链路的类身份消歧。  
> **不管什么**：拆成多个 `ci_system.component`（业务要求同一系统、一次任务、一份合并文档）；依赖 JAR 反编译进图（本期不做）；提示词大改 AI 输出契约。  
> **关联**：[tablelogic-partial-unique-plan.md](./tablelogic-partial-unique-plan.md)、[incremental-baseline-design.md](./incremental-baseline-design.md)、[module-doc-classpath-fix.md](./module-doc-classpath-fix.md)、[entrypoint-method-content-diff-design.md](./entrypoint-method-content-diff-design.md)。  
> **状态**：方案待评审（2026-07-22）；**Phase 0 去重已实施**（`ModuleHierarchyServiceImpl.dedupeBindingsByClassMethod`）。  
> **触发**：任务失败 `uk_mfb_task_class_method_active`；根因是活行 UK 与 BFS/反查均按 `(class[, method])`，不含组件维度。

---

## 〇、目标一句话

**在「一个 system / 一个 task / 一份合并知识文档」前提下，把类型身份从「FQCN / 短类名」升级为「(moduleKey, FQCN)」；物理落地优先用已有 `file_path`，逻辑图遍历与 UK 必须带 module 维度，避免同包同名跨组件撞车、混边、读错源码。**

---

## 一、问题与约束

### 1.1 业务约束

| 约束 | 含义 |
|---|---|
| 合并文档 | a/b/c 等同仓组件必须在一次任务里合成一份知识，**禁止**拆多 `system_id` 规避 |
| 同包同名合法 | 例：`ph_aaa` 与 `ph_bbb` 各有一份 `com.xxx.RefreshRefundEoaLinkJob` |
| base 组件 | c 可作为源码模块同仓存在；若仅以 JAR 引入且源码不在扫描树，本期仍**不进**内部 BFS（既有能力边界） |

### 1.2 现状身份混用（三层不一致）

| 层 | 典型键 | 能否区分同 FQCN 不同组件 |
|---|---|---|
| 物理 | `file_path`（相对仓库根，常含模块前缀） | **能** |
| 入口 / 层级 / binding | `class_name` / `class_paths` = FQCN | **不能** |
| 调用图 BFS | `caller_signature` ≈ `短类名#method(args)` | **不能** |

### 1.3 不做的选项

| 选项 | 判定 |
|---|---|
| 去掉 `uk_mfb_*` / 入口 UK | **否**——「一方法一功能」与入口唯一性仍要硬约束 |
| 只给 binding 加列、BFS 不动 | **否**——半套改造会读错源码 |
| `class_name` 编码 `ph_aaa::FQCN` | **不推荐作主方案**——隐形字段，全链路剥前缀成本高 |
| 拆多系统组件 | **否**——违反合并文档约束 |

---

## 二、身份模型

### 2.1 三类键

```text
moduleKey   = 从 file_path 推导的 Maven/Gradle 模块相对根（见 §2.2）
fqcn        = 标准 Java 全限定名（不含模块前缀）
file_path   = 相对仓库根的源文件路径（物理唯一）

类型身份 typeId     = (moduleKey, fqcn)     // 逻辑；展示用
物理身份 physicalId = file_path            // 落库 UK / 读源码权威
方法身份 methodId   = (physicalId | typeId) + methodSignature
调用边键 edgeKey    = caller 侧 methodId（见 §2.3）
```

**原则**：

1. **读源码 / DB UK / 增量删插**：以 **`file_path` 为权威**（已贯穿扫描与基线继承）。  
2. **人类 / AI 展示**：保留 **FQCN**；必要时旁注 `moduleKey`（如 `ph_aaa / com.xxx.Foo`）。  
3. **图遍历（正/反向 BFS）**：边键必须含 **moduleKey 或 file_path**，禁止纯短类名空间。  
4. **`deriveFqcnFromPath` 禁止**产出 `ph_aaa.com.xxx.Foo` 伪 FQCN；先剥 module 再得标准 FQCN。

### 2.2 moduleKey 推导（`ModuleKeyResolver`）

```text
输入: relativePath，如 "ph_aaa/src/main/java/com/xxx/Foo.java"

1. path = relativePath.replace('\\', '/')
2. 按优先级匹配源码根标记:
   /src/main/java/ | /src/test/java/ | /src/main/kotlin/ | /src/test/kotlin/ | /src/
3. 命中 "{prefix}src/main/java/{rest}":
   moduleKey = prefix 去尾 '/' → "ph_aaa"
   fqcn      = rest 去 .java 后 / → .
4. path 以 "src/main/java/" 开头（单模块仓库）:
   moduleKey = ""（或固定 "__root__"，全库统一一种）
5. 无法解析:
   moduleKey = "__unknown__"；physicalId 仍用完整 file_path
```

实现位置建议：`common/util/ModuleKeyResolver.java`（或 `callchain/support`），**唯一**推导入口；入口 / AST / AI / 增量全部调用，禁止各写一份截串。

### 2.3 caller_signature 升级

| 阶段 | 格式 |
|---|---|
| 现状 | `短类名#method(args)` |
| 目标 | `moduleKey!fqcn#method(args)`（moduleKey 为空时退化为 `!fqcn#...` 或省略前缀，全库约定一种） |

备选（更简、与物理键一致）：`file_path#method(args)`。  
**选定建议**：优先 **`moduleKey!fqcn#sig`**（签名较短、可读）；`file_path` 仍存列上供 UK/读文件。迁移期可双写旧短名键一段时间（见 §六）。

### 2.4 被调方（callee）同样需要 module 维度

入口带 moduleKey **不够**。场景：

```text
组件 a: ClassX → com.foo.SameUtil（a 份）
组件 b: ClassY → com.foo.SameUtil（b 份）
```

起点类名不同时正向根可不串，但：

- `lookupClassFilePath(className)` 仍可能读到错误组件的 `SameUtil.java`  
- 反向 BFS 按短类名匹配 `dependency_name` 会跨组件污染影响面  

故：**入口、被调、binding、调用边** 同一套身份模型（见 §四联动）。

---

## 三、UK 与表结构

### 3.1 原则

- **保留** partial unique（`WHERE is_deleted = 0`）方案 B。  
- 入口 / 发布入口 / binding：UK 从「仅 class」改为「**物理唯一**」。  
- `ci_method_call`：可不强 UK，但索引与查询必须可按 `file_path` / `module_key` 过滤。

### 3.2 表变更清单

| 表 | 现有 | 变更 | 新活行 UK（建议） |
|---|---|---|---|
| `ci_entrypoint` | 已有 `file_path`；UK `(task_id, class_name)` | 增 `module_key VARCHAR`（可空→空串）；写库时由 path 填充 | `(task_id, file_path)` 或 `(task_id, module_key, class_name)` |
| `ci_repository_entrypoint` | 同上 | 同左 | `(repository_id, file_path)` 或 `(repository_id, module_key, class_name)` |
| `ci_method_function_binding` | **无** path；UK `(task_id, class_name, method_signature)` | **增** `module_key` + **`file_path`**；UK 升级 | `(task_id, file_path, method_signature)` |
| `ci_method_call` | 已有 `file_path`；`class_name` 实为短名 | 增 `module_key`；`class_name` **改为存 FQCN**（或另加 `class_fqcn`，二选一需定稿）；`caller_signature` 新格式 | 索引 `(task_id, module_key, class_name)`、`(task_id, file_path)`；caller_sig 索引保留 |
| `ci_module_hierarchy.class_paths` | FQCN 字符串数组 | JSON 升级为对象数组（见 §3.3） | 无 class 级 UK |
| `ci_draft_source_reference` | 已有 `file_path` + `class_name` | 增 `module_key`（可选，便于展示） | 保持以 file_path 去重 |

**binding 为何必须加列**：现有列无物理路径；`module_node_id` 是知识树节点，**禁止**挪用。不加列则只能污染 `class_name` 编码，不作为主方案。

### 3.3 `class_paths` JSON 形态

```json
[
  {
    "moduleKey": "ph_aaa",
    "fqcn": "com.xxx.RefreshRefundEoaLinkJob",
    "filePath": "ph_aaa/src/main/java/com/xxx/RefreshRefundEoaLinkJob.java"
  }
]
```

- AI **仍可只输出 FQCN 字符串**（不改 prompt 根基）；落库 / merge 时由程序根据入口或调用链 **补全** `moduleKey` + `filePath`。  
- 读旧数据：字符串元素视为「无 moduleKey 的遗留 FQCN」，按单模块或首条 file_path 兜底，并打 warn。

---

## 四、流水线触点与改法（按阶段）

### 4.0 扫描 / 快照

| 触点 | 现状 | 改法 |
|---|---|---|
| `CodeScannerServiceImpl` / `ci_file_snapshot` | 已按 `file_path` | **保持**；作为 moduleKey 唯一物理源 |
| `BaselineInheritanceService` | 按 `file_path` 排除 | **保持** |

### 4.1 AST → `ci_method_call`

| 触点 | 现状 | 改法 |
|---|---|---|
| `AstJavaParserService` | `className`=短名；`sourceRelativePath` 单文件路径不稳定 | 统一写入相对 `file_path`；对外提供 fqcn |
| `MethodCallServiceImpl.walk` | `class_name` 短名；`caller_signature` 短名#sig | 写 `module_key`、`file_path`、FQCN、`edgeKey` 新签名 |
| `listByClass` | `eq(class_name)` | `(task_id, module_key, fqcn)` 或直接 `file_path` |
| SymbolSolver | 仅源码根 + JDK，**无 JarTypeSolver** | 本期不变；纯 JAR 依赖仍不进内部图 |

### 4.2 入口识别 / 复核

| 触点 | 现状 | 改法 |
|---|---|---|
| `discoverEntries*` Map key | FQCN，后者覆盖前者 | key = `file_path` 或 `typeId` |
| `shortNameToFilePath.putIfAbsent` | 短名霸占首个模块 | 禁止单值 Map；改为 `typeId → path` 或多值 |
| `uk_entrypoint_task_class_active` | `(task_id, class_name)` | 见 §3.2 |
| `EntrypointReviewServiceImpl` diff / methodsByClass | FQCN 分组 | 分组 key → `file_path` |
| `ExcludeTarget` | 仅 FQCN | 可选增 `filePath` / `moduleKey`；无则保持 FQ 匹配并 warn 歧义 |
| 入口是否扫描 base 组件 c | 同仓有源码且命中规则则扫 | **会**；可用 exclude 挡；纯 JAR 则 **不会** |

### 4.3 正向 BFS（文档生成）

| 触点 | 现状 | 改法 |
|---|---|---|
| `MethodCallGraphServiceImpl` | `caller_signature = cur` | 根与边使用新 `edgeKey`；扩边可选 **限定同 moduleKey**（默认同模块内扩；跨模块依赖边另议，见 §4.7） |
| `AiSummaryServiceImpl.loadFunctionRootSignatures` | binding → 截短 → 短名#sig | binding 带 `module_key`/`file_path` → 新 edgeKey |
| `lookupClassFilePath*` | `eq(class_name) LIMIT 1` | **禁止**无 module 的 LIMIT 1；优先 binding/入口已带的 `file_path`，否则 `(module_key, fqcn)` |
| `groupByClass` / `collectFunctionSourceCode` | 按短名聚合并读文件 | 按 `file_path` 或 `typeId` 聚合 |
| `resolveAuthoritativeClasses/Methods` | FQ 列表 | 输出含 moduleKey（或至少 filePath）供 prompt/溯源 |

**与 binding 联动（不变的结构，变的是键）**：

```text
ci_method_function_binding                    ci_method_call
  (function → 根方法 + moduleKey/file_path)      (边 + file_path + 新 caller_signature)
           │                                              │
           └──── edgeKey 对齐 ────────────────────────────┘
                              │
                     正向 BFS 扩边
                              │
                     按 file_path 读源码
```

### 4.4 反向 BFS / 增量影响

| 触点 | 现状 | 改法 |
|---|---|---|
| `MethodCallReverseGraphServiceImpl` | `dependency_name`=短名；入口 Set=FQCN；caller 列短名/FQ 不一致 | 变更种子带 `file_path`→moduleKey；匹配边时 callee 用 `(moduleKey, fqcn)` 或 path；入口命中用 typeId |
| `lookupClassFilePath`（反图） | LIMIT 1 | 变更文件 path **已知则直用**，禁止类名瞎猜 |
| `IncrementalImpactAnalyzerImpl.matchEntry` | FQCN 或 path | **优先 path** |
| `deriveFqcnFromPath`（hierarchy / impact） | 易产出 `module.com.Foo` | 统一走 `ModuleKeyResolver` |
| `IncrementalImpactSupport.moduleTouchedByChange` | `class_paths.contains(fq)` | class_paths 对象匹配 `(moduleKey,fqcn)` 或 filePath |

### 4.5 层级 AI 输出 → binding 落表

| 触点 | 现状 | 改法 |
|---|---|---|
| AI `class_paths` / `method_signatures` | FQ × sig 笛卡尔积 | **不改 prompt 契约**；Java 侧补全 moduleKey/filePath 再落 binding |
| `persistMethodBindingsFromIncrement` | 校验用短名#sig；UK 无 module | 白名单/交叉校验用新 edgeKey；写入前 **按 (file_path\|typeId, sig) 去重** |
| `uk_mfb_task_class_method_active` | `(task_id, class_name, method_signature)` | 见 §3.2 |
| `deleteByTaskIdAndClassMethodKeys` | 按 class+sig 腾键 | 按新 UK 列腾键 |
| 同功能多声明同一方法 | 导致同 batch 撞 UK | 应用层 last-wins + warn（与 §五止血一致） |

### 4.6 发布 / 知识查询 / 纠错

| 触点 | 现状 | 改法 |
|---|---|---|
| `RepositoryPublishServiceImpl` 入口复制 | UK `(repository_id, class_name)` | 与任务侧 UK 对齐 |
| 知识查看入口列表 | 仅 FQCN | 展示 `moduleKey / fqcn`，详情带 file_path |
| Remediation exclude | FQCN | 歧义时要求 file_path 或拒绝并提示 |

### 4.7 跨模块依赖边策略（产品默认）

| 策略 | 说明 | 建议默认 |
|---|---|---|
| A. 同 moduleKey 内扩边 | a 的 BFS 不进入 b/c 源码内部 | **文档生成正向 BFS 默认 A**（边界清晰） |
| B. 允许跨 module 一步依赖 | 记录调用到 c 的类名，但若继续扩需 c 在扫描树内 | 可选开关 |
| C. 纯 JAR 的 c | 仅有依赖名，无 caller 行、无源码 | **保持现状**：点到名即止，不当入口 |

同仓 base 模块 c：入口扫描会扫到 c；若不想当入口，用 exclude；若希望 a 的文档包含 c 源码，显式打开跨模块扩边（B）。

---

## 五、分期落地

### Phase 0 — 止血（最小改动，可先上）

| 项 | 内容 |
|---|---|
| P0-1 | `persistMethodBindingsFromIncrement`：`batchInsert` 前按 `(className, methodSignature)` **去重**（last-wins + warn） |
| P0-2 | **保留**现有 UK（不删） |
| P0-3 | 不解决同 FQCN 合法并存与 BFS 串味 |

### Phase 1 — 身份基础设施

| 项 | 内容 |
|---|---|
| P1-1 | `ModuleKeyResolver` + 单测（单模块 / 多模块 / 奇异路径） |
| P1-2 | schema：`module_key` / binding.`file_path`；新 partial UK；旧 UK drop |
| P1-3 | `ci_method_call` / `ci_entrypoint` 写入填充 `module_key`；签名双写或切换开关 |

### Phase 2 — 图与文档

| 项 | 内容 |
|---|---|
| P2-1 | 正向 BFS 改用新 edgeKey；`lookupClassFilePath` 去 LIMIT 1 歧义 |
| P2-2 | 反向 BFS / IncrementalImpact 全链路 typeId |
| P2-3 | AiSummary 根签名、权威类清单、draft source ref |

### Phase 3 — 层级 / 发布 / UI

| 项 | 内容 |
|---|---|
| P3-1 | class_paths 对象化 + merge/补全 |
| P3-2 | binding 落库用 file_path UK；交叉校验新键 |
| P3-3 | 发布 UK、知识入口展示、exclude 歧义处理 |
| P3-4 | 前端入口/层级 DIFF 以 file_path 为行键（若有撞 FQCN） |

---

## 六、兼容与迁移

1. **存量行**：`module_key` / binding.`file_path` 启动后对活行 backfill（从已有 `file_path` 或调用链反查）；反查失败标 `__unknown__`。  
2. **旧任务重跑**：全量任务重扫即自然正确；增量任务依赖基线继承 path，需保证基线行已 backfill。  
3. **caller_signature 切换**：建议配置 `callgraph.signature-version=v1|v2`；v2 任务只写新格式；文档 BFS 只认本任务版本。避免同一 task 混用。  
4. **AI 输出**：继续收 FQCN 字符串；**程序补全** module，不强迫模型输出模块名。

---

## 七、验证清单

- [ ] 同仓 `ph_aaa` / `ph_bbb` 同 FQCN 入口均可落库，不撞入口 UK  
- [ ] 两份类均可落 binding，不撞 `uk_mfb_*`  
- [ ] 对 a 入口做正向 BFS：读到的 `SameUtil` 源码属于 a（或策略 B 下明确跨到 c）  
- [ ] 改 a 的 `SameUtil`：反向 BFS **不**误伤仅调用 b 份的入口  
- [ ] 单模块仓库：`module_key=""` 行为与改造前一致  
- [ ] P0 去重：故意让 AI 双功能声明同一方法 → 任务成功 + warn 日志  
- [ ] 纯 JAR 依赖：仍不当入口、不进内部 BFS  
- [ ] `deriveFqcnFromPath` 单测：多模块 path → 标准 FQCN + 正确 moduleKey  

---

## 八、结论摘要

| 问题 | 答案 |
|---|---|
| UK 要不要？ | **要**；键升级，不删除 |
| 最小改动？ | **先去重（Phase 0）** |
| 入口要加 moduleKey？ | **要** |
| 被调也要？ | **要**（否则读源码 / 反向 BFS 仍串） |
| binding？ | **要**加 `module_key` + `file_path`（现表无路径列） |
| 能否只用已有字段、binding 不加列？ | 入口/调用链可以靠 `file_path`；**binding 不能** |
| BFS 是否只靠 method_call？ | 边在 `ci_method_call`；根来自 binding；用签名字符串联动，**必须把 module 打进该字符串或扩边过滤条件** |

---

## 九、主要改动文件（实施时对照）

| 区域 | 文件 |
|---|---|
| 公共 | 新建 `ModuleKeyResolver`；`schema.sql` / `schema-fresh.sql` |
| 调用链 | `MethodCallServiceImpl`、`MethodCall`、`MethodCallGraphServiceImpl`、`MethodCallReverseGraphServiceImpl`、`IncrementalImpactAnalyzerImpl`、`IncrementalImpactSupport` |
| 入口 | `EntryPointDiscoveryServiceImpl`、`EntrypointReviewServiceImpl`、`EntrypointEntity`/`Mapper`、发布 `RepositoryPublishServiceImpl` |
| 层级 | `ModuleHierarchyServiceImpl`（binding 持久化、deriveFqcn、whitelist） |
| binding | `MethodFunctionBinding`、`MethodFunctionBindingMapper` |
| 文档 | `AiSummaryServiceImpl` |
| 测试 | Resolver 单测；双模块同 FQCN 集成用例；P0 去重单测 |

---

## 十、修订记录

| 日期 | 说明 |
|---|---|
| 2026-07-22 | 初稿：基于 UK 撞车事故与全链路触点审计；分期 Phase 0–3 |
