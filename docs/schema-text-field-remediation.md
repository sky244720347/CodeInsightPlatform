# Schema TEXT 字段整改方案（PostgreSQL）

> 状态：**已实施**  
> 库：PostgreSQL  
> DDL 源：[backend/src/main/resources/db/schema.sql](../backend/src/main/resources/db/schema.sql)  
> 版本：v4（含代码改动清单、Redis 缓存策略；NAS 路径绑定集群存储方案 **选项 B**）  
> 依赖：[cluster-shared-storage-design.md](./cluster-shared-storage-design.md)（`runtimeRoot` 路径门面，已落地）

---

## 一、已确认约束

| # | 约束 | 结论 |
|---|---|---|
| 1 | 结构化字段 | **允许 JSONB** |
| 2 | 字符串上限 | **VARCHAR 最长 4000** |
| 3 | 大内容 | **可存 NAS**（延续 `content_uri` 模式） |
| 4 | A 组档位 | 错误 `2000`、异常/候选 `4000`、配置 `1000`、评论 `2000` |
| 5 | C 组范围 | 提示词、业务知识、待审文档、试跑结果、发布快照 **全部外置 NAS** |
| 6 | 迁移策略 | **直接改列，不做双写过渡** |
| 7 | NAS 路径策略 | **选项 B**：落在集群存储方案的 **`runtimeRoot`**；与路径门面 **同批或先落地门面** |
| 8 | 代码改动 | **涉及**（见第六节）；API 尽量仍返回正文，前端少动 |
| 9 | Redis 缓存 | **仅提示词 + 业务知识** 做读缓存；试跑/快照/待审正文 **不进 Redis**（见第七节） |

---

## 二、PG 现状要点

1. PG 中 `TEXT` 与无长度 `VARCHAR` 存储等价；禁 `TEXT` 是规范要求。
2. 本库已在用 JSONB：`ci_incremental_scan.changed_paths` / `deleted_paths`。
3. 大正文外置已是架构约定：`ci_knowledge_draft` / `ci_file_snapshot` / `ci_draft_revision` 使用 `content_uri`。
4. 因 `VARCHAR ≤ 4000`，典型 5–15 KB 的提示词、≤64 KB 的业务知识 **无法留库**，必须 NAS。

---

## 三、现状盘点（28 个 TEXT 列）

排除注释/种子数据中的单词 `text`（如 capabilities=`text,image`）。

### A. 短文本（8）— 改 VARCHAR

| # | 表.列 | 场景 | 目标类型 |
|---|---|---|---|
| 1 | `ci_task.error_reason` | 任务失败原因 | `VARCHAR(2000)` |
| 2 | `ci_ai_call_record.error_reason` | AI 调用失败原因 | `VARCHAR(2000)` |
| 3 | `ci_entry_scan_trial.error_message` | 试跑失败原因 | `VARCHAR(2000)` |
| 4 | `ci_push_task.error_message` | 推送失败原因 | `VARCHAR(2000)` |
| 5 | `ci_operation_log.exception_msg` | 操作日志异常摘要 | `VARCHAR(4000)` |
| 6 | `ci_draft_review_comment.comment` | 草稿评审意见 | `VARCHAR(2000)` |
| 7 | `ci_system_config.value` | 运行期 KV 配置值 | `VARCHAR(1000)` |
| 8 | `ci_method_call.dependency_candidates` | 多态候选 FQCN（逗号分隔，非 JSON） | `VARCHAR(4000)` |

### B. 结构化 JSON（14）— 改 JSONB

