#!/usr/bin/env python3
"""Generate schema-fresh.sql by folding ALTER ADD/TYPE into CREATE TABLE.

Reads backend/src/main/resources/db/schema.sql and emits a greenfield-only script:
  CREATE TABLE (final columns) + INDEX + COMMENT + seed DML.
Skips historical UPDATE / DROP COLUMN migrations / bulk TEXT remediation.
"""
from __future__ import annotations

import re
from collections import OrderedDict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src/main/resources/db/schema.sql"
OUT = ROOT / "src/main/resources/db/schema-fresh.sql"

SECTION_RE = re.compile(
    r"^-- =+\n-- \d+\. (\w+) —[^\n]*\n(?:--[^\n]*\n)*-- =+\n",
    re.M,
)
CREATE_RE = re.compile(
    r"CREATE TABLE IF NOT EXISTS (\w+)\s*\((.*?)\n\);",
    re.S,
)
ADD_COL_RE = re.compile(
    r"ALTER TABLE (\w+) ADD COLUMN IF NOT EXISTS (\w+)\s+(.+?);",
    re.S,
)
DROP_COL_RE = re.compile(
    r"ALTER TABLE (\w+) DROP COLUMN IF EXISTS (\w+)\s*;",
)
ALTER_TYPE_RE = re.compile(
    r"ALTER TABLE (\w+) ALTER COLUMN (\w+) TYPE\s+(\S+(?:\([^)]*\))?)"
    r"(?:\s+USING\s+[^;]+)?;",
    re.I,
)
SET_DEFAULT_RE = re.compile(
    r"ALTER TABLE (\w+) ALTER COLUMN (\w+) SET DEFAULT\s+([^;]+);",
    re.I,
)
SET_NOT_NULL_RE = re.compile(
    r"ALTER TABLE (\w+) ALTER COLUMN (\w+) SET NOT NULL\s*;",
    re.I,
)
DROP_NOT_NULL_RE = re.compile(
    r"ALTER TABLE (\w+) ALTER COLUMN (\w+) DROP NOT NULL\s*;",
    re.I,
)
DROP_DEFAULT_RE = re.compile(
    r"ALTER TABLE (\w+) ALTER COLUMN (\w+) DROP DEFAULT\s*;",
    re.I,
)

# Cut off trailing migration + design log
CUTOFF_MARKERS = (
    "-- Schema TEXT 字段整改迁移",
    "-- =====================================================================\n-- 设计变更日志",
)


def strip_cutoff(text: str) -> str:
    cut = len(text)
    for m in CUTOFF_MARKERS:
        i = text.find(m)
        if i != -1:
            cut = min(cut, i)
    return text[:cut].rstrip() + "\n"


def split_create_body(body: str) -> tuple[OrderedDict[str, str], list[str]]:
    """Parse CREATE body into columns + table-level constraints."""
    cols: OrderedDict[str, str] = OrderedDict()
    constraints: list[str] = []
    # Split on commas at depth 0
    parts: list[str] = []
    buf: list[str] = []
    depth = 0
    for ch in body:
        if ch == "(":
            depth += 1
            buf.append(ch)
        elif ch == ")":
            depth -= 1
            buf.append(ch)
        elif ch == "," and depth == 0:
            parts.append("".join(buf).strip())
            buf = []
        else:
            buf.append(ch)
    if buf:
        parts.append("".join(buf).strip())

    for part in parts:
        if not part:
            continue
        # table constraint
        if re.match(r"(?i)^(CONSTRAINT|PRIMARY KEY|UNIQUE|CHECK|FOREIGN KEY)\b", part):
            constraints.append(re.sub(r"\s+", " ", part).strip())
            continue
        m = re.match(r'^"?(\w+)"?\s+(.+)$', part, re.S)
        if not m:
            continue
        name, rest = m.group(1), re.sub(r"\s+", " ", m.group(2)).strip()
        cols[name] = rest
    return cols, constraints


