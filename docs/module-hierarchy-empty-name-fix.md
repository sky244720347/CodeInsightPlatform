# 模块层级空 `module_name` 落库失败修复方案

> **管什么**：AI 分析阶段 `ci_module_hierarchy.name` NOT NULL 违反（`DataIntegrityViolationException`），任务失败、模块数 0。  
> **不管什么**：提示词同步运维流程之外的 DB schema 变更；入口识别 / JavaParser language level。  
> **状态**：已实施（2026-07-20）。  
> **关联**：失败行形如 `level=MODULE, name=null, keywords=["预落单","订单","案件"]`。

---

## 〇、根因

1. `mergeIncrementIntoHierarchy` **只读** `module_name`；AI 漏写或写成 `moduleName`/`name` 时，`ModuleDto.moduleName` 保持 null。  
2. `keywords` 仍会合并 → 出现「有关键词、无名称」。  
3. `persistAll` / `persistIncremental` 直接 `setName(moduleName)`，**无空名校验**，撞 PG `name NOT NULL`。  
4. 人工替换才走 `validateReplacement`；AI 落库路径不走。

---

## 一、修复策略（L1 + L2 + L3）

| 层 | 内容 |
|---|---|
| **L1 读字段容错** | 名称取第一个非空：`module_name` → `moduleName` → `name`（子模块 / 功能同理） |
| **L2 合并期判空** | 名称仍空 → **跳过**该模块 / 子模块 / 功能整段（`SKIP_*_EMPTY_NAME`），**不做** keywords fallback，不新建空名节点 |
| **L3 落库前校验** | `persistAll` / `persistIncremental` 前 `assertHierarchyNamesPresent`；空名抛 `BusinessException`，避免 PG 报错 |

提示词：`analyze_prompt.md` 标明名称字段 **必填非空**。

**明确不做**：用 `keywords[0]` 或固定串「未命名」填名称。

---

## 二、改动文件

| 文件 | 改动 |
|---|---|
| `ModuleHierarchyServiceImpl.java` | L1/L2 合并；L3 assert；`readAiName` |
| `analyze_prompt.md` | 字段硬约束补「必填非空」 |
| `ModuleHierarchyEmptyNameMergeTest.java` | camelCase 可读；空名跳过；persist 前校验 |
| 本文档 | 状态已实施 |

---

## 三、验收

- [x] 仅 `moduleName`（camelCase）可合并出名称  
- [x] 无名称（即使有 keywords）→ **跳过**，不入库空名行  
- [x] persist 前仍有空名 → `BusinessException`（明确文案）
