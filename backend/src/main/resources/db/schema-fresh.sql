-- =====================================================================
-- CodeInsight Platform — 数据库初始化脚本（纯净版 / 新库专用）
-- 兼容 PostgreSQL 11+
--
-- 用途：
--   * 在空库上一次性建表 + 索引 + 注释 + 系统种子数据
--   * 不含历史 ALTER / UPDATE / DROP COLUMN 迁移语句
--
-- 与 schema.sql 的关系：
--   * schema.sql     — 旧库幂等升级（CREATE + ADD COLUMN IF NOT EXISTS + 迁移）
--   * schema-fresh.sql — 本文件，仅新库批量初始化
--
-- 切换（application-local.yml）：
--   spring.sql.init.schema-locations: classpath:db/schema-fresh.sql
--   并设置 SQL_INIT_MODE=always
-- =====================================================================


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
    component VARCHAR(100) DEFAULT '' NOT NULL,
    modularize_prompt_id BIGINT,
    document_prompt_id BIGINT,
    max_concurrent_tasks INT DEFAULT 1 NOT NULL,
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_system_name_component_active
    ON ci_system (name, component)
    WHERE is_deleted = 0;

COMMENT ON COLUMN ci_system.created_date IS '创建时间';
COMMENT ON COLUMN ci_system.updated_date IS '更新时间';
COMMENT ON COLUMN ci_system.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_system.created_by IS '创建人';
COMMENT ON COLUMN ci_system.updated_by IS '最后修改人';
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
    modularize_prompt_id BIGINT,
    document_prompt_id BIGINT,
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_repo_system_id ON ci_repository (system_id);

COMMENT ON COLUMN ci_repository.created_date IS '创建时间';
COMMENT ON COLUMN ci_repository.updated_date IS '更新时间';
COMMENT ON COLUMN ci_repository.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_repository.created_by IS '创建人';
COMMENT ON COLUMN ci_repository.updated_by IS '最后修改人';
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

