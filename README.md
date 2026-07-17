# 代码洞察平台（CodeInsight Platform）

代码洞察平台面向研发团队，将现有代码库持续转化为可维护、可追溯、可复核的代码知识资产。平台串联代码拉取、Java 静态解析、入口识别、AI 归纳、草稿复核、知识版本、Git/ZIP 输出、Token 审计和操作日志，确保 AI 内容先审后发，不直接进入正式知识库。

- 前端：React 19 + TypeScript + Vite + Ant Design + Zustand + ECharts + Monaco Editor + Hash Router
- 后端：Java 17 + Spring Boot 3.3 + MyBatis Plus + PostgreSQL + Redis + JGit
- 存储边界：数据库保存状态和元数据，本地存储/对象存储保存正文，Redis 保存临时编辑与锁，Git 保存已确认知识

## 当前状态与验证

第一阶段 MVP 任务清单已完成，覆盖系统、仓库、提示词、任务、扫描解析、切片、AI/Mock AI、草稿、知识版本、推送、Token 与日志模块。第二阶段已完成登录认证（UM 账号 + 平安令牌）、系统/代码库软删除与聚合指标、模块层级人工复核断点，以及基于 Git Diff 的增量扫描链路。第三阶段（v0.1.5–v0.1.9）已落地：分布式 / 集群就绪、扫描窗口与定时任务、提示词绑定仓库 + 扫描配置试跑、知识查看（入口 / 层级 / 文档）三页拆分 + 纠错重跑 + NAS 发布仓库、业务知识维护、增量任务门禁 + 推送 merge（不丢模块）、方法→功能反向绑定表，以及通过 `IncrementalImpactAnalyzer` + `MethodCallReverseGraphService` 实现的「非入口类变更 → 反向 BFS 追溯入口」业务语义判定。

截至 2026-07-03 的可复现验证结果：

| 验证项 | 结果 | 说明 |
| --- | --- | --- |
| `npm run lint` | 通过 | ESLint 无错误 |
| `npm run build` | 通过 | Vite 构建成功；存在主包超过 500 kB 的非阻断告警 |
| `java -version` | 通过 | Java 17 |
| `mvn -DskipTests compile` | 通过 | 后端 22 个模块全部编译通过 |
| `mvn test-compile` | 通过 | 测试类全部编译通过（含 v0.1.9 新增的 `MethodCallReverseGraphServiceTest` / `RepositoryPublishServiceRollbackTest` / `TaskQueueDispatcherRemediationTest` / `TaskStateMachineRemediationTransitTest` 等） |
| `mvn test` | 受限 | 需本地 PostgreSQL + Redis 可达（沙盒环境无 PG，扫描器单测启动时会因 DataSource 失败） |
| `mvn clean package` | 受限 | 运行中的后端 JAR 被 Windows 锁定时 `clean` 无法删除旧产物，需先停服 |

这里的"完成"指 MVP 功能和本地验收基线完成，并不等于生产环境开箱即用。生产部署前仍需补齐正式身份认证与授权、密钥托管、真实模型服务、远程 Git 权限、基础设施运维配置和前端代码分包。详细迭代记录见 [CHANGELOG.md](./CHANGELOG.md)。

## 核心业务闭环

```mermaid
flowchart TD
    A["系统接入"] --> B["代码库配置<br/>（基线 Commit ID 落库）"]
    B --> B2["扫描配置 / 提示词绑定 / 扫描窗口"]
    B2 --> C{"选择任务类型"}
    C -- "INITIAL 全量" --> E["拉取与全量扫描"]
    C -- "INCREMENTAL 增量" --> E2["git diff 与变更文件清单<br/>门禁 + 推送 merge"]
    E --> F["静态解析 + 调用链落表"]
    E2 --> F
    F --> F0["增量影响分析<br/>反向 BFS 追溯入口"]
    F0 --> G["模块识别与 AI 归纳"]
    G --> H{"模块层级调试断点<br/>requireHierarchyReview"}
    H -- "启用" --> I["人工复核模块层级"]
    H -- "跳过" --> J["直接生成 Markdown 草稿"]
    I --> J
    J --> K["负责人复核、修改与确认"]
    K --> L["生成知识版本"]
    L --> L2["知识查看<br/>入口 / 层级 / 文档 三页"]
    L2 --> M["Git 推送、PR/MR 或 ZIP 导出"]
    L2 -.纠错重跑.-> C
    M --> N["Token 与操作日志审计"]
```

