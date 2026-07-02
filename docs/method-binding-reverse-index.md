# 方法→功能 反向绑定表与提示词同步

> **记录日期**：2026-07-02（rollback 后定稿）  
> **状态**：地基 + Java 服务落地完成；待真实环境验收  
> **关联**：[incremental-hierarchy-doc-plan.md](./incremental-hierarchy-doc-plan.md)、[roadmap-8-9-plan.md](./roadmap-8-9-plan.md)

---

## 背景

模块提取（hierarchy）阶段此前依赖 `ci_module_hierarchy.method_signatures`（FUNCTION 行下的方法签名 JSON 数组）。AI 不输出该字段时被 `backfillMethodSignaturesFromEntrypoints` 回填为入口类全集，BFS 出大杂烩；模块说明文档的功能级提取因此不可信。

本次改造**不改 AI 输出契约**——`analyze_prompt.md` 的 `modules[].sub_modules[].functions[].class_paths[] + method_signatures[]` 结构保持不变（用户已在 2026-07-02 明确要求"不能动输出的结构"）。

干净的方式是新增**反向绑定表**：

- **旧**：`function → [method_signatures]`（function 持有清单，会被回填污染）
- **新**：`method → (module, sub_module, function)`（每方法 1 行指向功能，权威源）

新表由 Java 端在解析旧 `modules[]` 输出时填充，下游全部基于新表走，避开污染源。

---

## 新增数据表 `ci_method_function_binding`

**位置**：[schema.sql 第 30 节](../backend/src/main/resources/db/schema.sql)

每方法一行，由 `(task_id, class_name, method_signature)` 唯一定位到 `(module_node_id, sub_module_node_id, function_node_id)`。

| 列 | 类型 | 含义 |
|---|---|---|
| `id` | BIGSERIAL | 主键 |
| `task_id` | BIGINT | 关联 `ci_task.id` |
| `system_id` | BIGINT | 冗余系统 ID |
| `module_node_id` | VARCHAR(16) | 5 位 Base62（m 前缀，逻辑 FK → `ci_module_hierarchy.node_id`，不建物理 FK） |
| `sub_module_node_id` | VARCHAR(16) | 5 位 Base62（s 前缀） |
| `function_node_id` | VARCHAR(16) | 5 位 Base62（f 前缀） |
| `class_name` | VARCHAR(512) | 入口类全限定名（如 `com.example.UserController`） |
| `method_signature` | VARCHAR(512) | 方法签名 `methodName(ParamType1,ParamType2)`（不含返回类型） |
| `source` | VARCHAR(16) | 归属来源：`AI` / `USER` / `MIGRATED`（CHECK 约束） |
| `confidence` | DECIMAL(4,3) | 可选，AI 输出 0-1 置信度 |
| `created_at` / `updated_at` | TIMESTAMP | 自动维护 |

**索引**：
- `idx_mfb_task_function` (task_id, function_node_id)：按功能反查 BFS 根方法
- `idx_mfb_task_class` (task_id, class_name)：按入口类聚合（入口复核页用）
- `idx_mfb_task_module` (task_id, module_node_id)：按模块聚合（影响面 / 详情页用）
- `UNIQUE (task_id, class_name, method_signature)`：单方法全局唯一归属

**为什么不建物理 FK？**

- `ci_module_hierarchy.node_id` 不是全任务唯一，是 (task_id, node_id) 复合唯一，跨任务会重号
- 应用层维护逻辑 FK：解析 AI 输出时已保证 module_id/sub_module_id/function_id 三段都来自对应 DTO 树
- 简化迁移：未来如果模块层级重构（如换 DTO 树结构），不需要 ALTER CONSTRAINT

---

## binding 表如何填（不依赖 AI 改 schema）

`ModuleHierarchyServiceImpl.persistMethodBindingsFromIncrement` 现在解析 AI 输出的标准 `modules[]` 格式，不再要求 prompt 加 `method_bindings[]` 字段：

```
AI JSON:
{
  "modules": [{
    "sub_modules": [{
      "functions": [{
        "id": "f3AbC",
        "class_paths": ["com.example.UserController"],
        "method_signatures": ["listUsers(Integer,Integer)", "queryUser(Long)"]
      }]
    }]
  }]
}
   │
   ▼
笛卡尔积: class_paths × method_signatures = 2 个 (class, sig) 元组
   │
   ▼
ci_method_call 交叉校验（task 内 caller_signature 是否真实存在）
   │
   ▼
写入 ci_method_function_binding: 2 行
```

