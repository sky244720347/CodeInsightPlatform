-- 数据库初始化脚本 (PostgreSQL 兼容版)

-- 1. 系统管理表
CREATE TABLE IF NOT EXISTS ci_system (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    name_cn VARCHAR(200),
    description VARCHAR(500),
    owner VARCHAR(50) NOT NULL,
    status SMALLINT DEFAULT 1 NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP
);
-- 1.0.1 系统软删除字段（兼容旧库，必须在 COMMENT 之前）
ALTER TABLE ci_system ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
-- 1.0.2 系统中文名称字段（兼容旧库）
ALTER TABLE ci_system ADD COLUMN IF NOT EXISTS name_cn VARCHAR(200);

COMMENT ON TABLE ci_system IS '系统管理表';
COMMENT ON COLUMN ci_system.name IS '系统名称';
COMMENT ON COLUMN ci_system.name_cn IS '系统中文名称';
COMMENT ON COLUMN ci_system.description IS '系统描述';
COMMENT ON COLUMN ci_system.owner IS '系统负责人';
COMMENT ON COLUMN ci_system.deleted_at IS '逻辑删除时间，NULL=未删除';

-- 1.1 系统软删除字段（兼容旧库）
ALTER TABLE ci_system ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
COMMENT ON COLUMN ci_system.deleted_at IS '逻辑删除时间，NULL=未删除';

-- 1.1.1 旧 state 列清理（已废弃，新逻辑不再需要系统级启停状态）
ALTER TABLE ci_system DROP COLUMN IF EXISTS state;
ALTER TABLE ci_system DROP COLUMN IF EXISTS status;

-- 1.1.2 系统级提示词绑定（任务下发时继承此处；未设置时回退到默认提示词 is_default=1）
ALTER TABLE ci_system ADD COLUMN IF NOT EXISTS modularize_prompt_id BIGINT;
ALTER TABLE ci_system ADD COLUMN IF NOT EXISTS document_prompt_id BIGINT;
COMMENT ON COLUMN ci_system.modularize_prompt_id IS '模块提取提示词 ID（FK → ci_prompt.id，运行时未设置则回退到 is_default=1（已废弃，请使用 ci_repository 同名列））';
COMMENT ON COLUMN ci_system.document_prompt_id IS '文档生成提示词 ID（FK → ci_prompt.id，运行时未设置则回退到 is_default=1（已废弃，请使用 ci_repository 同名列））';

-- 1.1.3 任务队列：系统级并发上限（每系统同时在跑任务数）
ALTER TABLE ci_system ADD COLUMN IF NOT EXISTS max_concurrent_tasks INT DEFAULT 1 NOT NULL;
COMMENT ON COLUMN ci_system.max_concurrent_tasks IS '同时在跑任务上限（系统级并发闸门），默认 1';

-- 2. 代码库配置表
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
    entry_scan_config TEXT,
    push_git_url VARCHAR(500),
    push_branch VARCHAR(100),
    push_username VARCHAR(100),
    push_password VARCHAR(255),
    push_target_folder VARCHAR(255) DEFAULT 'docs/code-insight',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_repo_system_id ON ci_repository (system_id);
COMMENT ON TABLE ci_repository IS '代码库配置表';
COMMENT ON COLUMN ci_repository.system_id IS '关联系统ID';
COMMENT ON COLUMN ci_repository.git_url IS 'Git仓库地址';
COMMENT ON COLUMN ci_repository.branch IS '默认分支';
COMMENT ON COLUMN ci_repository.username IS '凭证用户名';
COMMENT ON COLUMN ci_repository.password IS '凭证密码/Token';
COMMENT ON COLUMN ci_repository.scan_root IS '扫描根目录';
COMMENT ON COLUMN ci_repository.exclude_dirs IS '排除目录，逗号分隔';
COMMENT ON COLUMN ci_repository.exclude_file_types IS '排除文件类型，逗号分隔';
COMMENT ON COLUMN ci_repository.last_commit_id IS '已发布知识对应的源代码基线 Commit ID（推送成功或回滚时更新；扫描任务不再写入）';
COMMENT ON COLUMN ci_repository.last_decompile_at IS '最后反编译时间';
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS entry_scan_config TEXT;
COMMENT ON COLUMN ci_repository.entry_scan_config IS '仓库级入口扫描配置 JSON：includesByType（CONTROLLER/SCHEDULED_JOB/MQ_LISTENER/OTHER 各含 includeAnnotations/includeClasspaths/includeExtends）+ excludeClasspaths/excludePackages/excludeAnnotations/excludeTargets；新建任务时默认带出，任务可覆盖';

-- 2.1 代码库软删除字段
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
COMMENT ON COLUMN ci_repository.deleted_at IS '逻辑删除时间，NULL=未删除';

ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS push_git_url VARCHAR(500);
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS push_branch VARCHAR(100);
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS push_username VARCHAR(100);
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS push_password VARCHAR(255);
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS push_target_folder VARCHAR(255) DEFAULT 'docs/code-insight';
COMMENT ON COLUMN ci_repository.push_git_url IS '推送目标 Git 仓库地址（为空则使用 git_url）';
COMMENT ON COLUMN ci_repository.push_branch IS '推送目标分支（为空则默认 docs-code-insight）';
COMMENT ON COLUMN ci_repository.push_username IS '推送 Git 凭证用户名';
COMMENT ON COLUMN ci_repository.push_password IS '推送 Git 凭证密码/Token';
COMMENT ON COLUMN ci_repository.push_target_folder IS '文档在仓库中的目标文件夹路径';

-- 2.2 仓库级提示词绑定（任务下发时继承此处；未设置时回退到默认提示词 is_default=1）
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS modularize_prompt_id BIGINT;
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS document_prompt_id BIGINT;
COMMENT ON COLUMN ci_repository.modularize_prompt_id IS '模块提取提示词 ID（FK → ci_prompt.id，运行时未设置则回退到 is_default=1（已废弃，请使用 ci_repository 同名列））';
COMMENT ON COLUMN ci_repository.document_prompt_id IS '文档生成提示词 ID（FK → ci_prompt.id，运行时未设置则回退到 is_default=1（已废弃，请使用 ci_repository 同名列））';
-- 数据迁移：把现有系统绑定的提示词 ID 同步到其所有仓库
UPDATE ci_repository r SET modularize_prompt_id = s.modularize_prompt_id, document_prompt_id = s.document_prompt_id FROM ci_system s WHERE r.system_id = s.id AND r.deleted_at IS NULL AND s.deleted_at IS NULL;


