"""Bundle THS concept memberships, ranks, daily K and metric history for Android."""
from pathlib import Path
import csv
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import concept_rotation_service as rotation
import industry_strength_service as strength
import stock_concept_service as concepts
from sync_board_kline import number

ASSETS = ROOT / "mobile-hammer/app/src/main/assets"
FIELDS = ("date", "relative_5d", "relative_20d", "outperform_5d_pct", "above_ma20_pct",
          "covered_5d", "covered_20d", "ma20_covered", "eligible_count",
          "benchmark_covered_5d", "benchmark_covered_20d", "benchmark_expected")

def write(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, allow_nan=False, separators=(",", ":")), encoding="utf-8")

def export():
    cache, boards, mapping, dates, paths, names, _ = rotation._data()
    trade_date, ranked = strength._ranked_rows(cache, boards)
    write(ASSETS / "concept_catalog.json", {"boards": boards, "stocks": {code: item["industries"] for code, item in mapping.items()}, "member_dates": dates})
    write(ASSETS / "concept_rankings.json", {"trade_date": trade_date, "concepts": ranked, "source": "网页同口径 · 内置快照"})
    references = {}
    for name, code in boards.items():
        rows = [{key: row[key] for key in FIELDS} for row in strength._history(cache, name)[-240:]]
        write(ASSETS / "concept_histories" / f"{code}.json", {"name": name, "source": "网页同口径 · 概念 · 不复权日K · 内置快照", "latest_date": trade_date, "member_date": dates.get(name), "data": rows})
        references[name] = rows[-30:]
        cache["histories"].pop(name, None)
        with (concepts.BOARD_DIR / f"{concepts.safe_name(name)}.csv").open(encoding="utf-8-sig", newline="") as handle:
            raw = list(csv.DictReader(handle))
        candles = []
        for row in raw:
            values = [number(row.get(key)) for key in ("开盘价", "最高价", "最低价", "收盘价", "成交量", "成交额")]
            if any(value is None for value in values):
                continue
            candles.append([row["日期"], *values])
        write(ASSETS / "concept_klines" / f"{code}.json", {"name": name, "source": "同花顺概念板块日K（内置快照）", "latest_date": candles[-1][0] if candles else None, "data": candles})
    write(ASSETS / "concept_kline_index.json", {"boards": boards, "latest_date": trade_date})
    calendar = cache["dates"][-30:]
    stocks = []
    for code in sorted(names):
        if not strength._eligible(code, names[code]):
            continue
        bars = []
        if code in paths:
            with paths[code].open(encoding="utf-8-sig", newline="") as handle:
                raw = list(csv.DictReader(handle))
            for row in raw:
                if row.get("date") in calendar:
                    bars.append([row["date"], *[number(row.get(key)) for key in ("open", "high", "low", "close")]])
        stocks.append({"code": code, "name": names[code], "industry": "", "bars": bars})
    write(ROOT / "mobile-hammer/app/src/test/resources/concept_reference.json", {"dates": calendar, "stocks": stocks, "histories": references, "ranked": ranked})
    print(f"Bundled {len(boards)} concepts and {len(mapping)} stocks through {trade_date}")

if __name__ == "__main__":
    export()
