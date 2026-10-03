"""Bundle recent local THS industry daily bars for the Android board chart."""

from __future__ import annotations

import csv
import json
import math
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "板块" / "行业板块"
ASSETS = Path(__file__).resolve().parent / "app" / "src" / "main" / "assets"
BOARD_ASSETS = ASSETS / "board_klines"
MAX_BARS = 240


def number(value: str) -> float | None:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return None
    return parsed if math.isfinite(parsed) else None


def export() -> tuple[int, str]:
    BOARD_ASSETS.mkdir(parents=True, exist_ok=True)
    index: dict[str, str] = {}
    latest = ""
    for path in sorted(SOURCE.glob("*.csv")):
        with path.open(encoding="utf-8-sig", newline="") as source:
            raw = list(csv.DictReader(source))[-MAX_BARS:]
        if not raw:
            continue
        code = str(raw[-1]["板块代码"]).strip()
        if not code.isdigit():
            raise ValueError(f"Invalid board code in {path}: {code!r}")
        rows = []
        for row in raw:
            values = [number(row[key]) for key in ("开盘价", "最高价", "最低价", "收盘价", "成交量", "成交额")]
            if not row["日期"] or any(value is None for value in values):
                continue
            open_, high, low, close, volume, amount = values
            if low <= 0 or high < low or volume < 0:
                continue
            rows.append([row["日期"], open_, high, low, close, volume, amount])
        if not rows:
            continue
        name = path.stem
        index[name] = code
        latest = max(latest, rows[-1][0])
        payload = {"name": name, "source": "同花顺行业板块日K（内置快照）", "latest_date": rows[-1][0], "data": rows}
        (BOARD_ASSETS / f"{code}.json").write_text(
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
        )
    if not index:
        raise RuntimeError(f"No industry board bars found in {SOURCE}")
    (ASSETS / "board_kline_index.json").write_text(
        json.dumps({"latest_date": latest, "boards": index}, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    return len(index), latest


if __name__ == "__main__":
    count, date = export()
    print(f"Bundled {count} industry daily K snapshots through {date}")