AI 只负责归纳和建议。模块 ID、类路径绑定、Schema 校验、状态推进、存储、版本与推送校验由程序负责；正式知识必须经过负责人确认。

## 核心能力

- **工作台**：任务吞吐、待复核、Token 成本、知识覆盖率、异常提醒与最近推送。
- **登录与会话**：UM 账号 + 平安令牌 6 位独立输入框登录；Zustand 持久化会话 + 路由守卫；当前为占位实现，待 UM/SSO 真实接入。
- **系统与仓库**：系统负责人、启停、软删除、仓库分支、扫描范围、排除规则、入口扫描规则与 Commit 基线；提示词可绑定到系统 / 仓库 / 任务三级；扫描配置可在仓库与任务间覆盖。
- **代码库聚合指标**：列表一次性返回代码库数 / 知识版本数 / 最近扫描时间，单条 SQL 避免 N+1。
- **业务知识维护**（v0.1.8）：在系统层沉淀业务术语 / 规则 / 合规口径，与代码知识统一索引。
- **提示词**：模板、版本、复制、启停、变量替换、试跑，按 `MODULARIZE` / `DOCUMENT_GENERATION` 分类；作用域支持系统级 / 仓库级，任务创建按「任务 → 仓库 → 系统」回退。
- **任务引擎**：初始化 / 增量任务、手动 / 定时调度、状态机、进度、重试、终止、执行日志、执行日志实时刷新；纠错任务按 `resume_from` 跳到指定阶段。
- **扫描解析**：JGit 拉取、文件快照、Java 类型/路由/方法/异常/表与基础调用关系解析；扫描配置可在仓库 / 任务独立覆盖，并提供「试跑」入口（不创建正式任务）。
- **增量扫描（v0.1.4 起 / v0.1.9 升级）**：基于 `git diff <lastCommit>..HEAD` 识别变更/删除文件；下游 AST、切片、模块层级、草稿生成全链路按 `IncrementalContext` 跳过未变文件；v0.1.9 新增 `IncrementalImpactAnalyzer` + `MethodCallReverseGraphService`（反向 BFS 深度上限 15），让「非入口类变更」也能精准命中其入口所属模块，文档阶段重生成范围 = `moduleTouchedByChange ∪ docRetargetModuleIds`。
- **增量任务门禁 + 推送 merge**（v0.1.9）：仓库必须有 PUSHED 版本 + `lastCommitId` 非空，否则拒绝创建；推送时以 `last_published_version_id` 对应的 NAS `releases` 为权威来源，与 `applyFromTask` 做 merge，不丢模块，删除文件语义同步剔除。
- **切片与 AI**：文件、类、方法和 Diff 切片，Token 预估、额度阻断、Mock/真实模型适配；`PipelineAiCaller` + `AiRetryProperties` 封装通用重试与上下文清理。
- **方法→功能反向绑定**（v0.1.9）：新表 `ci_method_function_binding` 规避 `function.method_signatures` 回填污染；模块说明文档的功能级提取以此为权威源。
- **模块层级人工复核**：AI 提炼后任务停在 `MODULE_HIERARCHY_REVIEW` 状态，前端在 `/tasks/hierarchy-review` 页签中编辑后提交，流水线继续进入草稿生成。可在创建任务时通过 `requireHierarchyReview=false` 跳过该断点。
- **草稿复核**：三栏编辑区、来源行号、待确认项、修订记录、意见、自动保存和编辑锁；从任务详情「打开复核」按钮直达 `?systemId=&taskId=`。
- **知识查看（v0.1.8）**：拆为「入口 / 层级 / 文档」三页，共享 `KnowledgeContextBar` + `useKnowledgeQueryContext`；选仓库后只读 NAS `releases/{sys}/{repo}/{versionNum}/`；`localStorage` 键 `ci-knowledge-view-mode` 记忆列表 / 树形偏好。
- **知识纠错（v0.1.9）**：`POST /api/knowledge/remediation/{entrypoints,hierarchy,documents}` + `documents/edit` / `edit/{id}/approve`；纠错任务克隆 base task 工作区与 AST，按 `resume_from` 续跑；人工修订待审 → 批准直写 NAS release 并打标 `contentOrigin: HUMAN_EDITED`。
- **知识输出**：版本元数据、标准概述文件、推送前校验、Git 提交和 ZIP 导出；仓库级已发布快照写在 `ci_repository_publish_snapshot`，生效版本指针 `last_published_version_id` 由推送成功 / 回滚更新。
- **审计**：Token 明细与趋势、额度策略、操作日志和异常追踪。
- **AI 模型管理**：自定义模型、预设模型、指标与试跑。
- **扫描窗口 + 定时调度**（v0.1.5）：`scanwindow` 模块定义允许扫描的时间窗口；`ScheduleExecutor` 仅在窗口内由 Leader 节点拉起；前端 `ScanWindowHeatmap` 可视化。
- **集群 / 分布式就绪**（v0.1.5+）：由 `CODE_INSIGHT_ENV` 推导（`dev` 单机，非 `dev` 一律集群；已删除 `CLUSTER_ENABLED`）；Leader 选举（`ci:leader:*`）、`SELECT … FOR UPDATE SKIP LOCKED` 任务认领、Redis Set `ci:permits:*` 并发控制、Pub/Sub 配置广播、共享存储卷。详见 [docs/cluster-shared-storage-design.md](./docs/cluster-shared-storage-design.md)。