# Fresh 库目标态不再包含这些兼容/弃用列（schema.sql 仍保留 created_at/updated_at）
DEPRECATED_FRESH_COLUMNS = frozenset({"created_at", "updated_at", "deleted_at"})


def apply_add(cols: OrderedDict[str, str], col: str, defn: str) -> None:
    defn = re.sub(r"\s+", " ", defn).strip()
    if col not in cols:
        cols[col] = defn
        return
    # Keep existing if more complete; prefer newer ADD when CREATE already has it
    # (CREATE is usually authoritative when both present)
    # If CREATE missing NOT NULL/DEFAULT that ADD has, merge carefully:
    # Prefer CREATE when column already exists in CREATE — only fill if ADD brings
    # tighter constraints we already folded later via ALTER COLUMN.
    pass


def apply_type(cols: OrderedDict[str, str], col: str, new_type: str) -> None:
    if col not in cols:
        return
    rest = cols[col]
    # Replace leading type token(s) e.g. TEXT / VARCHAR(n) / JSONB
    rest2 = re.sub(
        r"^(?:CHARACTER VARYING|DOUBLE PRECISION|TIMESTAMP WITHOUT TIME ZONE|"
        r"TIMESTAMP WITH TIME ZONE|[A-Z]+)(?:\([^)]*\))?",
        new_type,
        rest,
        count=1,
        flags=re.I,
    )
    cols[col] = rest2


def apply_set_default(cols: OrderedDict[str, str], col: str, default: str) -> None:
    if col not in cols:
        return
    rest = cols[col]
    rest = re.sub(r"(?i)\s+DEFAULT\s+\S+(?:\([^)]*\))?", "", rest)
    # Also remove DEFAULT CURRENT_TIMESTAMP style
    rest = re.sub(r"(?i)\s+DEFAULT\s+[^N]+(?=\s+NOT\s+NULL|\s*$)", "", rest)
    cols[col] = f"{rest.strip()} DEFAULT {default.strip()}"


def apply_not_null(cols: OrderedDict[str, str], col: str, not_null: bool) -> None:
    if col not in cols:
        return
    rest = re.sub(r"(?i)\s+NOT\s+NULL", "", cols[col]).strip()
    cols[col] = f"{rest} NOT NULL" if not_null else rest


def find_header_for_table(text: str, table: str) -> str:
    # Match section header containing the table name after number
    m = re.search(
        rf"(-- =+\n-- \d+\. {re.escape(table)} —.*?\n(?:--[^\n]*\n)*-- =+\n)",
        text,
        re.S,
    )
    if m:
        return m.group(1).rstrip("\n")
    return f"-- ============================================================\n-- {table}\n-- ============================================================"


INDEX_STMT_RE = re.compile(
    r"CREATE (?:UNIQUE )?INDEX IF NOT EXISTS[^\n]+(?:\n\s+[^\n]+)*;",
    re.I,
)
INDEX_ON_TABLE_RE = re.compile(r"\bON\s+(\w+)\s*\(", re.I)


def normalize_index_stmt(stmt: str) -> str:
    return re.sub(r"[ \t]+\n", "\n", stmt.strip())


def index_target_table(idx_sql: str) -> str | None:
    m = INDEX_ON_TABLE_RE.search(idx_sql)
    return m.group(1) if m else None


