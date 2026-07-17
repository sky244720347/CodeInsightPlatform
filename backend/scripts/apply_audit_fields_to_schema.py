#!/usr/bin/env python3
"""One-shot: inject audit columns into schema.sql per schema-audit-fields-rename-plan.md."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / "src/main/resources/db/schema.sql"

TABLES_WITH_CREATED_AT = {
    "ci_system", "ci_repository", "ci_prompt", "ci_scan_window", "ci_entry_scan_trial",
    "ci_task", "ci_file_snapshot", "ci_ai_call_record", "ci_draft_workspace",
    "ci_knowledge_draft", "ci_draft_revision", "ci_draft_review_comment",
    "ci_draft_source_reference", "ci_knowledge_version", "ci_knowledge_release_edit",
    "ci_push_task", "ci_token_usage_audit", "ci_operation_log", "ci_model",
    "ci_model_preset", "ci_method_call", "ci_module_hierarchy", "ci_entrypoint",
    "ci_user", "ci_user_quota", "ci_repository_entrypoint",
    "ci_repository_module_hierarchy", "ci_business_knowledge",
    "ci_method_function_binding", "ci_incremental_scan",
}
TABLES_WITH_UPDATED_AT = {
    "ci_system", "ci_repository", "ci_prompt", "ci_scan_window", "ci_entry_scan_trial",
    "ci_task", "ci_draft_workspace", "ci_knowledge_draft", "ci_model", "ci_model_preset",
    "ci_module_hierarchy", "ci_entrypoint", "ci_system_config", "ci_user", "ci_user_quota",
    "ci_repository_entrypoint", "ci_repository_module_hierarchy", "ci_business_knowledge",
    "ci_method_function_binding", "ci_incremental_scan",
}
TABLES_DROP_DELETED_AT = {"ci_system", "ci_repository", "ci_user"}
TABLES_HAS_UPDATED_BY = {"ci_system_config", "ci_business_knowledge"}
MARKER = "-- AUDIT_FIELDS_BEGIN"


def audit_block(table: str) -> str:
    lines = [
        f"-- 审计字段统一（is_deleted / created_by / updated_by / created_date / updated_date）",
        f"{MARKER} {table}",
        f"ALTER TABLE {table} ADD COLUMN IF NOT EXISTS created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;",
        f"ALTER TABLE {table} ADD COLUMN IF NOT EXISTS updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;",
        f"ALTER TABLE {table} ADD COLUMN IF NOT EXISTS is_deleted   SMALLINT     DEFAULT 0 NOT NULL;",
        f"ALTER TABLE {table} ADD COLUMN IF NOT EXISTS created_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;",
    ]
    if table not in TABLES_HAS_UPDATED_BY:
        lines.append(
            f"ALTER TABLE {table} ADD COLUMN IF NOT EXISTS updated_by   VARCHAR(100) DEFAULT 'sys' NOT NULL;"
        )
    else:
        lines.extend(
            [
                f"ALTER TABLE {table} ALTER COLUMN updated_by TYPE VARCHAR(100);",
                f"UPDATE {table} SET updated_by = 'sys' WHERE updated_by IS NULL OR btrim(updated_by) = '';",
                f"ALTER TABLE {table} ALTER COLUMN updated_by SET DEFAULT 'sys';",
                f"ALTER TABLE {table} ALTER COLUMN updated_by SET NOT NULL;",
            ]
        )

    # backfill created_date / updated_date
    if table in TABLES_WITH_CREATED_AT:
        lines.append(
            f"UPDATE {table} SET created_date = COALESCE(created_date, created_at, CURRENT_TIMESTAMP);"
        )
    elif table == "ci_system_config":
        lines.append(
            f"UPDATE {table} SET created_date = COALESCE(created_date, updated_at, CURRENT_TIMESTAMP);"
        )
    elif table == "ci_repository_publish_snapshot":
        lines.append(
            f"UPDATE {table} SET created_date = COALESCE(created_date, published_at, CURRENT_TIMESTAMP);"
        )
    else:
        lines.append(
            f"UPDATE {table} SET created_date = COALESCE(created_date, CURRENT_TIMESTAMP);"
        )

    if table in TABLES_WITH_UPDATED_AT and table in TABLES_WITH_CREATED_AT:
        lines.append(
            f"UPDATE {table} SET updated_date = COALESCE(updated_date, updated_at, created_at, CURRENT_TIMESTAMP);"
        )
    elif table in TABLES_WITH_UPDATED_AT:
        lines.append(
            f"UPDATE {table} SET updated_date = COALESCE(updated_date, updated_at, CURRENT_TIMESTAMP);"
        )
    elif table in TABLES_WITH_CREATED_AT:
        lines.append(
            f"UPDATE {table} SET updated_date = COALESCE(updated_date, created_at, CURRENT_TIMESTAMP);"
        )
    elif table == "ci_repository_publish_snapshot":
        lines.append(
            f"UPDATE {table} SET updated_date = COALESCE(updated_date, published_at, CURRENT_TIMESTAMP);"
        )
    else:
        lines.append(
            f"UPDATE {table} SET updated_date = COALESCE(updated_date, CURRENT_TIMESTAMP);"
        )

    if table in TABLES_DROP_DELETED_AT:
        # 旧库有 deleted_at 才回填并 DROP；新库 CREATE 已无该列
        lines.append(
            f"DO $$\n"
            f"BEGIN\n"
            f"  IF EXISTS (\n"
            f"    SELECT 1 FROM information_schema.columns\n"
            f"    WHERE table_schema = current_schema()\n"
            f"      AND table_name = '{table}' AND column_name = 'deleted_at'\n"
            f"  ) THEN\n"
            f"    EXECUTE 'UPDATE {table} SET is_deleted = 1 WHERE deleted_at IS NOT NULL';\n"
            f"    EXECUTE 'ALTER TABLE {table} DROP COLUMN IF EXISTS deleted_at';\n"
            f"  END IF;\n"
            f"END $$;"
        )

    # Indexes that depend on new columns (must run after ADD)
    if table == "ci_task":
        lines.append(
            "CREATE INDEX IF NOT EXISTS idx_task_queue_by_created_date "
            "ON ci_task (priority DESC, created_date ASC) WHERE status = 'PENDING';"
        )
    elif table == "ci_token_usage_audit":
        lines.append(
            "CREATE INDEX IF NOT EXISTS idx_audit_created_date ON ci_token_usage_audit (created_date);"
        )
    elif table == "ci_operation_log":
        lines.append(
            "CREATE INDEX IF NOT EXISTS idx_op_created_date ON ci_operation_log (created_date);"
        )
    elif table == "ci_user":
        lines.append(
            "CREATE INDEX IF NOT EXISTS idx_user_role ON ci_user (role) WHERE is_deleted = 0;"
        )

    lines.extend(
        [
            f"COMMENT ON COLUMN {table}.created_date IS '创建时间';",
            f"COMMENT ON COLUMN {table}.updated_date IS '更新时间';",
            f"COMMENT ON COLUMN {table}.is_deleted   IS '逻辑删除：0=未删除 1=已删除';",
            f"COMMENT ON COLUMN {table}.created_by   IS '创建人';",
            f"COMMENT ON COLUMN {table}.updated_by   IS '最后修改人';",
            f"-- AUDIT_FIELDS_END {table}",
            "",
        ]
    )
    return "\n".join(lines)


def strip_deleted_at_from_create(text: str) -> str:
    # Remove deleted_at lines inside CREATE TABLE bodies
    text = re.sub(
        r",\n\s*deleted_at\s+TIMESTAMP\s*\n(\);)",
        r"\n\1",
        text,
    )
    text = re.sub(
        r"\n\s*deleted_at\s+TIMESTAMP,?\s*\n",
        "\n",
        text,
    )
    # Remove ADD COLUMN deleted_at
    text = re.sub(
        r"ALTER TABLE \w+ ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;\n?",
        "",
        text,
    )
    # Remove comments on deleted_at
    text = re.sub(
        r"COMMENT ON COLUMN \w+\.deleted_at IS '[^']*';\n?",
        "",
        text,
    )
    return text


def inject_audit_blocks(text: str) -> str:
    if MARKER in text:
        text = re.sub(
            rf"-- 审计字段统一.*?\n{MARKER} (\w+)\n.*?-- AUDIT_FIELDS_END \1\n\n?",
            "",
            text,
            flags=re.S,
        )

    # Insert before COMMENT ON TABLE so all prior ALTER/INDEX of legacy cols stay,
    # then audit ADD runs before seed DML that may reference new cols.
    tables = re.findall(r"CREATE TABLE IF NOT EXISTS (\w+)", text)
    for table in tables:
        if MARKER + " " + table in text:
            continue
        pattern = rf"(COMMENT ON TABLE\s+{re.escape(table)} IS )"
        m = re.search(pattern, text)
        if not m:
            print(f"WARN: no COMMENT ON TABLE for {table}")
            continue
        text = text[: m.start()] + audit_block(table) + text[m.start() :]
    return text


def patch_misc(text: str) -> str:
    # Early idx_user_role references deleted_at — drop it; recreated in audit block
    text = re.sub(
        r"CREATE INDEX IF NOT EXISTS idx_user_role ON ci_user \(role\) WHERE deleted_at IS NULL;\n?",
        "-- idx_user_role：见审计字段块（is_deleted = 0）\n",
        text,
    )

    # Seed INSERT uses new date columns (defaults fill created_at/updated_at on old DBs)
    text = text.replace(
        'INSERT INTO ci_prompt ("name", content_uri, "version", status, is_default, created_at, updated_at, prompt_type, lifecycle, category, scope_id) VALUES',
        'INSERT INTO ci_prompt ("name", content_uri, "version", status, is_default, created_date, updated_date, prompt_type, lifecycle, category, scope_id) VALUES',
    )

    text = text.replace(
        "    updated_at   = CURRENT_TIMESTAMP;",
        "    updated_date = CURRENT_TIMESTAMP;",
    )

    note = (
        "-- 审计字段：全表统一 is_deleted / created_by / updated_by / created_date / updated_date；\n"
        "--           旧列 created_at/updated_at 保留不删；deleted_at 已废弃并 DROP。\n"
        "--           详见 docs/schema-audit-fields-rename-plan.md\n"
    )
    if "schema-audit-fields-rename-plan" not in text:
        text = text.replace(
            "-- =====================================================================\n\n\n-- ============================================================",
            f"-- =====================================================================\n\n{note}\n-- ============================================================",
            1,
        )
    return text


def main() -> None:
    text = SCHEMA.read_text(encoding="utf-8")
    text = strip_deleted_at_from_create(text)
    text = inject_audit_blocks(text)
    text = patch_misc(text)
    SCHEMA.write_text(text, encoding="utf-8")
    print(f"patched {SCHEMA}")
    print(f"AUDIT_FIELDS_BEGIN count={text.count(MARKER)}")
    print(f"deleted_at remaining (should be 0 in comments/create): "
          f"{len(re.findall(r'deleted_at', text))}")


if __name__ == "__main__":
    main()
