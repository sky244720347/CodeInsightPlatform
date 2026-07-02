# 知识查看：列表 / 树形双模式方案

> **状态**：7.0 知识查询已拆为三页；本文档描述「知识文档」子页行为。详见 [knowledge-query-split-plan.md](./knowledge-query-split-plan.md)。  
> **页面**：`frontend/src/pages/knowledge/documents.tsx`（实现于 `index.tsx`）  
> **API**：`GET /api/knowledge/browse`（分页）、`GET /api/knowledge/browse/tree`

## 已实现行为

| 维度 | 列表模式 | 树形模式（默认） |
|------|----------|------------------|
| 系统 | 可选（空=跨全部） | 必选 |
| 仓库 | 可选 | 必选 |
| 搜索 | 文件名 / 系统 / 仓库 + 高级筛选 | 树内关键字过滤 |
| 展示 | 平铺 Table + 服务端分页 | 模块 → 子模块 → 功能（叶子） |
| 文档 | 行点击 / 查看 | 叶子「查看」或点击节点 |
| 类型 | 未选仓库：DRAFT / INDEX / MANIFEST；**已选仓库：仅 INDEX / MANIFEST（生效发布版）** | 已发布 NAS releases 文档 |

## 后端

- `KnowledgeBrowseQuery`：`systemId` / `repositoryId` 可选；`current` / `size` 分页
- **已选 `repositoryId`**：读 `ci_repository.last_published_version_id` → `releases/{sys}/{repo}/{versionNum}/`
- 树形层级：`ci_repository_module_hierarchy`（仓库已发布态）
- `GET /knowledge/browse/tree?systemId=&repositoryId=` → `KnowledgeBrowseTreeResult`（含 `versionId` / `versionNum`）
- 文档粒度：`code-insight.doc-generation.granularity`（function / module）
- 跨仓库 / 未选仓库：仍走任务草稿 DB + temp_repos（开发调试路径）

## 前端

- 默认树形；`localStorage` 键 `ci-knowledge-view-mode` 记忆偏好
- 选仓库后展示「当前生效：vX.Y.Z」
- 预览支持 `contentUri`（`release:...`）读取 NAS 正文

## 发布 / 回滚联动

- 发布成功更新 `last_published_version_id` 并写入 `ci_repository_publish_snapshot`
- 「回滚到该版本」恢复仓库配置并切换生效指针；知识查看自动读对应 release 目录