| # | 表.列 | 场景 |
|---|---|---|
| 9 | `ci_repository.entry_scan_config` | 仓库入口扫描配置 |
| 10 | `ci_task.entry_scan_config` | 任务级入口扫描快照 |
| 11 | `ci_task.remediation_scope_json` | 纠错范围 |
| 12 | `ci_entry_scan_trial.config_snapshot` | 试跑配置快照 |
| 13 | `ci_push_task.target_info` | 推送目标摘要 |
| 14 | `ci_module_hierarchy.keywords` | 关键词 JSON 数组 |
| 15 | `ci_module_hierarchy.class_paths` | 类路径 JSON 数组 |
| 16 | `ci_module_hierarchy.method_signatures` | 方法签名 JSON 数组 |
| 17 | `ci_entrypoint.methods_json` | 入口方法列表 |
| 18 | `ci_repository_entrypoint.methods_json` | 已发布入口方法 |
| 19 | `ci_repository_module_hierarchy.keywords` | 已发布层级关键词 |
| 20 | `ci_repository_module_hierarchy.class_paths` | 已发布层级类路径 |
| 21 | `ci_repository_module_hierarchy.method_signatures` | 已发布层级方法签名 |
| 22 | `ci_repository_publish_snapshot.entry_scan_config` | 发布时配置快照 |

### C. 大文件（6）— 改 NAS URI（落 runtimeRoot）

| # | 表.列（现状） | 场景 | 典型体量 | 改造后库内列 |
|---|---|---|---|---|
| 23 | `ci_prompt.content` | 提示词 Markdown | 5–15 KB | `content_uri VARCHAR(255)` + `content_hash VARCHAR(100)` |
| 24 | `ci_business_knowledge.content` | 业务知识 Markdown | ≤64 KB | `content_uri VARCHAR(255)` + `content_hash VARCHAR(100)` |
| 25 | `ci_knowledge_release_edit.content_text` | 发布文档待审全文 | 100–500 KB+ | `content_uri VARCHAR(255)` + `hash VARCHAR(100)` |
| 26 | `ci_entry_scan_trial.result_json` | 试跑全量结果 | 可达 1–5 MB | `result_uri VARCHAR(255)` |
| 27 | `ci_repository_publish_snapshot.entrypoints_json` | 回滚入口快照 | 可达 1–5 MB | `entrypoints_uri VARCHAR(255)` |
| 28 | `ci_repository_publish_snapshot.module_hierarchy_json` | 回滚层级快照 | 可达 1–5 MB | `module_hierarchy_uri VARCHAR(255)` |

**已合规、无需改动**

- `ci_incremental_scan.changed_paths` / `deleted_paths` → 已是 JSONB
- `ci_knowledge_draft.content_uri`、`ci_file_snapshot.content_uri`、`ci_draft_revision.content_uri` → 已外置（其物理根随集群方案一并迁到 runtimeRoot / releasesRoot）

---

## 四、NAS 路径策略（选项 B：绑定集群存储）

### 4.1 与集群方案的关系

本整改 **不另造集群开关**，路径完全服从 [cluster-shared-storage-design.md](./cluster-shared-storage-design.md)：

| 逻辑根 | 用途 | C 组是否使用 |
|---|---|---|
| **runtimeRoot**（内含 data + workspaces） | 运行期数据（草稿、日志、配置正文、试跑/快照等）；C 组正文落 `runtimeRoot = runtimeRoot` 子目录 | **是（全部 6 类）** |
| **releasesRoot** | 已发布知识查看（独立卷） | 否（正式文档仍走现有 release 流程） |

| `env` | runtimeRoot 来源 |
|---|---|
| `dev` | 写死本机（如 `{user.dir}/storage`） |
| 非 `dev` | 必配 `STORAGE_RUNTIME_ROOT`（共享 NAS） |

**实施前置**：须先落地（或同 PR）`EnvStorageResolver` / 路径门面，禁止业务再直注 `local-path`。TEXT 整改的文件读写 **只经该门面** 的 `activeDataRoot`（= `activeRuntimeRoot`）。

### 4.2 runtimeRoot 下相对布局（dev / 非 dev 相同）

在集群方案已有布局上 **增补** 下列目录（不改变 drafts / ai_logs / workspaces / releases 既有约定）：

```text
{runtimeRoot}/                              # = runtimeRoot 物理区
  drafts/task_{taskId}/...          # 已有
  ai_logs/task_{taskId}/...         # 已有
  task_{taskId}/pipeline.log        # 已有
  prompts/{promptId}/content.md                 # 新增：提示词
  business-knowledge/{systemId}/content.md      # 新增：业务知识
  release-edits/{editId}/content.md             # 新增：待审修订
  trials/{trialId}/result.json                  # 新增：试跑结果
  publish-snapshots/{repoId}/{versionId}/
    entrypoints.json                            # 新增：回滚入口快照
    module_hierarchy.json                       # 新增：回滚层级快照
  workspaces/                                   # workspaceRoot 物理区（runtimeRoot 派生子目录）
    task_{taskId}/.../docs/code-insight/
```

