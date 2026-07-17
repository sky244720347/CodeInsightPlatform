from pathlib import Path

p = Path(__file__).resolve().parents[1] / "src/main/java/com/company/codeinsight/modules/callchain/mapper/MethodCallMapper.java"
t = p.read_text(encoding="utf-8")
old = (
    '"WHERE task_id = #{baselineTaskId} " +\n'
    '            "AND file_path NOT IN " +'
)
new = (
    '"WHERE task_id = #{baselineTaskId} " +\n'
    '            "AND is_deleted = 0 " +\n'
    '            "AND file_path NOT IN " +'
)
if old not in t:
    raise SystemExit("pattern not found")
p.write_text(t.replace(old, new, 1), encoding="utf-8")
print("patched")
