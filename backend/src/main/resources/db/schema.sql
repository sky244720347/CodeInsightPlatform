-- =====================================================================
-- CodeInsight Platform — 数据库初始化脚本（旧库幂等升级）
-- 兼容 PostgreSQL 11+，幂等执行
--
-- 新库请改用同目录 schema-fresh.sql（纯净 CREATE，无历史 ALTER/迁移）：
--   spring.sql.init.schema-locations: classpath:db/schema-fresh.sql
--   生成脚本：backend/scripts/generate_schema_fresh.py
--
-- 结构约定：
--   * 每张表一个独立段落，段落内按以下顺序：
--     1) CREATE TABLE IF NOT EXISTS
--     2) ALTER TABLE ... ADD COLUMN IF NOT EXISTS（列扩展，兼容旧库）
--     3) CREATE INDEX / CREATE UNIQUE INDEX
--     4) COMMENT ON TABLE / COMMENT ON COLUMN
--     5) DML（仅 ci_prompt / ci_model / ci_model_preset / ci_user 四张系统关键配置表允许保留）
--   * 调度相关表（ci_schedule_task / ci_schedule_fire_record）已下线（任务调度改由
--     ScanWindowScheduler + TaskQueueDispatcher 内存调度），从 schema 中移除
-- =====================================================================

-- 审计字段：全表统一 is_deleted / created_by / updated_by / created_date / updated_date；
--           旧列 created_at/updated_at 保留不删；deleted_at 已废弃并 DROP。
--           详见 docs/schema-audit-fields-rename-plan.md

