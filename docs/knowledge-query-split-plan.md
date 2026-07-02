# 知识查询：三页拆分方案

> **状态**：7.0–7.4 已实施（只读浏览 + 导航拆分 + 纠错重跑 + 文档直写）  
> **页面**：`frontend/src/pages/knowledge/{entrypoints,hierarchy,documents}.tsx`

## 导航

侧栏「知识查询」为父级（不跳转），子项：

| 子项 | 路由 | 数据源 |
|------|------|--------|
| 扫描入口 | `/knowledge/entrypoints` | `ci_repository_entrypoint` |
| 模块层级 | `/knowledge/hierarchy` | `ci_repository_module_hierarchy` |
| 知识文档 | `/knowledge/documents` | `releases/{sys}/{repo}/{versionNum}/` |

旧路由 `/knowledge/browse` 重定向至 `/knowledge/documents`。

## 共享壳层

- 组件：`KnowledgeContextBar` + `useKnowledgeQueryContext`
- 筛选：系统 / 仓库（localStorage 键 `ci-knowledge-query-context` 跨页记忆）
- 展示：当前生效 `versionNum`、来源 `taskId`
- 「调整并重跑」：有生效发布版时启用，各页行为不同（见下）

## 只读 API（7.0）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/knowledge/context?repositoryId=` | 生效版本上下文 |
| GET | `/knowledge/entrypoints?repositoryId=&systemId=` | 已发布入口清单 |
| GET | `/knowledge/hierarchy?repositoryId=&systemId=` | 已发布模块层级 |

## 纠错 API（7.1+）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/knowledge/remediation/entrypoints` | 排除入口后从 `AI_ANALYZING` 续跑，全量重算层级 |
| POST | `/knowledge/remediation/hierarchy` | 提交调整后层级 + `moduleIds`，从 `GENERATING_DOC` 续跑 |
| POST | `/knowledge/remediation/documents` | 按 `moduleIds` 从 `GENERATING_DOC` 续跑文档 |
| POST | `/knowledge/remediation/documents/edit` | 提交发布版 Markdown 人工修订（待审） |
| POST | `/knowledge/remediation/documents/edit/{id}/approve` | 批准并直写 NAS release 文件 |

## 各页纠错行为

| 页面 | 触发方式 | 流水线起点 | 备注 |
|------|----------|------------|------|
| 扫描入口 | 编辑模式排除类/方法 → 确认 | `AI_ANALYZING` | 克隆 base task 工作区与 AST |
| 模块层级 | Drawer 编辑 JSON + 选 moduleIds | `GENERATING_DOC` | scope 外模块暂不自动回填 release 草稿 |
| 知识文档 | 树节点「重跑」或批量选模块 | `GENERATING_DOC` | 预览发布版时可「批准并写入」直写 NAS |

## 数据模型

`ci_task` 扩展字段：`remediation_kind`、`base_version_id`、`base_task_id`、`resume_from`、`remediation_scope_json`；`trigger_source` 含 `KNOWLEDGE_REMEDIATION`。

`ci_knowledge_release_edit`：人工修订待审记录，批准后写入当前生效 release 目录并打标 `contentOrigin: HUMAN_EDITED`。

## 已知限制

- 层级/文档 scope 纠错：未在 scope 内的模块不会自动从 release 导入草稿，复核页可能不完整。
- 文档人工修订 MVP 为「提交待审 / 批准并写入」同页操作，无独立审批工作台。
- 纠错任务创建后跳转 `/tasks/{id}`，需在任务详情跟进至 `PENDING_REVIEW`。