**笛卡尔积不是"全集污染"**——是 AI 显式给出的 class_paths[i] × method_signatures[j] 的有限集合。对于一个 function 通常远小于入口类的全集方法数。AI 通常给出 1-3 个 class_paths、每个含 1-5 个 method_signatures，笛卡尔通常 1-15 行，远小于入口类的 20-50 个全集方法。

**交叉校验**是兜底：AI 偶尔笔误给出一个不存在的 (class, sig)，通过 `MethodCallMapper.selectExistingCallerSignatures` 一次查 DB 剔除。

## Java 层落地

### 实体与 Mapper

| 文件 | 职责 |
|---|---|
| [MethodFunctionBinding.java](../backend/src/main/java/com/company/codeinsight/modules/hierarchy/entity/MethodFunctionBinding.java) | MyBatis Plus 实体（11 字段） |
| [MethodFunctionBindingMapper.java](../backend/src/main/java/com/company/codeinsight/modules/hierarchy/mapper/MethodFunctionBindingMapper.java) | 5 能力：`upsertBinding` / `batchUpsertBindings`（PG `ON CONFLICT`）/ `selectByTaskAndFunction` / `selectByTaskAndClass` / `selectByTaskAndModule` / `deleteByTaskId` |
| [MethodCallMapper.java](../backend/src/main/java/com/company/codeinsight/modules/callchain/mapper/MethodCallMapper.java) | **新增** `selectExistingCallerSignatures(taskId, fullSignatures)`：批量按 full signature 查 ci_method_call 存在性 |

### 入口 [ModuleHierarchyServiceImpl](../backend/src/main/java/com/company/codeinsight/modules/hierarchy/service/impl/ModuleHierarchyServiceImpl.java) 改动

| 改动点 | 说明 |
|---|---|
| `callAiForEntry` **去掉** `methodsByClass` 参数 | prompt 不再注入 `{candidate_methods}`；回到 4 参数 |
| `persistMethodBindingsFromIncrement` **完全改写** | 不读 `method_bindings[]`，而读 `modules[]` 旧格式，对每个 function 做 `class_paths × method_signatures` 笛卡尔积 + `ci_method_call` 交叉校验 |
| `backfillMethodSignaturesFromEntrypoints` **限制回填条件** | 仅当 `fn.classPaths` 也为空时才回填，否则**完全跳过**——根除污染 |

**Phase 2 入口 [AiSummaryServiceImpl](../backend/src/main/java/com/company/codeinsight/modules/ai/service/impl/AiSummaryServiceImpl.java)**

| 原位置 | 改为 |
|---|---|
| `collectFunctionSourceCode` 入口收集 `fn.methodSignatures × classPaths[0]` | 新增 `loadFunctionRootSignatures(taskId, fn)`：先按 `(taskId, fn.id)` 查 `ci_method_function_binding`，每行 `(class, sig) → "class#sig"`；表为空时回退到旧 fn.methodSignatures × classPaths[0] |
| `collectModuleSourceCode` 累加所有 fn.methodSignatures | 直接按 `(taskId, moduleDto.id)` 查 `ci_method_function_binding`，单查询得全模块根方法；无 binding 时退化到按 fn 维度的回退 |
| `insertDraftSourceReferences` 类×方法笛卡尔积 | 按 `ci_method_function_binding` 行 1:1 写入，避免 N×M 笛卡尔积污染；无 binding 时回退 |

---

## 提示词约定（hierarchy AI 输出格式，**未改动**）

为遵循"不能动 analyze_prompt.md 的根基结构"约束，提示词**没有做任何升级**：
- 输入占位符：`{java_code}` / `{business_knowledge.md}` / `{module_hierarchy.json}`（与之前一致）
- 输出 schema：`modules[].sub_modules[].functions[].class_paths[] + method_signatures[]`（与之前一致）
- 字段硬约束、自检清单：完全保持原样

Java 端**反向索引的填表**与提示词的输出契约是**解耦**的——不管 AI 是否输出 `method_bindings[]`、不管旧 schema 是否将来再调整，binding 表的存在与查询都是独立的，提示词契约今后如果要演化也不会破坏已落表的数据。

