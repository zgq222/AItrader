"""Assign one industry per stock from the latest local industry constituents."""
from __future__ import annotations

import csv
import json
import os
import re
import threading
from datetime import datetime
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
MEMBER_DIR = BASE_DIR / "板块" / "成分股" / "industry"
SUPPLEMENTS_FILE = BASE_DIR / "data" / "stock_industry_supplements.json"
MAP_FILE = BASE_DIR / "data" / "stock_industry_map.json"
_lock = threading.RLock()
_signature = None
_mapping = {}


def load_industry_map():
    global _signature, _mapping
    files = sorted(MEMBER_DIR.glob("*.csv"))
    signature = tuple((str(path), path.stat().st_mtime_ns, path.stat().st_size)
                      for path in [*files, *([SUPPLEMENTS_FILE] if SUPPLEMENTS_FILE.exists() else [])])
    with _lock:
        if signature == _signature:
            return _mapping
        latest = {}
        for path in files:
            match = re.fullmatch(r"(.+)_(\d{4}-\d{2}-\d{2})", path.stem)
            if match and (match[1] not in latest or path.stem > latest[match[1]].stem):
                latest[match[1]] = path
        mapping = {}
        # Newer snapshots win if classifications change between snapshots.
        for industry, path in sorted(latest.items(), key=lambda item: (item[1].stem[-10:], item[0]), reverse=True):
            with path.open(encoding="utf-8-sig", newline="") as handle:
                for row in csv.DictReader(handle):
                    value = next((row.get(key) for key in ("code", "股票代码", "代码", "stock_code") if row.get(key)), "")
                    code = re.sub(r"^(sh|sz|bj)", "", str(value), flags=re.I).removesuffix(".0").zfill(6)
                    if re.fullmatch(r"\d{6}", code) and code not in mapping:
                        mapping[code] = {"industry": industry, "industry_date": path.stem[-10:],
                                         "industry_source": "同花顺行业成分股（开盘红/levistock）"}
        if SUPPLEMENTS_FILE.exists():
            supplements = json.loads(SUPPLEMENTS_FILE.read_text(encoding="utf-8"))
            for code, item in supplements.get("stocks", {}).items():
                if code not in mapping and item.get("industry"):
                    mapping[code] = {key: item[key] for key in ("industry", "industry_date", "industry_source")}
        _mapping, _signature = mapping, signature
        return mapping


def enrich_stocks(stocks):
    mapping = load_industry_map()
    for stock in stocks:
        stock.update(mapping.get(str(stock.get("code", "")).zfill(6), {"industry": "待分类"}))
    return stocks


def save_industry_map():
    """Persist the reusable mapping and report local catalog coverage."""
    mapping = load_industry_map()
    codes = {path.name[:6] for path in (BASE_DIR / "个股" / "原始数据").glob("*_原始数据.csv")}
    payload = {"updated_at": datetime.now().isoformat(timespec="seconds"),
               "local_stock_count": len(codes), "mapped_count": len(codes & mapping.keys()),
               "missing_codes": sorted(codes - mapping.keys()), "stocks": mapping}
    MAP_FILE.parent.mkdir(parents=True, exist_ok=True)
    temp = MAP_FILE.with_name(f".{MAP_FILE.stem}_{os.getpid()}.tmp")
    temp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(temp, MAP_FILE)
    return {key: value for key, value in payload.items() if key != "stocks"}


if __name__ == "__main__":
    print(json.dumps(save_industry_map(), ensure_ascii=False))