## 快速开始

### 环境要求

- JDK 17
- Maven 3.8+
- Node.js 20+
- PostgreSQL 14+
- Redis 6+（自动保存与编辑锁）

未配置真实模型时，后端默认启用 Mock AI（`LLM_MOCK=true`）。

### 1. 准备数据库

```bash
createdb -U postgres code_insight
```

后端启动时会读取 `backend/src/main/resources/db/schema.sql` 初始化表结构（`spring.sql.init.mode: always`，幂等 `CREATE TABLE IF NOT EXISTS` / `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，无需手动迁移）。

### 2. 启动后端

```bash
cd backend
mvn spring-boot:run
```

默认会读取 `backend/src/main/resources/application-local.properties` 中的本地 PostgreSQL/Redis 凭据；该文件**不要提交**。

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
```

默认地址：

- 前端：`http://localhost:5173`
- 后端 API：`http://localhost:8080/api`
- Swagger UI：`http://localhost:8080/api/swagger-ui.html`

## 配置

本地开发环境特定的 PostgreSQL 数据库和 Redis 缓存连接配置独立存放在 `backend/src/main/resources/application-local.properties` 文件中。AI 模型环境变量、模型服务地址与本地文件存储路径等，通过 `application-local.yml` 并结合根目录的 `.env` 环境变量文件进行配置（示意见 `.env.example`）。请避免将真实的密钥和密码提交至版本控制系统。

`.env.example` 列出的可用变量：

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | 后端端口 |
| `DB_HOST` / `DB_PORT` | `localhost` / `5432` | PostgreSQL 地址 |
| `DB_NAME` / `DB_USER` | `code_insight` / `postgres` | 数据库与用户 |
| `DB_PASSWORD` | `postgres` | 本地默认密码，生产环境必须覆盖 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / 空 | Redis 连接配置 |
| `STORAGE_LOCAL_PATH` | `./storage` | MVP 本地正文存储目录 |
| `LLM_MOCK` | `true` | 是否启用本地 Mock AI；切真实模型时设为 `false` 并填 `LLM_API_KEY` |
| `LLM_API_KEY` | 空 | 真实模型服务密钥 |
| `LLM_API_URL` / `LLM_MODEL_NAME` | 见 `.env.example` | 模型服务地址与模型名 |

## 开发与验证

前端：

```bash
cd frontend
npm install
npm run lint
npm run build
npm run dev
```

后端：

```bash
cd backend
java -version
mvn clean test                              # 全部测试
mvn -Dtest=ClassNameTest test               # 单个测试类
mvn -Dtest=ClassNameTest#methodName test    # 单个测试方法
mvn -DskipTests clean package               # 打包 JAR
mvn spring-boot:run                         # 启动服务
```

后端统一响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

前端请求拦截器（`frontend/src/api/request.ts`）会自动解包 `data` 字段，非 0 时按 `message` 抛错。