-- 3. 提示词模板表
CREATE TABLE IF NOT EXISTS ci_prompt (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    content TEXT NOT NULL,
    version INT DEFAULT 1 NOT NULL,
    status SMALLINT DEFAULT 1 NOT NULL,
    is_default SMALLINT DEFAULT 0 NOT NULL,
    prompt_type VARCHAR(32) DEFAULT 'MODULARIZE' NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
COMMENT ON TABLE ci_prompt IS '提示词模板表';
COMMENT ON COLUMN ci_prompt.name IS '提示词名称';
COMMENT ON COLUMN ci_prompt.content IS '提示词内容';
COMMENT ON COLUMN ci_prompt.version IS '版本号';
COMMENT ON COLUMN ci_prompt.status IS '已废弃，请使用 lifecycle；保留列仅为历史兼容';
COMMENT ON COLUMN ci_prompt.is_default IS '是否默认：0-否，1-是';
ALTER TABLE ci_prompt ADD COLUMN IF NOT EXISTS prompt_type VARCHAR(32) DEFAULT 'MODULARIZE' NOT NULL;
COMMENT ON COLUMN ci_prompt.prompt_type IS '提示词用途：MODULARIZE-模块提取（用于 AI_ANALYZING / MODULE_HIERARCHY 阶段），DOCUMENT_GENERATION-文档生成（用于 GENERATING_DOC 阶段）';

-- 3.1 提示词类型迁移：兼容历史 schema（无 prompt_type 列时补齐）

-- 3.2 同一 prompt_type 下只允许一条 is_default=1 的记录（约束已默认提示词唯一）
CREATE UNIQUE INDEX IF NOT EXISTS uk_ci_prompt_type_default
    ON ci_prompt (prompt_type) WHERE is_default = 1;

-- 3.3 提示词生命周期：DRAFT-草稿（可编辑） / RELEASED-已发布（不可直改，需复制） / ARCHIVED-已归档（不可用）
ALTER TABLE ci_prompt ADD COLUMN IF NOT EXISTS lifecycle VARCHAR(16) DEFAULT 'RELEASED' NOT NULL;
COMMENT ON COLUMN ci_prompt.lifecycle IS '生命周期：DRAFT-草稿(可编辑) / RELEASED-已发布(锁定,需复制改) / ARCHIVED-已归档';
CREATE INDEX IF NOT EXISTS idx_prompt_lifecycle ON ci_prompt (lifecycle, prompt_type);
-- 补齐存量(ALTER ADD COLUMN 在 PG 11+ 已自动赋默认值;本条为安全兜底,防止重复 ALTER 被 IF NOT EXISTS 跳过时字段留 NULL)
UPDATE ci_prompt SET lifecycle = 'RELEASED' WHERE lifecycle IS NULL;

-- 3.4 提示词分类(category)+ scope 隔离(避免不同仓库/系统互相看到对方的自定义提示词)
ALTER TABLE ci_prompt ADD COLUMN IF NOT EXISTS category VARCHAR(16) DEFAULT 'DEFAULT' NOT NULL;
COMMENT ON COLUMN ci_prompt.category IS '提示词分类:DEFAULT-全局默认提示词(基础配置 → 提示词页管理,is_default=1 表示真正启用),USER-用户自定义提示词(按 scope 隔离)';
ALTER TABLE ci_prompt ADD COLUMN IF NOT EXISTS scope_id BIGINT;
COMMENT ON COLUMN ci_prompt.scope_id IS 'USER 提示词的 scope ID（系统ID或仓库ID,表示该 USER 提示词归属哪个配置上下文）;DEFAULT 提示词此字段为 NULL（全局可见）';
CREATE INDEX IF NOT EXISTS idx_prompt_category_scope ON ci_prompt (category, scope_id);
-- 唯一约束改为只在 DEFAULT 类别内:同 prompt_type 只有一条 is_default=1
DROP INDEX IF EXISTS uk_ci_prompt_type_default;
CREATE UNIQUE INDEX IF NOT EXISTS uk_ci_prompt_type_default_active
    ON ci_prompt (prompt_type) WHERE is_default = 1 AND category = 'DEFAULT';

-- 3.4.1 数据迁移:把已有提示词的 category 设为 DEFAULT(兼容旧数据)
UPDATE ci_prompt SET category = 'DEFAULT' WHERE category IS NULL;

-- 3.6 仓库执行时间窗口（定时扫描任务专用）：每个仓库一行，按 weekday 位掩码 + hour/minute 命中即触发
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
CREATE UNIQUE INDEX IF NOT EXISTS uk_scan_window_repo ON ci_scan_window (repository_id);
COMMENT ON TABLE ci_scan_window IS '仓库执行时间窗口：定时扫描任务以此为准触发任务下发（week_days 位掩码：1=周一 2=周二 4=周三 8=周四 16=周五 32=周六 64=周日，127=每天）';
COMMENT ON COLUMN ci_scan_window.week_days IS '周几位掩码，bit0..bit6 对应周一到周日';
COMMENT ON COLUMN ci_scan_window.hour IS '小时 0-23';
COMMENT ON COLUMN ci_scan_window.minute IS '分钟 0-59';
COMMENT ON COLUMN ci_scan_window.last_fired_at IS '最近一次实际触发时间，用于幂等（同分钟窗口不重复触发）';

-- 3.5 入口扫描试跑记录表：保存"试跑"产生的历史结果（不入库真实任务）
CREATE TABLE IF NOT EXISTS ci_entry_scan_trial (
    id BIGSERIAL PRIMARY KEY,
    system_id BIGINT NOT NULL,
    repository_id BIGINT NOT NULL,
    user_id VARCHAR(50),
    status VARCHAR(16) NOT NULL,
    config_snapshot TEXT,
    result_json TEXT,
    error_message TEXT,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
ALTER TABLE ci_entry_scan_trial ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL;
CREATE INDEX IF NOT EXISTS idx_trial_repo ON ci_entry_scan_trial (repository_id);
CREATE INDEX IF NOT EXISTS idx_trial_status ON ci_entry_scan_trial (status, finished_at);
COMMENT ON TABLE ci_entry_scan_trial IS '入口扫描试跑记录：用户在仓库配置中点击"试跑"产生的入口识别结果（不入库真实任务，每次独立执行）';
COMMENT ON COLUMN ci_entry_scan_trial.config_snapshot IS '本次试跑用的 entryScanConfig（JSON 字符串）';
COMMENT ON COLUMN ci_entry_scan_trial.result_json IS '试跑结果：入口类 + 方法列表 JSON 字符串';
COMMENT ON COLUMN ci_entry_scan_trial.status IS '试跑状态：PENDING/RUNNING/SUCCESS/FAILED/CANCELLED';
COMMENT ON COLUMN ci_entry_scan_trial.updated_at IS '更新时间';

-- 4. 知识构建任务表
CREATE TABLE IF NOT EXISTS ci_task (
    id BIGSERIAL PRIMARY KEY,
    system_id BIGINT NOT NULL,
    repository_id BIGINT NOT NULL,
    model_name VARCHAR(100),
    status VARCHAR(50) NOT NULL,
    type VARCHAR(50) DEFAULT 'INITIAL' NOT NULL,
    progress INT DEFAULT 0 NOT NULL,
    error_reason TEXT,
    duration_ms BIGINT DEFAULT 0 NOT NULL,
    started_at TIMESTAMP,
    ended_at TIMESTAMP,
    entry_scan_config TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_task_system_id ON ci_task (system_id);
CREATE INDEX IF NOT EXISTS idx_task_status ON ci_task (status);
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS modularize_prompt_id BIGINT;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS document_prompt_id BIGINT;
COMMENT ON COLUMN ci_task.modularize_prompt_id IS '模块提取提示词 ID（按主键查 ci_prompt）';
COMMENT ON COLUMN ci_task.document_prompt_id IS '文档生成提示词 ID（按主键查 ci_prompt）';
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS model_name VARCHAR(100);
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS entry_scan_config TEXT;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS require_hierarchy_review BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS trigger_source VARCHAR(20) DEFAULT 'MANUAL' NOT NULL;
ALTER TABLE ci_task ALTER COLUMN trigger_source TYPE VARCHAR(40);
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS schedule_id BIGINT;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS remediation_kind VARCHAR(30);
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS base_version_id BIGINT;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS base_task_id BIGINT;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS resume_from VARCHAR(30);
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS remediation_scope_json TEXT;
COMMENT ON COLUMN ci_task.remediation_kind IS '知识纠错类型：ENTRYPOINT / HIERARCHY / DOCUMENT（trigger_source=KNOWLEDGE_REMEDIATION 时）';
COMMENT ON COLUMN ci_task.base_version_id IS '纠错所依据的已发布知识版本 ID';
COMMENT ON COLUMN ci_task.base_task_id IS '纠错克隆来源任务 ID（last_published_task_id）';
COMMENT ON COLUMN ci_task.resume_from IS '纠错续跑起点：AI_ANALYZING / GENERATING_DOC';
COMMENT ON COLUMN ci_task.remediation_scope_json IS '纠错范围 JSON（如 moduleIds / relativePath）';
COMMENT ON COLUMN ci_task.trigger_source IS '触发来源：MANUAL / SCHEDULED / KNOWLEDGE_REMEDIATION';
COMMENT ON COLUMN ci_task.schedule_id IS '触发该任务的定时配置 ID（trigger_source=SCHEDULED 时非空），FK → ci_schedule_task.id';
CREATE INDEX IF NOT EXISTS idx_task_trigger_source ON ci_task (trigger_source);
CREATE INDEX IF NOT EXISTS idx_task_schedule ON ci_task (schedule_id);

-- 4.2 任务队列：priority 字段 + 部分索引(只为 PENDING 行建立)
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS priority INT DEFAULT 50 NOT NULL;
COMMENT ON COLUMN ci_task.priority IS '队列优先级：0-100，越大越优先；SCHEDULED 默认 60，MANUAL 默认 50';
CREATE INDEX IF NOT EXISTS idx_task_queue ON ci_task (priority DESC, created_at ASC) WHERE status = 'PENDING';

-- 4.3 集群任务认领（PENDING 预留 + 断点恢复亲和）
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS claimed_by VARCHAR(128);
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMP;
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP;
COMMENT ON COLUMN ci_task.claimed_by IS '集群模式下认领/执行该任务的节点实例 ID';
COMMENT ON COLUMN ci_task.claimed_at IS '任务认领时间';
COMMENT ON COLUMN ci_task.lease_until IS '认领租约到期时间；过期后其他节点可重新认领 PENDING 预留';
CREATE INDEX IF NOT EXISTS idx_task_claimed ON ci_task (claimed_by) WHERE claimed_by IS NOT NULL;

ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS source_commit VARCHAR(100);
COMMENT ON COLUMN ci_task.source_commit IS '本任务扫描时的源代码 Commit ID（pullAndScan 写入；createVersion 与增量 diff 溯源依据）';

COMMENT ON TABLE ci_task IS '知识构建任务表';
COMMENT ON COLUMN ci_task.system_id IS '关联系统ID';
COMMENT ON COLUMN ci_task.repository_id IS '关联仓库ID';
COMMENT ON COLUMN ci_task.status IS '任务状态';
COMMENT ON COLUMN ci_task.type IS '任务类型：INITIAL-全量/初始化，INCREMENTAL-增量';
COMMENT ON COLUMN ci_task.progress IS '进度百分比：0-100';
COMMENT ON COLUMN ci_task.error_reason IS '失败原因';
COMMENT ON COLUMN ci_task.duration_ms IS '耗时（毫秒）';
COMMENT ON COLUMN ci_task.started_at IS '启动时间';
COMMENT ON COLUMN ci_task.ended_at IS '结束时间';

-- 4.1 清理 ci_task 已废弃/未使用列（幂等）
ALTER TABLE ci_task DROP COLUMN IF EXISTS prompt_version;
ALTER TABLE ci_task DROP COLUMN IF EXISTS modularize_prompt_version;
ALTER TABLE ci_task DROP COLUMN IF EXISTS document_prompt_version;
ALTER TABLE ci_task DROP COLUMN IF EXISTS log_uri;

-- 5. 代码文件快照表
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
COMMENT ON TABLE ci_file_snapshot IS '代码文件快照表';
COMMENT ON COLUMN ci_file_snapshot.task_id IS '任务ID';
COMMENT ON COLUMN ci_file_snapshot.file_path IS '相对路径';
COMMENT ON COLUMN ci_file_snapshot.file_type IS '文件类型';
COMMENT ON COLUMN ci_file_snapshot.line_count IS '行数';
COMMENT ON COLUMN ci_file_snapshot.file_hash IS '文件MD5哈希值';
COMMENT ON COLUMN ci_file_snapshot.content_uri IS '代码快照在存储中的地址';

-- 6. 代码切片表
CREATE TABLE IF NOT EXISTS ci_chunk (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    file_path VARCHAR(255) NOT NULL,
    class_name VARCHAR(255),
    method_name VARCHAR(100),
    chunk_type VARCHAR(50) NOT NULL,
    content_hash VARCHAR(100) NOT NULL,
    start_line INT NOT NULL,
    end_line INT NOT NULL,
    token_estimate INT DEFAULT 0 NOT NULL,
    status VARCHAR(50) DEFAULT 'PENDING' NOT NULL,
    error_reason TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_chunk_task_id ON ci_chunk (task_id);
CREATE INDEX IF NOT EXISTS idx_chunk_file_path ON ci_chunk (file_path);
COMMENT ON TABLE ci_chunk IS '代码切片表';
COMMENT ON COLUMN ci_chunk.task_id IS '任务ID';
COMMENT ON COLUMN ci_chunk.file_path IS '相对路径';
COMMENT ON COLUMN ci_chunk.class_name IS '类名';
COMMENT ON COLUMN ci_chunk.method_name IS '方法名';
COMMENT ON COLUMN ci_chunk.chunk_type IS '切片类型：FILE, CLASS, METHOD, DIFF';
COMMENT ON COLUMN ci_chunk.content_hash IS '切片内容哈希';
COMMENT ON COLUMN ci_chunk.start_line IS '起始行';
COMMENT ON COLUMN ci_chunk.end_line IS '结束行';
COMMENT ON COLUMN ci_chunk.token_estimate IS '预估 Token 数';
COMMENT ON COLUMN ci_chunk.status IS '切片分析状态：PENDING, ANALYZED, FAILED';
COMMENT ON COLUMN ci_chunk.error_reason IS '切片分析错误原因';

-- 7. AI模型调用记录表
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
    error_reason TEXT,
    duration_ms BIGINT DEFAULT 0 NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ai_task_id ON ci_ai_call_record (task_id);
CREATE INDEX IF NOT EXISTS idx_ai_chunk_id ON ci_ai_call_record (chunk_id);
COMMENT ON TABLE ci_ai_call_record IS 'AI模型调用记录表';
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
ALTER TABLE ci_ai_call_record ADD COLUMN IF NOT EXISTS call_stage VARCHAR(50);
COMMENT ON COLUMN ci_ai_call_record.call_stage IS '调用阶段标识：MODULE_HIERARCHY / GENERATING_DOC 等，用于按阶段分组统计 AI 调用';

-- 8. 草稿工作区表
CREATE TABLE IF NOT EXISTS ci_draft_workspace (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    system_id BIGINT NOT NULL,
    repository_id BIGINT NOT NULL,
    status VARCHAR(50) DEFAULT 'ACTIVE' NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uk_task_id UNIQUE (task_id)
);
COMMENT ON TABLE ci_draft_workspace IS '草稿工作区表';
COMMENT ON COLUMN ci_draft_workspace.task_id IS '任务ID';
COMMENT ON COLUMN ci_draft_workspace.system_id IS '系统ID';
COMMENT ON COLUMN ci_draft_workspace.repository_id IS '仓库ID';
COMMENT ON COLUMN ci_draft_workspace.status IS '状态：ACTIVE, COMPLETED, ARCHIVED';

-- 9. Markdown知识草稿表
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
-- 在线追加列（兼容已有数据库）
ALTER TABLE ci_knowledge_draft ADD COLUMN IF NOT EXISTS parent_id BIGINT;
ALTER TABLE ci_knowledge_draft ADD COLUMN IF NOT EXISTS sort_order INT DEFAULT 0 NOT NULL;
CREATE INDEX IF NOT EXISTS idx_draft_workspace_id ON ci_knowledge_draft (workspace_id);
CREATE INDEX IF NOT EXISTS idx_draft_status ON ci_knowledge_draft (status);
CREATE INDEX IF NOT EXISTS idx_draft_parent_id ON ci_knowledge_draft (parent_id);
COMMENT ON TABLE ci_knowledge_draft IS 'Markdown知识草稿表';
COMMENT ON COLUMN ci_knowledge_draft.workspace_id IS '关联草稿工作区ID';
COMMENT ON COLUMN ci_knowledge_draft.parent_id IS '父级草稿ID（自引用，用于构建模块目录树）';
COMMENT ON COLUMN ci_knowledge_draft.file_path IS '模块/文件 Markdown 路径';
COMMENT ON COLUMN ci_knowledge_draft.module_name IS '模块名称';
COMMENT ON COLUMN ci_knowledge_draft.content_uri IS '草稿内容在存储中的地址';
COMMENT ON COLUMN ci_knowledge_draft.status IS '草稿状态：DRAFT / EDITING / CONFIRMED / REJECTED / PUSHED / ARCHIVED（与 ci_task.status 解耦）';
COMMENT ON COLUMN ci_knowledge_draft.sort_order IS '同级排序权重（升序）';
COMMENT ON COLUMN ci_knowledge_draft.hash IS '草稿内容的 MD5 Hash';

-- 10. 草稿修订历史表
CREATE TABLE IF NOT EXISTS ci_draft_revision (
    id BIGSERIAL PRIMARY KEY,
    draft_id BIGINT NOT NULL,
    content_uri VARCHAR(255) NOT NULL,
    author VARCHAR(50) NOT NULL,
    remark VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_revision_draft_id ON ci_draft_revision (draft_id);
COMMENT ON TABLE ci_draft_revision IS '草稿修订历史表';
COMMENT ON COLUMN ci_draft_revision.draft_id IS '关联草稿ID';
COMMENT ON COLUMN ci_draft_revision.content_uri IS '修改后正文在存储中的地址';
COMMENT ON COLUMN ci_draft_revision.author IS '修改者';
COMMENT ON COLUMN ci_draft_revision.remark IS '修改备注';

-- 11. 草稿评审意见表
CREATE TABLE IF NOT EXISTS ci_draft_review_comment (
    id BIGSERIAL PRIMARY KEY,
    draft_id BIGINT NOT NULL,
    author VARCHAR(50) NOT NULL,
    comment TEXT NOT NULL,
    type VARCHAR(20) DEFAULT 'NORMAL' NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
-- 在线追加列（兼容已有数据库）
ALTER TABLE ci_draft_review_comment ADD COLUMN IF NOT EXISTS type VARCHAR(20) DEFAULT 'NORMAL' NOT NULL;
CREATE INDEX IF NOT EXISTS idx_comment_draft_id ON ci_draft_review_comment (draft_id);
COMMENT ON TABLE ci_draft_review_comment IS '草稿评审意见表';
COMMENT ON COLUMN ci_draft_review_comment.draft_id IS '关联草稿ID';
COMMENT ON COLUMN ci_draft_review_comment.author IS '评审人';
COMMENT ON COLUMN ci_draft_review_comment.comment IS '评审意见';
COMMENT ON COLUMN ci_draft_review_comment.type IS '意见类型：NORMAL=通用意见 / PASS=通过意见 / REJECT=驳回意见';

-- 12. 草稿代码来源引用表
CREATE TABLE IF NOT EXISTS ci_draft_source_reference (
    id BIGSERIAL PRIMARY KEY,
    draft_id BIGINT NOT NULL,
    file_path VARCHAR(255) NOT NULL,
    start_line INT NOT NULL,
    end_line INT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ref_draft_id ON ci_draft_source_reference (draft_id);
COMMENT ON TABLE ci_draft_source_reference IS '草稿代码来源引用表';
COMMENT ON COLUMN ci_draft_source_reference.draft_id IS '关联草稿ID';
COMMENT ON COLUMN ci_draft_source_reference.file_path IS '引用源文件路径';
COMMENT ON COLUMN ci_draft_source_reference.start_line IS '起始行号';
COMMENT ON COLUMN ci_draft_source_reference.end_line IS '结束行号';
ALTER TABLE ci_draft_source_reference ADD COLUMN IF NOT EXISTS class_name VARCHAR(512);
ALTER TABLE ci_draft_source_reference ADD COLUMN IF NOT EXISTS method_signature VARCHAR(512);
COMMENT ON COLUMN ci_draft_source_reference.class_name IS '入口类全限定名（可选，便于复核展示）';
COMMENT ON COLUMN ci_draft_source_reference.method_signature IS '方法签名 methodName(ParamTypes)，不含返回类型（可选）';

-- 13. 知识版本表
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
CREATE INDEX IF NOT EXISTS idx_version_system_id ON ci_knowledge_version (system_id);
CREATE INDEX IF NOT EXISTS idx_version_number ON ci_knowledge_version (version_num);
-- 同一仓库 version_num 唯一：由应用层强制。库内若已有重复 (repository_id, version_num) 则勿自动建唯一索引（会致启动失败）。
-- CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_version_repo_version_num ON ci_knowledge_version (repository_id, version_num);
ALTER TABLE ci_knowledge_version ADD COLUMN IF NOT EXISTS push_method VARCHAR(20) DEFAULT 'GIT';
COMMENT ON TABLE ci_knowledge_version IS '知识版本表';
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
COMMENT ON COLUMN ci_knowledge_version.push_method IS '推送方式: GIT=Git推送, S3=对象存储';
COMMENT ON COLUMN ci_knowledge_version.confirmed_by IS '确认人';
COMMENT ON COLUMN ci_knowledge_version.confirmed_at IS '确认时间';
COMMENT ON COLUMN ci_knowledge_version.pushed_at IS '推送时间';

-- 13.2 知识发布文档人工修订（审核通过后直写 release 目录）
CREATE TABLE IF NOT EXISTS ci_knowledge_release_edit (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    relative_path VARCHAR(500) NOT NULL,
    content_text TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    submitted_by VARCHAR(50) NOT NULL,
    approved_by VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    approved_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_release_edit_repo ON ci_knowledge_release_edit (repository_id);
CREATE INDEX IF NOT EXISTS idx_release_edit_status ON ci_knowledge_release_edit (status);
COMMENT ON TABLE ci_knowledge_release_edit IS '知识发布文档人工修订待审记录；通过后直写 NAS releases';

-- 13.5. 推送任务审计表
CREATE TABLE IF NOT EXISTS ci_push_task (
    id BIGSERIAL PRIMARY KEY,
    version_id BIGINT NOT NULL,
    push_method VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT DEFAULT 0 NOT NULL,
    max_retries INT DEFAULT 3 NOT NULL,
    target_info TEXT,
    error_message TEXT,
    enqueued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_push_task_version_id ON ci_push_task (version_id);
CREATE INDEX IF NOT EXISTS idx_push_task_status ON ci_push_task (status);
COMMENT ON TABLE ci_push_task IS '知识推送任务审计表';
COMMENT ON COLUMN ci_push_task.version_id IS '关联的知识版本ID';
COMMENT ON COLUMN ci_push_task.push_method IS '推送方式: GIT 或 S3';
COMMENT ON COLUMN ci_push_task.status IS '推送状态: PENDING, PROCESSING, SUCCESS, FAILED';
COMMENT ON COLUMN ci_push_task.retry_count IS '重试次数';
COMMENT ON COLUMN ci_push_task.max_retries IS '最大重试次数';
COMMENT ON COLUMN ci_push_task.target_info IS '推送目标摘要信息(JSON)';
COMMENT ON COLUMN ci_push_task.error_message IS '失败原因';
COMMENT ON COLUMN ci_push_task.enqueued_at IS '入队时间';
COMMENT ON COLUMN ci_push_task.started_at IS '开始执行时间';
COMMENT ON COLUMN ci_push_task.completed_at IS '完成时间';

-- 14. Token使用审计表
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
COMMENT ON TABLE ci_token_usage_audit IS 'Token使用审计表';
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

-- 15. 操作日志审计表
CREATE TABLE IF NOT EXISTS ci_operation_log (
    id BIGSERIAL PRIMARY KEY,
    system_id BIGINT,
    task_id BIGINT,
    user_id BIGINT,
    username VARCHAR(50) NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    detail VARCHAR(1000) NOT NULL,
    ip_address VARCHAR(50),
    exception_msg TEXT,
    is_success SMALLINT DEFAULT 1 NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_op_system_id ON ci_operation_log (system_id);
CREATE INDEX IF NOT EXISTS idx_op_task_id ON ci_operation_log (task_id);
CREATE INDEX IF NOT EXISTS idx_op_created_at ON ci_operation_log (created_at);
COMMENT ON TABLE ci_operation_log IS '操作日志审计表';
COMMENT ON COLUMN ci_operation_log.system_id IS '关联系统ID';
COMMENT ON COLUMN ci_operation_log.task_id IS '关联任务ID';
COMMENT ON COLUMN ci_operation_log.user_id IS '操作人ID';
COMMENT ON COLUMN ci_operation_log.username IS '操作人用户名';
COMMENT ON COLUMN ci_operation_log.action_type IS '操作类型(CREATE_SYSTEM, EDIT_REPO, RUN_TASK, SAVE_DRAFT, CONFIRM_KNOWLEDGE, PUSH_GIT, etc.)';
COMMENT ON COLUMN ci_operation_log.detail IS '操作详情描述';
COMMENT ON COLUMN ci_operation_log.ip_address IS 'IP地址';
COMMENT ON COLUMN ci_operation_log.exception_msg IS '异常日志信息';
COMMENT ON COLUMN ci_operation_log.is_success IS '操作是否成功：0-失败，1-成功';

-- 16. AI模型配置表
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
-- 16.0.1 AI 模型状态字段（兼容旧库，必须在 COMMENT 之前）
ALTER TABLE ci_model ADD COLUMN IF NOT EXISTS status SMALLINT DEFAULT 1 NOT NULL;

COMMENT ON TABLE ci_model IS 'AI模型配置表';
COMMENT ON COLUMN ci_model.name IS '模型显示名称';
COMMENT ON COLUMN ci_model.identifier IS '模型调用ID';
COMMENT ON COLUMN ci_model.provider IS '技术供应商';
COMMENT ON COLUMN ci_model.api_key IS 'API Key (密钥)';
COMMENT ON COLUMN ci_model.base_url IS 'Endpoint URL (接口地址)';
COMMENT ON COLUMN ci_model.is_default IS '是否默认模型：true/false';
COMMENT ON COLUMN ci_model.capabilities IS '支持能力，逗号分隔 (text,image,video)';
COMMENT ON COLUMN ci_model.description IS '功能描述';
COMMENT ON COLUMN ci_model.sort_order IS '排序权重';
COMMENT ON COLUMN ci_model.status IS '启用状态：0-停用，1-启用';

-- 16.1 AI 模型状态字段（兼容旧库）
ALTER TABLE ci_model ADD COLUMN IF NOT EXISTS status SMALLINT DEFAULT 1 NOT NULL;

-- 17. AI模型预设模板表
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
COMMENT ON TABLE ci_model_preset IS 'AI模型预设模板表';
COMMENT ON COLUMN ci_model_preset.name IS '预设显示名称';
COMMENT ON COLUMN ci_model_preset.identifier IS '模型调用ID';
COMMENT ON COLUMN ci_model_preset.provider IS '技术供应商';
COMMENT ON COLUMN ci_model_preset.base_url IS 'Endpoint URL (接口地址)';
COMMENT ON COLUMN ci_model_preset.capabilities IS '支持能力，逗号分隔 (text,image,video)';
COMMENT ON COLUMN ci_model_preset.description IS '模板说明';
COMMENT ON COLUMN ci_model_preset.sort_order IS '排序权重';
COMMENT ON COLUMN ci_model_preset.status IS '启用状态：0-停用，1-启用';

INSERT INTO ci_model_preset (name, provider, identifier, base_url, capabilities, description, sort_order, status)
VALUES
    ('Gemini 2.0 Pro', 'Google', 'gemini-2.0-pro-exp-02-05', 'https://generativelanguage.googleapis.com', 'text,image,video', 'Google 顶尖多模态模型，支持原生视频理解。', 10, 1),
    ('Qwen-VL-Max', 'Alibaba', 'qwen-vl-max', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 'text,image,video', '通义千问视觉大模型，视频理解能力强。', 20, 1),
    ('DeepSeek Chat', 'DeepSeek', 'deepseek-chat', 'https://api.deepseek.com', 'text', '深度求索高性能模型，代码分析极具性价比。', 30, 1),
    ('GPT-4o', 'OpenAI', 'gpt-4o', 'https://api.openai.com/v1', 'text,image,video', 'OpenAI 旗舰全能模型，推理能力卓越。', 40, 1),
    ('MiniMax-M2.7', 'MiniMax', 'MiniMax-M2.7', 'https://api.minimaxi.chat/v1', 'text,image', '国产多模态模型，支持图片理解与代码环境分析。', 50, 1),
    ('MiniMax-M3', 'MiniMax', 'MiniMax-M3', 'https://api.minimaxi.chat/v1', 'text,image,video', 'MiniMax 旗舰模型，适合长上下文代码洞察。', 60, 1)
ON CONFLICT (identifier) DO UPDATE SET
    name = EXCLUDED.name,
    provider = EXCLUDED.provider,
    base_url = EXCLUDED.base_url,
    capabilities = EXCLUDED.capabilities,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    status = EXCLUDED.status,
    updated_at = CURRENT_TIMESTAMP;

-- 18. 迁移或兼容性字段维护
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS model_name VARCHAR(100);

-- 18. 方法调用链路表（AST 静态分析结果）
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
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_method_call_task_id ON ci_method_call (task_id);
CREATE INDEX IF NOT EXISTS idx_method_call_class ON ci_method_call (task_id, class_name, caller_method);
COMMENT ON TABLE ci_method_call IS '方法调用链路表（AST 静态分析）';
COMMENT ON COLUMN ci_method_call.task_id IS '关联任务ID';
COMMENT ON COLUMN ci_method_call.file_path IS '源文件相对路径';
COMMENT ON COLUMN ci_method_call.class_name IS 'Java 类名';
COMMENT ON COLUMN ci_method_call.caller_method IS '调用方方法名';
COMMENT ON COLUMN ci_method_call.dependency_name IS '被调依赖的类型名';
COMMENT ON COLUMN ci_method_call.target_method IS '被调用的目标方法名';
COMMENT ON COLUMN ci_method_call.expression IS '调用表达式原始文本';
COMMENT ON COLUMN ci_method_call.line_number IS '源文件行号';

-- 18.1 ci_method_call 增加方法完整签名列（按方法粒度反查调用链用）
ALTER TABLE ci_method_call ADD COLUMN IF NOT EXISTS caller_signature VARCHAR(500);
ALTER TABLE ci_method_call ADD COLUMN IF NOT EXISTS target_signature VARCHAR(500);
CREATE INDEX IF NOT EXISTS idx_method_call_caller_sig ON ci_method_call (task_id, caller_signature);
CREATE INDEX IF NOT EXISTS idx_method_call_target_sig ON ci_method_call (task_id, target_signature);
COMMENT ON COLUMN ci_method_call.caller_signature IS '调用方方法完整签名：className#methodName(ParamType1,ParamType2)';
COMMENT ON COLUMN ci_method_call.target_signature IS '被调方方法完整签名：className#methodName(ParamType1,ParamType2)';

-- 18.2 Phase 3：声明类型的所有项目内具体候选子类 FQ（多态候选，逗号分隔）
--   支撑 #9 反向 BFS 在多态调用下也能找到真实被改的入口
ALTER TABLE ci_method_call ADD COLUMN IF NOT EXISTS dependency_candidates TEXT;
COMMENT ON COLUMN ci_method_call.dependency_candidates IS '声明类型的所有项目内具体候选子类 FQ（多态候选，逗号分隔）';

-- 19. 模块层级表（AI 提炼入口的业务归属，DTO 持久化）
CREATE TABLE IF NOT EXISTS ci_module_hierarchy (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    system_id BIGINT NOT NULL,
    level VARCHAR(20) NOT NULL,
    parent_id BIGINT,
    node_id VARCHAR(10),
    name VARCHAR(255) NOT NULL,
    keywords TEXT,
    class_paths TEXT,
    method_signatures TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_module_hierarchy_task ON ci_module_hierarchy (task_id);
CREATE INDEX IF NOT EXISTS idx_module_hierarchy_parent ON ci_module_hierarchy (parent_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_module_hierarchy_task_node ON ci_module_hierarchy (task_id, node_id);
COMMENT ON TABLE ci_module_hierarchy IS '模块层级表（AI 提炼的业务归属 DTO 落表）';
COMMENT ON COLUMN ci_module_hierarchy.level IS '层级：MODULE / SUB_MODULE / FUNCTION';
COMMENT ON COLUMN ci_module_hierarchy.parent_id IS '上级节点 ID（module.parent_id = NULL）';
COMMENT ON COLUMN ci_module_hierarchy.node_id IS '5位 Base62 ID（m/s/f 前缀），同任务内唯一';
COMMENT ON COLUMN ci_module_hierarchy.name IS '模块/子模块/功能名称';
COMMENT ON COLUMN ci_module_hierarchy.keywords IS '关键词 JSON 数组字符串';
COMMENT ON COLUMN ci_module_hierarchy.class_paths IS '入口类全限定名集合（仅 FUNCTION 级）JSON 数组';
ALTER TABLE ci_module_hierarchy ADD COLUMN IF NOT EXISTS method_signatures TEXT;
COMMENT ON COLUMN ci_module_hierarchy.method_signatures IS '该功能涉及的方法签名 JSON 数组（仅 FUNCTION 级），如 ["listUsers(Integer,Integer)","createUser(UserDTO)"]；用于阶段 2 按方法签名粒度反查调用链';

-- 26. ci_module_hierarchy 增加人工逐项确认标记（仅用于复核 UI 跟踪，不影响 AI 流程）
ALTER TABLE ci_module_hierarchy ADD COLUMN IF NOT EXISTS confirmed BOOLEAN DEFAULT FALSE NOT NULL;
COMMENT ON COLUMN ci_module_hierarchy.confirmed IS '人工逐项复核确认标记：TRUE-该节点（模块/子模块/功能）已被用户勾选为已确认；FALSE-未确认（默认）。仅作为审计痕迹，不影响 AI 提炼模块层级的下游逻辑';

-- 20. 任务级入口扫描配置（每任务独立，配置只跟任务绑定）
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS entry_scan_config TEXT;
COMMENT ON COLUMN ci_task.entry_scan_config IS '任务级入口扫描快照 JSON：创建时全量复制仓库配置（含 excludeTargets）后允许覆写；识别/复核/AI 阶段只读此字段，不再 merge 仓库；null 时运行时回退平台默认预置';

-- 21. 是否启用模块层级调试（人工复核断点）
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS require_hierarchy_review BOOLEAN DEFAULT TRUE NOT NULL;
COMMENT ON COLUMN ci_task.require_hierarchy_review IS '是否启用模块层级调试断点：TRUE-模块层级提炼完成后停在 MODULE_HIERARCHY_REVIEW 等待人工调试；FALSE-跳过断点直接进入 GENERATING_DOC。默认 TRUE';

-- 23. 是否启用知识入口复核断点（介于 SPLITTING_TASK 与 AI_ANALYZING 之间的人工断点）
ALTER TABLE ci_task ADD COLUMN IF NOT EXISTS require_entrypoint_review BOOLEAN DEFAULT TRUE NOT NULL;
COMMENT ON COLUMN ci_task.require_entrypoint_review IS '是否启用知识入口复核断点：TRUE-SPLITTING_TASK 完成后停在 ENTRYPOINT_REVIEW 等待人工确认；FALSE-跳过断点直接进入 AI_ANALYZING。默认 TRUE';

-- 24. 知识入口复核表（流水线 SPLITTING_TASK→AI_ANALYZING 之间落表，等待人工确认或驳回）
--    方法清单以 JSON 列存储（methods_json），仅供只读展示
CREATE TABLE IF NOT EXISTS ci_entrypoint (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    system_id BIGINT NOT NULL,
    class_name VARCHAR(500) NOT NULL,
    file_path VARCHAR(500),
    entry_type VARCHAR(50),
    annotation VARCHAR(255),
    remark VARCHAR(500),
    methods_json TEXT,
    sort_order INT DEFAULT 0 NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uk_entrypoint_task_class UNIQUE (task_id, class_name)
);
CREATE INDEX IF NOT EXISTS idx_entrypoint_task ON ci_entrypoint (task_id);
COMMENT ON TABLE ci_entrypoint IS '知识入口复核表（流水线 SPLITTING_TASK→AI_ANALYZING 之间落表，等待人工确认或驳回）；方法清单存 methods_json 列，仅供只读展示';
COMMENT ON COLUMN ci_entrypoint.class_name IS '入口类全限定名（如 com.demo.controller.UserController）';
COMMENT ON COLUMN ci_entrypoint.entry_type IS '入口类型：CONTROLLER / SCHEDULED_JOB / MQ_LISTENER / COMPONENT / APPLICATION / MAIN / CUSTOM';
COMMENT ON COLUMN ci_entrypoint.annotation IS '触发该类被识别为入口的注解简称（如 RestController、Scheduled）';
COMMENT ON COLUMN ci_entrypoint.remark IS '附加信息（如 RequestMapping 一级路径 / 队列名等）';
COMMENT ON COLUMN ci_entrypoint.methods_json IS '入口类下的关键方法列表 JSON 数组：[{methodName, methodSignature, annotation, httpPath, httpMethod}]；只读展示用，不参与 AI 调度';
COMMENT ON COLUMN ci_entrypoint.sort_order IS '同任务内入口排序权重（升序）';

-- 25. ci_schedule_task 加同步字段（定时任务触发时复制到 ci_task）
ALTER TABLE ci_schedule_task ADD COLUMN IF NOT EXISTS require_entrypoint_review SMALLINT DEFAULT 1 NOT NULL;
COMMENT ON COLUMN ci_schedule_task.require_entrypoint_review IS '是否启用知识入口复核断点；触发任务时复制到 ci_task';

-- === 草稿状态词汇迁移（兼容历史数据） ===
-- 历史草稿曾使用与 ci_task 共享的字面值（AI_GENERATED / PENDING_REVIEW / REVIEWING / REVISED），
-- 自 v0.2 起统一收敛到 DraftStatus 枚举（DRAFT / EDITING / CONFIRMED / REJECTED / PUSHED / ARCHIVED）。
-- 以下 UPDATE 在每次启动时幂等执行：第二次起所有命中行已为新值，0 行受影响。
UPDATE ci_knowledge_draft
   SET status = 'DRAFT'
 WHERE status IN ('AI_GENERATED', 'PENDING_REVIEW');

UPDATE ci_knowledge_draft
   SET status = 'EDITING'
 WHERE status IN ('REVIEWING', 'REVISED');

-- CONFIRMED / PUSHED / ARCHIVED 字面值不变。

-- === v0.3 移除 REJECTED 状态 ===
-- 复核流程不再使用驳回机制，复核人通过直接编辑修改草稿。
-- 历史 REJECTED 草稿视同 DRAFT（待复核），下次启动后端时由本 UPDATE 幂等迁移。
UPDATE ci_knowledge_draft
   SET status = 'DRAFT'
 WHERE status = 'REJECTED';


-- =====================================================================
-- 9. 定时任务调度（schedule）模块
-- =====================================================================

-- 9.1 定时任务配置表：定义一段 cron 表达式 + 任务参数，每次触发都会创建一个 ci_task 记录
CREATE TABLE IF NOT EXISTS ci_schedule_task (
    id                       BIGSERIAL PRIMARY KEY,
    system_id                BIGINT       NOT NULL,
    repository_id            BIGINT       NOT NULL,
    name                     VARCHAR(100) NOT NULL,
    description              VARCHAR(500),

    -- 调度配置
    cron_expression          VARCHAR(100) NOT NULL,
    timezone                 VARCHAR(50)  DEFAULT 'Asia/Shanghai' NOT NULL,
    enabled                  SMALLINT     DEFAULT 1 NOT NULL,
    fire_strategy            VARCHAR(20)  NOT NULL DEFAULT 'INCREMENTAL',
    overlap_strategy         VARCHAR(20)  NOT NULL DEFAULT 'SKIP',

    -- 任务参数（与 ci_task 对齐，触发时写入新建的 ci_task）
    modularize_prompt_id     BIGINT,
    document_prompt_id       BIGINT,
    model_name               VARCHAR(100),
    entry_scan_config        TEXT,
    require_hierarchy_review SMALLINT     DEFAULT 1 NOT NULL,

    -- 运行统计
    last_fired_at            TIMESTAMP,
    last_task_id             BIGINT,
    last_status              VARCHAR(20),
    next_fire_at             TIMESTAMP,
    total_fired              INT          DEFAULT 0 NOT NULL,
    total_success            INT          DEFAULT 0 NOT NULL,
    total_failed             INT          DEFAULT 0 NOT NULL,
    total_skipped            INT          DEFAULT 0 NOT NULL,

    created_by               BIGINT,
    created_at               TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at               TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at               TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_schedule_enabled_next
    ON ci_schedule_task (enabled, next_fire_at)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_schedule_system_id ON ci_schedule_task (system_id);
CREATE INDEX IF NOT EXISTS idx_schedule_repository_id ON ci_schedule_task (repository_id);

COMMENT ON TABLE ci_schedule_task IS '定时任务配置表';
COMMENT ON COLUMN ci_schedule_task.system_id IS '关联系统ID';
COMMENT ON COLUMN ci_schedule_task.repository_id IS '关联仓库ID';
COMMENT ON COLUMN ci_schedule_task.name IS '配置名';
COMMENT ON COLUMN ci_schedule_task.cron_expression IS '标准 cron 表达式（7 位含秒，Spring CronExpression 格式）';
COMMENT ON COLUMN ci_schedule_task.timezone IS '时区，默认为 Asia/Shanghai';
COMMENT ON COLUMN ci_schedule_task.enabled IS '是否启用：0-禁用，1-启用';
COMMENT ON COLUMN ci_schedule_task.fire_strategy IS '触发策略：INCREMENTAL-增量扫描，INITIAL-全量扫描';
COMMENT ON COLUMN ci_schedule_task.overlap_strategy IS '冲突策略：SKIP-上一次未结束则跳过本次，QUEUE-排队等待上一次结束，PARALLEL-允许并发';
COMMENT ON COLUMN ci_schedule_task.entry_scan_config IS 'JSON：入口扫描配置（EntryScanConfig）';
COMMENT ON COLUMN ci_schedule_task.require_hierarchy_review IS '是否启用模块层级人工复核断点';
COMMENT ON COLUMN ci_schedule_task.last_fired_at IS '最近一次触发时间';
COMMENT ON COLUMN ci_schedule_task.last_task_id IS '最近一次触发产生的 ci_task.id';
COMMENT ON COLUMN ci_schedule_task.last_status IS '最近一次触发状态：CREATED/RUNNING/SUCCESS/FAILED/SKIPPED/QUEUED';
COMMENT ON COLUMN ci_schedule_task.next_fire_at IS '计算出的下一次触发时间';

-- 9.2 触发记录表：每次 fire 写一行，可跳转到对应的 ci_task
CREATE TABLE IF NOT EXISTS ci_schedule_fire_record (
    id              BIGSERIAL PRIMARY KEY,
    schedule_id     BIGINT       NOT NULL,
    task_id         BIGINT,
    fire_time       TIMESTAMP    NOT NULL,
    planned_time    TIMESTAMP    NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    skip_reason     VARCHAR(200),
    error_message   TEXT,
    duration_ms     BIGINT,
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_fire_schedule_time
    ON ci_schedule_fire_record (schedule_id, fire_time DESC);

COMMENT ON TABLE ci_schedule_fire_record IS '定时任务触发记录表';
COMMENT ON COLUMN ci_schedule_fire_record.schedule_id IS '调度配置 ID（FK → ci_schedule_task.id）';
COMMENT ON COLUMN ci_schedule_fire_record.task_id IS '本次触发创建的知识构建任务 ID（FK → ci_task.id，可空，SKIPPED 时为空）';
COMMENT ON COLUMN ci_schedule_fire_record.fire_time IS '实际触发时间';
COMMENT ON COLUMN ci_schedule_fire_record.planned_time IS '计划触发时间（与 cron 计算结果对齐）';
COMMENT ON COLUMN ci_schedule_fire_record.status IS '本次触发状态：CREATED/RUNNING/SUCCESS/FAILED/SKIPPED/QUEUED';
COMMENT ON COLUMN ci_schedule_fire_record.skip_reason IS '跳过原因（SKIPPED 时填写）';
COMMENT ON COLUMN ci_schedule_fire_record.error_message IS '错误信息';

-- ============================================================
-- 10. 基础配置相关表（基础配置模块重构新增）
-- ============================================================

-- 10.1 系统配置表（key-value，运行期可在线修改）
CREATE TABLE IF NOT EXISTS ci_system_config (
    key         VARCHAR(64)  PRIMARY KEY,
    value       TEXT         NOT NULL,
    description VARCHAR(255),
    updated_by  VARCHAR(50),
    updated_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL
);
COMMENT ON TABLE ci_system_config IS '系统配置表（key-value）';
COMMENT ON COLUMN ci_system_config.key IS '配置键（业务语义名，如 token.task-limit）';
COMMENT ON COLUMN ci_system_config.value IS '配置值（文本型，由业务侧按需 parse）';
COMMENT ON COLUMN ci_system_config.description IS '配置说明';

-- 10.2 用户表（MVP 阶段先支持 admin 一个账号；后续扩展多账号）
CREATE TABLE IF NOT EXISTS ci_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50) UNIQUE NOT NULL,
    display_name  VARCHAR(100),
    role          VARCHAR(20) DEFAULT 'USER' NOT NULL,
    status        SMALLINT    DEFAULT 1   NOT NULL,
    last_login_at TIMESTAMP,
    created_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at    TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_user_role ON ci_user (role) WHERE deleted_at IS NULL;
COMMENT ON TABLE ci_user IS '用户表';
COMMENT ON COLUMN ci_user.username IS '登录账号';
COMMENT ON COLUMN ci_user.display_name IS '显示名';
COMMENT ON COLUMN ci_user.role IS '角色：ADMIN-管理员 / USER-普通用户';
COMMENT ON COLUMN ci_user.status IS '0-停用，1-启用';
COMMENT ON COLUMN ci_user.last_login_at IS '最近一次登录时间';

-- 10.3 用户额度表（按 user 维度的 Token 限额；0 表示不限）
CREATE TABLE IF NOT EXISTS ci_user_quota (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT       NOT NULL,
    daily_token_limit   INT          DEFAULT 0 NOT NULL,
    monthly_token_limit INT          DEFAULT 0 NOT NULL,
    enabled             SMALLINT     DEFAULT 1 NOT NULL,
    remark              VARCHAR(200),
    created_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UNIQUE (user_id)
);
COMMENT ON TABLE ci_user_quota IS '用户额度表';
COMMENT ON COLUMN ci_user_quota.user_id IS '用户 ID（FK → ci_user.id）';
COMMENT ON COLUMN ci_user_quota.daily_token_limit IS '单日 Token 上限（0 = 不限）';
COMMENT ON COLUMN ci_user_quota.monthly_token_limit IS '单月 Token 上限（0 = 不限）';
COMMENT ON COLUMN ci_user_quota.enabled IS '是否启用额度检查（0-否，1-是）';

-- 10.4 预置 admin 账号（与 AuthServiceImpl 硬编码账号对齐）
INSERT INTO ci_user (id, username, display_name, role, status)
VALUES (1, 'admin', '平台管理员', 'ADMIN', 1)
ON CONFLICT (id) DO NOTHING;
-- 序列对齐：避免后续显式插入 id 冲突
SELECT setval(pg_get_serial_sequence('ci_user', 'id'), GREATEST(1, (SELECT MAX(id) FROM ci_user)));

-- 10.5 预置 4 个全局限流配置（key 与 application.yml 中的字段同名以便迁移）
INSERT INTO ci_system_config (key, value, description) VALUES
    ('token.limit-enabled',       'true',     '是否启用 Token 限额检查（true/false）'),
    ('token.task-limit',          '100000',   '单任务 Token 总额上限'),
    ('token.system-monthly-limit', '1000000', '单系统月度 Token 总额上限'),
    ('ai.concurrency',            '4',        'AI 调用最大并发数（Semaphore 容量）'),
    ('task.concurrency',          '2',        '全局同时在跑任务上限（任务级并发闸门，TaskQueueDispatcher 调度）')
ON CONFLICT (key) DO NOTHING;

-- =====================================================================
-- 11. 仓库发布态（推送应用到仓库 + 按版本回滚）
-- =====================================================================

ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS last_published_task_id BIGINT;
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS last_published_version_id BIGINT;
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS published_at TIMESTAMP;
ALTER TABLE ci_repository ADD COLUMN IF NOT EXISTS published_by VARCHAR(100);
COMMENT ON COLUMN ci_repository.last_published_task_id IS '最近一次成功发布到仓库的来源任务 ID';
COMMENT ON COLUMN ci_repository.last_published_version_id IS '当前生效的已发布知识版本 ID（ci_knowledge_version.id）；知识浏览与回滚均以此指针读取 NAS releases';
COMMENT ON COLUMN ci_repository.published_at IS '最近一次成功发布到仓库的时间';
COMMENT ON COLUMN ci_repository.published_by IS '最近一次成功发布到仓库的操作人';

CREATE TABLE IF NOT EXISTS ci_repository_entrypoint (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    system_id BIGINT NOT NULL,
    class_name VARCHAR(500) NOT NULL,
    file_path VARCHAR(500),
    entry_type VARCHAR(50),
    annotation VARCHAR(255),
    remark VARCHAR(500),
    methods_json TEXT,
    sort_order INT DEFAULT 0 NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uk_repo_entrypoint_class UNIQUE (repository_id, class_name)
);
CREATE INDEX IF NOT EXISTS idx_repo_entrypoint_repo ON ci_repository_entrypoint (repository_id);
COMMENT ON TABLE ci_repository_entrypoint IS '仓库级已发布入口复核结果（推送成功时从 ci_entrypoint 覆盖写入）';

CREATE TABLE IF NOT EXISTS ci_repository_module_hierarchy (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    system_id BIGINT NOT NULL,
    level VARCHAR(20) NOT NULL,
    parent_id BIGINT,
    node_id VARCHAR(20) NOT NULL,
    name VARCHAR(200) NOT NULL,
    keywords TEXT,
    class_paths TEXT,
    method_signatures TEXT,
    confirmed BOOLEAN DEFAULT FALSE NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uk_repo_hierarchy_node UNIQUE (repository_id, node_id)
);
CREATE INDEX IF NOT EXISTS idx_repo_hierarchy_repo ON ci_repository_module_hierarchy (repository_id);
CREATE INDEX IF NOT EXISTS idx_repo_hierarchy_parent ON ci_repository_module_hierarchy (parent_id);
COMMENT ON TABLE ci_repository_module_hierarchy IS '仓库级已发布模块层级复核结果（推送成功时从 ci_module_hierarchy 覆盖写入）';

CREATE TABLE IF NOT EXISTS ci_repository_publish_snapshot (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    system_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    version_num VARCHAR(50) NOT NULL,
    entry_scan_config TEXT,
    modularize_prompt_id BIGINT,
    document_prompt_id BIGINT,
    model_name VARCHAR(100),
    entrypoints_json TEXT NOT NULL,
    module_hierarchy_json TEXT NOT NULL,
    published_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    published_by VARCHAR(100),
    CONSTRAINT uk_repo_publish_version UNIQUE (version_id)
);
CREATE INDEX IF NOT EXISTS idx_repo_publish_snapshot_repo ON ci_repository_publish_snapshot (repository_id, published_at DESC);
COMMENT ON TABLE ci_repository_publish_snapshot IS '仓库发布快照：每次推送成功写入，供按版本回滚；与 ci_knowledge_version 一一对应';

-- 9. 业务知识配置（按系统维度维护，喂给 AI 占位符 {business_knowledge.md}）
CREATE TABLE IF NOT EXISTS ci_business_knowledge (
    id          BIGSERIAL PRIMARY KEY,
    system_id   BIGINT       NOT NULL UNIQUE,
    content     TEXT         NOT NULL DEFAULT '',
    version     INT          NOT NULL DEFAULT 1,
    updated_by  VARCHAR(64),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_business_knowledge_system ON ci_business_knowledge (system_id);
COMMENT ON TABLE  ci_business_knowledge IS '业务知识配置（按系统维度，1:1 覆盖式保存）';
COMMENT ON COLUMN ci_business_knowledge.system_id  IS '所属业务系统ID（UNIQUE，1:1）';
COMMENT ON COLUMN ci_business_knowledge.content    IS 'Markdown 正文，写入到 {business_knowledge.md} 占位符';
COMMENT ON COLUMN ci_business_knowledge.version    IS '保存次数（每次保存 +1，便于审计）';
COMMENT ON COLUMN ci_business_knowledge.updated_by IS '最后修改人（来自会话用户）';

-- ============================================================
-- 30. 方法 → 功能 反向绑定表
-- ------------------------------------------------------------
-- 把 hierarchy 阶段 AI 的"每方法归属到 (module, sub_module, function)"输出
-- 由"function 持有 method_signatures 数组"反转为"方法指向 function"。
-- 设计动机：原 ci_module_hierarchy.method_signatures 在 AI 漏输出时被回填成
-- 入口类全集，BFS 出大杂烩；本表把方法级归属做成反向索引，BFS 只用于摸全
-- 调用链实现，模块归属只来自本表（每方法 1 行）。
-- ============================================================
CREATE TABLE IF NOT EXISTS ci_method_function_binding (
    id                  BIGSERIAL PRIMARY KEY,
    task_id             BIGINT       NOT NULL,
    system_id           BIGINT,
    module_node_id      VARCHAR(16)  NOT NULL,           -- ci_module_hierarchy.node_id（如 m0B1A），仅 FUNCTION 一级
    sub_module_node_id  VARCHAR(16)  NOT NULL,           -- ci_module_hierarchy.node_id（如 s2Xy9）
    function_node_id    VARCHAR(16)  NOT NULL,           -- ci_module_hierarchy.node_id（如 f3AbC）
    class_name          VARCHAR(512) NOT NULL,           -- 入口类全限定名 com.example.UserController
    method_signature    VARCHAR(512) NOT NULL,           -- methodName(ParamType1,ParamType2)，与 ci_method_call.caller_signature 风格一致
    source              VARCHAR(16)  NOT NULL,           -- AI / USER / MIGRATED，便于审计
    confidence          DECIMAL(4,3),                    -- 可选，AI 输出 0-1 置信度（暂不强制）
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mfb_task_class_method
        UNIQUE (task_id, class_name, method_signature),
    CONSTRAINT chk_mfb_source
        CHECK (source IN ('AI', 'USER', 'MIGRATED'))
);
CREATE INDEX IF NOT EXISTS idx_mfb_task_function
    ON ci_method_function_binding (task_id, function_node_id);
CREATE INDEX IF NOT EXISTS idx_mfb_task_class
    ON ci_method_function_binding (task_id, class_name);
CREATE INDEX IF NOT EXISTS idx_mfb_task_module
    ON ci_method_function_binding (task_id, module_node_id);
COMMENT ON TABLE  ci_method_function_binding IS '方法→功能 反向绑定（hierarchy 阶段 AI 输出按方法粒度落表）；每方法 1 行，由 (task_id, class_name, method_signature) 唯一定位到 (module, sub_module, function)';
COMMENT ON COLUMN ci_method_function_binding.task_id            IS '关联任务 ID（FK → ci_task.id）';
COMMENT ON COLUMN ci_method_function_binding.system_id          IS '冗余系统 ID，便于按系统维度查询';
COMMENT ON COLUMN ci_method_function_binding.module_node_id     IS '所属模块节点 ID（5 位 Base62，m 前缀；逻辑 FK → ci_module_hierarchy.node_id，本表不建物理 FK 以简化迁移）';
COMMENT ON COLUMN ci_method_function_binding.sub_module_node_id IS '所属子模块节点 ID（s 前缀）';
COMMENT ON COLUMN ci_method_function_binding.function_node_id   IS '所属功能节点 ID（f 前缀）';
COMMENT ON COLUMN ci_method_function_binding.class_name         IS '入口类全限定名';
COMMENT ON COLUMN ci_method_function_binding.method_signature   IS '方法签名 methodName(ParamType1,ParamType2)（不含返回类型）；与 ci_method_call.caller_signature 拆分后的函数名部分一致，便于按方法级反查调用链';
COMMENT ON COLUMN ci_method_function_binding.source             IS '归属来源：AI-hierarchy 阶段 AI 输出；USER-人工在 MODULE_HIERARCHY_REVIEW 调整；MIGRATED-从旧 ci_module_hierarchy.method_signatures 一次性迁移';
COMMENT ON COLUMN ci_method_function_binding.confidence        IS 'AI 输出的归属置信度（0-1，可空，向后兼容）';