### 4.3 逻辑 URI（库内存此，不存绝对路径）

| 用途 | URI 示例 | 解析到 |
|---|---|---|
| 提示词 | `prompt:{id}/content.md` | `{runtimeRoot}/prompts/{id}/content.md` |
| 业务知识 | `business-knowledge:{systemId}/content.md` | `{runtimeRoot}/business-knowledge/{systemId}/content.md` |
| 待审修订 | `release-edit:{id}/content.md` | `{runtimeRoot}/release-edits/{id}/content.md` |
| 试跑结果 | `trial:{trialId}/result.json` | `{runtimeRoot}/trials/{trialId}/result.json` |
| 发布快照 | `snapshot:{repoId}:{versionId}/entrypoints.json` | `{runtimeRoot}/publish-snapshots/{repoId}/{versionId}/entrypoints.json` |

集群多节点：非 dev 下 runtimeRoot 为共享盘，任意节点可读写同一 URI。

---

## 五、DDL 整改明细

### 5.1 分类总览

```text
28 个 TEXT 列
 ├─ A 短文本 ×8  → VARCHAR(n≤4000) + 写入截断
 ├─ B 结构化 JSON ×14 → JSONB
 └─ C 大文件 ×6 → 删旧列，加 URI（+hash），正文写 runtimeRoot
```

### 5.2 A 组：直接改类型

```sql
ALTER TABLE ci_task
  ALTER COLUMN error_reason TYPE VARCHAR(2000)
  USING LEFT(error_reason, 2000);

ALTER TABLE ci_operation_log
  ALTER COLUMN exception_msg TYPE VARCHAR(4000)
  USING LEFT(exception_msg, 4000);
```

### 5.3 B 组：TEXT → JSONB（直接改列）

```sql
UPDATE ci_repository
SET entry_scan_config = NULL
WHERE entry_scan_config IS NOT NULL AND TRIM(entry_scan_config) = '';

ALTER TABLE ci_repository
  ALTER COLUMN entry_scan_config TYPE JSONB
  USING entry_scan_config::jsonb;
```

约定：Java 实体继续 `String`；写入合法 JSON；`dependency_candidates` **不改 JSONB**。

### 5.4 C 组：直接改列（无双写）

| 表 | 删除列 | 新增列 |
|---|---|---|
| `ci_prompt` | `content` | `content_uri`、`content_hash` |
| `ci_business_knowledge` | `content` | `content_uri`、`content_hash` |
| `ci_knowledge_release_edit` | `content_text` | `content_uri`、`hash` |
| `ci_entry_scan_trial` | `result_json` | `result_uri` |
| `ci_repository_publish_snapshot` | `entrypoints_json`、`module_hierarchy_json` | `entrypoints_uri`、`module_hierarchy_uri` |

```sql
ALTER TABLE ci_prompt ADD COLUMN IF NOT EXISTS content_uri VARCHAR(255);
ALTER TABLE ci_prompt ADD COLUMN IF NOT EXISTS content_hash VARCHAR(100);
-- 一次性：旧 content → runtimeRoot 文件 → 回填 URI/hash
ALTER TABLE ci_prompt DROP COLUMN IF EXISTS content;
ALTER TABLE ci_prompt ALTER COLUMN content_uri SET NOT NULL;
```

---

## 六、代码改动清单（实施范围）

> 原则：对外 HTTP API **尽量仍返回正文/JSON 内容**（Service 内读文件），前端少改；DB 与实体改为 URI。

### 6.1 改动规模

| 类别 | 是否改代码 | 规模 |
|---|---|---|
| A → VARCHAR | 是 | 小（截断工具 + 写入点） |
| B → JSONB | 基本无感 | 极小（保证合法 JSON；迁移清洗） |
| C → runtimeRoot URI | 是 | **大**（实体、Service、URI 解析、测试） |
| 集群路径门面 | 是（前置/同批） | 见集群方案第十节 |