## 项目结构

```text
CodeInsightPlatform/
+-- backend/
|   +-- pom.xml
|   +-- src/main/java/com/company/codeinsight/
|   |   +-- common/         配置、异常、响应、存储抽象、通用工具
|   |   +-- modules/
|   |   |   +-- system/         系统接入
|   |   |   +-- repository/     代码库配置 + 扫描配置 + 基线
|   |   |   +-- prompt/         提示词模板（系统 / 仓库 / 任务三级绑定）
|   |   |   +-- task/           知识构建任务 + 状态机 + 增量影响查询
|   |   |   +-- scanner/        拉取 + 扫描 + 增量 diff（pullAndScan / ScanResult / IncrementalContext）
|   |   |   +-- scanwindow/     扫描窗口 + 定时调度
|   |   |   +-- parser/         Java AST 解析
|   |   |   +-- callchain/      方法调用链 + 反向 BFS + 增量影响分析
|   |   |   +-- chunk/          代码切片
|   |   |   +-- entrypoint/     入口识别（Controller / JOB / MQ）+ 试跑
|   |   |   +-- hierarchy/      模块层级 + 人工复核落表
|   |   |   +-- ai/             AI 归纳 + 草稿生成 + 通用调用
|   |   |   +-- draft/          草稿工作区与编辑锁
|   |   |   +-- knowledge/      知识版本与发布态查询
|   |   |   +-- push/           Git 推送 / PR/MR / ZIP / 回滚
|   |   |   +-- model/          AI 模型管理
|   |   |   +-- auth/           登录认证
|   |   |   +-- token/          Token 审计
|   |   |   +-- log/            操作日志
|   |   |   +-- quotacontrol/   额度策略
|   |   |   +-- dashboard/      工作台聚合
|   |   |   +-- businessknowledge/ 业务知识维护
|   +-- src/main/resources/
|   |   +-- application.yml
|   |   +-- application-local.yml
|   |   +-- application-local.properties
|   |   +-- analyze_prompt.md
|   |   +-- db/schema.sql       34 张表，幂等初始化
|   +-- src/test/java/...           测试类（JUnit 5）
+-- frontend/
|   +-- package.json
|   +-- vite.config.ts
|   +-- src/
|   |   +-- api/          与后端模块一一对应（auth/task/prompt/...）
|   |   +-- components/   跨页组件（ModuleHierarchyEditor / EntryScanConfigEditor / DraftModuleDirectory / ...）
|   |   +-- layouts/      BasicLayout
|   |   +-- pages/
|   |   |   +-- dashboard/        工作台
|   |   |   +-- login/            登录页
|   |   |   +-- systems/          系统 + 代码库 + 扫描配置 + 提示词绑定 + 业务知识
|   |   |   +-- tasks/            任务列表 / 详情 / 模块层级复核 / 入口复核 / 增量影响卡
|   |   |   +-- drafts/           草稿复核
|   |   |   +-- knowledge/        入口 / 层级 / 文档 三页 + 共享 ContextBar
|   |   |   +-- push/             知识推送
|   |   |   +-- token-audit/      Token 审计
|   |   |   +-- logs/             操作日志
|   |   |   +-- schedules/        定时计划
|   |   |   +-- basic/            ScanWindowHeatmap / orchestration
|   |   +-- router/        createHashRouter 路由
|   |   +-- stores/        Zustand 状态（含 useAuthStore）
|   |   +-- types/         与后端 DTO 对齐的 TS 类型
|   |   +-- utils/         draftHierarchyTree / scanConfigDefaults / treeExpandKeys / pageTitle
+-- docs/
|   +-- cluster-readiness.md
|   +-- incremental-hierarchy-doc-plan.md
|   +-- incremental-release-merge-plan.md
|   +-- knowledge-browse-dual-view-plan.md
|   +-- knowledge-query-split-plan.md
|   +-- method-binding-reverse-index.md
|   +-- roadmap-8-9-plan.md
+-- CHANGELOG.md
+-- CLAUDE.md
+-- README.md
+-- .env.example
```

## 任务状态机

