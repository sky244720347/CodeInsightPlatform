# 知识文档类路径幻觉修复方案

> **管什么**：`GENERATING_DOC` 阶段 AI 输出「涉及类清单」出现伪造包名（如 `com.peig.prep`）的根因与修复。  
> **不管什么**：模块层级分析（`analyze_prompt.md` / `PromptViewDtos` 剥离 `class_paths`）；AI Mock；文档 Markdown 表格后处理纠偏（二期）。  
> **状态**：已实施 A+B+C+E（2026-07-20）；D（表格后处理）暂缓。

---

## 〇、问题结论

| 假设 | 结论 |
|---|---|
| 走了 Mock？ | **否**。Mock 返回 `"{}"`，走占位文档，不会产出完整类路径表。 |
| Java 不含类名/包？ | **主路径基本成立**：`filterClassToMethods` 只截方法体，注释头多为短类名；`module_hierarchy.json` 故意不含 `class_paths`。 |
| 为何有时又对？ | 整文件回退、方法体内 FQ 引用、或模型按短名推断碰巧命中。 |
| 直接诱因 | `module_doc_prompt.md` 示例写死 `com.peig.prep...`，BFS 多类方法体时模型易照抄。 |

原则（与平台一致）：**类路径由程序提供，AI 只写业务说明。**

---

## 一、本期范围

### 做

| 编号 | 项 |
|---|---|
| A1–A3 | 改 `module_doc_prompt.md`：去掉可照抄包名；权威清单规则；方法标题用 FQ |
| A4 | 运维需对已 RELEASED 的 `DOCUMENT_GENERATION` 执行 `sync-from-resource`（见 §五） |
| B1–B4 | FUNCTION_DOC / MODULE_DOC 注入 `class_paths` + `methods[]`；FQ 解析链路 |
| C1–C2 | `{java.code}` 每类块：`// === Class: FQ ===` + `package ...;` + 方法体 |
| E1 | 单测：提示词无 `peig.prep`；scoped JSON / 源码头含 FQ |

### 暂缓

| 编号 | 项 |
|---|---|
| D1–D2 | AI 返回后表格类路径后处理纠偏 |

### 明确不做

- 不改 analyze 的 `PromptViewDtos` 剥离逻辑  
- 不默认喂整文件  
- 不改 Mock 行为  

---

## 二、目标行为

### 2.1 提示词输入

`{module_hierarchy.json}`（功能粒度 scoped）形状：

```json
{
  "modules": [{
    "id": "mxxxx",
    "moduleName": "商品管理",
    "keywords": [],
    "subModules": [{
      "id": "sxxxx",
      "subModuleName": "商品查询",
      "keywords": [],
      "functions": [{
        "id": "fxxxx",
        "functionName": "商品查询",
        "class_paths": [
          "com.codeinsight.demo.controller.ProductController",
          "com.codeinsight.demo.service.ProductService"
        ],
        "methods": [
          {
            "classFq": "com.codeinsight.demo.controller.ProductController",
            "methodSignature": "listInStockProducts()"
          }
        ]
      }]
    }]
  }]
}
```

规则写入提示词：

- 「涉及类清单」**类路径列必须原样使用** `class_paths`（及 java 块 FQ 头）  
- **禁止**自造包名、**禁止**照抄示例中的占位符  

### 2.2 `{java.code}` 块格式

```text
// === Class: com.example.controller.ProductController ===
package com.example.controller;
    public ProductVO detail(Long id) {
        ...
    }

// === Class: com.example.service.ProductService ===
package com.example.service;
    public ProductVO findById(Long id) {
        ...
    }
```

仍不喂 import / 字段 / 未命中方法（控 token）。

---

## 三、FQ 解析优先级（B4）

对短类名或可疑名 `resolveFqClassName(taskId, nameHint, projectDir, parsed?)`：

1. 若 `nameHint` 已含 `.`（视为 FQ）→ 直接使用  
2. `ParsedClassInfo.packageName` + `className`（解析源文件）  
3. 由 `file_path` 反推（去掉 `**/src/main/java/` 或 `**/src/test/java/` 前缀，`/` → `.`）  
4. 与 `fn.classPaths` 按短名匹配  
5. 仍失败 → **保留短名** + `warn` 日志，**不编造**包名  

权威类清单 `resolveAuthoritativeClasses(taskId, fn, projectDir)`：

1. `ci_method_function_binding` 中该类功能的 `class_name`（升 FQ）  
2. 否则 `fn.classPaths`  
3. 若仍空且能 BFS：可达类短名升 FQ（与源码收集同一根签名）  
4. 去重、保序  

`methods[]`：binding 行优先；否则 `classPaths[0] × methodSignatures` 笛卡尔积（兼容旧数据）。

---

## 四、代码改动点

| 文件 | 改动摘要 |
|---|---|
| `backend/src/main/resources/module_doc_prompt.md` | A：示例占位 + 权威清单硬规则；顺带去掉重复的「3. 输入输出」标题 |
| `AiSummaryServiceImpl.java` | B/C：`buildScopedHierarchyJson` 扩字段；模块文档 JSON 同构；`collect*SourceCode` 写 FQ+package 头；新增 resolve 辅助方法 |
| `PromptViewDtos.java` | **不改**（analyze 专用） |
| `backend/src/test/.../ModuleDocClassPathPromptTest.java` | E：无 Spring 单测 |

---

## 五、提示词 DB 同步（A4）

任务创建时会把 `DOCUMENT_GENERATION` 提示词内容快照进任务绑定。仅改 classpath 资源**不会**自动更新：

- 已存在的默认 RELEASED 提示词  
- 已创建任务的快照  

运维/本地在合并本改动后执行：

```http
POST /api/prompts/sync-from-resource?promptType=DOCUMENT_GENERATION&resourcePath=module_doc_prompt.md
```

或前端/运维等价入口。新任务走新默认；**旧任务重跑文档**需换绑提示词或重建任务（视现有绑定策略）。

---

## 六、测试计划

| 用例 | 期望 |
|---|---|
| 加载 `module_doc_prompt.md` | 不含 `com.peig.prep`；含「权威」/禁止自造类路径类表述 |
| `buildScopedHierarchyJson`（binding 有 FQ） | JSON 含 `class_paths` 与 `methods[].classFq` |
| 方法截取拼接头 | 输出含 `// === Class: com.demo.X ===` 与 `package com.demo;` |
| 短名 + file_path 可反推 | FQ 解析正确 |
| 升 FQ 失败 | 返回短名、不抛错 |

集成测 `AiSummaryModuleDocTest`（需 PG）不强制改；行为兼容即可。

---

## 七、风险与回滚

| 风险 | 缓解 |
|---|---|
| 提示词变长（多了 class_paths/methods） | 仅本功能 scoped；体量远小于整文件 |
| 多模块 `file_path` 反推偶发失败 | 优先级 2/4 兜底；失败保留短名 |
| 旧任务仍用旧提示词 | A4 sync + 文档说明 |
| analyze 误带 class_paths | 不改 PromptViewDtos / MODULARIZE 渲染路径 |

回滚：还原 `module_doc_prompt.md` + `AiSummaryServiceImpl` 相关方法；再 sync 提示词。

---

## 八、二期（D，未实施）

AI 返回后解析「涉及类清单」表格：类路径不在权威集合 → 按短名替换为权威 FQ；无法匹配则打日志保留。