def extract_post_create(section_after_create: str, table: str) -> tuple[list[str], list[str], list[str]]:
    """Return (indexes, comments, dmls) for this table; skip ALTERs/UPDATEs."""
    indexes: list[str] = []
    comments: list[str] = []
    dmls: list[str] = []

    # Indexes：仅保留 ON 本表的（文末 partial unique 迁移块会在全局二次归并）
    for m in INDEX_STMT_RE.finditer(section_after_create):
        idx = normalize_index_stmt(m.group(0))
        if index_target_table(idx) == table:
            indexes.append(idx)

    # Comments for this table（表名后必须接空白 + IS，避免前缀误伤如 ci_system/ci_system_config）
    seen_c: set[str] = set()
    for m in re.finditer(
        rf"COMMENT ON (?:TABLE|COLUMN)\s+{re.escape(table)}(?:\.\w+)?\s+IS\s+'[^']*(?:''[^']*)*'\s*;",
        section_after_create,
    ):
        c = re.sub(r"[ \t]+", " ", m.group(0).strip())
        c = re.sub(r"(COMMENT ON TABLE) +", r"\1 ", c)
        key = c
        if key in seen_c:
            continue
        seen_c.add(key)
        comments.append(c)

    # Seed inserts / setval related to this table
    # Capture multi-line INSERT ... ; and SELECT setval...
    for m in re.finditer(
        rf"INSERT INTO {re.escape(table)}\b.*?;",
        section_after_create,
        re.S | re.I,
    ):
        dmls.append(m.group(0).strip())
    for m in re.finditer(
        rf"SELECT setval\([^;]*{re.escape(table)}[^;]*\);",
        section_after_create,
        re.S | re.I,
    ):
        dmls.append(m.group(0).strip())

    return indexes, comments, dmls


def render_create(table: str, cols: OrderedDict[str, str], constraints: list[str]) -> str:
    lines = [f"CREATE TABLE IF NOT EXISTS {table} ("]
    items = [f"    {name} {defn}" for name, defn in cols.items()]
    items.extend(f"    {c}" for c in constraints)
    for i, item in enumerate(items):
        comma = "," if i < len(items) - 1 else ""
        lines.append(item + comma)
    lines.append(");")
    return "\n".join(lines)