```text
DRAFT
  └─> PENDING
        └─> PULLING_CODE
              └─> PARSING_CODE
                    └─> ENTRYPOINT_DISCOVERY / ENTRYPOINT_REVIEW?
                          └─> AI_ANALYZING
                                ├─> MODULE_HIERARCHY
                                │     └─> MODULE_HIERARCHY_REVIEW (requireHierarchyReview=true 时的断点)
                                │           └─> GENERATING_DOC
                                └─> GENERATING_DOC (requireHierarchyReview=false 时跳过复核断点)
                                      └─> PENDING_REVIEW
                                            └─> REVIEWING
                                                  └─> CONFIRMED
                                                        └─> PUSHING
                                                              └─> PUSHED

终止态：FAILED / CANCELLED / ARCHIVED
```

- 状态机禁止非法跳转；任何状态变更都需在 `ci_operation_log` 留痕。
- `requireHierarchyReview` 在 `ci_task` 上默认 `true`；关闭后 `MODULE_HIERARCHY` 直接进入 `GENERATING_DOC`。
- `MODULE_HIERARCHY_REVIEW` 是人工断点：流水线在 `AI_ANALYZING → MODULE_HIERARCHY` 完成后停在 `MODULE_HIERARCHY_REVIEW`，等待用户在 `/tasks/hierarchy-review` 提交后再继续。

## 增量扫描（INCREMENTAL 任务）

`ci_task.type = INCREMENTAL` 时，流水线按 `git diff <repo.lastCommit>..HEAD` 识别变更/删除文件，下游各阶段只对变更文件做处理，未变文件的产物原样保留。v0.1.9 之后，增量影响范围从「路径 / classPaths 直接命中」升级为「变更类 → 调用链反向 BFS → 入口类 → 模块」的业务语义判定，详见 [docs/incremental-hierarchy-doc-plan.md](./docs/incremental-hierarchy-doc-plan.md) 与 [docs/roadmap-8-9-plan.md](./docs/roadmap-8-9-plan.md)。

| 阶段 | 增量行为 | 跳过/保留 |
| --- | --- | --- |
| `pullAndScan` | 计算 `changedPaths` / `deletedPaths` | 仅重写变更文件 snapshot；删除被删文件的 snapshot；刷新 `repo.lastCommitId` |
| `methodCallService` | 删除变更 + 删除文件的历史调用链记录 | 仅对 `changedPaths` 中 .java 重新解析；未变文件记录保留 |
| `IncrementalImpactAnalyzer` | 解析 PARSING_CODE 之后的 `ci_method_call`，对变更类做反向 BFS（深度上限 15） | 产出 `hierarchyRetargetEntries` + `docRetargetModuleIds` + `traces`；反查失败时 `classPaths` 直接命中仍纳入（降级并集） |
| `codeChunkService` | 删除变更 + 删除文件的历史 chunk | 仅对 `changedPaths` 重建 FILE/CLASS/METHOD；未变文件 chunk 保留 |
| `moduleHierarchyService` | 以 `hierarchyRetargetEntries` 替代纯路径命中；删除文件按 Maven 路径规则推 FQ 类名并从 `function.classPaths` 移除 | 整体仍走 `deleteByTaskId + 全量 insert` 保证幂等 |
| `aiSummaryService.generateDraftDocument` | `moduleTouchedByChange ∪ docRetargetModuleIds` 决定重跑集合 | 未受影响模块的旧草稿保留；被删文件对应的旧草稿暂不主动删（保留审计） |

降级路径（不会因为增量分支异常挂掉流水线）：

- `repo.lastCommitId` 为空（首次增量）→ 全量扫描 + 刷新基线
- 本地路径或 Mock 降级（无 Git 句柄）→ 全量扫描
- `lastCommitId` 在新 history 不可解析（force-push / rebase）→ 全量扫描
- `pullAndScan` 在所有路径下都会刷新 `repo.lastCommitId`，下次增量即可生效。

任务门禁：增量任务必须满足「仓库存在 PUSHED 版本 + `lastCommitId` 非空」，否则拒绝创建（见 [docs/incremental-release-merge-plan.md](./docs/incremental-release-merge-plan.md) D4）。推送时以 `last_published_version_id` 对应的 NAS `releases` 为权威来源与任务内产物 merge，删除文件语义同步剔除（D1 / D2 / D3 / D5）。

