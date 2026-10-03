"""Bundle current names so suspended / missing-quote members remain visible."""
import csv
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
names = {}
with (ROOT / "个股" / "A股股票列表.csv").open(encoding="utf-8-sig", newline="") as source:
    for row in csv.DictReader(source):
        code, name = row["code"].zfill(6), row["name"].strip()
        if re.fullmatch(r"(?:000|001|002|003|600|601|603|605)\d{3}", code) and name:
            names[code] = name
target = ROOT / "mobile-hammer/app/src/main/assets/mainboard_catalog.json"
target.write_text(json.dumps(names, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
print(f"Bundled {len(names)} main-board names")