### 6.2 基础设施（依赖集群方案）

| 项 | 说明 |
|---|---|
| `EnvStorageResolver` / 路径门面 | 提供 `activeDataRoot()`；C 组只经此根拼接 |
| 扩展 `DraftFileUtil` 或新建 `DataUriUtil` | 解析/构建 `prompt:` / `business-knowledge:` / `release-edit:` / `trial:` / `snapshot:` |
| `StorageProperties` | 随集群方案删除 `StorageMode`；业务禁止再注 `local-path` |
| 公共截断工具 | 如 `DbStringLimits.truncate(s, 2000|4000|1000)` |

### 6.3 A 组写入截断（后端）

| 模块 | 主要文件 | 改动 |
|---|---|---|
| 任务状态机 | `TaskStateMachineServiceImpl` | `setErrorReason` 前截断 2000 |
| AI 调用 | `AiSummaryServiceImpl` | `errorReason` 截断 2000 |
| 操作日志 | `OperationLogServiceImpl` | `exceptionMsg` 截断 4000 |
| 推送 | `PushServiceImpl` | `errorMessage` 截断 2000 |
| 试跑 | `TrialRunServiceImpl` | `errorMessage` 截断 2000 |
| 草稿评论 | `DraftServiceImpl` / 相关 Controller | `comment` 截断 2000 |
| 调用链 | `MethodCallServiceImpl` / parser 落库 | `dependencyCandidates` 截断 4000 |
| 系统配置 | `SystemConfig` 写入服务 | `value` 截断 1000 |

实体字段类型仍为 `String`，仅 DDL 与写入长度变化。

### 6.4 B 组 JSONB（后端，改动最小）

| 模块 | 说明 |
|---|---|
| 仓库/任务入口配置 | 继续 `ObjectMapper` 序列化为 String 写入；PG 存 JSONB |
| 层级 / 入口 `methods_json` 等 | 同上 |
| 推送 `target_info` | 同上 |
| MyBatis-Plus | 一般无需 TypeHandler；若驱动/映射异常再加 `JacksonTypeHandler` |

### 6.5 C 组外置（后端，核心）

| 模块 | 主要文件 | 改动要点 |
|---|---|---|
| 提示词 | `DecompilePrompt`、`DecompilePromptServiceImpl`、`DecompilePromptController` | 删实体 `content`；增 `contentUri`/`contentHash`；create/update/clone/sync/get 经文件 load/save；API 响应仍可带 `content` 字符串；**读路径带 Redis 缓存**（见第七节） |
| 业务知识 | `BusinessKnowledge*`、`BusinessKnowledgeServiceImpl`、Controller | 同上；64KB 策略改为文件侧限制（可保留）；**读路径带 Redis 缓存** |
| 待审修订 | `KnowledgeReleaseEditEntity`、`KnowledgeReleaseEditService` | `contentText` → URI+hash；submit/approve 读文件再写 releases；**不加 Redis 正文缓存** |
| 试跑 | `EntryScanTrialEntity`、`TrialRunServiceImpl`、试跑 Controller/DTO | 成功时写 `result.json` 到 runtimeRoot；详情接口读文件再 `parseResultEntries`；**整包不进 Redis**（列表仍用摘要） |
| 发布快照 | `RepositoryPublishSnapshot`、`RepositoryPublishServiceImpl` | `buildSnapshot` 写两个 JSON 文件；回滚 `deserialize*` 从 URI 读；**不加 Redis** |

### 6.6 前端

| 预期 | 说明 |
|---|---|
| **默认不动** | 提示词编辑、业务知识弹窗、试跑抽屉、知识纠错等仍收正文 |
| 可能微调 | 若某 API 曾直接透出 DB 字段名且前端依赖；对齐 DTO 即可 |
| 类型定义 | `frontend/src/types` / `api/*.ts` 仅在后端契约变更时同步 |

### 6.7 测试

