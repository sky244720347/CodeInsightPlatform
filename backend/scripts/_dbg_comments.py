from pathlib import Path
import re

t = Path(r"C:\project\codeInsight\CodeInsightPlatform\backend\src\main\resources\db\schema-fresh.sql").read_text(encoding="utf-8")
i = t.index("COMMENT ON TABLE ci_task")
print(repr(t[i : i + 250]))
print("--- matches ---")
for m in re.finditer(r"COMMENT ON TABLE ci_task IS '[^']*';", t):
    print(repr(m.group(0)))
