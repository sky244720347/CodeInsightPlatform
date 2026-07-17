# 知识查看下拉过滤已发布仓库 — 实施方案

## 背景 / 需求

`/knowledge/entrypoints`、`/knowledge/hierarchy`、`/knowledge/documents` 三个查询页（"入口查看"、"模块层级查看"、"知识查看"）顶部的"系统 / 仓库"下拉当前会列出全部已登记的系统和仓库，没有发布的也会出现，造成空表/空树无意义提示。

**目标**：下拉只展示有已发布知识的系统和仓库；从未发布过知识的仓库不出现。

## 判断"已发布"的依据

- 数据列：`ci_repository.last_published_version_id`（推送成功时设置，NULL 即未发布）
- 数据库：
  - `ci_repository.last_published_version_id IS NOT NULL` ⇒ 该仓库已发布
  - 该系统下存在至少一个 `last_published_version_id IS NOT NULL` 的仓库 ⇒ 该系统已发布

## 实现思路：后端过滤 + 前端传参（不破坏现有调用）

### 后端：新增可选参数 `hasPublished`

| 接口 | 默认行为 | `hasPublished=true` 行为 |
|---|---|---|
| `GET /systems` | 列出全部系统 | 仅返回含已发布仓库的系统 |
| `GET /repositories` | 列出全部仓库 | 仅返回 `last_published_version_id IS NOT NULL` 的仓库 |

`hasPublished` 缺省为 null（兼容老调用方）：行为与改动前一致。

### 前端：`useKnowledgeQueryContext` 传 `hasPublished: true`

三个知识查询页都通过 `useKnowledgeQueryContext` 读取系统/仓库下拉数据，仅这一个 hook 内传参即可让三个页面全部受惠；其他页面（systems/、tasks/ 等）继续调原始 API、不传参，行为不变。

## 受影响页面

- 知识查看（`frontend/src/pages/knowledge/index.tsx`）— 文档
- 入口查看（`frontend/src/pages/knowledge/entrypoints.tsx`）
- 模块层级查看（`frontend/src/pages/knowledge/hierarchy.tsx`）

均通过 `useKnowledgeQueryContext` 拿到 `systems` / `repositories`，由 hook 内部统一加过滤，无需在页面里再处理。

## 涉及文件

### 后端

| 文件 | 改动 |
|---|---|
| `backend/src/main/java/com/company/codeinsight/modules/system/controller/SystemApplicationController.java` | `listSystems` 加 `@RequestParam(required = false) Boolean hasPublished`，转发给 service |
| `backend/src/main/java/com/company/codeinsight/modules/system/service/SystemApplicationService.java` | `listSystemsPage` 签名追加 `Boolean hasPublished` 参数 |
| `backend/src/main/java/com/company/codeinsight/modules/system/service/impl/SystemApplicationServiceImpl.java` | 转发 `hasPublished` 给 mapper |
| `backend/src/main/java/com/company/codeinsight/modules/system/mapper/SystemApplicationMapper.java` | 方法签名追加 `@Param("hasPublished") Boolean hasPublished` |
| `backend/src/main/resources/mapper/SystemApplicationMapper.xml` | SQL 在 `<where>` 内追加 `EXISTS` 子查询（仅 `hasPublished=true` 时生效），系统聚合指标 `kv.cnt` 保持不变 |
| `backend/src/main/java/com/company/codeinsight/modules/repository/controller/CodeRepositoryController.java` | `listRepositories` 加 `@RequestParam(required = false) Boolean hasPublished`，转发给 service |
| `backend/src/main/java/com/company/codeinsight/modules/repository/service/CodeRepositoryService.java` | `listRepositoriesPage` 签名追加 `Boolean hasPublished` 参数 |
| `backend/src/main/java/com/company/codeinsight/modules/repository/service/impl/CodeRepositoryServiceImpl.java` | `LambdaQueryWrapper` 追加 `isNotNull(CodeRepository::getLastPublishedVersionId)`（仅 `hasPublished=true` 时生效） |

### 前端

| 文件 | 改动 |
|---|---|
| `frontend/src/api/system.ts` | `listSystems` 入参类型追加 `hasPublished?: boolean`，透传给 query |
| `frontend/src/api/repository.ts` | `listRepositories` 入参类型追加 `hasPublished?: boolean`，透传给 query |
| `frontend/src/pages/knowledge/useKnowledgeQueryContext.ts` | 调用 `listSystems` 和 `listRepositories` 时加 `hasPublished: true` |

## 关键 SQL 草案

`SystemApplicationMapper.xml` 在现有 `<where>` 末尾追加（MyBatis 动态 SQL）：

```xml
<if test="hasPublished != null and hasPublished == true">
    AND EXISTS (
        SELECT 1
        FROM ci_repository r
        WHERE r.system_id = s.id
          AND r.deleted_at IS NULL
          AND r.last_published_version_id IS NOT NULL
    )
</if>
```

`CodeRepositoryServiceImpl` 追加：

```java
queryWrapper.isNotNull(Boolean.TRUE.equals(hasPublished), CodeRepository::getLastPublishedVersionId);
```

## 验证步骤

1. 后端编译：`mvn -DskipTests compile`
2. 前端 Lint：`cd frontend && npm run lint`
3. 端到端手工：
   - 准备一个含 PUSHED 版本记录的仓库 A、一个无任何发布记录的仓库 B
   - 打开 `/knowledge/entrypoints` → 系统下拉应只含 A 所属的系统；选该系统后，仓库下拉只显示 A
   - 同样验证 `/knowledge/hierarchy`、`/knowledge/documents`
   - 打开 `/systems`、`/repositories` 管理页（如果使用未传参的列表）确认无回归
4. 单测（沙盒无 PG 可跳过）：`mvn -Dtest=CodeRepositoryServiceTests test`、`mvn -Dtest=SystemApplicationServiceTests test`

## 兼容性

- `hasPublished` 缺省为 null → 与改动前完全一致
- 所有现有调用方（前端 systems 页、tasks 页等）不传参，无回归
- Service / Mapper 方法签名向后兼容（旧测试调用 `listRepositoriesPage(1, 10, 1L, "dummy")` 编译期就允许 hasPublished 缺省为 null）

## 不在范围内

- 不修改 `KnowledgeContextBar.tsx`：它接收的就是已过滤的 systems/repositories
- 不新增独立的 `/systems/published` 接口：使用参数开关比新接口更轻量
- 不改数据库 schema：现有 `last_published_version_id` 列已足够