-- ============================================================
-- 1. ci_system — 业务系统管理表
-- 对应 Entity: SystemApplication.java (modules/system)
-- 对应 Mapper: SystemApplicationMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_system (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    name_cn VARCHAR(200),
    description VARCHAR(500),
    owner VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 兼容旧库列扩展
ALTER TABLE ci_system ADD COLUMN IF NOT EXISTS name_cn VARCHAR(200);
ALTER TABLE ci_system ADD COLUMN IF NOT EXISTS modularize_prompt_id BIGINT;
ALTER TABLE ci_system ADD COLUMN IF NOT EXISTS document_prompt_id BIGINT;
ALTER TABLE ci_system ADD COLUMN IF NOT EXISTS max_concurrent_tasks INT DEFAULT 1 NOT NULL;
ALTER TABLE ci_system ADD COLUMN IF NOT EXISTS component VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE ci_system DROP COLUMN IF EXISTS state;
ALTER TABLE ci_system DROP COLUMN IF EXISTS status;

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_system
ALTER TABLE ci_system ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_system ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_system ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_system ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_system ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_system SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_system SET updated_date = COALESCE(updated_date, updated_at, created_at, CURRENT_TIMESTAMP);
-- Spring ScriptUtils 不支持 DO $$；旧 deleted_at 直接幂等删除
ALTER TABLE ci_system DROP COLUMN IF EXISTS deleted_at;
COMMENT ON COLUMN ci_system.created_date IS '创建时间';
COMMENT ON COLUMN ci_system.updated_date IS '更新时间';
COMMENT ON COLUMN ci_system.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_system.created_by   IS '创建人';
COMMENT ON COLUMN ci_system.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_system

-- 未删除行上 (name, component) 唯一：系统+组件作为业务身份（须在 is_deleted 列就绪后）
CREATE UNIQUE INDEX IF NOT EXISTS uk_system_name_component_active
    ON ci_system (name, component)
    WHERE is_deleted = 0;

COMMENT ON TABLE ci_system IS '业务系统管理表（多业务系统隔离的根）';
COMMENT ON COLUMN ci_system.name IS '系统名称';
COMMENT ON COLUMN ci_system.name_cn IS '系统中文名称';
COMMENT ON COLUMN ci_system.description IS '系统描述';
COMMENT ON COLUMN ci_system.owner IS '系统负责人';
COMMENT ON COLUMN ci_system.modularize_prompt_id IS '已废弃：模块提取提示词 ID（运行时未设置则回退到 ci_prompt.is_default=1）';
COMMENT ON COLUMN ci_system.document_prompt_id IS '已废弃：文档生成提示词 ID（运行时未设置则回退到 ci_prompt.is_default=1）';
COMMENT ON COLUMN ci_system.max_concurrent_tasks IS '同时在跑任务上限（系统级并发闸门），默认 1';
COMMENT ON COLUMN ci_system.component IS '组件标识；与 name 联合构成业务身份；空串表示无组件';


-- ============================================================
-- 2. ci_repository — 代码库配置表
-- 对应 Entity: CodeRepository.java (modules/repository)
-- 对应 Mapper: CodeRepositoryMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_repository (
    id BIGSERIAL PRIMARY KEY,
    system_id BIGINT NOT NULL,
    git_url VARCHAR(255) NOT NULL,
    branch VARCHAR(100) DEFAULT 'master' NOT NULL,
    username VARCHAR(100),
    password VARCHAR(255),
    scan_root VARCHAR(255) DEFAULT '/' NOT NULL,
    exclude_dirs VARCHAR(500),
    exclude_file_types VARCHAR(200),
    last_commit_id VARCHAR(100),
    last_decompile_at TIMESTAMP,
    entry_scan_config JSONB,
    push_git_url VARCHAR(500),
    push_branch VARCHAR(100),
    push_username VARCHAR(100),
    push_password VARCHAR(255),
    push_target_folder VARCHAR(255) DEFAULT 'docs/code-insight',
    last_published_task_id BIGINT,
    last_published_version_id BIGINT,
    published_at TIMESTAMP,
    published_by VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 兼容旧库列扩展
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS entry_scan_config JSONB;
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS push_git_url VARCHAR(500);
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS push_branch VARCHAR(100);
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS push_username VARCHAR(100);
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS push_password VARCHAR(255);
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS push_target_folder VARCHAR(255) DEFAULT 'docs/code-insight';
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS modularize_prompt_id BIGINT;
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS document_prompt_id BIGINT;
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS last_published_task_id BIGINT;
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS last_published_version_id BIGINT;
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS published_at TIMESTAMP;
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS published_by VARCHAR(100);
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS repo_type VARCHAR(32);
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS tech_stack VARCHAR(64);
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS git_reachable SMALLINT;
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS git_checked_at TIMESTAMP;
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS git_check_msg VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_repo_system_id ON ci_repository (system_id);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_repository
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_repository SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_repository SET updated_date = COALESCE(updated_date, updated_at, created_at, CURRENT_TIMESTAMP);
-- Spring ScriptUtils 不支持 DO $$；旧 deleted_at 直接幂等删除
ALTER TABLE ci_repository DROP COLUMN IF EXISTS deleted_at;
COMMENT ON COLUMN ci_repository.created_date IS '创建时间';
COMMENT ON COLUMN ci_repository.updated_date IS '更新时间';
COMMENT ON COLUMN ci_repository.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_repository.created_by   IS '创建人';
COMMENT ON COLUMN ci_repository.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_repository
COMMENT ON TABLE ci_repository IS '代码库配置表';
COMMENT ON COLUMN ci_repository.system_id IS '关联系统ID';
COMMENT ON COLUMN ci_repository.git_url IS 'Git 仓库地址';
COMMENT ON COLUMN ci_repository.branch IS '默认分支';
COMMENT ON COLUMN ci_repository.username IS '凭证用户名';
COMMENT ON COLUMN ci_repository.password IS '凭证密码/Token';
COMMENT ON COLUMN ci_repository.scan_root IS '扫描根目录';
COMMENT ON COLUMN ci_repository.exclude_dirs IS '排除目录，逗号分隔';
COMMENT ON COLUMN ci_repository.exclude_file_types IS '排除文件类型，逗号分隔';
COMMENT ON COLUMN ci_repository.last_commit_id IS '已发布知识对应的源代码基线 Commit ID（推送成功或回滚时更新；扫描任务不再写入）';
COMMENT ON COLUMN ci_repository.last_decompile_at IS '最后反编译时间';
COMMENT ON COLUMN ci_repository.entry_scan_config IS '仓库级入口扫描配置 JSON：includesByType + excludeClasspaths/excludePackages/excludeAnnotations/excludeTargets；新建任务时默认带出，任务可覆盖';
COMMENT ON COLUMN ci_repository.push_git_url IS '推送目标 Git 仓库地址（为空则使用 git_url）';
COMMENT ON COLUMN ci_repository.push_branch IS '推送目标分支（为空则默认 docs-code-insight）';
COMMENT ON COLUMN ci_repository.push_username IS '推送 Git 凭证用户名';
COMMENT ON COLUMN ci_repository.push_password IS '推送 Git 凭证密码/Token';
COMMENT ON COLUMN ci_repository.push_target_folder IS '文档在仓库中的目标文件夹路径';
COMMENT ON COLUMN ci_repository.modularize_prompt_id IS '模块提取提示词 ID（FK → ci_prompt.id，未设置则回退 is_default=1）';
COMMENT ON COLUMN ci_repository.document_prompt_id IS '文档生成提示词 ID（FK → ci_prompt.id，未设置则回退 is_default=1）';
COMMENT ON COLUMN ci_repository.last_published_task_id IS '最近一次成功发布到仓库的来源任务 ID';
COMMENT ON COLUMN ci_repository.last_published_version_id IS '当前生效的已发布知识版本 ID（ci_knowledge_version.id）；知识浏览与回滚均以此指针读取 NAS releases';
COMMENT ON COLUMN ci_repository.published_at IS '最近一次成功发布到仓库的时间';
COMMENT ON COLUMN ci_repository.published_by IS '最近一次成功发布到仓库的操作人';
COMMENT ON COLUMN ci_repository.repo_type IS '代码库类型（与展示文案一致）：前端 / 后端 / DB；前后端拆分靠独立仓库 + scan_root';
COMMENT ON COLUMN ci_repository.tech_stack IS '技术栈（与展示文案一致，如 Java / React）；须属于 repo_type 对应目录；任务下发再校验可执行白名单';
COMMENT ON COLUMN ci_repository.git_reachable IS 'Git 连通性：NULL=未检测（默认） 1=连通 0=不通；任务下发要求=1；超时不写 0';
COMMENT ON COLUMN ci_repository.git_checked_at IS '最近一次 Git 连通性检测时间';
COMMENT ON COLUMN ci_repository.git_check_msg IS '最近一次检测失败摘要（可选）';

-- 历史误判：超时曾落成「不通」的回退为未检测
UPDATE ci_repository
SET git_reachable = NULL,
    git_checked_at = NULL,
    git_check_msg = NULL,
    updated_date = CURRENT_TIMESTAMP
WHERE is_deleted = 0
  AND git_reachable = 0
  AND git_check_msg IS NOT NULL
  AND git_check_msg LIKE '检测超时%';

-- 类型/技术栈真空由 RepoStackProbe 多机探测补全（docs/repo-stack-probe-plan.md）；
-- 不再 schema 回填「后端/Java」，以免挡住自动识别。


-- ============================================================
-- 3. ci_prompt — 提示词模板表
-- 对应 Entity: DecompilePrompt.java (modules/prompt)
-- 对应 Mapper: DecompilePromptMapper.java
-- 正文外置 NAS：content_uri / content_hash（schema DEFAULT 种子仅元数据，正文由 classpath 兜底写入）
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_prompt (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    content_uri VARCHAR(255) NOT NULL DEFAULT '',
    content_hash VARCHAR(100),
    version INT DEFAULT 1 NOT NULL,
    status SMALLINT DEFAULT 1 NOT NULL,
    is_default SMALLINT DEFAULT 0 NOT NULL,
    prompt_type VARCHAR(32) DEFAULT 'MODULARIZE' NOT NULL,
    lifecycle VARCHAR(16) DEFAULT 'RELEASED' NOT NULL,
    category VARCHAR(16) DEFAULT 'DEFAULT' NOT NULL,
    scope_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 兼容旧库列扩展
ALTER TABLE ci_prompt ADD COLUMN IF NOT EXISTS prompt_type VARCHAR(32) DEFAULT 'MODULARIZE' NOT NULL;
ALTER TABLE ci_prompt ADD COLUMN IF NOT EXISTS lifecycle VARCHAR(16) DEFAULT 'RELEASED' NOT NULL;
ALTER TABLE ci_prompt ADD COLUMN IF NOT EXISTS category VARCHAR(16) DEFAULT 'DEFAULT' NOT NULL;
ALTER TABLE ci_prompt ADD COLUMN IF NOT EXISTS scope_id BIGINT;

-- C 组：种子 INSERT 前必须已有 content_uri（旧库幂等加列）
ALTER TABLE ci_prompt ADD COLUMN IF NOT EXISTS content_uri VARCHAR(255) DEFAULT '';
ALTER TABLE ci_prompt ADD COLUMN IF NOT EXISTS content_hash VARCHAR(100);
ALTER TABLE ci_prompt DROP COLUMN IF EXISTS content;
ALTER TABLE ci_prompt ALTER COLUMN content_uri SET DEFAULT '';
UPDATE ci_prompt SET content_uri = '' WHERE content_uri IS NULL;
ALTER TABLE ci_prompt ALTER COLUMN content_uri SET NOT NULL;

-- 索引
CREATE INDEX IF NOT EXISTS idx_prompt_lifecycle ON ci_prompt (lifecycle, prompt_type);
CREATE INDEX IF NOT EXISTS idx_prompt_category_scope ON ci_prompt (category, scope_id);
-- 唯一约束：同 prompt_type 下 DEFAULT 类别内只允许一条 is_default=1
CREATE UNIQUE INDEX IF NOT EXISTS uk_ci_prompt_type_default_active
    ON ci_prompt (prompt_type) WHERE is_default = 1 AND category = 'DEFAULT';

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_prompt
ALTER TABLE ci_prompt ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_prompt ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_prompt ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_prompt ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_prompt ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_prompt SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_prompt SET updated_date = COALESCE(updated_date, updated_at, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_prompt.created_date IS '创建时间';
COMMENT ON COLUMN ci_prompt.updated_date IS '更新时间';
COMMENT ON COLUMN ci_prompt.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_prompt.created_by   IS '创建人';
COMMENT ON COLUMN ci_prompt.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_prompt
COMMENT ON TABLE ci_prompt IS '提示词模板表（正文外置 NAS；schema 仅种子 2 条 DEFAULT 元数据）';
COMMENT ON COLUMN ci_prompt.name IS '提示词名称';
COMMENT ON COLUMN ci_prompt.content_uri IS '提示词正文 URI（prompt:{id}/content.md，落 runtimeRoot/prompts）';
COMMENT ON COLUMN ci_prompt.content_hash IS '提示词正文 MD5';
COMMENT ON COLUMN ci_prompt.version IS '版本号';
COMMENT ON COLUMN ci_prompt.status IS '已废弃，请使用 lifecycle；保留列仅为历史兼容';
COMMENT ON COLUMN ci_prompt.is_default IS '是否默认：0-否，1-是';
COMMENT ON COLUMN ci_prompt.prompt_type IS '提示词用途：MODULARIZE-模块提取（AI_ANALYZING / MODULE_HIERARCHY 阶段）；DOCUMENT_GENERATION-文档生成（GENERATING_DOC 阶段）';
COMMENT ON COLUMN ci_prompt.lifecycle IS '生命周期：DRAFT-草稿(可编辑) / RELEASED-已发布(锁定,需复制改) / ARCHIVED-已归档';
COMMENT ON COLUMN ci_prompt.category IS '提示词分类：DEFAULT-全局默认提示词 / USER-用户自定义提示词（按 scope 隔离）';
COMMENT ON COLUMN ci_prompt.scope_id IS 'USER 提示词的 scope ID（系统ID或仓库ID,表示该 USER 提示词归属哪个配置上下文）；DEFAULT 提示词此字段为 NULL（全局可见）';

-- 种子：仅元数据，content_uri=''；正文由 PromptDefaultsBootstrap / 读路径 classpath 兜底写入 NAS
INSERT INTO ci_prompt ("name", content_uri, "version", status, is_default, created_date, updated_date, prompt_type, lifecycle, category, scope_id) VALUES
	 ('默认模块提取提示词', '', 1, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'MODULARIZE', 'RELEASED', 'DEFAULT', NULL),
	 ('知识文档生成提示词', '', 1, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'DOCUMENT_GENERATION', 'RELEASED', 'DEFAULT', NULL)
ON CONFLICT (prompt_type) WHERE is_default = 1 AND category = 'DEFAULT' DO NOTHING;



-- ============================================================
-- 4. ci_scan_window — 仓库执行时间窗口
-- 对应 Entity: ScanWindowEntity.java (modules/scanwindow)
-- 对应 Mapper: ScanWindowMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_scan_window (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    week_days SMALLINT NOT NULL DEFAULT 127,
    hour SMALLINT NOT NULL DEFAULT 2,
    minute SMALLINT NOT NULL DEFAULT 0,
    enabled BOOLEAN DEFAULT TRUE NOT NULL,
    last_fired_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- uk_scan_window_repo → 见文末 partial unique uk_scan_window_repo_active

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_scan_window
ALTER TABLE ci_scan_window ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_scan_window ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_scan_window ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_scan_window ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_scan_window ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_scan_window SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_scan_window SET updated_date = COALESCE(updated_date, updated_at, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_scan_window.created_date IS '创建时间';
COMMENT ON COLUMN ci_scan_window.updated_date IS '更新时间';
COMMENT ON COLUMN ci_scan_window.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_scan_window.created_by   IS '创建人';
COMMENT ON COLUMN ci_scan_window.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_scan_window
COMMENT ON TABLE ci_scan_window IS '仓库执行时间窗口：定时扫描任务以此为准触发任务下发（week_days 位掩码：1=周一 2=周二 4=周三 8=周四 16=周五 32=周六 64=周日，127=每天）';
COMMENT ON COLUMN ci_scan_window.week_days IS '周几位掩码，bit0..bit6 对应周一到周日';
COMMENT ON COLUMN ci_scan_window.hour IS '小时 0-23';
COMMENT ON COLUMN ci_scan_window.minute IS '分钟 0-59';
COMMENT ON COLUMN ci_scan_window.last_fired_at IS '最近一次实际触发时间，用于幂等（同分钟窗口不重复触发）';

-- ============================================================
-- 4b. ci_scan_probe_record — 定时 commit 探测流水（每次尝试一行）
-- 对应 Entity: ScanProbeRecordEntity.java (modules/scanwindow)
-- 见 docs/scan-orchestration-ui-plan.md
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_scan_probe_record (
    id BIGSERIAL PRIMARY KEY,
    probe_date DATE NOT NULL,
    repository_id BIGINT NOT NULL,
    system_id BIGINT,
    attempt_no INT,
    status VARCHAR(32) NOT NULL,
    remote_head VARCHAR(64),
    baseline_commit VARCHAR(64),
    dispatch_action VARCHAR(32),
    task_id BIGINT,
    message VARCHAR(512),
    probed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_scan_probe_date_probed
    ON ci_scan_probe_record (probe_date, probed_at DESC);
CREATE INDEX IF NOT EXISTS idx_scan_probe_date_repo
    ON ci_scan_probe_record (probe_date, repository_id, probed_at DESC);
COMMENT ON TABLE ci_scan_probe_record IS '定时 commit 探测流水：每次 ls-remote/决策尝试一行，同仓同日可多行';
COMMENT ON COLUMN ci_scan_probe_record.id IS '主键';
COMMENT ON COLUMN ci_scan_probe_record.probe_date IS '探测所属自然日（按日统计/筛选）';
COMMENT ON COLUMN ci_scan_probe_record.repository_id IS '代码库 ID（ci_repository.id）';
COMMENT ON COLUMN ci_scan_probe_record.system_id IS '所属系统 ID（ci_system.id）';
COMMENT ON COLUMN ci_scan_probe_record.attempt_no IS '同仓同日第几次探测尝试（从 1 递增）';
COMMENT ON COLUMN ci_scan_probe_record.status IS '探测结论：SUCCESS=了结成功；FAILED=探测明确失败；SKIPPED_LOCAL=本地路径/空 URL；INCONCLUSIVE=超时等不确定；DEFERRED_DISPATCH=探测成功但下发暂缓（技术栈等）；DISPATCH_FAILED=历史下发失败状态';
COMMENT ON COLUMN ci_scan_probe_record.remote_head IS '本次 ls-remote 解析到的远端 tip commit';
COMMENT ON COLUMN ci_scan_probe_record.baseline_commit IS '比对时仓库发布基线 commit（ci_repository.last_commit_id）';
COMMENT ON COLUMN ci_scan_probe_record.dispatch_action IS '下发动作：INITIAL=全量；INCREMENTAL=增量；NONE=不下发；空=未决策到下发';
COMMENT ON COLUMN ci_scan_probe_record.task_id IS '若已创建并启动任务则记录 ci_task.id，否则为空';
COMMENT ON COLUMN ci_scan_probe_record.message IS '说明或失败/延期原因摘要';
COMMENT ON COLUMN ci_scan_probe_record.probed_at IS '本次探测发生时间';

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_scan_probe_record
ALTER TABLE ci_scan_probe_record ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_scan_probe_record ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_scan_probe_record ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_scan_probe_record ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_scan_probe_record ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_scan_probe_record SET created_date = COALESCE(created_date, probed_at, CURRENT_TIMESTAMP);
UPDATE ci_scan_probe_record SET updated_date = COALESCE(updated_date, created_date, probed_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_scan_probe_record.created_date IS '创建时间';
COMMENT ON COLUMN ci_scan_probe_record.updated_date IS '更新时间';
COMMENT ON COLUMN ci_scan_probe_record.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_scan_probe_record.created_by   IS '创建人';
COMMENT ON COLUMN ci_scan_probe_record.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_scan_probe_record


-- ============================================================
-- 5. ci_entry_scan_trial — 入口扫描试跑记录
-- 对应 Entity: EntryScanTrialEntity.java (modules/entrypoint/trial)
-- 对应 Mapper: EntryScanTrialMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_entry_scan_trial (
    id BIGSERIAL PRIMARY KEY,
    system_id BIGINT NOT NULL,
    repository_id BIGINT NOT NULL,
    user_id VARCHAR(50),
    status VARCHAR(16) NOT NULL,
    config_snapshot JSONB,
    result_uri VARCHAR(255),
    error_message VARCHAR(2000),
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

ALTER TABLE ci_entry_scan_trial ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL;
ALTER TABLE ci_entry_scan_trial ADD COLUMN IF NOT EXISTS result_uri VARCHAR(255);
ALTER TABLE ci_entry_scan_trial DROP COLUMN IF EXISTS result_json;

CREATE INDEX IF NOT EXISTS idx_trial_repo ON ci_entry_scan_trial (repository_id);
CREATE INDEX IF NOT EXISTS idx_trial_status ON ci_entry_scan_trial (status, finished_at);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_entry_scan_trial
ALTER TABLE ci_entry_scan_trial ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_entry_scan_trial ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_entry_scan_trial ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_entry_scan_trial ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_entry_scan_trial ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_entry_scan_trial SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_entry_scan_trial SET updated_date = COALESCE(updated_date, updated_at, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_entry_scan_trial.created_date IS '创建时间';
COMMENT ON COLUMN ci_entry_scan_trial.updated_date IS '更新时间';
COMMENT ON COLUMN ci_entry_scan_trial.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_entry_scan_trial.created_by   IS '创建人';
COMMENT ON COLUMN ci_entry_scan_trial.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_entry_scan_trial
COMMENT ON TABLE ci_entry_scan_trial IS '入口扫描试跑记录：用户在仓库配置中点击"试跑"产生的入口识别结果（不入库真实任务，每次独立执行）';
COMMENT ON COLUMN ci_entry_scan_trial.config_snapshot IS '本次试跑用的 entryScanConfig（JSONB）';
COMMENT ON COLUMN ci_entry_scan_trial.result_uri IS '试跑结果 URI（trial:{id}/result.json，落 runtimeRoot/trials）';
COMMENT ON COLUMN ci_entry_scan_trial.status IS '试跑状态：PENDING/RUNNING/SUCCESS/FAILED/CANCELLED';
COMMENT ON COLUMN ci_entry_scan_trial.user_id IS '触发用户';
COMMENT ON COLUMN ci_entry_scan_trial.started_at IS '开始时间';
COMMENT ON COLUMN ci_entry_scan_trial.finished_at IS '完成时间';
COMMENT ON COLUMN ci_entry_scan_trial.error_message IS '失败原因';
COMMENT ON COLUMN ci_entry_scan_trial.updated_at IS '更新时间';


-- ============================================================
-- 6. ci_task — 知识构建任务表
-- 对应 Entity: DecompileTask.java (modules/task)
-- 对应 Mapper: DecompileTaskMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_task (
    id BIGSERIAL PRIMARY KEY,
    system_id BIGINT NOT NULL,
    repository_id BIGINT NOT NULL,
    model_name VARCHAR(100),
    status VARCHAR(50) NOT NULL,
    type VARCHAR(50) DEFAULT 'INITIAL' NOT NULL,
    progress INT DEFAULT 0 NOT NULL,
    error_reason VARCHAR(2000),
    duration_ms BIGINT,
    started_at TIMESTAMP,
    ended_at TIMESTAMP,
    entry_scan_config JSONB,
    active_segment_started_at TIMESTAMP,
    modularize_prompt_id BIGINT,
    document_prompt_id BIGINT,
    require_hierarchy_review BOOLEAN DEFAULT TRUE NOT NULL,
    require_entrypoint_review BOOLEAN DEFAULT TRUE NOT NULL,
    require_knowledge_review BOOLEAN DEFAULT TRUE NOT NULL,
    trigger_source VARCHAR(40) DEFAULT 'MANUAL' NOT NULL,
    schedule_id BIGINT,
    priority INT DEFAULT 50 NOT NULL,
    claimed_by VARCHAR(128),
    claimed_at TIMESTAMP,
    lease_until TIMESTAMP,
    source_commit VARCHAR(100),
    remediation_kind VARCHAR(30),
    base_version_id BIGINT,
    base_task_id BIGINT,
    resume_from VARCHAR(30),
    remediation_scope_json JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 兼容旧库列扩展（包含已下线字段的清理）
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS modularize_prompt_id BIGINT;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS document_prompt_id BIGINT;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS model_name VARCHAR(100);
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS entry_scan_config JSONB;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS require_hierarchy_review BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS trigger_source VARCHAR(20) DEFAULT 'MANUAL' NOT NULL;
ALTER TABLE ci_task ALTER COLUMN trigger_source TYPE VARCHAR(40);
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS schedule_id BIGINT;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS remediation_kind VARCHAR(30);
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS base_version_id BIGINT;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS base_task_id BIGINT;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS resume_from VARCHAR(30);
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS remediation_scope_json JSONB;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS priority INT DEFAULT 50 NOT NULL;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS claimed_by VARCHAR(128);
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMP;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS source_commit VARCHAR(100);
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS active_segment_started_at TIMESTAMP;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS require_entrypoint_review BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS require_knowledge_review BOOLEAN DEFAULT TRUE NOT NULL;
-- 创建时后端 CODE_INSIGHT_ENV 是否为 dev（dev 进程只跑 is_dev=true 的任务）
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS is_dev BOOLEAN DEFAULT FALSE;
-- client_ip 已废弃：机器区分走 ci_operation_log.ip_address，任务亲和走 is_dev
ALTER TABLE ci_task DROP COLUMN IF EXISTS client_ip;
ALTER TABLE ci_task DROP COLUMN IF EXISTS prompt_version;
ALTER TABLE ci_task DROP COLUMN IF EXISTS modularize_prompt_version;
ALTER TABLE ci_task DROP COLUMN IF EXISTS document_prompt_version;
ALTER TABLE ci_task DROP COLUMN IF EXISTS log_uri;
-- duration_ms 允许 NULL：重试/重跑时状态机显式置 null，避免基于旧值累加
ALTER TABLE ci_task ALTER COLUMN duration_ms DROP NOT NULL;
ALTER TABLE ci_task ALTER COLUMN duration_ms DROP DEFAULT;

-- 索引
CREATE INDEX IF NOT EXISTS idx_task_system_id ON ci_task (system_id);
CREATE INDEX IF NOT EXISTS idx_task_status ON ci_task (status);
CREATE INDEX IF NOT EXISTS idx_task_trigger_source ON ci_task (trigger_source);
CREATE INDEX IF NOT EXISTS idx_task_schedule ON ci_task (schedule_id);
CREATE INDEX IF NOT EXISTS idx_task_queue ON ci_task (priority DESC, created_at ASC) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_task_claimed ON ci_task (claimed_by) WHERE claimed_by IS NOT NULL;

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_task
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_task SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_task SET updated_date = COALESCE(updated_date, updated_at, created_at, CURRENT_TIMESTAMP);
CREATE INDEX IF NOT EXISTS idx_task_queue_by_created_date ON ci_task (priority DESC, created_date ASC) WHERE status = 'PENDING';
COMMENT ON COLUMN ci_task.created_date IS '创建时间';
COMMENT ON COLUMN ci_task.updated_date IS '更新时间';
COMMENT ON COLUMN ci_task.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_task.created_by   IS '创建人';
COMMENT ON COLUMN ci_task.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_task
COMMENT ON TABLE ci_task IS '知识构建任务表（任务状态机的权威源）';
COMMENT ON COLUMN ci_task.system_id IS '关联系统ID';
COMMENT ON COLUMN ci_task.repository_id IS '关联仓库ID';
COMMENT ON COLUMN ci_task.model_name IS '所用 AI 模型 identifier';
COMMENT ON COLUMN ci_task.status IS '任务状态：DRAFT / PENDING / PULLING_CODE / PARSING_CODE / SPLITTING_TASK / ENTRYPOINT_REVIEW / AI_ANALYZING / MODULE_HIERARCHY / MODULE_HIERARCHY_REVIEW / GENERATING_DOC / PENDING_REVIEW / REVIEWING / CONFIRMED / PUSHING / PUSHED / FAILED / CANCELLED / ARCHIVED';
COMMENT ON COLUMN ci_task.type IS '任务类型：INITIAL-全量/初始化，INCREMENTAL-增量';
COMMENT ON COLUMN ci_task.progress IS '进度百分比：0-100';
COMMENT ON COLUMN ci_task.error_reason IS '失败原因（重试时由状态机置 null）';
COMMENT ON COLUMN ci_task.duration_ms IS '流水线自动执行累计耗时（毫秒），不含人工断点/排队/待推送等待（重试时由状态机置 null，避免基于旧值累加）';
COMMENT ON COLUMN ci_task.started_at IS '启动时间（重试时置 null）';
COMMENT ON COLUMN ci_task.ended_at IS '结束时间（重试时置 null）';
COMMENT ON COLUMN ci_task.entry_scan_config IS '任务级入口扫描快照 JSON：创建时全量复制仓库配置（含 excludeTargets）后允许覆写；识别/复核/AI 阶段只读此字段；null 时运行时回退平台默认预置';
COMMENT ON COLUMN ci_task.active_segment_started_at IS '当前自动执行段起点；断点/排队/待推送时为 NULL（重试时置 null）';
COMMENT ON COLUMN ci_task.modularize_prompt_id IS '模块提取提示词 ID（按主键查 ci_prompt）';
COMMENT ON COLUMN ci_task.document_prompt_id IS '文档生成提示词 ID（按主键查 ci_prompt）';
COMMENT ON COLUMN ci_task.require_hierarchy_review IS '是否启用模块层级人工复核断点：TRUE-停在 MODULE_HIERARCHY_REVIEW 等待人工调试；FALSE-跳过断点直接进入 GENERATING_DOC。默认 TRUE';
COMMENT ON COLUMN ci_task.require_entrypoint_review IS '是否启用知识入口人工复核断点：TRUE-停在 ENTRYPOINT_REVIEW 等待人工确认；FALSE-跳过断点直接进入 AI_ANALYZING。默认 TRUE';
COMMENT ON COLUMN ci_task.require_knowledge_review IS '是否启用知识文档人工复核断点：TRUE-停在 PENDING_REVIEW；FALSE-跳过并自动确认/建版/NAS推送。默认 TRUE；下发页 UI 默认 false 并以请求体为准';
COMMENT ON COLUMN ci_task.trigger_source IS '触发来源：MANUAL / SCHEDULED / KNOWLEDGE_REMEDIATION';
COMMENT ON COLUMN ci_task.schedule_id IS '触发该任务的调度配置 ID（trigger_source=SCHEDULED 时非空）';
COMMENT ON COLUMN ci_task.priority IS '队列优先级：0-100，越大越优先；SCHEDULED 默认 60，MANUAL 默认 50';
COMMENT ON COLUMN ci_task.claimed_by IS '集群模式下认领/执行该任务的节点实例 ID（重试时置 null）';
COMMENT ON COLUMN ci_task.claimed_at IS '任务认领时间（重试时置 null）';
COMMENT ON COLUMN ci_task.lease_until IS '认领租约到期时间（重试时置 null）';
COMMENT ON COLUMN ci_task.source_commit IS '本任务扫描时的源代码 Commit ID（pullAndScan 写入；createVersion 与增量 diff 溯源依据）';
COMMENT ON COLUMN ci_task.remediation_kind IS '知识纠错类型：ENTRYPOINT / HIERARCHY / DOCUMENT（trigger_source=KNOWLEDGE_REMEDIATION 时）';
COMMENT ON COLUMN ci_task.base_version_id IS '纠错所依据的已发布知识版本 ID';
COMMENT ON COLUMN ci_task.base_task_id IS '纠错克隆来源任务 ID（last_published_task_id）';
COMMENT ON COLUMN ci_task.resume_from IS '续跑起点：纠错 AI_ANALYZING/GENERATING_DOC；断点 AFTER_ENTRYPOINT/AFTER_HIERARCHY';
COMMENT ON COLUMN ci_task.remediation_scope_json IS '纠错范围 JSON（如 moduleIds / relativePath）';


-- ============================================================
-- 7. ci_file_snapshot — 代码文件快照表
-- 对应 Entity: CodeFileSnapshot.java (modules/scanner)
-- 对应 Mapper: CodeFileSnapshotMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_file_snapshot (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    file_path VARCHAR(255) NOT NULL,
    file_type VARCHAR(50),
    line_count INT DEFAULT 0 NOT NULL,
    file_hash VARCHAR(100) NOT NULL,
    content_uri VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_snapshot_task_id ON ci_file_snapshot (task_id);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_file_snapshot
ALTER TABLE ci_file_snapshot ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_file_snapshot ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_file_snapshot ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_file_snapshot ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_file_snapshot ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_file_snapshot SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_file_snapshot SET updated_date = COALESCE(updated_date, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_file_snapshot.created_date IS '创建时间';
COMMENT ON COLUMN ci_file_snapshot.updated_date IS '更新时间';
COMMENT ON COLUMN ci_file_snapshot.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_file_snapshot.created_by   IS '创建人';
COMMENT ON COLUMN ci_file_snapshot.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_file_snapshot
COMMENT ON TABLE ci_file_snapshot IS '代码文件快照表（任务扫描阶段拉取仓库后落表）';
COMMENT ON COLUMN ci_file_snapshot.task_id IS '任务ID';
COMMENT ON COLUMN ci_file_snapshot.file_path IS '相对路径';
COMMENT ON COLUMN ci_file_snapshot.file_type IS '文件类型';
COMMENT ON COLUMN ci_file_snapshot.line_count IS '行数';
COMMENT ON COLUMN ci_file_snapshot.file_hash IS '文件 MD5 哈希值';
COMMENT ON COLUMN ci_file_snapshot.content_uri IS '代码快照在存储中的地址';


-- ============================================================
-- 8. ci_ai_call_record — AI 模型调用记录
-- 对应 Entity: AiCallRecord.java (modules/ai)
-- 对应 Mapper: AiCallRecordMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_ai_call_record (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    chunk_id BIGINT,
    prompt_id BIGINT,
    prompt_version INT,
    model_name VARCHAR(100) NOT NULL,
    input_token INT DEFAULT 0 NOT NULL,
    output_token INT DEFAULT 0 NOT NULL,
    request_uri VARCHAR(255),
    response_uri VARCHAR(255),
    is_success SMALLINT DEFAULT 1 NOT NULL,
    error_reason VARCHAR(2000),
    duration_ms BIGINT DEFAULT 0 NOT NULL,
    call_stage VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

ALTER TABLE ci_ai_call_record ADD COLUMN IF NOT EXISTS call_stage VARCHAR(50);

CREATE INDEX IF NOT EXISTS idx_ai_task_id ON ci_ai_call_record (task_id);
CREATE INDEX IF NOT EXISTS idx_ai_chunk_id ON ci_ai_call_record (chunk_id);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_ai_call_record
ALTER TABLE ci_ai_call_record ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_ai_call_record ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_ai_call_record ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_ai_call_record ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_ai_call_record ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_ai_call_record SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_ai_call_record SET updated_date = COALESCE(updated_date, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_ai_call_record.created_date IS '创建时间';
COMMENT ON COLUMN ci_ai_call_record.updated_date IS '更新时间';
COMMENT ON COLUMN ci_ai_call_record.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_ai_call_record.created_by   IS '创建人';
COMMENT ON COLUMN ci_ai_call_record.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_ai_call_record
COMMENT ON TABLE ci_ai_call_record IS 'AI模型调用记录表（按阶段 + 任务聚合统计 Token/成功率/耗时）';
COMMENT ON COLUMN ci_ai_call_record.task_id IS '任务ID';
COMMENT ON COLUMN ci_ai_call_record.chunk_id IS '关联切片ID';
COMMENT ON COLUMN ci_ai_call_record.prompt_id IS '使用的提示词ID';
COMMENT ON COLUMN ci_ai_call_record.prompt_version IS '使用的提示词版本';
COMMENT ON COLUMN ci_ai_call_record.model_name IS '模型名称';
COMMENT ON COLUMN ci_ai_call_record.input_token IS '输入Token数';
COMMENT ON COLUMN ci_ai_call_record.output_token IS '输出Token数';
COMMENT ON COLUMN ci_ai_call_record.request_uri IS '请求正文在存储中的地址';
COMMENT ON COLUMN ci_ai_call_record.response_uri IS '响应正文在存储中的地址';
COMMENT ON COLUMN ci_ai_call_record.is_success IS '是否成功：0-失败，1-成功';
COMMENT ON COLUMN ci_ai_call_record.error_reason IS '失败原因';
COMMENT ON COLUMN ci_ai_call_record.duration_ms IS '耗时（毫秒）';
COMMENT ON COLUMN ci_ai_call_record.call_stage IS '调用阶段标识：MODULE_HIERARCHY / FUNCTION_DOC / MODULE_DOC 等，用于按阶段分组统计';


-- ============================================================
-- 9. ci_draft_workspace — 草稿工作区表
-- 对应 Entity: DraftWorkspace.java (modules/draft)
-- 对应 Mapper: DraftWorkspaceMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_draft_workspace (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    system_id BIGINT NOT NULL,
    repository_id BIGINT NOT NULL,
    status VARCHAR(50) DEFAULT 'ACTIVE' NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- v1: INCREMENTAL 任务基线引用（增量任务的 workspace 引用最近 PUSHED 任务的 workspace）
ALTER TABLE ci_draft_workspace ADD COLUMN IF NOT EXISTS baseline_workspace_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_draft_workspace_baseline ON ci_draft_workspace (baseline_workspace_id);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_draft_workspace
ALTER TABLE ci_draft_workspace ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_draft_workspace ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_draft_workspace ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_draft_workspace ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_draft_workspace ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_draft_workspace SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_draft_workspace SET updated_date = COALESCE(updated_date, updated_at, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_draft_workspace.created_date IS '创建时间';
COMMENT ON COLUMN ci_draft_workspace.updated_date IS '更新时间';
COMMENT ON COLUMN ci_draft_workspace.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_draft_workspace.created_by   IS '创建人';
COMMENT ON COLUMN ci_draft_workspace.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_draft_workspace
COMMENT ON TABLE ci_draft_workspace IS '草稿工作区表（每个任务 1 个 workspace，作为草稿聚合的根）';
COMMENT ON COLUMN ci_draft_workspace.task_id IS '任务ID（活行唯一，见 uk_draft_workspace_task_active）';
COMMENT ON COLUMN ci_draft_workspace.system_id IS '系统ID';
COMMENT ON COLUMN ci_draft_workspace.repository_id IS '仓库ID';
COMMENT ON COLUMN ci_draft_workspace.status IS '状态：ACTIVE, COMPLETED, ARCHIVED';
COMMENT ON COLUMN ci_draft_workspace.baseline_workspace_id IS 'INCREMENTAL 任务的基线 workspace ID（引用最近 PUSHED 任务的 workspace；非增量任务为 NULL）';


-- ============================================================
-- 10. ci_knowledge_draft — Markdown 知识草稿表
-- 对应 Entity: KnowledgeDraft.java (modules/draft)
-- 对应 Mapper: KnowledgeDraftMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_knowledge_draft (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    parent_id BIGINT,
    file_path VARCHAR(255) NOT NULL,
    module_name VARCHAR(100) NOT NULL,
    content_uri VARCHAR(255) NOT NULL,
    status VARCHAR(50) DEFAULT 'DRAFT' NOT NULL,
    sort_order INT DEFAULT 0 NOT NULL,
    hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

ALTER TABLE ci_knowledge_draft ADD COLUMN IF NOT EXISTS parent_id BIGINT;
ALTER TABLE ci_knowledge_draft ADD COLUMN IF NOT EXISTS sort_order INT DEFAULT 0 NOT NULL;

-- v1: INCREMENTAL 任务基线继承（NULL=本次新增；非空=从该基线任务继承）
ALTER TABLE ci_knowledge_draft ADD COLUMN IF NOT EXISTS baseline_task_id BIGINT;
-- 功能节点 ID：与 ci_method_function_binding.function_node_id 对齐，单篇重跑定位用
ALTER TABLE ci_knowledge_draft ADD COLUMN IF NOT EXISTS function_node_id VARCHAR(16);
-- 注意：ci_knowledge_draft 没有 task_id 列（task_id 存在 ci_draft_workspace 表），
-- 索引应以 workspace_id 为第一列。基线继承查询场景：workspace_id 范围内按 baseline_task_id 过滤
CREATE INDEX IF NOT EXISTS idx_draft_workspace_baseline ON ci_knowledge_draft (workspace_id, baseline_task_id);
CREATE INDEX IF NOT EXISTS idx_draft_function_node
    ON ci_knowledge_draft (workspace_id, function_node_id)
    WHERE function_node_id IS NOT NULL AND is_deleted = 0;

CREATE INDEX IF NOT EXISTS idx_draft_workspace_id ON ci_knowledge_draft (workspace_id);
CREATE INDEX IF NOT EXISTS idx_draft_status ON ci_knowledge_draft (status);
CREATE INDEX IF NOT EXISTS idx_draft_parent_id ON ci_knowledge_draft (parent_id);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_knowledge_draft
ALTER TABLE ci_knowledge_draft ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_knowledge_draft ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_knowledge_draft ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_knowledge_draft ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_knowledge_draft ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_knowledge_draft SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_knowledge_draft SET updated_date = COALESCE(updated_date, updated_at, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_knowledge_draft.created_date IS '创建时间';
COMMENT ON COLUMN ci_knowledge_draft.updated_date IS '更新时间';
COMMENT ON COLUMN ci_knowledge_draft.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_knowledge_draft.created_by   IS '创建人';
COMMENT ON COLUMN ci_knowledge_draft.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_knowledge_draft
COMMENT ON TABLE ci_knowledge_draft IS 'Markdown 知识草稿表（自引用树结构，组成模块目录）';
COMMENT ON COLUMN ci_knowledge_draft.workspace_id IS '关联草稿工作区ID';
COMMENT ON COLUMN ci_knowledge_draft.parent_id IS '父级草稿ID（自引用，用于构建模块目录树）';
COMMENT ON COLUMN ci_knowledge_draft.file_path IS '模块/文件 Markdown 路径';
COMMENT ON COLUMN ci_knowledge_draft.module_name IS '模块名称';
COMMENT ON COLUMN ci_knowledge_draft.content_uri IS '草稿内容在存储中的地址';
COMMENT ON COLUMN ci_knowledge_draft.status IS '草稿状态：DRAFT / EDITING / CONFIRMED / PUSHED / ARCHIVED（与 ci_task.status 解耦）';
COMMENT ON COLUMN ci_knowledge_draft.sort_order IS '同级排序权重（升序）';
COMMENT ON COLUMN ci_knowledge_draft.hash IS '草稿内容的 MD5 Hash';
COMMENT ON COLUMN ci_knowledge_draft.function_node_id IS '功能节点 ID（f 前缀），与 binding 对齐；单篇重跑定位用';
COMMENT ON COLUMN ci_knowledge_draft.baseline_task_id IS 'INCREMENTAL 基线任务 ID（NULL=本次生成）';

ALTER TABLE ci_knowledge_draft ADD COLUMN IF NOT EXISTS generated_at TIMESTAMP;
COMMENT ON COLUMN ci_knowledge_draft.generated_at IS '正文最后一次 AI/流水线生成时间；继承保留原文；人工编辑不刷新';


-- ============================================================
-- 11. ci_draft_revision — 草稿修订历史表
-- 对应 Entity: DraftRevision.java (modules/draft)
-- 对应 Mapper: DraftRevisionMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_draft_revision (
    id BIGSERIAL PRIMARY KEY,
    draft_id BIGINT NOT NULL,
    content_uri VARCHAR(255) NOT NULL,
    author VARCHAR(50) NOT NULL,
    remark VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_revision_draft_id ON ci_draft_revision (draft_id);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_draft_revision
ALTER TABLE ci_draft_revision ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_draft_revision ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_draft_revision ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_draft_revision ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_draft_revision ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_draft_revision SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_draft_revision SET updated_date = COALESCE(updated_date, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_draft_revision.created_date IS '创建时间';
COMMENT ON COLUMN ci_draft_revision.updated_date IS '更新时间';
COMMENT ON COLUMN ci_draft_revision.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_draft_revision.created_by   IS '创建人';
COMMENT ON COLUMN ci_draft_revision.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_draft_revision
COMMENT ON TABLE ci_draft_revision IS '草稿修订历史表（每次保存修改留一版，可 diff）';
COMMENT ON COLUMN ci_draft_revision.draft_id IS '关联草稿ID';
COMMENT ON COLUMN ci_draft_revision.content_uri IS '修改后正文在存储中的地址';
COMMENT ON COLUMN ci_draft_revision.author IS '修改者';
COMMENT ON COLUMN ci_draft_revision.remark IS '修改备注';


-- ============================================================
-- 12. ci_draft_review_comment — 草稿评审意见表
-- 对应 Entity: DraftReviewComment.java (modules/draft)
-- 对应 Mapper: DraftReviewCommentMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_draft_review_comment (
    id BIGSERIAL PRIMARY KEY,
    draft_id BIGINT NOT NULL,
    author VARCHAR(50) NOT NULL,
    comment VARCHAR(2000) NOT NULL,
    type VARCHAR(20) DEFAULT 'NORMAL' NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

ALTER TABLE ci_draft_review_comment ADD COLUMN IF NOT EXISTS type VARCHAR(20) DEFAULT 'NORMAL' NOT NULL;

CREATE INDEX IF NOT EXISTS idx_comment_draft_id ON ci_draft_review_comment (draft_id);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_draft_review_comment
ALTER TABLE ci_draft_review_comment ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_draft_review_comment ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_draft_review_comment ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_draft_review_comment ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_draft_review_comment ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_draft_review_comment SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_draft_review_comment SET updated_date = COALESCE(updated_date, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_draft_review_comment.created_date IS '创建时间';
COMMENT ON COLUMN ci_draft_review_comment.updated_date IS '更新时间';
COMMENT ON COLUMN ci_draft_review_comment.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_draft_review_comment.created_by   IS '创建人';
COMMENT ON COLUMN ci_draft_review_comment.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_draft_review_comment
COMMENT ON TABLE ci_draft_review_comment IS '草稿评审意见表（confirm / 通用批注，type 区分场景）';
COMMENT ON COLUMN ci_draft_review_comment.draft_id IS '关联草稿ID';
COMMENT ON COLUMN ci_draft_review_comment.author IS '评审人';
COMMENT ON COLUMN ci_draft_review_comment.comment IS '评审意见';
COMMENT ON COLUMN ci_draft_review_comment.type IS '意见类型：NORMAL=通用意见 / PASS=通过意见 / REJECT=驳回意见（v0.3 后已废弃驳回流程，仅保留历史数据兼容）';


-- ============================================================
-- 13. ci_draft_source_reference — 草稿代码来源引用表
-- 对应 Entity: DraftSourceReference.java (modules/draft)
-- 对应 Mapper: DraftSourceReferenceMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_draft_source_reference (
    id BIGSERIAL PRIMARY KEY,
    draft_id BIGINT NOT NULL,
    file_path VARCHAR(255) NOT NULL,
    start_line INT NOT NULL,
    end_line INT NOT NULL,
    class_name VARCHAR(512),
    method_signature VARCHAR(512),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

ALTER TABLE ci_draft_source_reference ADD COLUMN IF NOT EXISTS class_name VARCHAR(512);
ALTER TABLE ci_draft_source_reference ADD COLUMN IF NOT EXISTS method_signature VARCHAR(512);
ALTER TABLE ci_draft_source_reference ADD COLUMN IF NOT EXISTS ref_kind VARCHAR(16) DEFAULT 'REACHABLE' NOT NULL;
ALTER TABLE ci_draft_source_reference ADD COLUMN IF NOT EXISTS bfs_order INT DEFAULT 0 NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ref_draft_id ON ci_draft_source_reference (draft_id);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_draft_source_reference
ALTER TABLE ci_draft_source_reference ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_draft_source_reference ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_draft_source_reference ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_draft_source_reference ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_draft_source_reference ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_draft_source_reference SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_draft_source_reference SET updated_date = COALESCE(updated_date, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_draft_source_reference.created_date IS '创建时间';
COMMENT ON COLUMN ci_draft_source_reference.updated_date IS '更新时间';
COMMENT ON COLUMN ci_draft_source_reference.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_draft_source_reference.created_by   IS '创建人';
COMMENT ON COLUMN ci_draft_source_reference.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_draft_source_reference
COMMENT ON TABLE ci_draft_source_reference IS '草稿代码来源引用表（草稿正文与被引用源码行号区间的双向追溯链）';
COMMENT ON COLUMN ci_draft_source_reference.draft_id IS '关联草稿ID';
COMMENT ON COLUMN ci_draft_source_reference.file_path IS '引用源文件路径';
COMMENT ON COLUMN ci_draft_source_reference.start_line IS '起始行号';
COMMENT ON COLUMN ci_draft_source_reference.end_line IS '结束行号（0 表示整文件）';
COMMENT ON COLUMN ci_draft_source_reference.class_name IS '入口类全限定名（可选，便于复核展示）';
COMMENT ON COLUMN ci_draft_source_reference.method_signature IS '方法签名 methodName(ParamTypes)，不含返回类型（可选）';
COMMENT ON COLUMN ci_draft_source_reference.ref_kind IS 'ROOT=binding 入口；REACHABLE=BFS 下游（含同类助手）';
COMMENT ON COLUMN ci_draft_source_reference.bfs_order IS 'BFS 发现序（从 0 起），代码来源列表排序用';


-- ============================================================
-- 14. ci_knowledge_version — 知识版本表
-- 对应 Entity: KnowledgeVersion.java (modules/knowledge)
-- 对应 Mapper: KnowledgeVersionMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_knowledge_version (
    id BIGSERIAL PRIMARY KEY,
    system_id BIGINT NOT NULL,
    repository_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    version_num VARCHAR(50) NOT NULL,
    source_branch VARCHAR(100) NOT NULL,
    source_commit VARCHAR(100) NOT NULL,
    target_branch VARCHAR(100) NOT NULL,
    target_commit VARCHAR(100),
    prompt_version INT,
    model_name VARCHAR(100),
    status VARCHAR(50) NOT NULL,
    push_method VARCHAR(20) DEFAULT 'GIT',
    confirmed_by VARCHAR(50) NOT NULL,
    confirmed_at TIMESTAMP NOT NULL,
    pushed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

ALTER TABLE ci_knowledge_version ADD COLUMN IF NOT EXISTS push_method VARCHAR(20) DEFAULT 'GIT';

CREATE INDEX IF NOT EXISTS idx_version_system_id ON ci_knowledge_version (system_id);
CREATE INDEX IF NOT EXISTS idx_version_number ON ci_knowledge_version (version_num);
-- (repository_id, version_num) 唯一约束：库内若已有重复则勿自动建唯一索引（会致启动失败），由应用层强制

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_knowledge_version
ALTER TABLE ci_knowledge_version ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_knowledge_version ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_knowledge_version ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_knowledge_version ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_knowledge_version ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_knowledge_version SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_knowledge_version SET updated_date = COALESCE(updated_date, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_knowledge_version.created_date IS '创建时间';
COMMENT ON COLUMN ci_knowledge_version.updated_date IS '更新时间';
COMMENT ON COLUMN ci_knowledge_version.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_knowledge_version.created_by   IS '创建人';
COMMENT ON COLUMN ci_knowledge_version.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_knowledge_version
COMMENT ON TABLE ci_knowledge_version IS '知识版本表（确认 → 推送 → 发布全链路审计）';
COMMENT ON COLUMN ci_knowledge_version.system_id IS '关联系统ID';
COMMENT ON COLUMN ci_knowledge_version.repository_id IS '关联代码库ID';
COMMENT ON COLUMN ci_knowledge_version.task_id IS '关联任务ID';
COMMENT ON COLUMN ci_knowledge_version.version_num IS '知识版本号 (如 v1.0.0)';
COMMENT ON COLUMN ci_knowledge_version.source_branch IS '源分支';
COMMENT ON COLUMN ci_knowledge_version.source_commit IS '源提交 Commit ID';
COMMENT ON COLUMN ci_knowledge_version.target_branch IS '目标推送分支';
COMMENT ON COLUMN ci_knowledge_version.target_commit IS '推送后的提交 Commit ID';
COMMENT ON COLUMN ci_knowledge_version.prompt_version IS '所用提示词版本';
COMMENT ON COLUMN ci_knowledge_version.model_name IS '所用 AI 模型名称';
COMMENT ON COLUMN ci_knowledge_version.status IS '状态：DRAFT, PUSHING, PUSHED, FAILED';
COMMENT ON COLUMN ci_knowledge_version.push_method IS '推送方式：GIT=Git 推送 / S3=对象存储';
COMMENT ON COLUMN ci_knowledge_version.confirmed_by IS '确认人';
COMMENT ON COLUMN ci_knowledge_version.confirmed_at IS '确认时间';
COMMENT ON COLUMN ci_knowledge_version.pushed_at IS '推送时间';


-- ============================================================
-- 15. ci_knowledge_release_edit — 知识发布文档人工修订待审记录
-- 对应 Entity: KnowledgeReleaseEditEntity.java (modules/knowledge/remediation)
-- 对应 Mapper: KnowledgeReleaseEditMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_knowledge_release_edit (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    relative_path VARCHAR(500) NOT NULL,
    content_uri VARCHAR(255) NOT NULL DEFAULT '',
    hash VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    submitted_by VARCHAR(50) NOT NULL,
    approved_by VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    approved_at TIMESTAMP
);

ALTER TABLE ci_knowledge_release_edit ADD COLUMN IF NOT EXISTS content_uri VARCHAR(255) DEFAULT '';
ALTER TABLE ci_knowledge_release_edit ADD COLUMN IF NOT EXISTS hash VARCHAR(100);
ALTER TABLE ci_knowledge_release_edit DROP COLUMN IF EXISTS content_text;
ALTER TABLE ci_knowledge_release_edit ALTER COLUMN content_uri SET DEFAULT '';
UPDATE ci_knowledge_release_edit SET content_uri = '' WHERE content_uri IS NULL;
ALTER TABLE ci_knowledge_release_edit ALTER COLUMN content_uri SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_release_edit_repo ON ci_knowledge_release_edit (repository_id);
CREATE INDEX IF NOT EXISTS idx_release_edit_status ON ci_knowledge_release_edit (status);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_knowledge_release_edit
ALTER TABLE ci_knowledge_release_edit ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_knowledge_release_edit ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_knowledge_release_edit ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_knowledge_release_edit ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_knowledge_release_edit ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_knowledge_release_edit SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_knowledge_release_edit SET updated_date = COALESCE(updated_date, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_knowledge_release_edit.created_date IS '创建时间';
COMMENT ON COLUMN ci_knowledge_release_edit.updated_date IS '更新时间';
COMMENT ON COLUMN ci_knowledge_release_edit.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_knowledge_release_edit.created_by   IS '创建人';
COMMENT ON COLUMN ci_knowledge_release_edit.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_knowledge_release_edit
COMMENT ON TABLE ci_knowledge_release_edit IS '知识发布文档人工修订待审记录；正文外置 NAS，通过后直写 releases';
COMMENT ON COLUMN ci_knowledge_release_edit.content_uri IS '待审正文 URI（release-edit:{id}/content.md）';
COMMENT ON COLUMN ci_knowledge_release_edit.hash IS '待审正文 MD5';



-- ============================================================
-- 16. ci_push_task — 知识推送任务审计表
-- 对应 Entity: PushTask.java (modules/push)
-- 对应 Mapper: PushTaskMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_push_task (
    id BIGSERIAL PRIMARY KEY,
    version_id BIGINT NOT NULL,
    push_method VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT DEFAULT 0 NOT NULL,
    max_retries INT DEFAULT 3 NOT NULL,
    target_info JSONB,
    error_message VARCHAR(2000),
    enqueued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_push_task_version_id ON ci_push_task (version_id);
CREATE INDEX IF NOT EXISTS idx_push_task_status ON ci_push_task (status);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_push_task
ALTER TABLE ci_push_task ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_push_task ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_push_task ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_push_task ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_push_task ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_push_task SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_push_task SET updated_date = COALESCE(updated_date, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_push_task.created_date IS '创建时间';
COMMENT ON COLUMN ci_push_task.updated_date IS '更新时间';
COMMENT ON COLUMN ci_push_task.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_push_task.created_by   IS '创建人';
COMMENT ON COLUMN ci_push_task.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_push_task
COMMENT ON TABLE ci_push_task IS '知识推送任务审计表';
COMMENT ON COLUMN ci_push_task.version_id IS '关联的知识版本ID';
COMMENT ON COLUMN ci_push_task.push_method IS '推送方式：GIT 或 S3';
COMMENT ON COLUMN ci_push_task.status IS '推送状态：PENDING, PROCESSING, SUCCESS, FAILED';
COMMENT ON COLUMN ci_push_task.retry_count IS '重试次数';
COMMENT ON COLUMN ci_push_task.max_retries IS '最大重试次数';
COMMENT ON COLUMN ci_push_task.target_info IS '推送目标摘要信息(JSON)';
COMMENT ON COLUMN ci_push_task.error_message IS '失败原因';
COMMENT ON COLUMN ci_push_task.enqueued_at IS '入队时间';
COMMENT ON COLUMN ci_push_task.started_at IS '开始执行时间';
COMMENT ON COLUMN ci_push_task.completed_at IS '完成时间';


-- ============================================================
-- 17. ci_token_usage_audit — Token 使用审计表
-- 对应 Entity: TokenUsageAudit.java (modules/token)
-- 对应 Mapper: TokenUsageAuditMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_token_usage_audit (
    id BIGSERIAL PRIMARY KEY,
    system_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    user_id BIGINT,
    prompt_version INT,
    model_name VARCHAR(100) NOT NULL,
    input_tokens INT DEFAULT 0 NOT NULL,
    output_tokens INT DEFAULT 0 NOT NULL,
    total_tokens INT DEFAULT 0 NOT NULL,
    cost DECIMAL(10,4) DEFAULT 0.0000 NOT NULL,
    type VARCHAR(50) DEFAULT 'INITIAL' NOT NULL,
    status SMALLINT DEFAULT 1 NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_system_id ON ci_token_usage_audit (system_id);
CREATE INDEX IF NOT EXISTS idx_audit_task_id ON ci_token_usage_audit (task_id);
CREATE INDEX IF NOT EXISTS idx_audit_created_at ON ci_token_usage_audit (created_at);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_token_usage_audit
ALTER TABLE ci_token_usage_audit ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_token_usage_audit ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_token_usage_audit ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_token_usage_audit ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_token_usage_audit ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_token_usage_audit SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_token_usage_audit SET updated_date = COALESCE(updated_date, created_at, CURRENT_TIMESTAMP);
CREATE INDEX IF NOT EXISTS idx_audit_created_date ON ci_token_usage_audit (created_date);
COMMENT ON COLUMN ci_token_usage_audit.created_date IS '创建时间';
COMMENT ON COLUMN ci_token_usage_audit.updated_date IS '更新时间';
COMMENT ON COLUMN ci_token_usage_audit.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_token_usage_audit.created_by   IS '创建人';
COMMENT ON COLUMN ci_token_usage_audit.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_token_usage_audit
COMMENT ON TABLE ci_token_usage_audit IS 'Token 使用审计表（按系统/任务/模型维度统计成本）';
COMMENT ON COLUMN ci_token_usage_audit.system_id IS '关联系统ID';
COMMENT ON COLUMN ci_token_usage_audit.task_id IS '关联任务ID';
COMMENT ON COLUMN ci_token_usage_audit.user_id IS '用户ID';
COMMENT ON COLUMN ci_token_usage_audit.prompt_version IS '提示词版本';
COMMENT ON COLUMN ci_token_usage_audit.model_name IS '模型名称';
COMMENT ON COLUMN ci_token_usage_audit.input_tokens IS '输入Token数';
COMMENT ON COLUMN ci_token_usage_audit.output_tokens IS '输出Token数';
COMMENT ON COLUMN ci_token_usage_audit.total_tokens IS '总Token数';
COMMENT ON COLUMN ci_token_usage_audit.cost IS '预估消耗成本(美元)';
COMMENT ON COLUMN ci_token_usage_audit.type IS '调用类型：INITIAL, INCREMENTAL, TEST';
COMMENT ON COLUMN ci_token_usage_audit.status IS '调用结果：0-失败，1-成功';


-- ============================================================
-- 18. ci_operation_log — 操作日志审计表
-- 对应 Entity: OperationLog.java (modules/log)
-- 对应 Mapper: OperationLogMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_operation_log (
    id BIGSERIAL PRIMARY KEY,
    system_id BIGINT,
    task_id BIGINT,
    user_id BIGINT,
    username VARCHAR(50) NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    detail VARCHAR(1000) NOT NULL,
    ip_address VARCHAR(50),
    exception_msg VARCHAR(4000),
    is_success SMALLINT DEFAULT 1 NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_op_system_id ON ci_operation_log (system_id);
CREATE INDEX IF NOT EXISTS idx_op_task_id ON ci_operation_log (task_id);
CREATE INDEX IF NOT EXISTS idx_op_created_at ON ci_operation_log (created_at);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_operation_log
ALTER TABLE ci_operation_log ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_operation_log ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_operation_log ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_operation_log ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_operation_log ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_operation_log SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_operation_log SET updated_date = COALESCE(updated_date, created_at, CURRENT_TIMESTAMP);
CREATE INDEX IF NOT EXISTS idx_op_created_date ON ci_operation_log (created_date);
COMMENT ON COLUMN ci_operation_log.created_date IS '创建时间';
COMMENT ON COLUMN ci_operation_log.updated_date IS '更新时间';
COMMENT ON COLUMN ci_operation_log.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_operation_log.created_by   IS '创建人';
COMMENT ON COLUMN ci_operation_log.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_operation_log
COMMENT ON TABLE ci_operation_log IS '操作日志审计表（用户行为 / 系统状态机迁移记录）';
COMMENT ON COLUMN ci_operation_log.system_id IS '关联系统ID';
COMMENT ON COLUMN ci_operation_log.task_id IS '关联任务ID';
COMMENT ON COLUMN ci_operation_log.user_id IS '操作人ID';
COMMENT ON COLUMN ci_operation_log.username IS '操作人用户名';
COMMENT ON COLUMN ci_operation_log.action_type IS '操作类型(CREATE_SYSTEM, EDIT_REPO, RUN_TASK, SAVE_DRAFT, CONFIRM_KNOWLEDGE, PUSH_GIT, etc.)';
COMMENT ON COLUMN ci_operation_log.detail IS '操作详情描述';
COMMENT ON COLUMN ci_operation_log.ip_address IS 'IP地址';
COMMENT ON COLUMN ci_operation_log.exception_msg IS '异常日志信息';
COMMENT ON COLUMN ci_operation_log.is_success IS '操作是否成功：0-失败，1-成功';


-- ============================================================
-- 19. ci_model — AI 模型配置表
-- 对应 Entity: AiModel.java (modules/model)
-- 对应 Mapper: AiModelMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_model (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    identifier VARCHAR(100) NOT NULL,
    provider VARCHAR(100) NOT NULL,
    api_key VARCHAR(255),
    base_url VARCHAR(255),
    is_default VARCHAR(10) DEFAULT 'false' NOT NULL,
    capabilities VARCHAR(255),
    description VARCHAR(500),
    sort_order INT DEFAULT 0 NOT NULL,
    status SMALLINT DEFAULT 1 NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

ALTER TABLE ci_model ADD COLUMN IF NOT EXISTS status SMALLINT DEFAULT 1 NOT NULL;

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_model
ALTER TABLE ci_model ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_model ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_model ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_model ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_model ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_model SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_model SET updated_date = COALESCE(updated_date, updated_at, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_model.created_date IS '创建时间';
COMMENT ON COLUMN ci_model.updated_date IS '更新时间';
COMMENT ON COLUMN ci_model.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_model.created_by   IS '创建人';
COMMENT ON COLUMN ci_model.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_model
COMMENT ON TABLE ci_model IS 'AI 模型配置表（系统关键配置 — 保留 DML 入口但当前不预置种子，由前端基础配置 → 模型配置管理）';
COMMENT ON COLUMN ci_model.name IS '模型显示名称';
COMMENT ON COLUMN ci_model.identifier IS '模型调用ID';
COMMENT ON COLUMN ci_model.provider IS '技术供应商';
COMMENT ON COLUMN ci_model.api_key IS 'API Key（密钥）';
COMMENT ON COLUMN ci_model.base_url IS 'Endpoint URL（接口地址）';
COMMENT ON COLUMN ci_model.is_default IS '是否默认模型：true / false';
COMMENT ON COLUMN ci_model.capabilities IS '支持能力，逗号分隔 (text,image,video)';
COMMENT ON COLUMN ci_model.description IS '功能描述';
COMMENT ON COLUMN ci_model.sort_order IS '排序权重';
COMMENT ON COLUMN ci_model.status IS '启用状态：0-停用，1-启用';

-- 4 个 KEEP-DML 表之一；当前无种子，预留 DML 入口
-- 由前端基础配置 → 模型配置页录入


-- ============================================================
-- 20. ci_model_preset — AI 模型预设模板表
-- 对应 Entity: AiModelPreset.java (modules/model)
-- 对应 Mapper: AiModelPresetMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_model_preset (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    identifier VARCHAR(100) NOT NULL,
    provider VARCHAR(100) NOT NULL,
    base_url VARCHAR(255),
    capabilities VARCHAR(255),
    description VARCHAR(500),
    sort_order INT DEFAULT 0 NOT NULL,
    status SMALLINT DEFAULT 1 NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_model_preset_identifier ON ci_model_preset (identifier);
CREATE INDEX IF NOT EXISTS idx_model_preset_status_sort ON ci_model_preset (status, sort_order);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_model_preset
ALTER TABLE ci_model_preset ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_model_preset ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_model_preset ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_model_preset ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_model_preset ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_model_preset SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_model_preset SET updated_date = COALESCE(updated_date, updated_at, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_model_preset.created_date IS '创建时间';
COMMENT ON COLUMN ci_model_preset.updated_date IS '更新时间';
COMMENT ON COLUMN ci_model_preset.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_model_preset.created_by   IS '创建人';
COMMENT ON COLUMN ci_model_preset.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_model_preset
COMMENT ON TABLE ci_model_preset IS 'AI 模型预设模板表（系统关键配置 — 预置 6 个常见厂商模板供用户一键克隆）';
COMMENT ON COLUMN ci_model_preset.name IS '预设显示名称';
COMMENT ON COLUMN ci_model_preset.identifier IS '模型调用ID';
COMMENT ON COLUMN ci_model_preset.provider IS '技术供应商';
COMMENT ON COLUMN ci_model_preset.base_url IS 'Endpoint URL（接口地址）';
COMMENT ON COLUMN ci_model_preset.capabilities IS '支持能力，逗号分隔 (text,image,video)';
COMMENT ON COLUMN ci_model_preset.description IS '模板说明';
COMMENT ON COLUMN ci_model_preset.sort_order IS '排序权重';
COMMENT ON COLUMN ci_model_preset.status IS '启用状态：0-停用，1-启用';

-- 系统关键配置：预置 6 个常见厂商模板（按 identifier ON CONFLICT 更新）
INSERT INTO ci_model_preset (name, provider, identifier, base_url, capabilities, description, sort_order, status)
VALUES
    ('Gemini 2.0 Pro', 'Google',     'gemini-2.0-pro-exp-02-05', 'https://generativelanguage.googleapis.com',          'text,image,video', 'Google 顶尖多模态模型，支持原生视频理解。',          10, 1),
    ('Qwen-VL-Max',     'Alibaba',    'qwen-vl-max',              'https://dashscope.aliyuncs.com/compatible-mode/v1',     'text,image,video', '通义千问视觉大模型，视频理解能力强。',                  20, 1),
    ('DeepSeek Chat',   'DeepSeek',   'deepseek-chat',            'https://api.deepseek.com',                              'text',             '深度求索高性能模型，代码分析极具性价比。',               30, 1),
    ('GPT-4o',          'OpenAI',     'gpt-4o',                   'https://api.openai.com/v1',                              'text,image,video', 'OpenAI 旗舰全能模型，推理能力卓越。',                     40, 1),
    ('MiniMax-M2.7',    'MiniMax',  'MiniMax-M2.7',          'https://api.minimaxi.chat/v1',                            'text,image',       '国产多模态模型，支持图片理解与代码环境分析。',          50, 1),
    ('MiniMax-M3',      'MiniMax',  'MiniMax-M3',            'https://api.minimaxi.chat/v1',                            'text,image,video', 'MiniMax 旗舰模型，适合长上下文代码洞察。',           60, 1)
ON CONFLICT (identifier) DO UPDATE SET
    name         = EXCLUDED.name,
    provider     = EXCLUDED.provider,
    base_url     = EXCLUDED.base_url,
    capabilities = EXCLUDED.capabilities,
    description  = EXCLUDED.description,
    sort_order   = EXCLUDED.sort_order,
    status       = EXCLUDED.status,
    updated_date = CURRENT_TIMESTAMP;


-- ============================================================
-- 21. ci_method_call — 方法调用链路表（AST 静态分析结果）
-- 对应 Entity: MethodCall.java (modules/callchain)
-- 对应 Mapper: MethodCallMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_method_call (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    class_name VARCHAR(255),
    caller_method VARCHAR(255),
    dependency_name VARCHAR(255),
    target_method VARCHAR(255),
    expression VARCHAR(1000),
    line_number INT,
    caller_signature VARCHAR(500),
    target_signature VARCHAR(500),
    dependency_candidates VARCHAR(4000),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

ALTER TABLE ci_method_call ADD COLUMN IF NOT EXISTS caller_signature VARCHAR(500);
ALTER TABLE ci_method_call ADD COLUMN IF NOT EXISTS target_signature VARCHAR(500);
ALTER TABLE ci_method_call ADD COLUMN IF NOT EXISTS dependency_candidates VARCHAR(4000);

-- v1: INCREMENTAL 任务基线继承（NULL=本次新增；非空=从该基线任务继承）
ALTER TABLE ci_method_call ADD COLUMN IF NOT EXISTS baseline_task_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_method_call_baseline_task ON ci_method_call (task_id, baseline_task_id);

CREATE INDEX IF NOT EXISTS idx_method_call_task_id ON ci_method_call (task_id);
CREATE INDEX IF NOT EXISTS idx_method_call_class ON ci_method_call (task_id, class_name, caller_method);
CREATE INDEX IF NOT EXISTS idx_method_call_caller_sig ON ci_method_call (task_id, caller_signature);
CREATE INDEX IF NOT EXISTS idx_method_call_target_sig ON ci_method_call (task_id, target_signature);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_method_call
ALTER TABLE ci_method_call ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_method_call ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_method_call ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_method_call ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_method_call ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_method_call SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_method_call SET updated_date = COALESCE(updated_date, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_method_call.created_date IS '创建时间';
COMMENT ON COLUMN ci_method_call.updated_date IS '更新时间';
COMMENT ON COLUMN ci_method_call.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_method_call.created_by   IS '创建人';
COMMENT ON COLUMN ci_method_call.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_method_call
COMMENT ON TABLE ci_method_call IS '方法调用链路表（AST 静态分析）';
COMMENT ON COLUMN ci_method_call.task_id IS '关联任务ID';
COMMENT ON COLUMN ci_method_call.file_path IS '源文件相对路径';
COMMENT ON COLUMN ci_method_call.class_name IS 'Java 类名';
COMMENT ON COLUMN ci_method_call.caller_method IS '调用方方法名';
COMMENT ON COLUMN ci_method_call.dependency_name IS '被调依赖的类型名（格式 "variable:Type"，如 "userService:UserService"）';
COMMENT ON COLUMN ci_method_call.target_method IS '被调用的目标方法名';
COMMENT ON COLUMN ci_method_call.expression IS '调用表达式原始文本';
COMMENT ON COLUMN ci_method_call.line_number IS '源文件行号';
COMMENT ON COLUMN ci_method_call.caller_signature IS '调用方方法完整签名：className#methodName(ParamType1,ParamType2)';
COMMENT ON COLUMN ci_method_call.target_signature IS '被调方方法完整签名：className#methodName(ParamType1,ParamType2) — MVP 阶段仅方法名（不带参数也不带类名）';
COMMENT ON COLUMN ci_method_call.dependency_candidates IS '声明类型的所有项目内具体候选子类 FQ（多态候选，逗号分隔）';


-- ============================================================
-- 22. ci_module_hierarchy — 模块层级表
-- 对应 Entity: ModuleHierarchyNode.java (modules/hierarchy)
-- 对应 Mapper: ModuleHierarchyNodeMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_module_hierarchy (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    system_id BIGINT NOT NULL,
    level VARCHAR(20) NOT NULL,
    parent_id BIGINT,
    node_id VARCHAR(10),
    name VARCHAR(255) NOT NULL,
    keywords JSONB,
    class_paths JSONB,
    method_signatures JSONB,
    confirmed BOOLEAN DEFAULT FALSE NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

ALTER TABLE ci_module_hierarchy ADD COLUMN IF NOT EXISTS method_signatures JSONB;
ALTER TABLE ci_module_hierarchy ADD COLUMN IF NOT EXISTS confirmed BOOLEAN DEFAULT FALSE NOT NULL;

-- v1: FUNCTION 节点关联的入口类全限定名（用于按 entry 维度增量清理）
ALTER TABLE ci_module_hierarchy ADD COLUMN IF NOT EXISTS source_entry_class VARCHAR(500);
CREATE INDEX IF NOT EXISTS idx_module_hierarchy_source_entry ON ci_module_hierarchy (task_id, source_entry_class);

CREATE INDEX IF NOT EXISTS idx_module_hierarchy_task ON ci_module_hierarchy (task_id);
CREATE INDEX IF NOT EXISTS idx_module_hierarchy_parent ON ci_module_hierarchy (parent_id);
-- uk_module_hierarchy_task_node → 见文末 partial unique uk_module_hierarchy_task_node_active

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_module_hierarchy
ALTER TABLE ci_module_hierarchy ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_module_hierarchy ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_module_hierarchy ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_module_hierarchy ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_module_hierarchy ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_module_hierarchy SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_module_hierarchy SET updated_date = COALESCE(updated_date, updated_at, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_module_hierarchy.created_date IS '创建时间';
COMMENT ON COLUMN ci_module_hierarchy.updated_date IS '更新时间';
COMMENT ON COLUMN ci_module_hierarchy.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_module_hierarchy.created_by   IS '创建人';
COMMENT ON COLUMN ci_module_hierarchy.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_module_hierarchy
COMMENT ON TABLE ci_module_hierarchy IS '模块层级表（AI 提炼入口的业务归属 DTO 落表）';
COMMENT ON COLUMN ci_module_hierarchy.task_id IS '任务ID';
COMMENT ON COLUMN ci_module_hierarchy.system_id IS '系统ID';
COMMENT ON COLUMN ci_module_hierarchy.level IS '层级：MODULE / SUB_MODULE / FUNCTION';
COMMENT ON COLUMN ci_module_hierarchy.parent_id IS '上级节点 ID（module.parent_id = NULL）';
COMMENT ON COLUMN ci_module_hierarchy.node_id IS '5 位 Base62 ID（m/s/f 前缀），同任务内唯一';
COMMENT ON COLUMN ci_module_hierarchy.name IS '模块/子模块/功能名称';
COMMENT ON COLUMN ci_module_hierarchy.keywords IS '关键词 JSON 数组字符串';
COMMENT ON COLUMN ci_module_hierarchy.class_paths IS '入口类全限定名集合（仅 FUNCTION 级）JSON 数组';
COMMENT ON COLUMN ci_module_hierarchy.method_signatures IS '该功能涉及的方法签名 JSON 数组（仅 FUNCTION 级）';
COMMENT ON COLUMN ci_module_hierarchy.confirmed IS '人工逐项复核确认标记：TRUE-已确认 / FALSE-未确认（仅作为审计痕迹）';


-- ============================================================
-- 23. ci_entrypoint — 知识入口复核表
-- 对应 Entity: EntrypointEntity.java (modules/entrypoint)
-- 对应 Mapper: EntrypointMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_entrypoint (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    system_id BIGINT NOT NULL,
    class_name VARCHAR(500) NOT NULL,
    file_path VARCHAR(500),
    entry_type VARCHAR(50),
    annotation VARCHAR(255),
    remark VARCHAR(500),
    methods_json JSONB,
    sort_order INT DEFAULT 0 NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- v1: INCREMENTAL 任务基线继承（NULL=本次新增；非空=从该基线任务继承）
ALTER TABLE ci_entrypoint ADD COLUMN IF NOT EXISTS baseline_task_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_entrypoint_baseline_task ON ci_entrypoint (task_id, baseline_task_id);

CREATE INDEX IF NOT EXISTS idx_entrypoint_task ON ci_entrypoint (task_id);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_entrypoint
ALTER TABLE ci_entrypoint ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_entrypoint ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_entrypoint ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_entrypoint ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_entrypoint ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_entrypoint SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_entrypoint SET updated_date = COALESCE(updated_date, updated_at, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_entrypoint.created_date IS '创建时间';
COMMENT ON COLUMN ci_entrypoint.updated_date IS '更新时间';
COMMENT ON COLUMN ci_entrypoint.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_entrypoint.created_by   IS '创建人';
COMMENT ON COLUMN ci_entrypoint.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_entrypoint
COMMENT ON TABLE ci_entrypoint IS '知识入口复核表（流水线 PARSING_CODE→AI_ANALYZING 之间落表，等待人工确认或驳回）；方法清单存 methods_json 列，仅供只读展示';
COMMENT ON COLUMN ci_entrypoint.task_id IS '任务ID';
COMMENT ON COLUMN ci_entrypoint.system_id IS '系统ID';
COMMENT ON COLUMN ci_entrypoint.class_name IS '入口类全限定名（如 com.demo.controller.UserController）';
COMMENT ON COLUMN ci_entrypoint.file_path IS '源文件相对路径';
COMMENT ON COLUMN ci_entrypoint.entry_type IS '入口类型：CONTROLLER / SCHEDULED_JOB / MQ_LISTENER / COMPONENT / APPLICATION / MAIN / CUSTOM';
COMMENT ON COLUMN ci_entrypoint.annotation IS '触发该类被识别为入口的注解简称（如 RestController、Scheduled）';
COMMENT ON COLUMN ci_entrypoint.remark IS '附加信息（如 RequestMapping 一级路径 / 队列名等）';
COMMENT ON COLUMN ci_entrypoint.methods_json IS '入口类下的关键方法列表 JSON 数组：[{methodName, methodSignature, annotation, httpPath, httpMethod}]；只读展示用';
COMMENT ON COLUMN ci_entrypoint.sort_order IS '同任务内入口排序权重（升序）';
COMMENT ON COLUMN ci_entrypoint.baseline_task_id IS 'INCREMENTAL 任务基线继承（NULL=本次识别；非空=从该基线任务继承）';


-- ============================================================
-- 24. ci_system_config — 系统配置表（key-value，运行期可在线修改）
-- 对应 Entity: SystemConfig.java (modules/quotacontrol)
-- 对应 Mapper: SystemConfigMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_system_config (
    key         VARCHAR(64)  PRIMARY KEY,
    value       VARCHAR(1000) NOT NULL,
    description VARCHAR(255),
    updated_by  VARCHAR(50),
    updated_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_system_config
ALTER TABLE ci_system_config ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_system_config ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_system_config ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_system_config ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_system_config ALTER COLUMN updated_by TYPE VARCHAR(100);
UPDATE ci_system_config SET updated_by = 'sys' WHERE updated_by IS NULL OR btrim(updated_by) = '';
ALTER TABLE ci_system_config ALTER COLUMN updated_by SET DEFAULT 'sys';
ALTER TABLE ci_system_config ALTER COLUMN updated_by SET NOT NULL;
UPDATE ci_system_config SET created_date = COALESCE(created_date, updated_at, CURRENT_TIMESTAMP);
UPDATE ci_system_config SET updated_date = COALESCE(updated_date, updated_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_system_config.created_date IS '创建时间';
COMMENT ON COLUMN ci_system_config.updated_date IS '更新时间';
COMMENT ON COLUMN ci_system_config.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_system_config.created_by   IS '创建人';
COMMENT ON COLUMN ci_system_config.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_system_config
COMMENT ON TABLE ci_system_config IS '系统配置表（key-value，运行期可在线修改；与 application.yml 同名 key 迁移）';
COMMENT ON COLUMN ci_system_config.key IS '配置键（业务语义名，如 token.task-limit）';
COMMENT ON COLUMN ci_system_config.value IS '配置值（文本型，由业务侧按需 parse）';
COMMENT ON COLUMN ci_system_config.description IS '配置说明';
COMMENT ON COLUMN ci_system_config.updated_by IS '最后修改人';

-- 本机三闸默认：task=4 / pull=1 / parse=1（见 docs/pull-parse-concurrency-redesign.md）
INSERT INTO ci_system_config (key, value, description, updated_by, created_by)
VALUES ('task.concurrency', '4', '【本机】同时持有任务执行槽的流水线数上限', 'sys', 'sys')
ON CONFLICT (key) DO NOTHING;

INSERT INTO ci_system_config (key, value, description, updated_by, created_by)
VALUES ('pull.concurrency', '1', '【本机】同时进行代码拉取（pullAndScan）的任务数上限', 'sys', 'sys')
ON CONFLICT (key) DO NOTHING;

INSERT INTO ci_system_config (key, value, description, updated_by, created_by)
VALUES ('parse.concurrency', '1', '【本机】同时进行重解析（AST + 入口发现）的任务数上限；不含 AI 层级/文档', 'sys', 'sys')
ON CONFLICT (key) DO NOTHING;

UPDATE ci_system_config
SET description = '【本机】同时进行重解析（AST + 入口发现）的任务数上限；不含 AI 层级/文档',
    updated_date = CURRENT_TIMESTAMP
WHERE key = 'parse.concurrency'
  AND description IS DISTINCT FROM '【本机】同时进行重解析（AST + 入口发现）的任务数上限；不含 AI 层级/文档';


-- ============================================================
-- 25. ci_user — 用户表
-- 对应 Entity: UserAccount.java (modules/auth)
-- 对应 Mapper: UserAccountMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50) NOT NULL,
    display_name  VARCHAR(100),
    role          VARCHAR(20) DEFAULT 'USER' NOT NULL,
    status        SMALLINT    DEFAULT 1   NOT NULL,
    last_login_at TIMESTAMP,
    created_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- idx_user_role：见审计字段块（is_deleted = 0）

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_user
ALTER TABLE ci_user ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_user ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_user ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_user ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_user ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_user SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_user SET updated_date = COALESCE(updated_date, updated_at, created_at, CURRENT_TIMESTAMP);
-- Spring ScriptUtils 不支持 DO $$；旧 deleted_at 直接幂等删除
ALTER TABLE ci_user DROP COLUMN IF EXISTS deleted_at;
CREATE INDEX IF NOT EXISTS idx_user_role ON ci_user (role) WHERE is_deleted = 0;
COMMENT ON COLUMN ci_user.created_date IS '创建时间';
COMMENT ON COLUMN ci_user.updated_date IS '更新时间';
COMMENT ON COLUMN ci_user.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_user.created_by   IS '创建人';
COMMENT ON COLUMN ci_user.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_user
COMMENT ON TABLE ci_user IS '用户表（MVP 阶段预置 admin 账号，后续扩展多账号）';
COMMENT ON COLUMN ci_user.username IS '登录账号';
COMMENT ON COLUMN ci_user.display_name IS '显示名';
COMMENT ON COLUMN ci_user.role IS '角色：ADMIN-管理员 / USER-普通用户';
COMMENT ON COLUMN ci_user.status IS '0-停用，1-启用';
COMMENT ON COLUMN ci_user.last_login_at IS '最近一次登录时间';

-- 系统关键配置：预置 admin 账号（与 AuthServiceImpl 硬编码账号对齐）
INSERT INTO ci_user (id, username, display_name, role, status)
VALUES (1, 'admin', '平台管理员', 'ADMIN', 1)
ON CONFLICT (id) DO NOTHING;
-- 序列对齐：避免后续显式插入 id 冲突
SELECT setval(pg_get_serial_sequence('ci_user', 'id'), GREATEST(1, (SELECT MAX(id) FROM ci_user)));


-- ============================================================
-- 26. ci_user_quota — 用户额度表
-- 对应 Entity: UserQuota.java (modules/quotacontrol)
-- 对应 Mapper: UserQuotaMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_user_quota (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT       NOT NULL,
    daily_token_limit   INT          DEFAULT 0 NOT NULL,
    monthly_token_limit INT          DEFAULT 0 NOT NULL,
    enabled             SMALLINT     DEFAULT 1 NOT NULL,
    remark              VARCHAR(200),
    created_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_user_quota
ALTER TABLE ci_user_quota ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_user_quota ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_user_quota ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_user_quota ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_user_quota ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_user_quota SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_user_quota SET updated_date = COALESCE(updated_date, updated_at, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_user_quota.created_date IS '创建时间';
COMMENT ON COLUMN ci_user_quota.updated_date IS '更新时间';
COMMENT ON COLUMN ci_user_quota.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_user_quota.created_by   IS '创建人';
COMMENT ON COLUMN ci_user_quota.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_user_quota
COMMENT ON TABLE ci_user_quota IS '用户额度表（按 user 维度的 Token 限额；0 表示不限）';
COMMENT ON COLUMN ci_user_quota.user_id IS '用户 ID（FK → ci_user.id）';
COMMENT ON COLUMN ci_user_quota.daily_token_limit IS '单日 Token 上限（0 = 不限）';
COMMENT ON COLUMN ci_user_quota.monthly_token_limit IS '单月 Token 上限（0 = 不限）';
COMMENT ON COLUMN ci_user_quota.enabled IS '是否启用额度检查（0-否，1-是）';
COMMENT ON COLUMN ci_user_quota.remark IS '备注';


-- ============================================================
-- 27. ci_repository_entrypoint — 仓库级已发布入口
-- 对应 Entity: RepositoryEntrypointEntity.java (modules/repository/publish)
-- 对应 Mapper: RepositoryEntrypointMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_repository_entrypoint (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    system_id BIGINT NOT NULL,
    class_name VARCHAR(500) NOT NULL,
    file_path VARCHAR(500),
    entry_type VARCHAR(50),
    annotation VARCHAR(255),
    remark VARCHAR(500),
    methods_json JSONB,
    sort_order INT DEFAULT 0 NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_repo_entrypoint_repo ON ci_repository_entrypoint (repository_id);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_repository_entrypoint
ALTER TABLE ci_repository_entrypoint ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_repository_entrypoint ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_repository_entrypoint ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_repository_entrypoint ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_repository_entrypoint ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_repository_entrypoint SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_repository_entrypoint SET updated_date = COALESCE(updated_date, updated_at, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_repository_entrypoint.created_date IS '创建时间';
COMMENT ON COLUMN ci_repository_entrypoint.updated_date IS '更新时间';
COMMENT ON COLUMN ci_repository_entrypoint.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_repository_entrypoint.created_by   IS '创建人';
COMMENT ON COLUMN ci_repository_entrypoint.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_repository_entrypoint
COMMENT ON TABLE ci_repository_entrypoint IS '仓库级已发布入口复核结果（推送成功时从 ci_entrypoint 覆盖写入；知识浏览页只读）';


-- ============================================================
-- 28. ci_repository_module_hierarchy — 仓库级已发布模块层级
-- 对应 Entity: RepositoryModuleHierarchyNode.java (modules/repository/publish)
-- 对应 Mapper: RepositoryModuleHierarchyNodeMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_repository_module_hierarchy (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    system_id BIGINT NOT NULL,
    level VARCHAR(20) NOT NULL,
    parent_id BIGINT,
    node_id VARCHAR(20) NOT NULL,
    name VARCHAR(200) NOT NULL,
    keywords JSONB,
    class_paths JSONB,
    method_signatures JSONB,
    confirmed BOOLEAN DEFAULT FALSE NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_repo_hierarchy_repo ON ci_repository_module_hierarchy (repository_id);
CREATE INDEX IF NOT EXISTS idx_repo_hierarchy_parent ON ci_repository_module_hierarchy (parent_id);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_repository_module_hierarchy
ALTER TABLE ci_repository_module_hierarchy ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_repository_module_hierarchy ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_repository_module_hierarchy ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_repository_module_hierarchy ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_repository_module_hierarchy ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_repository_module_hierarchy SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_repository_module_hierarchy SET updated_date = COALESCE(updated_date, updated_at, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_repository_module_hierarchy.created_date IS '创建时间';
COMMENT ON COLUMN ci_repository_module_hierarchy.updated_date IS '更新时间';
COMMENT ON COLUMN ci_repository_module_hierarchy.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_repository_module_hierarchy.created_by   IS '创建人';
COMMENT ON COLUMN ci_repository_module_hierarchy.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_repository_module_hierarchy
COMMENT ON TABLE ci_repository_module_hierarchy IS '仓库级已发布模块层级复核结果（推送成功时从 ci_module_hierarchy 覆盖写入；知识浏览页只读）';


-- ============================================================
-- 29. ci_repository_publish_snapshot — 仓库发布快照
-- 对应 Entity: RepositoryPublishSnapshot.java (modules/repository/publish)
-- 对应 Mapper: RepositoryPublishSnapshotMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_repository_publish_snapshot (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    system_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    version_num VARCHAR(50) NOT NULL,
    entry_scan_config JSONB,
    modularize_prompt_id BIGINT,
    document_prompt_id BIGINT,
    model_name VARCHAR(100),
    entrypoints_uri VARCHAR(255) NOT NULL DEFAULT '',
    module_hierarchy_uri VARCHAR(255) NOT NULL DEFAULT '',
    published_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    published_by VARCHAR(100),
    CONSTRAINT uk_repo_publish_version UNIQUE (version_id)
);

ALTER TABLE ci_repository_publish_snapshot ADD COLUMN IF NOT EXISTS entrypoints_uri VARCHAR(255) DEFAULT '';
ALTER TABLE ci_repository_publish_snapshot ADD COLUMN IF NOT EXISTS module_hierarchy_uri VARCHAR(255) DEFAULT '';
ALTER TABLE ci_repository_publish_snapshot DROP COLUMN IF EXISTS entrypoints_json;
ALTER TABLE ci_repository_publish_snapshot DROP COLUMN IF EXISTS module_hierarchy_json;
UPDATE ci_repository_publish_snapshot SET entrypoints_uri = '' WHERE entrypoints_uri IS NULL;
UPDATE ci_repository_publish_snapshot SET module_hierarchy_uri = '' WHERE module_hierarchy_uri IS NULL;
ALTER TABLE ci_repository_publish_snapshot ALTER COLUMN entrypoints_uri SET DEFAULT '';
ALTER TABLE ci_repository_publish_snapshot ALTER COLUMN module_hierarchy_uri SET DEFAULT '';
ALTER TABLE ci_repository_publish_snapshot ALTER COLUMN entrypoints_uri SET NOT NULL;
ALTER TABLE ci_repository_publish_snapshot ALTER COLUMN module_hierarchy_uri SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_repo_publish_snapshot_repo ON ci_repository_publish_snapshot (repository_id, published_at DESC);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_repository_publish_snapshot
ALTER TABLE ci_repository_publish_snapshot ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_repository_publish_snapshot ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_repository_publish_snapshot ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_repository_publish_snapshot ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_repository_publish_snapshot ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_repository_publish_snapshot SET created_date = COALESCE(created_date, published_at, CURRENT_TIMESTAMP);
UPDATE ci_repository_publish_snapshot SET updated_date = COALESCE(updated_date, published_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_repository_publish_snapshot.created_date IS '创建时间';
COMMENT ON COLUMN ci_repository_publish_snapshot.updated_date IS '更新时间';
COMMENT ON COLUMN ci_repository_publish_snapshot.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_repository_publish_snapshot.created_by   IS '创建人';
COMMENT ON COLUMN ci_repository_publish_snapshot.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_repository_publish_snapshot
COMMENT ON TABLE ci_repository_publish_snapshot IS '仓库发布快照：每次推送成功写入，供按版本回滚；与 ci_knowledge_version 一一对应';
COMMENT ON COLUMN ci_repository_publish_snapshot.entrypoints_uri IS '入口清单快照 URI（snapshot:{repoId}:{versionId}/entrypoints.json）';
COMMENT ON COLUMN ci_repository_publish_snapshot.module_hierarchy_uri IS '模块层级快照 URI（snapshot:{repoId}:{versionId}/module_hierarchy.json）';



-- ============================================================
-- 30. ci_business_knowledge — 业务知识配置
-- 对应 Entity: BusinessKnowledge.java (modules/businessknowledge)
-- 对应 Mapper: BusinessKnowledgeMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_business_knowledge (
    id          BIGSERIAL PRIMARY KEY,
    system_id   BIGINT       NOT NULL,
    content_uri VARCHAR(255) NOT NULL DEFAULT '',
    content_hash VARCHAR(100),
    version     INT          NOT NULL DEFAULT 1,
    updated_by  VARCHAR(64),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE ci_business_knowledge ADD COLUMN IF NOT EXISTS content_uri VARCHAR(255) DEFAULT '';
ALTER TABLE ci_business_knowledge ADD COLUMN IF NOT EXISTS content_hash VARCHAR(100);
ALTER TABLE ci_business_knowledge DROP COLUMN IF EXISTS content;
ALTER TABLE ci_business_knowledge ALTER COLUMN content_uri SET DEFAULT '';
UPDATE ci_business_knowledge SET content_uri = '' WHERE content_uri IS NULL;
ALTER TABLE ci_business_knowledge ALTER COLUMN content_uri SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_business_knowledge_system ON ci_business_knowledge (system_id);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_business_knowledge
ALTER TABLE ci_business_knowledge ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_business_knowledge ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_business_knowledge ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_business_knowledge ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_business_knowledge ALTER COLUMN updated_by TYPE VARCHAR(100);
UPDATE ci_business_knowledge SET updated_by = 'sys' WHERE updated_by IS NULL OR btrim(updated_by) = '';
ALTER TABLE ci_business_knowledge ALTER COLUMN updated_by SET DEFAULT 'sys';
ALTER TABLE ci_business_knowledge ALTER COLUMN updated_by SET NOT NULL;
UPDATE ci_business_knowledge SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_business_knowledge SET updated_date = COALESCE(updated_date, updated_at, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_business_knowledge.created_date IS '创建时间';
COMMENT ON COLUMN ci_business_knowledge.updated_date IS '更新时间';
COMMENT ON COLUMN ci_business_knowledge.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_business_knowledge.created_by   IS '创建人';
COMMENT ON COLUMN ci_business_knowledge.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_business_knowledge
COMMENT ON TABLE  ci_business_knowledge IS '业务知识配置（按系统维度，1:1 覆盖式保存；正文外置 NAS）';
COMMENT ON COLUMN ci_business_knowledge.system_id  IS '所属业务系统ID（活行唯一，见 uk_business_knowledge_system_active）';
COMMENT ON COLUMN ci_business_knowledge.content_uri IS '业务知识正文 URI（business-knowledge:{systemId}/content.md）';
COMMENT ON COLUMN ci_business_knowledge.content_hash IS '业务知识正文 MD5';
COMMENT ON COLUMN ci_business_knowledge.version    IS '保存次数（每次保存 +1，便于审计）';
COMMENT ON COLUMN ci_business_knowledge.updated_by IS '最后修改人（来自会话用户）';


-- ============================================================
-- 31. ci_method_function_binding — 方法 → 功能 反向绑定
-- 对应 Entity: MethodFunctionBinding.java (modules/hierarchy)
-- 对应 Mapper: MethodFunctionBindingMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_method_function_binding (
    id                  BIGSERIAL PRIMARY KEY,
    task_id             BIGINT       NOT NULL,
    system_id           BIGINT,
    module_node_id      VARCHAR(16)  NOT NULL,
    sub_module_node_id  VARCHAR(16)  NOT NULL,
    function_node_id    VARCHAR(16)  NOT NULL,
    class_name          VARCHAR(512) NOT NULL,
    method_signature    VARCHAR(512) NOT NULL,
    file_path           VARCHAR(500),
    source              VARCHAR(16)  NOT NULL,
    confidence          DECIMAL(4,3),
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_mfb_source
        CHECK (source IN ('AI', 'USER', 'MIGRATED', 'BACKFILL'))
);

CREATE INDEX IF NOT EXISTS idx_mfb_task_function
    ON ci_method_function_binding (task_id, function_node_id);
CREATE INDEX IF NOT EXISTS idx_mfb_task_class
    ON ci_method_function_binding (task_id, class_name);
CREATE INDEX IF NOT EXISTS idx_mfb_task_module
    ON ci_method_function_binding (task_id, module_node_id);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_method_function_binding
ALTER TABLE ci_method_function_binding ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_method_function_binding ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_method_function_binding ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_method_function_binding ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_method_function_binding ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_method_function_binding SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_method_function_binding SET updated_date = COALESCE(updated_date, updated_at, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_method_function_binding.created_date IS '创建时间';
COMMENT ON COLUMN ci_method_function_binding.updated_date IS '更新时间';
COMMENT ON COLUMN ci_method_function_binding.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_method_function_binding.created_by   IS '创建人';
COMMENT ON COLUMN ci_method_function_binding.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_method_function_binding
COMMENT ON TABLE  ci_method_function_binding IS '方法→功能 反向绑定（hierarchy 阶段 AI 输出按方法粒度落表）；每方法 1 行，由 (task_id, class_name, method_signature) 唯一定位到 (module, sub_module, function)';
COMMENT ON COLUMN ci_method_function_binding.task_id            IS '关联任务 ID（FK → ci_task.id）';
COMMENT ON COLUMN ci_method_function_binding.system_id          IS '冗余系统 ID，便于按系统维度查询';
COMMENT ON COLUMN ci_method_function_binding.module_node_id     IS '所属模块节点 ID（5 位 Base62，m 前缀；逻辑 FK → ci_module_hierarchy.node_id）';
COMMENT ON COLUMN ci_method_function_binding.sub_module_node_id IS '所属子模块节点 ID（s 前缀）';
COMMENT ON COLUMN ci_method_function_binding.function_node_id   IS '所属功能节点 ID（f 前缀）';
COMMENT ON COLUMN ci_method_function_binding.class_name         IS '入口类全限定名';
COMMENT ON COLUMN ci_method_function_binding.method_signature   IS '方法签名 methodName(ParamType1,ParamType2)（不含返回类型）';
COMMENT ON COLUMN ci_method_function_binding.source             IS '归属来源：AI / USER / MIGRATED / BACKFILL（程序回填保证文档可达）';
COMMENT ON COLUMN ci_method_function_binding.confidence        IS 'AI 输出的归属置信度（0-1，可空）';

-- 文档源码可达性：固化相对路径 + 允许 BACKFILL（幂等）
ALTER TABLE ci_method_function_binding ADD COLUMN IF NOT EXISTS file_path VARCHAR(500);
COMMENT ON COLUMN ci_method_function_binding.file_path IS '源文件相对路径（落表时尽量固化，文档取源优先）';
ALTER TABLE ci_method_function_binding DROP CONSTRAINT IF EXISTS chk_mfb_source;
ALTER TABLE ci_method_function_binding ADD CONSTRAINT chk_mfb_source
    CHECK (source IN ('AI', 'USER', 'MIGRATED', 'BACKFILL'));


-- ============================================================
-- v1: 增量扫描结果表（INCREMENTAL 任务的扫描结果落盘）
-- 对应 Entity: IncrementalScanRecord.java (modules/scanner)
-- 对应 Mapper: IncrementalScanMapper.java
-- 用途：PULLING_CODE 完成后把 git diff 结果（changedPaths / deletedPaths / baselineTaskId / baselineCommitId / headCommitId）
--       持久化下来；后续 PARSING_CODE / ENTRYPOINT_DISCOVERY / MODULE_HIERARCHY 阶段都从这里读取，不再依赖内存
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_incremental_scan (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    system_id BIGINT NOT NULL,
    repository_id BIGINT NOT NULL,
    baseline_task_id BIGINT,
    baseline_commit_id VARCHAR(100),
    head_commit_id VARCHAR(100),
    scan_mode VARCHAR(20) NOT NULL,
    changed_paths JSONB NOT NULL DEFAULT '[]'::jsonb,
    deleted_paths JSONB NOT NULL DEFAULT '[]'::jsonb,
    inherited_count INT DEFAULT 0 NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_incremental_scan_repo ON ci_incremental_scan (repository_id, scan_mode);
CREATE INDEX IF NOT EXISTS idx_incremental_scan_baseline ON ci_incremental_scan (baseline_task_id);

-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）
-- AUDIT_FIELDS_BEGIN ci_incremental_scan
ALTER TABLE ci_incremental_scan ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_incremental_scan ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ci_incremental_scan ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;
ALTER TABLE ci_incremental_scan ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
ALTER TABLE ci_incremental_scan ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;
UPDATE ci_incremental_scan SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);
UPDATE ci_incremental_scan SET updated_date = COALESCE(updated_date, updated_at, created_at, CURRENT_TIMESTAMP);
COMMENT ON COLUMN ci_incremental_scan.created_date IS '创建时间';
COMMENT ON COLUMN ci_incremental_scan.updated_date IS '更新时间';
COMMENT ON COLUMN ci_incremental_scan.is_deleted   IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_incremental_scan.created_by   IS '创建人';
COMMENT ON COLUMN ci_incremental_scan.updated_by   IS '最后修改人';
-- AUDIT_FIELDS_END ci_incremental_scan
COMMENT ON TABLE ci_incremental_scan IS 'INCREMENTAL 任务扫描结果落盘（git diff → changed/deleted paths + baseline 引用）';
COMMENT ON COLUMN ci_incremental_scan.task_id IS '任务ID（活行唯一，见 uk_incremental_scan_task_active）';
COMMENT ON COLUMN ci_incremental_scan.system_id IS '系统ID';
COMMENT ON COLUMN ci_incremental_scan.repository_id IS '仓库ID';
COMMENT ON COLUMN ci_incremental_scan.baseline_task_id IS '数据复制源：最近一次 PUSHED 任务的 ID（INITIAL 任务为 NULL）';
COMMENT ON COLUMN ci_incremental_scan.baseline_commit_id IS '对比基准 commit（仓库 last_published_commit_id）';
COMMENT ON COLUMN ci_incremental_scan.head_commit_id IS '本次扫描 HEAD commit';
COMMENT ON COLUMN ci_incremental_scan.scan_mode IS 'INITIAL / INCREMENTAL';
COMMENT ON COLUMN ci_incremental_scan.changed_paths IS '本次变更文件相对路径列表（JSONB 数组）';
COMMENT ON COLUMN ci_incremental_scan.deleted_paths IS '本次删除文件相对路径列表（JSONB 数组）';
COMMENT ON COLUMN ci_incremental_scan.inherited_count IS '从基线任务继承的入口数（仅 INCREMENTAL 任务有意义）';



-- ============================================================

-- ============================================================
-- 审计软删 × 唯一约束：改为部分唯一索引（WHERE is_deleted = 0）
-- 方案 B：逻辑删腾出「活行」唯一键，覆盖写可再 insert，无需 upsert
-- 详见 docs/tablelogic-partial-unique-plan.md
-- ============================================================

-- ci_incremental_scan：原 PK(task_id) → 代理键 id + 活行唯一(task_id)
-- 注意：Spring ScriptUtils 不支持 DO $$，改为平铺语句（每次启动 DROP+ADD PK 幂等可接受）
ALTER TABLE ci_incremental_scan ADD COLUMN IF NOT EXISTS id BIGSERIAL;
ALTER TABLE ci_incremental_scan DROP CONSTRAINT IF EXISTS ci_incremental_scan_pkey;
ALTER TABLE ci_incremental_scan ADD PRIMARY KEY (id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_incremental_scan_task_active
  ON ci_incremental_scan (task_id) WHERE is_deleted = 0;

-- ci_draft_workspace
ALTER TABLE ci_draft_workspace DROP CONSTRAINT IF EXISTS uk_task_id;
CREATE UNIQUE INDEX IF NOT EXISTS uk_draft_workspace_task_active
  ON ci_draft_workspace (task_id) WHERE is_deleted = 0;

-- ci_scan_window
DROP INDEX IF EXISTS uk_scan_window_repo;
CREATE UNIQUE INDEX IF NOT EXISTS uk_scan_window_repo_active
  ON ci_scan_window (repository_id) WHERE is_deleted = 0;

-- ci_entrypoint
ALTER TABLE ci_entrypoint DROP CONSTRAINT IF EXISTS uk_entrypoint_task_class;
CREATE UNIQUE INDEX IF NOT EXISTS uk_entrypoint_task_class_active
  ON ci_entrypoint (task_id, class_name) WHERE is_deleted = 0;

-- ci_module_hierarchy
DROP INDEX IF EXISTS uk_module_hierarchy_task_node;
CREATE UNIQUE INDEX IF NOT EXISTS uk_module_hierarchy_task_node_active
  ON ci_module_hierarchy (task_id, node_id) WHERE is_deleted = 0;

-- ci_method_function_binding
ALTER TABLE ci_method_function_binding DROP CONSTRAINT IF EXISTS uk_mfb_task_class_method;
CREATE UNIQUE INDEX IF NOT EXISTS uk_mfb_task_class_method_active
  ON ci_method_function_binding (task_id, class_name, method_signature) WHERE is_deleted = 0;

-- ci_repository_entrypoint
ALTER TABLE ci_repository_entrypoint DROP CONSTRAINT IF EXISTS uk_repo_entrypoint_class;
CREATE UNIQUE INDEX IF NOT EXISTS uk_repo_entrypoint_class_active
  ON ci_repository_entrypoint (repository_id, class_name) WHERE is_deleted = 0;

-- ci_repository_module_hierarchy
ALTER TABLE ci_repository_module_hierarchy DROP CONSTRAINT IF EXISTS uk_repo_hierarchy_node;
CREATE UNIQUE INDEX IF NOT EXISTS uk_repo_hierarchy_node_active
  ON ci_repository_module_hierarchy (repository_id, node_id) WHERE is_deleted = 0;

-- ci_business_knowledge（1:1 系统）
ALTER TABLE ci_business_knowledge DROP CONSTRAINT IF EXISTS ci_business_knowledge_system_id_key;
CREATE UNIQUE INDEX IF NOT EXISTS uk_business_knowledge_system_active
  ON ci_business_knowledge (system_id) WHERE is_deleted = 0;

-- ci_user.username / ci_user_quota.user_id
ALTER TABLE ci_user DROP CONSTRAINT IF EXISTS ci_user_username_key;
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_username_active
  ON ci_user (username) WHERE is_deleted = 0;
ALTER TABLE ci_user_quota DROP CONSTRAINT IF EXISTS ci_user_quota_user_id_key;
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_quota_user_active
  ON ci_user_quota (user_id) WHERE is_deleted = 0;


-- Schema TEXT 字段整改迁移（幂等）：A VARCHAR / B JSONB / C URI
-- 不做旧正文文件迁移；DROP 旧列后允许丢失非 DEFAULT 正文
-- ============================================================

-- A 组：TEXT → VARCHAR（Spring ScriptUtils 不支持 DO $$，改为平铺幂等语句）
UPDATE ci_task SET error_reason = LEFT(error_reason, 2000) WHERE error_reason IS NOT NULL AND length(error_reason) > 2000;
ALTER TABLE ci_task ALTER COLUMN error_reason TYPE VARCHAR(2000) USING LEFT(error_reason::text, 2000);
UPDATE ci_ai_call_record SET error_reason = LEFT(error_reason, 2000) WHERE error_reason IS NOT NULL AND length(error_reason) > 2000;
ALTER TABLE ci_ai_call_record ALTER COLUMN error_reason TYPE VARCHAR(2000) USING LEFT(error_reason::text, 2000);
UPDATE ci_entry_scan_trial SET error_message = LEFT(error_message, 2000) WHERE error_message IS NOT NULL AND length(error_message) > 2000;
ALTER TABLE ci_entry_scan_trial ALTER COLUMN error_message TYPE VARCHAR(2000) USING LEFT(error_message::text, 2000);
UPDATE ci_push_task SET error_message = LEFT(error_message, 2000) WHERE error_message IS NOT NULL AND length(error_message) > 2000;
ALTER TABLE ci_push_task ALTER COLUMN error_message TYPE VARCHAR(2000) USING LEFT(error_message::text, 2000);
UPDATE ci_operation_log SET exception_msg = LEFT(exception_msg, 4000) WHERE exception_msg IS NOT NULL AND length(exception_msg) > 4000;
ALTER TABLE ci_operation_log ALTER COLUMN exception_msg TYPE VARCHAR(4000) USING LEFT(exception_msg::text, 4000);
UPDATE ci_draft_review_comment SET comment = LEFT(comment, 2000) WHERE comment IS NOT NULL AND length(comment) > 2000;
ALTER TABLE ci_draft_review_comment ALTER COLUMN comment TYPE VARCHAR(2000) USING LEFT(comment::text, 2000);
UPDATE ci_system_config SET value = LEFT(value, 1000) WHERE value IS NOT NULL AND length(value) > 1000;
ALTER TABLE ci_system_config ALTER COLUMN value TYPE VARCHAR(1000) USING LEFT(value::text, 1000);
UPDATE ci_method_call SET dependency_candidates = LEFT(dependency_candidates, 4000) WHERE dependency_candidates IS NOT NULL AND length(dependency_candidates) > 4000;
ALTER TABLE ci_method_call ALTER COLUMN dependency_candidates TYPE VARCHAR(4000) USING LEFT(dependency_candidates::text, 4000);

-- B 组：TEXT → JSONB（空串置 NULL；已是 JSONB 时 ALTER TYPE 仍安全）
UPDATE ci_repository SET entry_scan_config = NULL WHERE entry_scan_config IS NOT NULL AND btrim(entry_scan_config::text) = '';
ALTER TABLE ci_repository ALTER COLUMN entry_scan_config TYPE JSONB USING entry_scan_config::jsonb;
UPDATE ci_task SET entry_scan_config = NULL WHERE entry_scan_config IS NOT NULL AND btrim(entry_scan_config::text) = '';
ALTER TABLE ci_task ALTER COLUMN entry_scan_config TYPE JSONB USING entry_scan_config::jsonb;
UPDATE ci_task SET remediation_scope_json = NULL WHERE remediation_scope_json IS NOT NULL AND btrim(remediation_scope_json::text) = '';
ALTER TABLE ci_task ALTER COLUMN remediation_scope_json TYPE JSONB USING remediation_scope_json::jsonb;
UPDATE ci_entry_scan_trial SET config_snapshot = NULL WHERE config_snapshot IS NOT NULL AND btrim(config_snapshot::text) = '';
ALTER TABLE ci_entry_scan_trial ALTER COLUMN config_snapshot TYPE JSONB USING config_snapshot::jsonb;
UPDATE ci_push_task SET target_info = NULL WHERE target_info IS NOT NULL AND btrim(target_info::text) = '';
ALTER TABLE ci_push_task ALTER COLUMN target_info TYPE JSONB USING target_info::jsonb;
UPDATE ci_module_hierarchy SET keywords = NULL WHERE keywords IS NOT NULL AND btrim(keywords::text) = '';
ALTER TABLE ci_module_hierarchy ALTER COLUMN keywords TYPE JSONB USING keywords::jsonb;
UPDATE ci_module_hierarchy SET class_paths = NULL WHERE class_paths IS NOT NULL AND btrim(class_paths::text) = '';
ALTER TABLE ci_module_hierarchy ALTER COLUMN class_paths TYPE JSONB USING class_paths::jsonb;
UPDATE ci_module_hierarchy SET method_signatures = NULL WHERE method_signatures IS NOT NULL AND btrim(method_signatures::text) = '';
ALTER TABLE ci_module_hierarchy ALTER COLUMN method_signatures TYPE JSONB USING method_signatures::jsonb;
UPDATE ci_entrypoint SET methods_json = NULL WHERE methods_json IS NOT NULL AND btrim(methods_json::text) = '';
ALTER TABLE ci_entrypoint ALTER COLUMN methods_json TYPE JSONB USING methods_json::jsonb;
UPDATE ci_repository_entrypoint SET methods_json = NULL WHERE methods_json IS NOT NULL AND btrim(methods_json::text) = '';
ALTER TABLE ci_repository_entrypoint ALTER COLUMN methods_json TYPE JSONB USING methods_json::jsonb;
UPDATE ci_repository_module_hierarchy SET keywords = NULL WHERE keywords IS NOT NULL AND btrim(keywords::text) = '';
ALTER TABLE ci_repository_module_hierarchy ALTER COLUMN keywords TYPE JSONB USING keywords::jsonb;
UPDATE ci_repository_module_hierarchy SET class_paths = NULL WHERE class_paths IS NOT NULL AND btrim(class_paths::text) = '';
ALTER TABLE ci_repository_module_hierarchy ALTER COLUMN class_paths TYPE JSONB USING class_paths::jsonb;
UPDATE ci_repository_module_hierarchy SET method_signatures = NULL WHERE method_signatures IS NOT NULL AND btrim(method_signatures::text) = '';
ALTER TABLE ci_repository_module_hierarchy ALTER COLUMN method_signatures TYPE JSONB USING method_signatures::jsonb;
UPDATE ci_repository_publish_snapshot SET entry_scan_config = NULL WHERE entry_scan_config IS NOT NULL AND btrim(entry_scan_config::text) = '';
ALTER TABLE ci_repository_publish_snapshot ALTER COLUMN entry_scan_config TYPE JSONB USING entry_scan_config::jsonb;

-- C 组：ADD URI / DROP 旧列（幂等；不做正文迁移）
ALTER TABLE ci_prompt ADD COLUMN IF NOT EXISTS content_uri VARCHAR(255) DEFAULT '';
ALTER TABLE ci_prompt ADD COLUMN IF NOT EXISTS content_hash VARCHAR(100);
ALTER TABLE ci_prompt DROP COLUMN IF EXISTS content;

ALTER TABLE ci_business_knowledge ADD COLUMN IF NOT EXISTS content_uri VARCHAR(255) DEFAULT '';
ALTER TABLE ci_business_knowledge ADD COLUMN IF NOT EXISTS content_hash VARCHAR(100);
ALTER TABLE ci_business_knowledge DROP COLUMN IF EXISTS content;

ALTER TABLE ci_knowledge_release_edit ADD COLUMN IF NOT EXISTS content_uri VARCHAR(255) DEFAULT '';
ALTER TABLE ci_knowledge_release_edit ADD COLUMN IF NOT EXISTS hash VARCHAR(100);
ALTER TABLE ci_knowledge_release_edit DROP COLUMN IF EXISTS content_text;

ALTER TABLE ci_entry_scan_trial ADD COLUMN IF NOT EXISTS result_uri VARCHAR(255);
ALTER TABLE ci_entry_scan_trial DROP COLUMN IF EXISTS result_json;

ALTER TABLE ci_repository_publish_snapshot ADD COLUMN IF NOT EXISTS entrypoints_uri VARCHAR(255) DEFAULT '';
ALTER TABLE ci_repository_publish_snapshot ADD COLUMN IF NOT EXISTS module_hierarchy_uri VARCHAR(255) DEFAULT '';
ALTER TABLE ci_repository_publish_snapshot DROP COLUMN IF EXISTS entrypoints_json;
ALTER TABLE ci_repository_publish_snapshot DROP COLUMN IF EXISTS module_hierarchy_json;


-- =====================================================================
-- 设计变更日志（与代码侧耦合点）
-- =====================================================================
--
-- 1. 调度相关表已下线
--    * ci_schedule_task / ci_schedule_fire_record 已删除
--    * 任务调度改由 ScanWindowScheduler（基于 ci_scan_window）+ TaskQueueDispatcher 内存调度实现
--    * 如需历史审计查询，可从 ci_task 的 trigger_source='SCHEDULED' + ci_scan_window.last_fired_at 还原
--
-- 2. 重跑清理已对齐 MyBatis-Plus FieldStrategy
--    * DecompileTask 的 timing/cluster 字段（durationMs / startedAt / endedAt /
--      activeSegmentStartedAt / claimedBy / claimedAt / leaseUntil）已加
--      @TableField(updateStrategy = FieldStrategy.ALWAYS)，确保 retry 时 setXxx(null)
--      能真正落库为 null（避免基于旧值累加 / 残留）
--
-- 3. 系统配置已脱离硬编码
--    * ci_system_config 取代原 application.yml 里的 token.task-limit / ai.concurrency 等
--    * 数据库首次创建时为空，由前端「系统配置」页在线修改
--
-- 4. 4 个 KEEP-DML 系统关键配置表
--    * ci_prompt：当前无种子，由前端基础配置 → 提示词页录入
--    * ci_model：当前无种子，由前端基础配置 → 模型配置页录入
--    * ci_model_preset：6 个常见厂商模板
--    * ci_user：1 条 admin 账号（id=1），配套 setval 序列对齐
--
-- =====================================================================