- 后端：提示词 CRUD、业务知识 upsert、试跑成功落盘、发布回滚、错误超长截断
- 提示词/业务知识：写后读应命中新内容（缓存失效）；Redis 不可用时降级直读 NAS
- 编译：`mvn -DskipTests compile`；有 PG 时跑相关测试类

---

## 七、Redis 缓存策略（C 组补充）

### 7.1 总原则

- **runtimeRoot / NAS = 唯一持久源**；Redis 只做读加速，**不替代落盘**。
- **禁止**「只写 Redis、异步再刷 NAS」作为正式写路径（宕机丢正文）。
- 与草稿 `draft:autosave` 区分：草稿是编辑缓冲；本节是 **已落盘正文的读缓存**。
- Redis 不可用时：**降级直读 NAS**（元数据缓存应可降级；不同于草稿 autosave 强依赖 Redis）。

### 7.2 逐项结论

| # | 对象 | Redis？ | 模式 | 理由 |
|---|---|---|---|---|
| 1 | 提示词 | **做** | read-through；写 NAS 成功后删/覆盖 key | 流水线高频读、管理端偶发改；5–15 KB |
| 2 | 业务知识 | **做** | 同上 | 模块化 AI 每任务读；≤64 KB |
| 3 | 待审修订 | **默认不做** | — | 提交式、读 1～2 次；可达 100–500 KB；命中低。若日后有连续自动保存，可另加 `ci:release-edit:autosave:{id}`（编辑缓冲，非正文缓存） |
| 4 | 试跑结果 | **整包不做** | 列表继续用摘要 | 可达 1–5 MB；写一次、详情偶发读 |
| 5 | 快照 entrypoints | **不做** | — | 冷数据、大、仅回滚 |
| 6 | 快照 hierarchy | **不做** | — | 同上 |

### 7.3 提示词 / 业务知识：读写路径

```text
读：Redis hit → 返回；miss → 读 NAS → SET Redis → 返回
写：写 NAS 成功 → DEL（或覆盖）对应 key
```

| 项 | 约定 |
|---|---|
| Key | `ci:meta:prompt:{id}` / `ci:meta:biz-knowledge:{systemId}` |
| TTL | 1h～24h 兜底；**以写时失效为主** |
| 值 | 正文 String（可选附带 content_hash 便于校验） |
| 实现位置 | Service 的 load/save，不进 Controller |
| 集群 | 多节点共用同一 Redis，与现有草稿/锁一致 |

### 7.4 明确不做

- 试跑 / 发布快照 / 待审修订正文整包进 Redis  
- 用 Redis 替代 runtimeRoot 作为持久层  
- 写路径先 Redis 后异步刷盘  

---

## 八、落地顺序（确认后实施）

```text
1. 落地集群存储路径门面（env + runtimeRoot（含 workspaces/）+ releasesRoot）
       ↓ 同批或紧随
2. 扩展 URI 解析（DataUriUtil）+ runtimeRoot 相对目录
       ↓
3. schema.sql：A/B 改类型；C 加 URI 列、迁数据、删旧列
       ↓
4. 应用层：C 组 6 处读写 + A 组截断
       ↓
5. 提示词 / 业务知识：Redis 读缓存 + 写失效 + Redis 宕机降级
       ↓
6. 回归：提示词 / 业务知识 / 入口配置 / 层级 / 试跑 / 发布回滚 / 错误展示 / 缓存失效
```

**不可**在路径门面未就绪时先写死 `./storage` 再改二次——与选项 B 冲突。

---

## 九、风险与注意

| 风险 | 处理 |
|---|---|
| 集群方案未确认/未落地 | TEXT 的 C 组阻塞；可先只做 A/B DDL+截断，C 等门面 |
| 非法 JSON / 空串 → `::jsonb` 失败 | `ALTER` 前清洗 |
| C 组删列不可回退库内正文 | NAS/runtimeRoot 文件为唯一源 |
| 大表 `ALTER TYPE` 锁表 | 按表分批；开发库可接受 |
| 多节点读本地盘 | 非 dev 必须共享 runtimeRoot（集群方案已要求） |
| Redis 与 NAS 短暂不一致 | 写成功后立即删 key；读 miss 回源 NAS |
| Redis 不可用 | 提示词/业务知识降级直读 NAS，功能不阻断 |

