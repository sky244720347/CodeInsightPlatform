# MODULE_HIERARCHY 阶段 AI 提取方案

> 独立设计文档：与 [incremental-baseline-design.md](./incremental-baseline-design.md) 解耦，可独立实施。
> 适用范围：所有类型的反编译任务（INITIAL / INCREMENTAL）。

---

## 一、设计目标

**保证 AI 提取 MODULE_HIERARCHY 的准确性，同时不让知识文档生成阶段变慢。**

### 三个核心结论

1. **AI 提取（MODULE_HIERARCHY）必须串行**——AI 看到最新 hierarchy 才能避免"重新发明"
2. **知识文档生成（GENERATING_DOC）保持并行**——按模块独立，无依赖关系
3. **共享上下文模式**——AI 看到的是精简版 hierarchy（id + name + keywords），按 ID 复用/新建

### 核心思想

```
MODULE_HIERARCHY（AI 提取）：串行 + 共享上下文
  for entry in toProcess:
    hierarchyJson = serializeForPrompt(hierarchy)   ← 精简版（去 classPath / methodSignature）
    inc = callAiForEntry(entry, hierarchyJson)      ← AI 看到最新结构
    mergeIncrementIntoHierarchy(hierarchy, inc)     ← 立即合并
  # 下一个 entry 看到的是合并后的最新树

GENERATING_DOC（知识草稿）：保持并行
  for module in retargetModules:                     ← 按模块独立
    async generateModuleDraft(module)                ← 互不影响
```

---

## 二、当前实现的问题

### 2.1 AI 并行调用，hierarchy 是调用时点快照