-- ============================================================
-- 3. ci_prompt — 提示词模板表
-- 对应 Entity: DecompilePrompt.java (modules/prompt)
-- 对应 Mapper: DecompilePromptMapper.java
-- 正文外置 NAS：content_uri / content_hash（schema DEFAULT 种子仅元数据，正文由 classpath 兜底写入）
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_prompt (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    content_uri VARCHAR(255) DEFAULT '' NOT NULL,
    content_hash VARCHAR(100),
    version INT DEFAULT 1 NOT NULL,
    status SMALLINT DEFAULT 1 NOT NULL,
    is_default SMALLINT DEFAULT 0 NOT NULL,
    prompt_type VARCHAR(32) DEFAULT 'MODULARIZE' NOT NULL,
    lifecycle VARCHAR(16) DEFAULT 'RELEASED' NOT NULL,
    category VARCHAR(16) DEFAULT 'DEFAULT' NOT NULL,
    scope_id BIGINT,
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_prompt_lifecycle ON ci_prompt (lifecycle, prompt_type);
CREATE INDEX IF NOT EXISTS idx_prompt_category_scope ON ci_prompt (category, scope_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ci_prompt_type_default_active
    ON ci_prompt (prompt_type) WHERE is_default = 1 AND category = 'DEFAULT';

COMMENT ON COLUMN ci_prompt.created_date IS '创建时间';
COMMENT ON COLUMN ci_prompt.updated_date IS '更新时间';
COMMENT ON COLUMN ci_prompt.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_prompt.created_by IS '创建人';
COMMENT ON COLUMN ci_prompt.updated_by IS '最后修改人';
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
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_scan_window_repo_active
  ON ci_scan_window (repository_id) WHERE is_deleted = 0;

COMMENT ON COLUMN ci_scan_window.created_date IS '创建时间';
COMMENT ON COLUMN ci_scan_window.updated_date IS '更新时间';
COMMENT ON COLUMN ci_scan_window.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_scan_window.created_by IS '创建人';
COMMENT ON COLUMN ci_scan_window.updated_by IS '最后修改人';
COMMENT ON TABLE ci_scan_window IS '仓库执行时间窗口：定时扫描任务以此为准触发任务下发（week_days 位掩码：1=周一 2=周二 4=周三 8=周四 16=周五 32=周六 64=周日，127=每天）';
COMMENT ON COLUMN ci_scan_window.week_days IS '周几位掩码，bit0..bit6 对应周一到周日';
COMMENT ON COLUMN ci_scan_window.hour IS '小时 0-23';
COMMENT ON COLUMN ci_scan_window.minute IS '分钟 0-59';
COMMENT ON COLUMN ci_scan_window.last_fired_at IS '最近一次实际触发时间，用于幂等（同分钟窗口不重复触发）';

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
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_trial_repo ON ci_entry_scan_trial (repository_id);
CREATE INDEX IF NOT EXISTS idx_trial_status ON ci_entry_scan_trial (status, finished_at);

COMMENT ON COLUMN ci_entry_scan_trial.created_date IS '创建时间';
COMMENT ON COLUMN ci_entry_scan_trial.updated_date IS '更新时间';
COMMENT ON COLUMN ci_entry_scan_trial.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_entry_scan_trial.created_by IS '创建人';
COMMENT ON COLUMN ci_entry_scan_trial.updated_by IS '最后修改人';
COMMENT ON TABLE ci_entry_scan_trial IS '入口扫描试跑记录：用户在仓库配置中点击"试跑"产生的入口识别结果（不入库真实任务，每次独立执行）';
COMMENT ON COLUMN ci_entry_scan_trial.config_snapshot IS '本次试跑用的 entryScanConfig（JSONB）';
COMMENT ON COLUMN ci_entry_scan_trial.result_uri IS '试跑结果 URI（trial:{id}/result.json，落 runtimeRoot/trials）';
COMMENT ON COLUMN ci_entry_scan_trial.status IS '试跑状态：PENDING/RUNNING/SUCCESS/FAILED/CANCELLED';
COMMENT ON COLUMN ci_entry_scan_trial.user_id IS '触发用户';
COMMENT ON COLUMN ci_entry_scan_trial.started_at IS '开始时间';
COMMENT ON COLUMN ci_entry_scan_trial.finished_at IS '完成时间';
COMMENT ON COLUMN ci_entry_scan_trial.error_message IS '失败原因';

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
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_task_system_id ON ci_task (system_id);
CREATE INDEX IF NOT EXISTS idx_task_status ON ci_task (status);
CREATE INDEX IF NOT EXISTS idx_task_trigger_source ON ci_task (trigger_source);
CREATE INDEX IF NOT EXISTS idx_task_schedule ON ci_task (schedule_id);
CREATE INDEX IF NOT EXISTS idx_task_claimed ON ci_task (claimed_by) WHERE claimed_by IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_task_queue_by_created_date ON ci_task (priority DESC, created_date ASC) WHERE status = 'PENDING';

COMMENT ON COLUMN ci_task.created_date IS '创建时间';
COMMENT ON COLUMN ci_task.updated_date IS '更新时间';
COMMENT ON COLUMN ci_task.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_task.created_by IS '创建人';
COMMENT ON COLUMN ci_task.updated_by IS '最后修改人';
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
COMMENT ON COLUMN ci_task.resume_from IS '纠错续跑起点：AI_ANALYZING / GENERATING_DOC';
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
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_snapshot_task_id ON ci_file_snapshot (task_id);

COMMENT ON COLUMN ci_file_snapshot.created_date IS '创建时间';
COMMENT ON COLUMN ci_file_snapshot.updated_date IS '更新时间';
COMMENT ON COLUMN ci_file_snapshot.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_file_snapshot.created_by IS '创建人';
COMMENT ON COLUMN ci_file_snapshot.updated_by IS '最后修改人';
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
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ai_task_id ON ci_ai_call_record (task_id);
CREATE INDEX IF NOT EXISTS idx_ai_chunk_id ON ci_ai_call_record (chunk_id);

COMMENT ON COLUMN ci_ai_call_record.created_date IS '创建时间';
COMMENT ON COLUMN ci_ai_call_record.updated_date IS '更新时间';
COMMENT ON COLUMN ci_ai_call_record.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_ai_call_record.created_by IS '创建人';
COMMENT ON COLUMN ci_ai_call_record.updated_by IS '最后修改人';
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
COMMENT ON COLUMN ci_ai_call_record.call_stage IS '调用阶段标识：MODULE_HIERARCHY / GENERATING_DOC 等，用于按阶段分组统计';

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
    baseline_workspace_id BIGINT,
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_draft_workspace_baseline ON ci_draft_workspace (baseline_workspace_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_draft_workspace_task_active
  ON ci_draft_workspace (task_id) WHERE is_deleted = 0;

COMMENT ON COLUMN ci_draft_workspace.created_date IS '创建时间';
COMMENT ON COLUMN ci_draft_workspace.updated_date IS '更新时间';
COMMENT ON COLUMN ci_draft_workspace.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_draft_workspace.created_by IS '创建人';
COMMENT ON COLUMN ci_draft_workspace.updated_by IS '最后修改人';
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
    baseline_task_id BIGINT,
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_draft_workspace_baseline ON ci_knowledge_draft (workspace_id, baseline_task_id);

CREATE INDEX IF NOT EXISTS idx_draft_workspace_id ON ci_knowledge_draft (workspace_id);
CREATE INDEX IF NOT EXISTS idx_draft_status ON ci_knowledge_draft (status);
CREATE INDEX IF NOT EXISTS idx_draft_parent_id ON ci_knowledge_draft (parent_id);

COMMENT ON COLUMN ci_knowledge_draft.created_date IS '创建时间';
COMMENT ON COLUMN ci_knowledge_draft.updated_date IS '更新时间';
COMMENT ON COLUMN ci_knowledge_draft.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_knowledge_draft.created_by IS '创建人';
COMMENT ON COLUMN ci_knowledge_draft.updated_by IS '最后修改人';
COMMENT ON TABLE ci_knowledge_draft IS 'Markdown 知识草稿表（自引用树结构，组成模块目录）';
COMMENT ON COLUMN ci_knowledge_draft.workspace_id IS '关联草稿工作区ID';
COMMENT ON COLUMN ci_knowledge_draft.parent_id IS '父级草稿ID（自引用，用于构建模块目录树）';
COMMENT ON COLUMN ci_knowledge_draft.file_path IS '模块/文件 Markdown 路径';
COMMENT ON COLUMN ci_knowledge_draft.module_name IS '模块名称';
COMMENT ON COLUMN ci_knowledge_draft.content_uri IS '草稿内容在存储中的地址';
COMMENT ON COLUMN ci_knowledge_draft.status IS '草稿状态：DRAFT / EDITING / CONFIRMED / PUSHED / ARCHIVED（与 ci_task.status 解耦）';
COMMENT ON COLUMN ci_knowledge_draft.sort_order IS '同级排序权重（升序）';
COMMENT ON COLUMN ci_knowledge_draft.hash IS '草稿内容的 MD5 Hash';

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
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_revision_draft_id ON ci_draft_revision (draft_id);

COMMENT ON COLUMN ci_draft_revision.created_date IS '创建时间';
COMMENT ON COLUMN ci_draft_revision.updated_date IS '更新时间';
COMMENT ON COLUMN ci_draft_revision.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_draft_revision.created_by IS '创建人';
COMMENT ON COLUMN ci_draft_revision.updated_by IS '最后修改人';
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
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_comment_draft_id ON ci_draft_review_comment (draft_id);

COMMENT ON COLUMN ci_draft_review_comment.created_date IS '创建时间';
COMMENT ON COLUMN ci_draft_review_comment.updated_date IS '更新时间';
COMMENT ON COLUMN ci_draft_review_comment.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_draft_review_comment.created_by IS '创建人';
COMMENT ON COLUMN ci_draft_review_comment.updated_by IS '最后修改人';
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
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ref_draft_id ON ci_draft_source_reference (draft_id);

COMMENT ON COLUMN ci_draft_source_reference.created_date IS '创建时间';
COMMENT ON COLUMN ci_draft_source_reference.updated_date IS '更新时间';
COMMENT ON COLUMN ci_draft_source_reference.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_draft_source_reference.created_by IS '创建人';
COMMENT ON COLUMN ci_draft_source_reference.updated_by IS '最后修改人';
COMMENT ON TABLE ci_draft_source_reference IS '草稿代码来源引用表（草稿正文与被引用源码行号区间的双向追溯链）';
COMMENT ON COLUMN ci_draft_source_reference.draft_id IS '关联草稿ID';
COMMENT ON COLUMN ci_draft_source_reference.file_path IS '引用源文件路径';
COMMENT ON COLUMN ci_draft_source_reference.start_line IS '起始行号';
COMMENT ON COLUMN ci_draft_source_reference.end_line IS '结束行号（0 表示整文件）';
COMMENT ON COLUMN ci_draft_source_reference.class_name IS '入口类全限定名（可选，便于复核展示）';
COMMENT ON COLUMN ci_draft_source_reference.method_signature IS '方法签名 methodName(ParamTypes)，不含返回类型（可选）';

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
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_version_system_id ON ci_knowledge_version (system_id);
CREATE INDEX IF NOT EXISTS idx_version_number ON ci_knowledge_version (version_num);

COMMENT ON COLUMN ci_knowledge_version.created_date IS '创建时间';
COMMENT ON COLUMN ci_knowledge_version.updated_date IS '更新时间';
COMMENT ON COLUMN ci_knowledge_version.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_knowledge_version.created_by IS '创建人';
COMMENT ON COLUMN ci_knowledge_version.updated_by IS '最后修改人';
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
    content_uri VARCHAR(255) DEFAULT '' NOT NULL,
    hash VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    submitted_by VARCHAR(50) NOT NULL,
    approved_by VARCHAR(50),
    approved_at TIMESTAMP,
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_release_edit_repo ON ci_knowledge_release_edit (repository_id);
CREATE INDEX IF NOT EXISTS idx_release_edit_status ON ci_knowledge_release_edit (status);

COMMENT ON COLUMN ci_knowledge_release_edit.created_date IS '创建时间';
COMMENT ON COLUMN ci_knowledge_release_edit.updated_date IS '更新时间';
COMMENT ON COLUMN ci_knowledge_release_edit.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_knowledge_release_edit.created_by IS '创建人';
COMMENT ON COLUMN ci_knowledge_release_edit.updated_by IS '最后修改人';
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
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_push_task_version_id ON ci_push_task (version_id);
CREATE INDEX IF NOT EXISTS idx_push_task_status ON ci_push_task (status);

COMMENT ON COLUMN ci_push_task.created_date IS '创建时间';
COMMENT ON COLUMN ci_push_task.updated_date IS '更新时间';
COMMENT ON COLUMN ci_push_task.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_push_task.created_by IS '创建人';
COMMENT ON COLUMN ci_push_task.updated_by IS '最后修改人';
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
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_system_id ON ci_token_usage_audit (system_id);
CREATE INDEX IF NOT EXISTS idx_audit_task_id ON ci_token_usage_audit (task_id);
CREATE INDEX IF NOT EXISTS idx_audit_created_date ON ci_token_usage_audit (created_date);

COMMENT ON COLUMN ci_token_usage_audit.created_date IS '创建时间';
COMMENT ON COLUMN ci_token_usage_audit.updated_date IS '更新时间';
COMMENT ON COLUMN ci_token_usage_audit.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_token_usage_audit.created_by IS '创建人';
COMMENT ON COLUMN ci_token_usage_audit.updated_by IS '最后修改人';
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
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_op_system_id ON ci_operation_log (system_id);
CREATE INDEX IF NOT EXISTS idx_op_task_id ON ci_operation_log (task_id);
CREATE INDEX IF NOT EXISTS idx_op_created_date ON ci_operation_log (created_date);

COMMENT ON COLUMN ci_operation_log.created_date IS '创建时间';
COMMENT ON COLUMN ci_operation_log.updated_date IS '更新时间';
COMMENT ON COLUMN ci_operation_log.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_operation_log.created_by IS '创建人';
COMMENT ON COLUMN ci_operation_log.updated_by IS '最后修改人';
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
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

COMMENT ON COLUMN ci_model.created_date IS '创建时间';
COMMENT ON COLUMN ci_model.updated_date IS '更新时间';
COMMENT ON COLUMN ci_model.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_model.created_by IS '创建人';
COMMENT ON COLUMN ci_model.updated_by IS '最后修改人';
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
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_model_preset_identifier ON ci_model_preset (identifier);
CREATE INDEX IF NOT EXISTS idx_model_preset_status_sort ON ci_model_preset (status, sort_order);

COMMENT ON COLUMN ci_model_preset.created_date IS '创建时间';
COMMENT ON COLUMN ci_model_preset.updated_date IS '更新时间';
COMMENT ON COLUMN ci_model_preset.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_model_preset.created_by IS '创建人';
COMMENT ON COLUMN ci_model_preset.updated_by IS '最后修改人';
COMMENT ON TABLE ci_model_preset IS 'AI 模型预设模板表（系统关键配置 — 预置 6 个常见厂商模板供用户一键克隆）';
COMMENT ON COLUMN ci_model_preset.name IS '预设显示名称';
COMMENT ON COLUMN ci_model_preset.identifier IS '模型调用ID';
COMMENT ON COLUMN ci_model_preset.provider IS '技术供应商';
COMMENT ON COLUMN ci_model_preset.base_url IS 'Endpoint URL（接口地址）';
COMMENT ON COLUMN ci_model_preset.capabilities IS '支持能力，逗号分隔 (text,image,video)';
COMMENT ON COLUMN ci_model_preset.description IS '模板说明';
COMMENT ON COLUMN ci_model_preset.sort_order IS '排序权重';
COMMENT ON COLUMN ci_model_preset.status IS '启用状态：0-停用，1-启用';

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
    baseline_task_id BIGINT,
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_method_call_baseline_task ON ci_method_call (task_id, baseline_task_id);

CREATE INDEX IF NOT EXISTS idx_method_call_task_id ON ci_method_call (task_id);
CREATE INDEX IF NOT EXISTS idx_method_call_class ON ci_method_call (task_id, class_name, caller_method);
CREATE INDEX IF NOT EXISTS idx_method_call_caller_sig ON ci_method_call (task_id, caller_signature);
CREATE INDEX IF NOT EXISTS idx_method_call_target_sig ON ci_method_call (task_id, target_signature);

COMMENT ON COLUMN ci_method_call.created_date IS '创建时间';
COMMENT ON COLUMN ci_method_call.updated_date IS '更新时间';
COMMENT ON COLUMN ci_method_call.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_method_call.created_by IS '创建人';
COMMENT ON COLUMN ci_method_call.updated_by IS '最后修改人';
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
    source_entry_class VARCHAR(500),
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_module_hierarchy_source_entry ON ci_module_hierarchy (task_id, source_entry_class);

CREATE INDEX IF NOT EXISTS idx_module_hierarchy_task ON ci_module_hierarchy (task_id);
CREATE INDEX IF NOT EXISTS idx_module_hierarchy_parent ON ci_module_hierarchy (parent_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_module_hierarchy_task_node_active
  ON ci_module_hierarchy (task_id, node_id) WHERE is_deleted = 0;

COMMENT ON COLUMN ci_module_hierarchy.created_date IS '创建时间';
COMMENT ON COLUMN ci_module_hierarchy.updated_date IS '更新时间';
COMMENT ON COLUMN ci_module_hierarchy.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_module_hierarchy.created_by IS '创建人';
COMMENT ON COLUMN ci_module_hierarchy.updated_by IS '最后修改人';
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
    baseline_task_id BIGINT,
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_entrypoint_baseline_task ON ci_entrypoint (task_id, baseline_task_id);

CREATE INDEX IF NOT EXISTS idx_entrypoint_task ON ci_entrypoint (task_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_entrypoint_task_class_active
  ON ci_entrypoint (task_id, class_name) WHERE is_deleted = 0;

COMMENT ON COLUMN ci_entrypoint.created_date IS '创建时间';
COMMENT ON COLUMN ci_entrypoint.updated_date IS '更新时间';
COMMENT ON COLUMN ci_entrypoint.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_entrypoint.created_by IS '创建人';
COMMENT ON COLUMN ci_entrypoint.updated_by IS '最后修改人';
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
    key VARCHAR(64) PRIMARY KEY,
    value VARCHAR(1000) NOT NULL,
    description VARCHAR(255),
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

COMMENT ON COLUMN ci_system_config.created_date IS '创建时间';
COMMENT ON COLUMN ci_system_config.updated_date IS '更新时间';
COMMENT ON COLUMN ci_system_config.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_system_config.created_by IS '创建人';
COMMENT ON COLUMN ci_system_config.updated_by IS '最后修改人';
COMMENT ON TABLE ci_system_config IS '系统配置表（key-value，运行期可在线修改；与 application.yml 同名 key 迁移）';
COMMENT ON COLUMN ci_system_config.key IS '配置键（业务语义名，如 token.task-limit）';
COMMENT ON COLUMN ci_system_config.value IS '配置值（文本型，由业务侧按需 parse）';
COMMENT ON COLUMN ci_system_config.description IS '配置说明';

-- ============================================================
-- 25. ci_user — 用户表
-- 对应 Entity: UserAccount.java (modules/auth)
-- 对应 Mapper: UserAccountMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    display_name VARCHAR(100),
    role VARCHAR(20) DEFAULT 'USER' NOT NULL,
    status SMALLINT DEFAULT 1 NOT NULL,
    last_login_at TIMESTAMP,
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_user_role ON ci_user (role) WHERE is_deleted = 0;
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_username_active
  ON ci_user (username) WHERE is_deleted = 0;

COMMENT ON COLUMN ci_user.created_date IS '创建时间';
COMMENT ON COLUMN ci_user.updated_date IS '更新时间';
COMMENT ON COLUMN ci_user.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_user.created_by IS '创建人';
COMMENT ON COLUMN ci_user.updated_by IS '最后修改人';
COMMENT ON TABLE ci_user IS '用户表（MVP 阶段预置 admin 账号，后续扩展多账号）';
COMMENT ON COLUMN ci_user.username IS '登录账号';
COMMENT ON COLUMN ci_user.display_name IS '显示名';
COMMENT ON COLUMN ci_user.role IS '角色：ADMIN-管理员 / USER-普通用户';
COMMENT ON COLUMN ci_user.status IS '0-停用，1-启用';
COMMENT ON COLUMN ci_user.last_login_at IS '最近一次登录时间';

INSERT INTO ci_user (id, username, display_name, role, status)
VALUES (1, 'admin', '平台管理员', 'ADMIN', 1)
ON CONFLICT (id) DO NOTHING;
SELECT setval(pg_get_serial_sequence('ci_user', 'id'), GREATEST(1, (SELECT MAX(id) FROM ci_user)));

-- ============================================================
-- 26. ci_user_quota — 用户额度表
-- 对应 Entity: UserQuota.java (modules/quotacontrol)
-- 对应 Mapper: UserQuotaMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_user_quota (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    daily_token_limit INT DEFAULT 0 NOT NULL,
    monthly_token_limit INT DEFAULT 0 NOT NULL,
    enabled SMALLINT DEFAULT 1 NOT NULL,
    remark VARCHAR(200),
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_quota_user_active
  ON ci_user_quota (user_id) WHERE is_deleted = 0;

COMMENT ON COLUMN ci_user_quota.created_date IS '创建时间';
COMMENT ON COLUMN ci_user_quota.updated_date IS '更新时间';
COMMENT ON COLUMN ci_user_quota.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_user_quota.created_by IS '创建人';
COMMENT ON COLUMN ci_user_quota.updated_by IS '最后修改人';
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
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_repo_entrypoint_repo ON ci_repository_entrypoint (repository_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_repo_entrypoint_class_active
  ON ci_repository_entrypoint (repository_id, class_name) WHERE is_deleted = 0;

COMMENT ON COLUMN ci_repository_entrypoint.created_date IS '创建时间';
COMMENT ON COLUMN ci_repository_entrypoint.updated_date IS '更新时间';
COMMENT ON COLUMN ci_repository_entrypoint.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_repository_entrypoint.created_by IS '创建人';
COMMENT ON COLUMN ci_repository_entrypoint.updated_by IS '最后修改人';
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
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_repo_hierarchy_repo ON ci_repository_module_hierarchy (repository_id);
CREATE INDEX IF NOT EXISTS idx_repo_hierarchy_parent ON ci_repository_module_hierarchy (parent_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_repo_hierarchy_node_active
  ON ci_repository_module_hierarchy (repository_id, node_id) WHERE is_deleted = 0;

COMMENT ON COLUMN ci_repository_module_hierarchy.created_date IS '创建时间';
COMMENT ON COLUMN ci_repository_module_hierarchy.updated_date IS '更新时间';
COMMENT ON COLUMN ci_repository_module_hierarchy.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_repository_module_hierarchy.created_by IS '创建人';
COMMENT ON COLUMN ci_repository_module_hierarchy.updated_by IS '最后修改人';
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
    entrypoints_uri VARCHAR(255) DEFAULT '' NOT NULL,
    module_hierarchy_uri VARCHAR(255) DEFAULT '' NOT NULL,
    published_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    published_by VARCHAR(100),
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    CONSTRAINT uk_repo_publish_version UNIQUE (version_id)
);

CREATE INDEX IF NOT EXISTS idx_repo_publish_snapshot_repo ON ci_repository_publish_snapshot (repository_id, published_at DESC);

COMMENT ON COLUMN ci_repository_publish_snapshot.created_date IS '创建时间';
COMMENT ON COLUMN ci_repository_publish_snapshot.updated_date IS '更新时间';
COMMENT ON COLUMN ci_repository_publish_snapshot.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_repository_publish_snapshot.created_by IS '创建人';
COMMENT ON COLUMN ci_repository_publish_snapshot.updated_by IS '最后修改人';
COMMENT ON TABLE ci_repository_publish_snapshot IS '仓库发布快照：每次推送成功写入，供按版本回滚；与 ci_knowledge_version 一一对应';
COMMENT ON COLUMN ci_repository_publish_snapshot.entrypoints_uri IS '入口清单快照 URI（snapshot:{repoId}:{versionId}/entrypoints.json）';
COMMENT ON COLUMN ci_repository_publish_snapshot.module_hierarchy_uri IS '模块层级快照 URI（snapshot:{repoId}:{versionId}/module_hierarchy.json）';

-- ============================================================
-- 30. ci_business_knowledge — 业务知识配置
-- 对应 Entity: BusinessKnowledge.java (modules/businessknowledge)
-- 对应 Mapper: BusinessKnowledgeMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_business_knowledge (
    id BIGSERIAL PRIMARY KEY,
    system_id BIGINT NOT NULL,
    content_uri VARCHAR(255) DEFAULT '' NOT NULL,
    content_hash VARCHAR(100),
    version INT NOT NULL DEFAULT 1,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_business_knowledge_system ON ci_business_knowledge (system_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_business_knowledge_system_active
  ON ci_business_knowledge (system_id) WHERE is_deleted = 0;

COMMENT ON COLUMN ci_business_knowledge.created_date IS '创建时间';
COMMENT ON COLUMN ci_business_knowledge.updated_date IS '更新时间';
COMMENT ON COLUMN ci_business_knowledge.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_business_knowledge.created_by IS '创建人';
COMMENT ON COLUMN ci_business_knowledge.updated_by IS '最后修改人';
COMMENT ON TABLE ci_business_knowledge IS '业务知识配置（按系统维度，1:1 覆盖式保存；正文外置 NAS）';
COMMENT ON COLUMN ci_business_knowledge.system_id IS '所属业务系统ID（活行唯一，见 uk_business_knowledge_system_active）';
COMMENT ON COLUMN ci_business_knowledge.content_uri IS '业务知识正文 URI（business-knowledge:{systemId}/content.md）';
COMMENT ON COLUMN ci_business_knowledge.content_hash IS '业务知识正文 MD5';
COMMENT ON COLUMN ci_business_knowledge.version IS '保存次数（每次保存 +1，便于审计）';
COMMENT ON COLUMN ci_business_knowledge.updated_by IS '最后修改人（来自会话用户）';

-- ============================================================
-- 31. ci_method_function_binding — 方法 → 功能 反向绑定
-- 对应 Entity: MethodFunctionBinding.java (modules/hierarchy)
-- 对应 Mapper: MethodFunctionBindingMapper.java
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_method_function_binding (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    system_id BIGINT,
    module_node_id VARCHAR(16) NOT NULL,
    sub_module_node_id VARCHAR(16) NOT NULL,
    function_node_id VARCHAR(16) NOT NULL,
    class_name VARCHAR(512) NOT NULL,
    method_signature VARCHAR(512) NOT NULL,
    source VARCHAR(16) NOT NULL,
    confidence DECIMAL(4,3),
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    CONSTRAINT chk_mfb_source CHECK (source IN ('AI', 'USER', 'MIGRATED'))
);

CREATE INDEX IF NOT EXISTS idx_mfb_task_function
    ON ci_method_function_binding (task_id, function_node_id);
CREATE INDEX IF NOT EXISTS idx_mfb_task_class
    ON ci_method_function_binding (task_id, class_name);
CREATE INDEX IF NOT EXISTS idx_mfb_task_module
    ON ci_method_function_binding (task_id, module_node_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_mfb_task_class_method_active
  ON ci_method_function_binding (task_id, class_name, method_signature) WHERE is_deleted = 0;

COMMENT ON COLUMN ci_method_function_binding.created_date IS '创建时间';
COMMENT ON COLUMN ci_method_function_binding.updated_date IS '更新时间';
COMMENT ON COLUMN ci_method_function_binding.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_method_function_binding.created_by IS '创建人';
COMMENT ON COLUMN ci_method_function_binding.updated_by IS '最后修改人';
COMMENT ON TABLE ci_method_function_binding IS '方法→功能 反向绑定（hierarchy 阶段 AI 输出按方法粒度落表）；每方法 1 行，由 (task_id, class_name, method_signature) 唯一定位到 (module, sub_module, function)';
COMMENT ON COLUMN ci_method_function_binding.task_id IS '关联任务 ID（FK → ci_task.id）';
COMMENT ON COLUMN ci_method_function_binding.system_id IS '冗余系统 ID，便于按系统维度查询';
COMMENT ON COLUMN ci_method_function_binding.module_node_id IS '所属模块节点 ID（5 位 Base62，m 前缀；逻辑 FK → ci_module_hierarchy.node_id）';
COMMENT ON COLUMN ci_method_function_binding.sub_module_node_id IS '所属子模块节点 ID（s 前缀）';
COMMENT ON COLUMN ci_method_function_binding.function_node_id IS '所属功能节点 ID（f 前缀）';
COMMENT ON COLUMN ci_method_function_binding.class_name IS '入口类全限定名';
COMMENT ON COLUMN ci_method_function_binding.method_signature IS '方法签名 methodName(ParamType1,ParamType2)（不含返回类型）';
COMMENT ON COLUMN ci_method_function_binding.source IS '归属来源：AI-hierarchy 阶段 AI 输出；USER-人工在 MODULE_HIERARCHY_REVIEW 调整；MIGRATED-从旧 ci_module_hierarchy.method_signatures 一次性迁移';
COMMENT ON COLUMN ci_method_function_binding.confidence IS 'AI 输出的归属置信度（0-1，可空）';

-- ============================================================
-- ci_incremental_scan
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
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted SMALLINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(100) DEFAULT 'sys' NOT NULL,
    updated_by VARCHAR(100) DEFAULT 'sys' NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_incremental_scan_repo ON ci_incremental_scan (repository_id, scan_mode);
CREATE INDEX IF NOT EXISTS idx_incremental_scan_baseline ON ci_incremental_scan (baseline_task_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_incremental_scan_task_active
  ON ci_incremental_scan (task_id) WHERE is_deleted = 0;

COMMENT ON COLUMN ci_incremental_scan.created_date IS '创建时间';
COMMENT ON COLUMN ci_incremental_scan.updated_date IS '更新时间';
COMMENT ON COLUMN ci_incremental_scan.is_deleted IS '逻辑删除：0=未删除 1=已删除';
COMMENT ON COLUMN ci_incremental_scan.created_by IS '创建人';
COMMENT ON COLUMN ci_incremental_scan.updated_by IS '最后修改人';
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


-- =====================================================================
-- 说明
-- =====================================================================
-- 1. 调度相关表（ci_schedule_task / ci_schedule_fire_record）已下线，本文件不含。
-- 2. 大正文一律 URI 外置（content_uri / result_uri / entrypoints_uri 等），无 TEXT 正文字段。
-- 3. 结构化配置字段使用 JSONB；短文案使用 VARCHAR（≤4000）。
-- 4. KEEP-DML 种子：ci_prompt（2 条 DEFAULT 元数据）、ci_model_preset（6 厂商模板）、ci_user（admin，见上文）。
-- =====================================================================
