# 正向 BFS 扩到 Service · 修复方案

> **管什么**：文档生成正向 BFS 断在 Controller、知识「涉及类清单」缺 Service/Impl 的根因与修复。  
> **不管什么**：层级 AI `class_paths` 仍只挂入口；反向 BFS / 增量影响面；跨模块同 FQCN 消歧（见 `cross-module-class-identity-design.md`）。  
> **状态：已实施**（方案确认并落地，2026-07-23）。

---

## 〇、已确认决策

| # | 决策 | 结论 |
|---|---|---|
| 1 | 修哪些块 | **A + B 都做**（写库/BFS 可达 + 权威类清单合并） |
| 2 | callee 参数 | **完整参数签名**（与 `caller_signature` 同形：`短类名#method(ParamTypes)`） |
| 3 | 多态 | **带上 `dependency_candidates`**（声明类型 + 项目内具体子类/实现一并入队） |
| 4 | 层级 `class_paths` | **不改**（仍只填入口类；Service 靠 BFS / 权威清单补） |
| 5 | 全量 vs 增量 | **同一套正向 BFS**；本问题与任务类型无关 |

---

## 一、问题结论

| 环节 | 现状 | 后果 |
|---|---|---|
| 层级 AI | `class_paths` 只写入口 Controller（设计如此） |  alone 不会出现 Service |
| 解析落库 | `target_signature` = 裸方法名（如 `listProducts`） | BFS 下一步查 `caller_signature=listProducts` 永远不命中 |
| 正向 BFS | 入队裸方法名；`groupByClass` 丢弃无 `#` 的节点 | `{java.code}` 只有 Controller 方法片段 |
| 权威类清单 | `resolveAuthoritativeClasses` 有 binding 就返回，**空才** BFS | 「涉及类清单」锁死在 Controller |

全量 / 增量都会复现。

---

## 二、目标行为

以 demo `ProductController#listProducts()` → `productService.listProducts()` 为例：

1. 边落库：
   - `caller_signature` = `ProductController#listProducts()`
   - `dependency_name` = `…ProductService`（FQ 或短名）
   - `dependency_candidates` = 若有实现类则逗号分隔 FQ（可空）
   - `target_signature` = `ProductService#listProducts()`（**含参数类型列表**，与 callee 声明一致）
2. BFS：根 → Service 方法 →（若有）更深下游；`visited` 全是 `Class#method(args)` 形态。
3. 文档：`{java.code}` 含 Controller + Service（+ Impl）；scoped JSON 的 `class_paths` 含上述可达 FQ。

---

## 三、方案 A — 写库 + 正向 BFS

### A1. AST 解析写出完整 target（主路径）

**文件**：`AstJavaParserService.attachMethodBody`

对每条 `MethodCallExpr`（已有 `depType` 才落边，逻辑不变）：

1. **Receiver 类型**：沿用现有 `depVars` + `tryResolveReceiverType` → `dependency_name`；`findCandidatesForFqcn` → `dependency_candidates`。
2. **Callee 参数类型（完整）**：
   - **优先**：`symbolSolver` 解析该方法调用的 resolved method → 取声明参数类型，压成与 `buildArgsText` 同风格的短类型列表（去泛型、与 caller 端一致）。
   - **次选**：对每个实参 `calculateType` / 字面量启发式（`String`/`int`/…）拼参。
   - **失败**：仍写出 `短类名#methodName(` + 能推断的部分；若完全无参信息则 `短类名#methodName()` 仅当 call 实参为空；有实参但推不出类型时写 `短类名#methodName` **不带括号**，交给 BFS 前缀匹配兜底（见 A3），并打 debug/warn。
3. **`target_signature` 规范**：
   ```text
   stripPackage(dependency_name) + "#" + methodName + "(" + ParamType1 + "," + ParamType2 + ")"
   ```
   例：`ProductService#getProduct(Long)`  
   （类名用短名，与现有 `caller_signature` 短类名策略一致；FQ 只留在 `dependency_name`。）

**文件**：`ParsedClassInfo.MethodCallInfo` 注释更新为「target 含类 + 方法 + 参数（阶段 3）」。

### A2. Regex 解析对齐

**文件**：`RegexJavaParserService`  
能力弱于 AST：在能拿到 receiver 类型时，尽量写出 `短类名#methodName(...)`；参数推不出则同 A1 失败分支。不阻塞主路径（生产以 AST 为准）。

### A3. 落库规范化（双保险）

**文件**：`MethodCallServiceImpl`  
新增 `buildTargetSignature(dependencyName, targetMethod, targetSignatureFromParser)`：

- 若 parser 已给出含 `#` 的完整/半完整签名 → 规范化短类名前缀后写入；
- 否则用 `dependency_name` 短名 + `targetMethod` 补全。

### A4. 正向 BFS（读侧，兼容旧数据）

**文件**：`MethodCallGraphServiceImpl.resolveReachableMethods`

入队下一跳时：