**不做**

- 不做双写过渡期  
- 不引入 CLOB / BYTEA  
- 不把草稿/知识正文重新塞回 PG  
- 不为 TEXT 整改单独增加集群开关  
- 不把试跑/快照/待审正文整包进 Redis  

---

## 十、确认签字栏

| 项 | 结论 |
|---|---|
| JSONB 用于 B 组 14 列 | 同意 |
| VARCHAR 最长 4000；A 组档位如上 | 同意 |
| C 组 6 列全部 NAS + URI | 同意 |
| 直接改列，无双写 | 同意 |
| NAS 落 **runtimeRoot**，绑定集群存储方案（选项 B） | 同意 |
| 仅提示词 + 业务知识做 Redis 读缓存；其余 C 组不加 | 同意 |
| 代码改动范围以第六、七节为准；API 尽量仍返回正文 | 同意（已实施） |
| **仅 schema 中 2 条 DEFAULT 提示词做 classpath 兜底**；读 miss 时写 NAS 文件并回填 URI | 同意（已实施） |

---

## 十一、默认提示词兜底（补充，已确认；已实施）

### 11.1 范围

**仅** schema 种子的 2 条平台默认提示词：

| name | prompt_type | classpath 模板 |
|---|---|---|
| 默认模块提取提示词 | `MODULARIZE` | `analyze_prompt.md` |
| 知识文档生成提示词 | `DOCUMENT_GENERATION` | `module_doc_prompt.md` |

识别：`category=DEFAULT` **且** `is_default=1` **且** `promptType ∈ {MODULARIZE, DOCUMENT_GENERATION}`。  
USER / 非默认 / 其它类型：**不兜底**。业务知识 / 试跑 / 快照 / 待审：**不做**。

### 11.2 行为（dev / 非 dev 相同）

读正文路径（`hydrate` / 流水线取默认提示词）：

```text
Redis → NAS(content_uri)
  → 若空白且命中上述 2 条 DEFAULT
       → 读 classpath 模板
       → DataUriUtil.writeUtf8 → 物理文件写入 {runtimeRoot}/prompts/{id}/content.md
       → UPDATE content_uri / content_hash
       → 回填 Redis
  → 返回正文
```

- **会真正写本地或 NAS 文件**（经 `EnvStorageResolver.activeDataRoot`），不是只改 URI。  
- 文件已存在且非空：**不覆盖**（避免冲掉运营修改）。  
- schema `INSERT` 只插元数据（`content_uri=''`），正文不再塞进 SQL。  
- 与 `syncFromResource`：兜底=读路径自愈；sync=运维主动升版本，仍共用同一 classpath 文件。

### 11.3 启动补写

`PromptDefaultsBootstrap`：对上述 2 条若 URI/文件为空，执行一次与读路径相同的写文件逻辑。

### 11.4 旧库数据策略（已确认）

- **2 条 schema DEFAULT**：不依赖旧 PG 正文；classpath 兜底即可。  
- **旧库非默认正文（USER 提示词 / 业务知识 / 试跑 / 快照 / 待审）**：**不做文件迁移**；`DROP` 旧列后允许丢失，由业务重新录入或重新生成。

---

## 十二、审查补丁（已确认；已实施）

| 项 | 处理 |
|---|---|
| schema：`INSERT content_uri` 早于旧库 `ADD COLUMN` | 在种子 INSERT **之前**幂等 `ADD COLUMN content_uri/content_hash` |
| 过期 `COMMENT ON` 已删列 | 改为 URI 列注释 |
| `content_uri NOT NULL` 导致 insert 失败 | `DEFAULT ''`；应用 insert 前占位 |
| 业务知识 Redis 空串缓存 | 与 prompt 一致：blank 不命中、不回填空缓存 |
| `SystemConfig.value` 超长 | `DbStringLimits.CONFIG_VALUE` 截断 |
| 残留 `StorageMode` | 删除 |
| 旧库非默认正文迁移 | **明确不做** |

**文档状态：已实施（含第十一、十二节）。**