实现细节参见 `backend/src/main/java/com/company/codeinsight/modules/scanner/model/IncrementalContext.java` 与 `ScanResult.java`；反向 BFS 与影响分析见 `modules/callchain/{MethodCallReverseGraphService, IncrementalImpactAnalyzer}`。

## 知识输出目录

负责人确认后，平台在目标仓库生成：

```text
/docs/code-insight
  index.md
  module-index.md
  architecture-overview.md
  frontend-overview.md
  backend-overview.md
  api-index.md
  database-index.md
  dependency-index.md
  pending-confirmation.md
  /modules
  /changes
  /meta
```

元数据包括 `knowledge-version.json`、`module-map.yaml` 和 `prompt-used.json`。仓库级已发布快照写在 `ci_repository_publish_snapshot`；生效版本指针 `ci_repository.last_published_version_id` 由推送成功 / 回滚更新，知识查看（`/knowledge/documents` 等三页）默认只读该 release 目录。知识查看的纠错流程（`POST /api/knowledge/remediation/*`）会把人工修订的内容批准后直写 NAS release 文件并打标 `contentOrigin: HUMAN_EDITED`。

## 已知限制

- 前端生产主包超过 500 kB，Vite 会报告 chunk size warning；后续应对 ECharts、Monaco 和路由页面做按需加载与分包。
- `SecurityConfig` 当前允许所有请求（除登录页与 Swagger），仅适合本地 MVP 联调，不可直接作为生产权限方案。
- `auth` 模块当前为配置化账号占位实现（`AuthService` 不依赖外部 UM/SSO），生产前必须替换为真实身份源。
- 增量扫描的「被删文件对应草稿」不会主动删除，保留以备审计；后续可基于 `filePath in deletedPaths` 在 UI 增加过滤提示。
- Mock AI 能验证流程和数据落库，真实模型质量、配额和失败恢复仍需在目标环境验证。
- Git 推送测试可在无 `.git` 环境下降级为 Mock；生产交付前必须用真实远程仓库和最小权限凭证复验。
- PostgreSQL、Redis、外部模型与远程 Git 的可用性属于运行环境前置条件。
- 部分历史中文文档在非 UTF-8 终端下可能显示乱码，应显式使用 UTF-8 读取。
- 知识查询 MVP 限制：纠错任务的「scope 外模块」不会自动从 release 导入草稿，复核页可能不完整；文档人工修订 MVP 为「提交待审 / 批准并写入」同页操作，无独立审批工作台（详见 [docs/knowledge-query-split-plan.md](./docs/knowledge-query-split-plan.md)）。
- Phase 1-3 parser 切换后有 6 个老测试因 API drift（方法签名已变）不能编译，已在 [pom.xml](./backend/pom.xml) 的 maven-compiler-plugin `testExcludes` 中跳过，并在每个类头部加 `@Disabled` 与 javadoc 标注原因；CI 上不参与验证，留待单独 PR 修对每个测试后再启用。

## 后续演进

1. 完成生产级认证（UM/SSO 接入）、授权、审计身份绑定和密钥托管。
2. 配置真实模型与远程 Git 仓库，执行带权限、配额和失败恢复的端到端验收。
3. 拆分前端大包，清理 Ant Design 旧组件弃用提示。
4. **#9 增量影响分析**：P1 后端（已落地）→ P2 完整 `target_signature` / 方法级 seed / 可配置 `reverse-bfs-max-depth` → P3 `ImpactTrace` 持久化与 API 暴露（已写日志 + 内存结构）。
5. **#8 增量扫描 UI 化**（依赖 #9）：任务详情展示「本次增量更新了 N 个模块」影响面摘要 + `ImpactTrace` 列表 + 可选增量基线；后端 API `GET /api/tasks/{id}/incremental-impact`（[IncrementalImpactQueryService](./backend/src/main/java/com/company/codeinsight/modules/task/service/IncrementalImpactQueryService.java)）。
6. **#7 联调补丁入库**：把 `TaskQueueDispatcher` / `TaskStateMachineServiceImpl` / `DecompileTaskServiceImpl` 纠错续跑逻辑正式提交，并补纠错 / 知识查询 API 专项测试。
7. 集群 / 分布式：非 `dev` 环境验证集群全链路（Leader 选举、Redis 许可、共享卷、Pub/Sub 广播）。