def process(text: str) -> str:
    text = strip_cutoff(text)

    # Global ALTER operations apply across whole file (may appear after CREATE)
    # Build per-table final column map from each CREATE then apply all ALTERS for that table in file order

    creates = list(CREATE_RE.finditer(text))
    table_order: list[str] = []
    table_state: dict[str, dict] = {}

    for m in creates:
        table = m.group(1)
        cols, constraints = split_create_body(m.group(2))
        table_order.append(table)
        table_state[table] = {
            "cols": cols,
            "constraints": constraints,
            "create_end": m.end(),
            "header": find_header_for_table(text, table),
        }

    # Apply ALTER statements in document order
    for m in re.finditer(r"ALTER TABLE (\w+)\b[^;]*;", text, re.S):
        stmt = m.group(0)
        table = m.group(1)
        if table not in table_state:
            continue
        cols = table_state[table]["cols"]

        m_add = ADD_COL_RE.match(stmt)
        if m_add:
            apply_add(cols, m_add.group(2), m_add.group(3))
            continue
        m_drop = DROP_COL_RE.match(stmt)
        if m_drop:
            cols.pop(m_drop.group(2), None)
            continue
        m_type = ALTER_TYPE_RE.match(stmt)
        if m_type:
            apply_type(cols, m_type.group(2), m_type.group(3))
            continue
        m_def = SET_DEFAULT_RE.match(stmt)
        if m_def:
            apply_set_default(cols, m_def.group(2), m_def.group(3))
            continue
        m_nn = SET_NOT_NULL_RE.match(stmt)
        if m_nn:
            apply_not_null(cols, m_nn.group(2), True)
            continue
        m_dnn = DROP_NOT_NULL_RE.match(stmt)
        if m_dnn:
            apply_not_null(cols, m_dnn.group(2), False)
            continue
        m_dd = DROP_DEFAULT_RE.match(stmt)
        if m_dd:
            col = m_dd.group(2)
            if col in cols:
                cols[col] = re.sub(r"(?i)\s+DEFAULT\s+\S+(?:\([^)]*\))?", "", cols[col]).strip()
                cols[col] = re.sub(
                    r"(?i)\s+DEFAULT\s+CURRENT_TIMESTAMP", "", cols[col]
                ).strip()
            continue

    # 全文索引按 ON 表名归并（覆盖文末 partial unique 迁移块，避免全堆在末表）
    indexes_by_table: dict[str, list[str]] = {t: [] for t in table_order}
    for m in INDEX_STMT_RE.finditer(text):
        idx = normalize_index_stmt(m.group(0))
        target = index_target_table(idx)
        if target in indexes_by_table:
            indexes_by_table[target].append(idx)

    # Build sections: for each table, content between this create and next table header / create
    out_parts: list[str] = []
    header = """-- =====================================================================
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
"""
    out_parts.append(header)

    for i, table in enumerate(table_order):
        st = table_state[table]
        # section slice: from after create to before next CREATE TABLE or end
        start = st["create_end"]
        if i + 1 < len(table_order):
            next_create = CREATE_RE.search(text, start)
            # find next section header before next create
            end = next_create.start() if next_create else len(text)
        else:
            end = len(text)
        after = text[start:end]

        _section_indexes, comments, dmls = extract_post_create(after, table)
        indexes = indexes_by_table.get(table, [])

        # 剔除弃用列，保留审计新列
        for dep in DEPRECATED_FRESH_COLUMNS:
            st["cols"].pop(dep, None)

        # 规范化审计列定义（NOT NULL + DEFAULT）
        for col, defn in list(st["cols"].items()):
            if col == "is_deleted":
                st["cols"][col] = "SMALLINT DEFAULT 0 NOT NULL"
            elif col in ("created_by", "updated_by"):
                st["cols"][col] = "VARCHAR(100) DEFAULT 'sys' NOT NULL"
            elif col in ("created_date", "updated_date"):
                st["cols"][col] = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL"

        # Deduplicate index names (same index defined twice in legacy file)
        # Skip indexes that still reference deprecated columns
        seen_idx: set[str] = set()
        uniq_indexes: list[str] = []
        for idx in indexes:
            if re.search(r"\b(created_at|updated_at|deleted_at)\b", idx):
                continue
            name_m = re.search(r"INDEX IF NOT EXISTS (\w+)", idx)
            key = name_m.group(1) if name_m else idx
            if key in seen_idx:
                continue
            seen_idx.add(key)
            uniq_indexes.append(idx)

        # 过滤对弃用列的 COMMENT
        comments = [
            c
            for c in comments
            if not re.search(r"\.(created_at|updated_at|deleted_at)\s+IS", c)
        ]

        # DML：旧时间列名 → 新列名
        dmls = [
            re.sub(r"\bupdated_at\b", "updated_date", re.sub(r"\bcreated_at\b", "created_date", d))
            for d in dmls
        ]

        block: list[str] = []
        block.append("")
        block.append(st["header"])
        block.append(render_create(table, st["cols"], st["constraints"]))
        if uniq_indexes:
            block.append("")
            block.extend(uniq_indexes)
        if comments:
            block.append("")
            block.extend(comments)
        if dmls:
            block.append("")
            block.extend(dmls)
        out_parts.append("\n".join(block))

    footer = """

-- =====================================================================
-- 说明
-- =====================================================================
-- 1. 调度相关表（ci_schedule_task / ci_schedule_fire_record）已下线，本文件不含。
-- 2. 大正文一律 URI 外置（content_uri / result_uri / entrypoints_uri 等），无 TEXT 正文字段。
-- 3. 结构化配置字段使用 JSONB；短文案使用 VARCHAR（≤4000）。
-- 4. KEEP-DML 种子：ci_prompt（2 条 DEFAULT 元数据）、ci_model_preset（6 厂商模板）、ci_user（admin，见上文）。
-- =====================================================================
"""
    out_parts.append(footer)
    return "\n".join(out_parts).rstrip() + "\n"


def main() -> None:
    text = SRC.read_text(encoding="utf-8")
    out = process(text)
    OUT.write_text(out, encoding="utf-8")
    # sanity
    creates = len(re.findall(r"CREATE TABLE IF NOT EXISTS", out))
    alters = len(re.findall(r"ALTER TABLE", out))
    updates = len(re.findall(r"^UPDATE ", out, re.M))
    dos = len(re.findall(r"^DO \$\$", out, re.M))
    print(f"wrote {OUT}")
    print(f"CREATE={creates} ALTER={alters} UPDATE={updates} DO={dos} lines={out.count(chr(10))+1}")


if __name__ == "__main__":
    main()