[`ModuleHierarchyServiceImpl.java:225-240`](backend/src/main/java/com/company/codeinsight/modules/hierarchy/service/impl/ModuleHierarchyServiceImpl.java#L225-L240)：

```java
List<CompletableFuture<JsonNode>> futures = toProcess.stream()
        .map(entry -> CompletableFuture.supplyAsync(
                () -> callAiForEntry(finalTask, entry, finalPrompt, finalProjectDir, finalConfig),
                aiExecutor))   // ← 并行（4 线程池）
        .toList();

// 顺序合并（hierarchy 在 AI 调用期间是只读快照）
for (int i = 0; i < futures.size(); i++) {
    JsonNode inc = futures.get(i).join();
    if (inc != null) {
        mergeEntryResult(hierarchy, toProcess.get(i), inc, methodsByClass);
        ...
    }
}
```

**问题**：
- AI 1 看到 `hierarchy` 快照（假设只有 mA1b2）→ 输出新模块 mC3d4
- AI 2 **也**看到同样的 `hierarchy` 快照（mA1b2）→ 输出新模块 mE5f6
- AI 1 + AI 2 并行期间，hierarchy 不变
- 合并时把 mA1b2 + mC3d4 + mE5f6 一起写入——**模块结构被拆碎**
- 同一类入口被分到不同模块

### 2.2 AI 不传已有 hierarchy 给 prompt

[`ModuleHierarchyServiceImpl.java:573`](backend/src/main/java/com/company/codeinsight/modules/hierarchy/service/impl/ModuleHierarchyServiceImpl.java#L573)：

```java
String promptInput = promptTemplateLoader.render(promptTemplate, javaCode, businessKnowledge, "{}");
//                                                                          ^^^
//                                                                          第 4 个参数：moduleHierarchy
//                                                                          当前传的是空对象 "{}"
```

**问题**：
- prompt 模板 [`analyze_prompt.md:18`](backend/src/main/resources/analyze_prompt.md#L18) 已经预留 `{module_hierarchy.json}` 占位符
- prompt 模板第 9 行明确要求 AI "**只输出相对已有 module_hierarchy.json 的增量模块信息**"
- 但代码传的是 `"{}"`（空对象），AI 看不到已有结构
- 结果：AI 每次都"重新发明"模块 ID / 名称

### 2.3 影响的严重程度

| 任务类型 | 当前问题 | 严重程度 |
|---|---|---|
| **INITIAL 任务** | AI 多次独立分析，模块结构被拆碎 | 中（首次接入） |
| **INCREMENTAL 任务** | 基线节点 + 本次新节点共存（详见 incremental-baseline-design.md） | **高** |
| **GENERATING_DOC** | 按模块独立，**不受影响** | 无 |

---

## 三、方案设计

### 3.1 核心改造点

| 改造点 | 位置 | 改动 |
|---|---|---|
| **A. AI 串行** | `ModuleHierarchyServiceImpl.buildAndPersist` | for 循环串行调用，**立即合并** |
| **B. 传 hierarchy 给 AI** | `callAiForEntry` | 加 `ModuleHierarchy` 参数，序列化为精简 JSON |
| **C. 精简序列化** | 新增 `serializeHierarchyForPrompt` 方法 | 去 `class_paths` / `method_signatures` |
| **D. 知识文档保持并行** | `AiSummaryServiceImpl.generateDraftDocument` | **不动** |

### 3.2 串行 vs 并行对比

| 维度 | 当前（并行） | 改造后（串行） |
|---|---|---|
| AI 看到的 hierarchy | 调用时点快照 | **每次最新** |
| 模块结构稳定性 | 拆碎风险 | **稳定一致**（AI 复用 ID） |
| INITIAL 任务耗时 | ~N/4 × T_ai（4 线程） | N × T_ai（串行） |
| INCREMENTAL 任务耗时 | retarget/4 × T_ai | retarget × T_ai（通常 < 5） |
| 知识文档生成 | 独立，不受影响 | **保持并行** |
| 提示词模板 | 不动 | 不动 |

### 3.3 精简 hierarchy 字段

**保留**：
- `module.id` / `module.module_name` / `module.keywords`
- `sub_module.id` / `sub_module.sub_module_name` / `sub_module.keywords`
- `function.id` / `function.function_name`（function 没有 keywords 字段，详见 prompt 模板第 109 行）

**去掉**：
- `function.class_paths`（冗余，AI 会自行判断）
- `function.method_signatures`（冗余，对结构判断无帮助）
- 任何 URL / 时间戳等元数据

**精简 JSON 形状**（与 prompt 模板第 22-46 行格式一致）：

```json
{
  "modules": [
    {
      "id": "mA1b2",
      "module_name": "存量扫描",
      "keywords": ["配置", "查询"],
      "sub_modules": [
        {
          "id": "sXy9Z",
          "sub_module_name": "存量查询",
          "keywords": ["查询"],
          "functions": [
            {"id": "fC3d4", "function_name": "存量查询执行"}
          ]
        }
      ]
    }
  ]
}
```

### 3.4 单次调用 + 共享上下文（不循环）

**选择理由**：
- 串行调用后，AI 输出**立即合并**到 hierarchy，下一个 AI 看到的是合并后的最新树
- 效果上等价于"AI 协作编辑"，但实现是单次调用 + 顺序合并
- 不需要"循环收敛"判定
- 不增加 prompt 调用次数

```
入口 1 串行调用 → AI 输出增量 → 合并 → hierarchy 更新
入口 2 串行调用 → AI 看到入口 1 的输出 → 复用 ID → 合并
入口 3 串行调用 → AI 看到入口 1+2 的输出 → 复用 ID → 合并
```

---

## 四、详细设计

### 4.1 新增 `serializeHierarchyForPrompt(ModuleHierarchy)`

**文件**：`ModuleHierarchyServiceImpl`

```java
/**
 * 序列化 hierarchy 为 AI prompt 用的精简 JSON。
 * <p>保留：id / module_name / sub_module_name / function_name / keywords<br>
 * 去掉：class_paths / method_signatures / url / 时间戳</p>
 * <p>用途：作为 {module_hierarchy.json} 占位符值传给 AI；让 AI 看到已有结构以复用 ID。</p>
 */
private String serializeHierarchyForPrompt(ModuleHierarchy hierarchy) {
    if (hierarchy == null || hierarchy.getModules() == null || hierarchy.getModules().isEmpty()) {
        return "{\"modules\":[]}";
    }
    Map<String, Object> root = new LinkedHashMap<>();
    List<Map<String, Object>> modulesOut = new ArrayList<>();
    for (ModuleDto m : hierarchy.getModules().values()) {
        Map<String, Object> modMap = new LinkedHashMap<>();
        modMap.put("id", m.getId());
        modMap.put("module_name", m.getModuleName());
        modMap.put("keywords", m.getKeywords());
        List<Map<String, Object>> subsOut = new ArrayList<>();
        for (SubModuleDto sm : m.getSubModules().values()) {
            Map<String, Object> subMap = new LinkedHashMap<>();
            subMap.put("id", sm.getId());
            subMap.put("sub_module_name", sm.getSubModuleName());
            subMap.put("keywords", sm.getKeywords());
            List<Map<String, Object>> fnsOut = new ArrayList<>();
            for (FunctionDto fn : sm.getFunctions().values()) {
                Map<String, Object> fnMap = new LinkedHashMap<>();
                fnMap.put("id", fn.getId());
                fnMap.put("function_name", fn.getFunctionName());
                fnsOut.add(fnMap);
            }
            subMap.put("functions", fnsOut);
            subsOut.add(subMap);
        }
        modMap.put("sub_modules", subsOut);
        modulesOut.add(modMap);
    }
    root.put("modules", modulesOut);
    try {
        return objectMapper.writeValueAsString(root);
    } catch (Exception e) {
        log.warn("serializeHierarchyForPrompt 失败，返回空对象：{}", e.getMessage());
        return "{\"modules\":[]}";
    }
}
```

### 4.2 改造 `callAiForEntry`

**文件**：`ModuleHierarchyServiceImpl.java:559`

**当前签名**：
```java
private JsonNode callAiForEntry(DecompileTask task, EntryPoint entry,
                                String promptTemplate, File projectDir,
                                EntryPointConfig entryPointConfig)
```

**改造后签名**：
```java
private JsonNode callAiForEntry(DecompileTask task, EntryPoint entry,
                                String promptTemplate, File projectDir,
                                EntryPointConfig entryPointConfig,
                                ModuleHierarchy existingHierarchy)   // ← 新增
```

**关键改动**（line 573）：

```java
// 改造前
String promptInput = promptTemplateLoader.render(promptTemplate, javaCode, businessKnowledge, "{}");

// 改造后
String hierarchyJson = serializeHierarchyForPrompt(existingHierarchy);
String promptInput = promptTemplateLoader.render(
        promptTemplate, javaCode, businessKnowledge, hierarchyJson);
```

### 4.3 改造 `buildAndPersist` 调度入口（串行）

**文件**：`ModuleHierarchyServiceImpl.java:225-240`

**改造前（并行）**：
```java
List<CompletableFuture<JsonNode>> futures = toProcess.stream()
        .map(entry -> CompletableFuture.supplyAsync(
                () -> callAiForEntry(finalTask, entry, finalPrompt, finalProjectDir, finalConfig),
                aiExecutor))
        .toList();
for (int i = 0; i < futures.size(); i++) {
    JsonNode inc = futures.get(i).join();
    if (inc != null) {
        mergeEntryResult(hierarchy, toProcess.get(i), inc, methodsByClass);
        persistMethodBindingsFromIncrement(taskId, task.getSystemId(),
                toProcess.get(i), inc, methodsByClass);
        processedByAi++;
    }
}
```

**改造后（串行）**：
```java
// 串行调用 AI：每次合并后下一个 AI 立即看到最新 hierarchy
for (EntryPoint entry : toProcess) {
    JsonNode inc = callAiForEntry(finalTask, entry, finalPrompt, finalProjectDir, finalConfig, hierarchy);
    if (inc != null) {
        mergeEntryResult(hierarchy, entry, inc, methodsByClass);
        persistMethodBindingsFromIncrement(taskId, task.getSystemId(),
                entry, inc, methodsByClass);
        processedByAi++;
        log.info("MODULE_HIERARCHY 串行处理进度 — taskId={} {}/{} entry={}",
                taskId, processedByAi, toProcess.size(), entry.getClassName());
    }
}
```

### 4.4 知识文档生成（保持并行，不动）

**文件**：`AiSummaryServiceImpl.generateDraftDocument`（line 289+）

```java
for (ModuleDto moduleDto : hierarchy.getModules().values()) {
    if (impact != null && impact.isIncremental()) {
        if (!impact.getDocRetargetModuleIds().contains(moduleDto.getId())) {
            continue;  // 跳过：从基线继承
        }
    }
    generateModuleDraft(task, ws, moduleDto, hierarchy, projectDir);
    // ↑ 当前是顺序 for 循环；可改为 CompletableFuture 并行
}
```

**后续可优化**（本次不做）：把 for 循环改为 `CompletableFuture` 并行，每个模块独立生成。

---

## 五、性能影响

### 5.1 INITIAL 任务（首次接入）

| 入口数 N | 单次 AI 耗时 T | 当前并行（4 线程） | 改造后串行 |
|---|---|---|---|
| 10 | 5s | ~12s | 50s |
| 50 | 5s | ~62s | 250s |
| 100 | 5s | ~125s | 500s |

**影响**：INITIAL 任务总耗时增加 3-4 倍，但**仅首次接入**有影响；后续 INCREMENTAL 任务几乎无影响。

**业务可接受**：
- INITIAL 任务是"首次接入"的初始化操作，运行频率低
- 增加的耗时换来"模块结构稳定 + 后续 INCREMENTAL 任务基线准确"
- ROI 合理

### 5.2 INCREMENTAL 任务

| 场景 | 串行耗时 | 说明 |
|---|---|---|
| retarget = 1 个入口 | 1 × T_ai（~5s） | 几乎无影响 |
| retarget = 5 个入口 | 5 × T_ai（~25s） | 可接受 |
| retarget = 20 个入口 | 20 × T_ai（~100s） | 仍可接受（罕见场景） |

**影响**：INCREMENTAL 任务串行化**几乎不影响性能**（retarget 入口少）。

### 5.3 知识文档生成（保持并行）

- 按模块粒度独立，无依赖关系
- N 个模块并行（4 线程）= N/4 × T_ai
- INITIAL 任务 50 个模块：~12 × 5 = 60s
- INCREMENTAL 任务 5 个模块：~5/4 × 5 = 6s

**不受影响**——本次改造只动 AI 提取阶段。

---

## 六、行为保证

### 6.1 INITIAL 任务

```
入口 1（UserController）：
  hierarchy = {}（空）
  AI 输出: mA1b2 / sXy9Z / fC3d4（用户管理 / 用户认证 / 登录接口）
  合并到 hierarchy
  hierarchy = {mA1b2: {sXy9Z: {fC3d4}}}

入口 2（UserService）：
  hierarchy = {mA1b2: {sXy9Z: {fC3d4}}}（上一个 AI 的输出）
  AI 看到 mA1b2，输出"我建议挂到 mA1b2.sXy9Z.fE5f6"（用户管理 / 用户认证 / 用户校验）
  合并到 hierarchy（ID 命中，复用）
  hierarchy = {mA1b2: {sXy9Z: {fC3d4, fE5f6}}}

入口 3（OrderController）：
  hierarchy = {mA1b2: {sXy9Z: {fC3d4, fE5f6}}}
  AI 看到 mA1b2（用户管理），但 OrderController 是订单相关
  AI 输出: mG7h8 / sI9j0 / fK1l2（订单管理 / 订单查询 / 订单列表）
  合并到 hierarchy
  hierarchy = {mA1b2: {...}, mG7h8: {...}}
```

**结果**：模块结构稳定，避免被拆碎。

### 6.2 INCREMENTAL 任务

```
hierarchy（基线继承）= {mA1b2: {sXy9Z: {fC3d4}}, mM3n4: {sO5p6: {fQ7r8}}}

retarget 入口（UserController）：
  hierarchy = 基线继承
  AI 看到 mA1b2，输出"我建议复用 mA1b2.sXy9Z.fE5f6"
  合并到 hierarchy
  hierarchy = 基线 + 复用节点

retarget 入口（新加 OrderController）：
  hierarchy = 基线 + 复用节点
  AI 输出: mG7h8 / sI9j0 / fK1l2
  合并到 hierarchy
```

**结果**：避免基线节点 + 本次新节点共存。

### 6.3 知识文档生成（保持并行）

- 模块树已经稳定（来自 AI 提取串行）
- 按模块粒度生成草稿，**互不影响**
- 并行度 = 4 线程池

---

## 七、风险与缓解

| 风险 | 缓解 |
|---|---|
| INITIAL 任务串行耗时增加 | 仅首次接入影响；后续 INCREMENTAL 任务不受影响；可接受 |
| 串行调用时某次 AI 失败 | 现有 try-catch 兜底；不影响后续入口 |
| 串行调用时 OOM / 超时 | 每次 AI 调用独立 HTTP 请求，无累积风险 |
| `hierarchy` 太大序列化超时 | 即使 100 个模块，精简后 ~5KB；远小于 javaCode |
| 多个 AI 并发调用时 hierarchy 写入竞态 | **不存在**——串行后是单线程访问 |
| `mergeIncrementIntoHierarchy` 行为变化 | **不变**——已按 ID 匹配 / 合并 |
| 提示词模板不匹配 | **不动**——已预留占位符 |

---

## 八、关键文件清单

### 修改

| 文件 | 改动 | 行数 |
|---|---|---|
| `ModuleHierarchyServiceImpl.java` | 新增 `serializeHierarchyForPrompt`；`callAiForEntry` 加 `ModuleHierarchy` 参数；`buildAndPersist` 调度入口串行化 | ~70 |

### 不动

| 文件 | 原因 |
|---|---|
| `analyze_prompt.md` | 已预留 `{module_hierarchy.json}` 占位符 |
| `mergeIncrementIntoHierarchy` | 已按 ID 匹配 / 合并，符合 AI 输出增量的语义 |
| `AiSummaryServiceImpl.generateDraftDocument` | 知识文档生成保持并行 |
| `IncrementalContext` | 与本方案独立（详见 incremental-baseline-design.md） |
| `BaselineInheritanceService` | 与本方案独立 |

---

## 九、关键工具与模式

### 复用现有函数

| 函数 | 路径 | 用途 |
|---|---|---|
| `mergeIncrementIntoHierarchy` | `hierarchy/service/impl/ModuleHierarchyServiceImpl.java` | AI 输出增量合并（已实现） |
| `mergeEntryResult` | 同上 | 单次 AI 输出的合并入口 |
| `objectMapper` | 同上 | JSON 序列化（已注入） |
| `promptTemplateLoader.render` | `common/util/PromptTemplateLoader.java` | 模板渲染（已支持 4 个参数） |

### 新增方法

| 方法 | 位置 | 用途 |
|---|---|---|
| `serializeHierarchyForPrompt(ModuleHierarchy)` | `ModuleHierarchyServiceImpl` | 精简序列化（去 classPath / methodSignature） |

---

## 十、验证

### 编译

```bash
cd backend
mvn -DskipTests compile
```

### 单元测试

```bash
mvn -Dtest=DecompileTaskServiceTests test
mvn -Dtest=TaskStateMachineRemediationTransitTest test
```

### 端到端验证（需要本地 PG + Redis）

| 场景 | 预期 |
|---|---|
| 1. INITIAL 任务跑完，模块结构稳定 | 同一类入口被分到同一模块；模块数合理（不暴增） |
| 2. INCREMENTAL 任务（基线已有结构） | AI 复用基线 ID；基线节点 + 本次新节点不共存 |
| 3. 看 log：`MODULE_HIERARCHY 串行处理进度` | 串行打印每个入口处理进度 |
| 4. 看 log：传给 AI 的 prompt 包含 `{module_hierarchy.json}` 占位符值 | 不是空 `{}` |

### 调试建议

在 `callAiForEntry` 内加一行日志（**实施后**可选加）：

```java
log.debug("传给 AI 的 hierarchy 精简版: {}", hierarchyJson);
```

这样可以在调试时看到 AI 实际收到的 hierarchy。

---

## 十一、实施步骤

| Phase | 步骤 | 工作量 |
|---|---|---|
| **1** | 新增 `serializeHierarchyForPrompt` 方法 + `callAiForEntry` 加 `ModuleHierarchy` 参数 | 1-2 小时 |
| **2** | 改造 `buildAndPersist` 调度入口为串行 | 1 小时 |
| **3** | 编译验证 + 端到端测试 | 1-2 小时 |
| **总计** | | 半天 |

---

## 十二、与 INCREMENTAL 任务方案的关系

本方案是**独立**的——可以在不实施 INCREMENTAL 任务改造的情况下单独上线。

| 维度 | INCREMENTAL 任务 | 本方案（AI 提取串行） |
|---|---|---|
| 目标 | 任务间增量继承基线 | 任务内 AI 提取准确性 |
| 改造范围 | 5 阶段流水线 | MODULE_HIERARCHY 单阶段 |
| 实施顺序 | 可独立 | **先做本方案**（作为基础） |
| 协同效果 | INCREMENTAL 任务下，本方案确保基线节点 + 本次新节点正确合并 | 本方案独立运作，INITIAL/INCREMENTAL 都受益 |

**推荐实施顺序**：
1. **先做本方案**（AI 提取串行 + 共享上下文）—— 立即提升所有任务的 AI 提取准确性
2. **再做 INCREMENTAL 任务方案**（incremental-baseline-design.md）—— 跨任务基线继承

---

## 十三、一句话总结

> **AI 提取（MODULE_HIERARCHY）改为串行 + 共享上下文（精简版 hierarchy 作为 prompt 占位符值）；知识文档生成（GENERATING_DOC）保持并行。**
>
> 串行确保 AI 看到最新 hierarchy 避免"重新发明"；并行知识文档生成保持高效。
