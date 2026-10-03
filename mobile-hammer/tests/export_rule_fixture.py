"""Export local daily bars and independently calculated mobile selection results."""
from pathlib import Path
import csv
import json
import math
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
import five_minute_selection_service as five
import rising_structure_service
import watchlist_service

target = ROOT / "outputs/mobile-rule-fixture"
target.mkdir(parents=True, exist_ok=True)
dates = watchlist_service.recent_market_dates(30)
(target / "calendar.txt").write_text(",".join(dates), encoding="utf-8")
paths = {}
for path in sorted(watchlist_service.RAW_DIR.glob("*_原始数据.csv"), key=lambda item: (item.stat().st_mtime_ns, item.name)):
    paths[path.name[:6]] = path
mobile_codes, ranked = [], []
industry_map = json.loads((ROOT / "mobile-hammer" / "app" / "src" / "main" / "assets" / "stock_industries.json").read_text(encoding="utf-8"))["stocks"]
with (target / "bars.tsv").open("w", encoding="utf-8", newline="") as handle:
    writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
    for code, path in sorted(paths.items()):
        name = path.stem[7:].removesuffix("_原始数据")
        if not watchlist_service._is_main_board(code) or "ST" in name.upper():
            continue
        rows = [row for row in rising_structure_service._daily_rows(path, 100) if row["date"] <= dates[-1]]
        if not rows or rows[-1]["date"] != dates[-1]:
            continue
        for row in rows:
            writer.writerow([code, name, row["date"], row["open"], row["high"], row["low"], row["close"]])
        by_date = {row["date"]: row for row in rows}
        previous = {row["date"]: prior for prior, row in zip(rows, rows[1:])}
        triple = dates[-4:-1]
        if (five.daily_ma20_rising(rows) and five.recent_limit_up_date(rows, dates)
                and all(day in by_date and day in previous and
                        (by_date[day]["close"] < by_date[day]["open"] or
                         by_date[day]["close"] < previous[day]["close"]) for day in triple)):
            mobile_codes.append(code)
        lows = [(row["low"], row["date"]) for row in rows if row["date"] in dates
                and math.isfinite(row["low"]) and row["low"] > 0]
        if lows and math.isfinite(rows[-1]["close"]) and rows[-1]["close"] > 0:
            low, day = min(lows)
            ranked.append((code, (rows[-1]["close"] / low - 1) * 100, day, low))
(target / "five.txt").write_text("\n".join(mobile_codes) + "\n", encoding="utf-8")
with (target / "gain.tsv").open("w", encoding="utf-8", newline="") as handle:
    csv.writer(handle, delimiter="\t", lineterminator="\n").writerows(sorted(ranked, key=lambda row: (-row[1], row[0]))[:150])
with (target / "industries.tsv").open("w", encoding="utf-8", newline="") as handle:
    csv.writer(handle, delimiter="\t", lineterminator="\n").writerows(
        (code, industry_map.get(code, "待分类")) for code, *_ in ranked)
ordered = sorted(ranked, key=lambda row: (-row[1], row[0]))
seed = list(dict.fromkeys(industry_map.get(code, "待分类") for code, *_ in ordered[:150]))
buckets = {industry: [] for industry in seed}
for global_rank, (code, pct, _, _) in enumerate(ordered, 1):
    industry = industry_map.get(code, "待分类")
    sector = buckets.get(industry)
    if sector is not None and pct > 0 and len(sector) < 50:
        sector.append((code, pct, industry, global_rank, len(sector) + 1))
with (target / "leaders.tsv").open("w", encoding="utf-8", newline="") as handle:
    writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
    for industry in seed:
        writer.writerows(buckets[industry])
print(f"APK条件对照：最新交易日 {dates[-1]}，回调第3天 {dates[-2]}，符合 {len(mobile_codes)} 只")
print(f"涨幅前150只覆盖 {len(seed)} 个行业，各行业正涨幅前50共 {sum(map(len, buckets.values()))} 只")
print(target)
