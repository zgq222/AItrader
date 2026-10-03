#!/usr/bin/env python3
"""统计涨停后低开收阴、随后两日震荡向上形态的 T+4 表现。"""
from __future__ import annotations

import csv
import json
import math
import re
from collections import Counter
from datetime import date
from pathlib import Path
from statistics import mean, median


ROOT = Path(__file__).resolve().parent
DATA_DIR = ROOT / "个股" / "原始数据"
OUT_DIR = ROOT / "data" / "research"
START_DATE = "2010-01-01"


def f(value):
    try:
        x = float(value)
        return x if math.isfinite(x) else None
    except (TypeError, ValueError):
        return None


def limit_threshold(code: str, day: str) -> float:
    """用涨幅阈值识别涨停，留 0.5pct 容差处理价格四舍五入。"""
    if code.startswith(("688", "689")):
        return 19.5
    if code.startswith(("300", "301")) and day >= "2020-08-24":
        return 19.5
    if code.startswith(("4", "8", "92")) and day >= "2021-11-15":
        return 29.5
    return 9.5


def quantile(values, p):
    vals = sorted(values)
    if not vals:
        return None
    pos = (len(vals) - 1) * p
    lo, hi = math.floor(pos), math.ceil(pos)
    return vals[lo] if lo == hi else vals[lo] * (hi - pos) + vals[hi] * (pos - lo)


def summarize(events):
    n = len(events)
    if not n:
        return {"n": 0}

    def stats(key):
        vals = [e[key] for e in events]
        return {
            "mean_pct": round(mean(vals), 3),
            "median_pct": round(median(vals), 3),
            "p25_pct": round(quantile(vals, .25), 3),
            "p75_pct": round(quantile(vals, .75), 3),
            "positive_rate_pct": round(100 * sum(x > 0 for x in vals) / n, 2),
        }

    candles = Counter(e["t4_candle"] for e in events)
    return {
        "n": n,
        "t4_open_vs_t3_close": stats("t4_open_ret"),
        "t4_close_vs_t3_close": stats("t4_close_ret"),
        "t4_intraday_close_vs_open": stats("t4_intraday_ret"),
        "t4_high_vs_t3_close": stats("t4_high_ret"),
        "t4_low_vs_t3_close": stats("t4_low_ret"),
        "close_above_t_limit_price_pct": round(100 * sum(e["t4_close_above_t"] for e in events) / n, 2),
        "low_held_t_limit_price_pct": round(100 * sum(e["t4_low_above_t"] for e in events) / n, 2),
        "candle_distribution_pct": {k: round(100 * v / n, 2) for k, v in sorted(candles.items())},
    }


def scan_file(path: Path):
    match = re.match(r"(\d{6})_(.+?)_原始数据\.csv$", path.name)
    if not match:
        return []
    code, name = match.groups()
    if "ST" in name.upper() or "退" in name:
        return []
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    events = []
    for i in range(len(rows) - 4):
        t, t1, t2, t3, t4 = rows[i:i + 5]
        day = t.get("date", "")
        if day < START_DATE:
            continue
        pct = f(t.get("pct_change"))
        tc, o1, c1, c2, c3 = map(f, (t.get("close"), t1.get("open"), t1.get("close"), t2.get("close"), t3.get("close")))
        o4, h4, l4, c4 = map(f, (t4.get("open"), t4.get("high"), t4.get("low"), t4.get("close")))
        if None in (pct, tc, o1, c1, c2, c3, o4, h4, l4, c4) or min(tc, o1, c1, c2, c3, o4, h4, l4, c4) <= 0:
            continue
        if pct < limit_threshold(code, day):
            continue
        if o1 / tc - 1 > -0.03 or c1 >= o1:
            continue
        if not (c2 > tc and c3 > tc and c3 > c2):
            continue
        candle = "阳线" if c4 > o4 else "阴线" if c4 < o4 else "平盘"
        events.append({
            "code": code, "name": name, "t_date": day, "t4_date": t4["date"],
            "t_limit_close": tc, "t1_gap_pct": round((o1 / tc - 1) * 100, 4),
            "t4_open_ret": (o4 / c3 - 1) * 100,
            "t4_close_ret": (c4 / c3 - 1) * 100,
            "t4_intraday_ret": (c4 / o4 - 1) * 100,
            "t4_high_ret": (h4 / c3 - 1) * 100,
            "t4_low_ret": (l4 / c3 - 1) * 100,
            "t4_close_above_t": c4 > tc,
            "t4_low_above_t": l4 >= tc,
            "t4_candle": candle,
        })
    return events


def main():
    paths = sorted(DATA_DIR.glob("*_原始数据.csv"))
    events = []
    for idx, path in enumerate(paths, 1):
        events.extend(scan_file(path))
        if idx % 500 == 0:
            print(f"scanned={idx}/{len(paths)} events={len(events)}", flush=True)
    recent_cutoff = f"{date.today().year - 5:04d}-{date.today().month:02d}-{date.today().day:02d}"
    report = {
        "definition": {
            "universe": "A股本地原始日线；剔除当前名称含ST或退市的证券",
            "period_start": START_DATE,
            "limit_up": "主板>=9.5%；科创板及创业板注册制后>=19.5%；北交所>=29.5%",
            "t1": "开盘相对T收盘<=-3%，且收盘<开盘",
            "t2_t3": "T+2和T+3收盘均>T收盘，且T+3收盘>T+2收盘",
            "returns": "T+4开高低收相对T+3收盘；日内收益为T+4收盘相对开盘",
            "recent_cutoff": recent_cutoff,
        },
        "all_since_2010": summarize(events),
        "recent_5y": summarize([e for e in events if e["t_date"] >= recent_cutoff]),
    }
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    json_path = OUT_DIR / "limitup_lowopen_recovery_t4_summary.json"
    csv_path = OUT_DIR / "limitup_lowopen_recovery_t4_events.csv"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    if events:
        with csv_path.open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=events[0].keys())
            writer.writeheader()
            writer.writerows(events)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"events_csv={csv_path}")


if __name__ == "__main__":
    main()
