#!/usr/bin/env python3
"""Inject partial-unique (WHERE is_deleted=0) migration into schema.sql and fix CREATE for ci_incremental_scan."""
from __future__ import annotations

import re
from pathlib import Path

SCHEMA = Path(__file__).resolve().parents[1] / "src/main/resources/db/schema.sql"

MIGRATION = r'''
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

'''

MARKER = "-- 审计软删 × 唯一约束：改为部分唯一索引"


def fix_incremental_create(text: str) -> str:
    old = """CREATE TABLE IF NOT EXISTS ci_incremental_scan (
    task_id BIGINT PRIMARY KEY,
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
);"""
    new = """CREATE TABLE IF NOT EXISTS ci_incremental_scan (
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
);"""
    if old in text:
        text = text.replace(old, new)
    else:
        # already patched or formatting differs — try looser
        text2 = re.sub(
            r"CREATE TABLE IF NOT EXISTS ci_incremental_scan \(\s*"
            r"task_id BIGINT PRIMARY KEY,",
            "CREATE TABLE IF NOT EXISTS ci_incremental_scan (\n"
            "    id BIGSERIAL PRIMARY KEY,\n"
            "    task_id BIGINT NOT NULL,",
            text,
            count=1,
        )
        text = text2
    text = text.replace(
        "COMMENT ON COLUMN ci_incremental_scan.task_id IS '任务ID（PRIMARY KEY）';",
        "COMMENT ON COLUMN ci_incremental_scan.task_id IS '任务ID（活行唯一，见 uk_incremental_scan_task_active）';",
    )
    # strip absolute UNIQUEs from CREATE bodies（须连同前一行尾逗号一起删，避免 `,);`）
    replacements = [
        (
            ",\n    CONSTRAINT uk_task_id UNIQUE (task_id)\n);",
            "\n);",
        ),
        (
            ",\n    CONSTRAINT uk_entrypoint_task_class UNIQUE (task_id, class_name)\n);",
            "\n);",
        ),
        (
            ",\n    CONSTRAINT uk_repo_entrypoint_class UNIQUE (repository_id, class_name)\n);",
            "\n);",
        ),
        (
            ",\n    CONSTRAINT uk_repo_hierarchy_node UNIQUE (repository_id, node_id)\n);",
            "\n);",
        ),
        (
            "    system_id   BIGINT       NOT NULL UNIQUE,",
            "    system_id   BIGINT       NOT NULL,",
        ),
        (
            "    username      VARCHAR(50) UNIQUE NOT NULL,",
            "    username      VARCHAR(50) NOT NULL,",
        ),
        (
            ",\n    UNIQUE (user_id)\n);",
            "\n);",
        ),
        (
            "    CONSTRAINT uk_mfb_task_class_method\n"
            "        UNIQUE (task_id, class_name, method_signature),\n",
            "",
        ),
    ]
    for a, b in replacements:
        text = text.replace(a, b)
    # drop absolute unique index creates (recreated as _active in migration)
    text = text.replace(
        "CREATE UNIQUE INDEX IF NOT EXISTS uk_scan_window_repo ON ci_scan_window (repository_id);\n",
        "-- uk_scan_window_repo → 见文末 partial unique uk_scan_window_repo_active\n",
    )
    text = text.replace(
        "CREATE UNIQUE INDEX IF NOT EXISTS uk_module_hierarchy_task_node ON ci_module_hierarchy (task_id, node_id);\n",
        "-- uk_module_hierarchy_task_node → 见文末 partial unique uk_module_hierarchy_task_node_active\n",
    )
    return text


def main() -> None:
    text = SCHEMA.read_text(encoding="utf-8")
    text = fix_incremental_create(text)
    if MARKER not in text:
        # insert before TEXT 字段整改迁移
        anchor = "-- Schema TEXT 字段整改迁移"
        if anchor not in text:
            raise SystemExit("anchor not found")
        text = text.replace(anchor, MIGRATION + "\n" + anchor, 1)
    SCHEMA.write_text(text, encoding="utf-8")
    print(f"patched {SCHEMA}")


if __name__ == "__main__":
    main()