---

## DB 提示词同步（新接口）

模块升级后，工程里的 `analyze_prompt.md` / `module_doc_prompt.md` 与 DB 里以 RELEASED 锁定的提示词会出现不一致；旧的提示词还在被 `requireTaskPromptContent` 解析出来使用。
**注意：本次方案并不强制依赖 prompt 改动——所以即使不同步该端点，新行为也能工作**；但该端点仍然是基础设施，未来对 analyze_prompt.md 真的有改动（措辞、示例、字段说明）时仍然可用。

新增运维端点：

```
POST /api/prompts/sync-from-resource?promptType=MODULARIZE&resourcePath=analyze_prompt.md
POST /api/prompts/sync-from-resource?promptType=DOCUMENT_GENERATION&resourcePath=module_doc_prompt.md
```

**实现**：[DecompilePromptServiceImpl.syncFromResource](../backend/src/main/java/com/company/codeinsight/modules/prompt/service/impl/DecompilePromptServiceImpl.java)

| 行为 | 说明 |
|---|---|
| 内容一致（MD5 相同） | `{changed:false, reason:"content identical"}`，不落表 |
| 内容不一致 | 新插入一行 RELEASED 默认提示词（version = 老 + 1 或 1），置 is_default=1；调用 `archivePrompt` 把旧默认归档 |
| 首次 seed（无老默认） | 创建 version=1 默认提示词，reason="first seed" |
| 写入审计 | `ci_operation_log` 留痕：`SYNC_PROMPT_FROM_RESOURCE` |

**调用约定**：升级代码（修改了 .md 文件）后运维打这两个端点即可。**不要**直接 UPDATE 表内容（这会破坏 RELEASED 锁定语义）。

---

## 验收清单

跑通下列场景（在真实 PG + Redis 环境）：

1. **新任务**（含入口有候选方法）
   - 创建全量任务 → 等层级阶段完成 → `SELECT * FROM ci_method_function_binding WHERE task_id = ?`
   - 期望每条 binding 的 `method_signature` 严格来自入口的 `methods_json`
   - 期望没有"全集污染"（方法数 ≈ 入口有意义的 HTTP/Scheduler/Listener 方法个数）

2. **老任务**（无 binding 行）
   - 老任务在 Phase 2 时查 `ci_method_function_binding` 返回空 → 自动回退到 `fn.method_signatures × classPaths[0]`
   - 草稿文档与升级前一致（兼容）

3. **同步端点**
   ```
   curl -X POST 'http://localhost:8080/api/prompts/sync-from-resource?promptType=MODULARIZE'
   curl -X POST 'http://localhost:8080/api/prompts/sync-from-resource?promptType=DOCUMENT_GENERATION'
   ```
   - 第一次：`changed=true, reason="first seed"`
   - 内容一致再调用：`changed=false, reason="content identical"`
   - 修改 .md 后再调用：`changed=true, reason="content changed"`，新 version = 老 + 1

4. **multi-class 多入口聚合**
   - 一个功能跨多个 Controller（如 `UserController.listUsers` + `AdminController.listUsers` 同属"账户查询 - 分页查询用户"）
   - 期望 `ci_method_function_binding` 同 `(task_id, function_node_id)` 下有 2 行不同 class 的 binding
   - BFS 同时以两行的 (class, sig) 为根走调用链，不会丢任何一边的实现

---

## 不在本期范围

| 项 | 后续 |
|---|---|
| `ci_module_hierarchy.method_signatures` 列下线 | 留作回退路径，等所有任务全用 binding 后再做 schema deprecation |
| 前端 `KnowledgeHierarchyPage` 展示每条方法归属并支持拖拽调整 → `source='USER'` | 下一迭代 |
| 已有任务的 `method_function_binding` 自动回填（从 `method_signatures` 反推） | 一条 SQL `INSERT INTO ci_method_function_binding SELECT ... FROM ci_module_hierarchy`；本期未做，避免回填后新旧数据不一致 |
| 与 #9 P1 的 `IncrementalImpactAnalyzer` 联动（按 binding 表做"反向 BFS 的入口映射"） | roadmap 已规划，本期为 P5/P6 单独打洞 |
