"""Bundle the exact web strength/breadth history as a dated offline fallback."""
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import industry_strength_service as strength

ASSETS = ROOT / "mobile-hammer/app/src/main/assets"
FIELDS = ("date", "relative_5d", "relative_20d", "outperform_5d_pct", "above_ma20_pct",
          "covered_5d", "covered_20d", "ma20_covered", "eligible_count",
          "benchmark_covered_5d", "benchmark_covered_20d", "benchmark_expected")

def export():
    index = json.loads((ASSETS / "board_kline_index.json").read_text(encoding="utf-8"))["boards"]
    target = ASSETS / "sector_histories"
    target.mkdir(exist_ok=True)
    references = {}
    for name, code in index.items():
        payload = strength.get_industry_strength_history(name)
        rows = [{k: row[k] for k in FIELDS} for row in payload["data"][-240:]]
        exported = {"name": name, "source": "网页同口径 · 不复权日K · 内置快照", "latest_date": payload["latest_date"], "data": rows}
        (target / f"{code}.json").write_text(json.dumps(exported, ensure_ascii=False, allow_nan=False, separators=(",", ":")), encoding="utf-8")
        references[name] = rows[-30:]
    fixture = json.loads((ROOT / "outputs/mobile-market-rotation-fixture.json").read_text(encoding="utf-8"))
    fixture["histories"] = references
    test = ROOT / "mobile-hammer/app/src/test/resources/sector_history_reference.json"
    test.write_text(json.dumps({k: fixture[k] for k in ("dates", "stocks", "histories")}, ensure_ascii=False, allow_nan=False, separators=(",", ":")), encoding="utf-8")
    print(f"Bundled {len(index)} sector histories through {payload['latest_date']}")

if __name__ == "__main__":
    export()