| `target_signature` 形态 | 行为 |
|---|---|
| 已是 `Class#method(args)` | 直接作为候选键 |
| 仅有方法名（旧数据） | 用本边 `dependency_name` 拼 `短类名#method`；再走匹配 |
| 空 | 跳过 |

**解析下一跳真实节点（完整参数策略下的匹配）**：

1. **精确命中**：`caller_signature == 候选键` → 入队。
2. **同任务查表**：`caller_signature` 以 `短类名#methodName(` 为前缀，或等于 `短类名#methodName()`；若候选已带完整 `(ParamTypes)` 则优先精确，失败再前缀（应对解析与声明格式细微差异，如 `int` vs `Integer`——**本期先精确 + 前缀；装箱差异若实测多，再加类型归一表**）。
3. **多态（决策 3）**：对 `dependency_name` 与 `dependency_candidates` 拆出的每个类型短名，重复 1–2；全部命中的实现方法都入队（去重）。
4. 无任何命中：不入队（避免再把裸方法名塞进 `visited`）；可 warn。

`visited` / 返回集：**只保留含 `#` 的签名**，保证下游 `groupByClass` 能拆出类名。

### A5. 单测

**文件**：`MethodCallGraphServiceTest`

- 改现有「visited 含裸 `method2`」断言 → 期望 `B#method2()` 等完整键。  
- 新增：`Controller → Service` 完整参数精确跳转。  
- 新增：旧边（裸 `target_signature` + `dependency_name`）仍能走到 Service。  
- 新增：`dependency_candidates` 含 Impl 时，Impl 方法入 `visited`。  
- 可选：重载方法只命中参数匹配的那一个（完整参数价值点）。

---

## 四、方案 B — 权威类清单始终合并 BFS

**文件**：`AiSummaryServiceImpl.resolveAuthoritativeClasses`

顺序（去重保序）：

1. binding 中的 `class_name` → FQ  
2. `fn.classPaths` → FQ（binding 非空也保留并集，避免丢 AI 已写入口）  
3. **始终**（不再要求 `out.isEmpty()`）：`loadFunctionRootSignatures` → `resolveReachableMethods` → `groupByClass` 的类 → FQ  

与 `collectFunctionSourceCode` 同源根签名，保证「清单 ⊆ 源码类」大方向一致。

**文档**（可选同步）：`docs/module-doc-classpath-fix.md` § 权威类清单「仅空时 BFS」改为「始终 union BFS」。

---

## 五、涉及文件清单

| 文件 | 变更 |
|---|---|
| `modules/parser/.../AstJavaParserService.java` | 完整 `target_signature` + 实参/声明类型解析 |
| `modules/parser/.../RegexJavaParserService.java` | 尽力对齐 |
| `modules/parser/model/ParsedClassInfo.java` | 字段注释 |
| `modules/callchain/.../MethodCallServiceImpl.java` | `buildTargetSignature` 落库规范化 |
| `modules/callchain/.../MethodCallGraphServiceImpl.java` | 完整键入队 + candidates 多跳 + 旧数据兜底 |
| `modules/callchain/entity/MethodCall.java` | 注释与阶段 3 对齐 |
| `modules/ai/.../AiSummaryServiceImpl.java` | `resolveAuthoritativeClasses` 始终 union BFS |
| `modules/callchain/MethodCallGraphServiceTest.java` | 断言与新用例 |
| `docs/module-doc-classpath-fix.md` | 可选：权威清单规则一句 |

不改 schema；不改 `analyze_prompt.md`。

---

## 六、风险与降级

| 风险 | 处理 |
|---|---|
| symbolSolver 推不出实参类型 | 前缀匹配兜底；不写裸方法名进 visited |
| `int` vs `Integer` 等签名不一致 | 先精确+前缀；若 demo/真实仓踩坑再加简单归一 |
| candidates 过多导致文档膨胀 | 仅项目内 subtypeIndex 候选（现有能力）；跨 JAR 本来就没有 |
| 旧任务未重解析 | A4 读侧用 `dependency_name`/`candidates` 拼键，**不必**先重跑 PARSING；要完整参数键需重跑解析 |
| Regex 解析参数弱 | 生产 AST 为主；Regex 失败走 A4 兜底 |

---

## 七、验证计划

1. `mvn -Dtest=MethodCallGraphServiceTest test`  
2. demo 仓 **全量** 新任务：某功能文档「涉及类清单」含 `ProductController` + `ProductService`（有 Impl 则含 Impl）  
3. 抽一条 `ci_method_call`：`target_signature` 形如 `ProductService#listProducts()`  
4. 旧增量/全量任务不重解析：仅部署后重跑 `GENERATING_DOC`（若产品支持）或整任务，确认读侧兜底仍能扩到 Service  

---

## 八、实施顺序

1. A1 + A3（写出完整 target）  
2. A4（BFS + candidates + 旧数据）  
3. A5 单测绿灯  
4. B（权威清单）  
5. A2 Regex 对齐  
6. 本地全量任务目测文档  

确认本方案后开始改代码。
