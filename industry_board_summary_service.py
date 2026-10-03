"""Read THS industry snapshots for the web workbench without fetching new data."""
from __future__ import annotations

import csv
import math
import re
import threading
from collections import Counter, defaultdict
from pathlib import Path

import stock_industry_service

BASE_DIR = Path(__file__).resolve().parent
BOARD_DIR = BASE_DIR / "板块"
STOCK_FLOW_DIR = BASE_DIR / "个股" / "资金流"
_lock = threading.Lock()
_cache = None
_signature = None


def number(value):
    if value is None:
        return None
    try:
        result = float(str(value).strip().replace(",", "").removesuffix("%"))
        return result if math.isfinite(result) else None
    except ValueError:
        return None


def industry_money(value):
    """THS industry numeric money fields are in 亿元; normalize to 元."""
    text = str(value if value is not None else "").strip().replace(",", "")
    unit = 1e8
    if text.endswith("万"):
        unit, text = 1e4, text[:-1]
    elif text.endswith("亿"):
        text = text[:-1]
    value = number(text)
    return value * unit if value is not None else None


def _latest(directory, prefix):
    return next(iter(sorted(directory.glob(f"{prefix}_*.csv"), reverse=True)), None)


def _read(path):
    if path is None:
        return []
    with path.open(encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def _date(path):
    return path.stem.rsplit("_", 1)[-1] if path else None


def get_industry_flow_history(name, board_type="industry"):
    """Saved daily snapshots only; missing or invalid money remains unknown."""
    data = []
    if board_type not in {"industry", "concept"}:raise ValueError("type 必须为 industry 或 concept")
    for path in sorted((BOARD_DIR / "资金流").glob(f"{board_type}_moneyflow_*.csv")):
        date = _date(path)
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date):
            continue
        row = next((row for row in _read(path) if (row.get("行业") or row.get("概念") or row.get("板块名称")) == name), None)
        if row is not None:
            data.append({"date": date, "net_inflow": industry_money(row.get("净额")),
                         "inflow": industry_money(row.get("流入资金")),
                         "outflow": industry_money(row.get("流出资金"))})
    valid = [row for row in data if row["net_inflow"] is not None]
    return {"name": name, "data": data, "total": len(valid), "unit": "元",
            "first_date": valid[0]["date"] if valid else None,
            "latest_date": valid[-1]["date"] if valid else None,
            "source": "同花顺"+("概念" if board_type=="concept" else "行业")+"资金流本地日期快照"}


def _last_kline_rows(path):
    # Discard the leading partial byte line before decoding: a tail chunk can
    # start inside a Chinese character in the board name.
    with path.open("rb") as file:
        fields = next(csv.reader([file.readline().decode("utf-8-sig").strip()]))
        header_end = file.tell()
        file.seek(0, 2)
        position, tail = file.tell(), b""
        while position > header_end and tail.count(b"\n") < 3:
            size = min(4096, position - header_end)
            position -= size
            file.seek(position)
            tail = file.read(size) + tail
    lines = tail.split(b"\n")
    if position > header_end:
        lines = lines[1:]
    return [dict(zip(fields, next(csv.reader([line.decode("utf-8").rstrip("\r")]))))
            for line in [line for line in lines if line.strip()][-2:]]


def get_industry_summaries():
    global _signature, _cache
    kline_paths = sorted((BOARD_DIR / "行业板块").glob("*.csv"))
    flow_path = _latest(BOARD_DIR / "资金流", "industry_moneyflow")
    stock_path = _latest(STOCK_FLOW_DIR, "stock_moneyflow")
    mapping = stock_industry_service.load_industry_map()
    paths = [*kline_paths, *([flow_path] if flow_path else []), *([stock_path] if stock_path else [])]
    signature = (tuple((str(p), p.stat().st_mtime_ns, p.stat().st_size) for p in paths),
                 tuple(sorted((code, item.get("industry"), item.get("industry_date")) for code, item in mapping.items())))
    with _lock:
        if _cache is not None and signature == _signature:
            return _cache
        flow_date, breadth_date = _date(flow_path), _date(stock_path)
        flows = {row["行业"]: row for row in _read(flow_path) if row.get("行业")}
        ranks = sorted(((name, industry_money(row.get("净额"))) for name, row in flows.items()),
                       key=lambda item: (-(item[1] if item[1] is not None else -math.inf), item[0]))
        ranks = [item for item in ranks if item[1] is not None]
        rank_by_name = {name: index + 1 for index, (name, _) in enumerate(ranks)}
        expected = Counter(item["industry"] for item in mapping.values() if item.get("industry"))
        breadth = defaultdict(Counter)
        seen = set()
        for row in _read(stock_path):
            raw_code = str(row.get("股票代码", row.get("code", ""))).strip().removesuffix(".0")
            code = re.sub(r"^(sh|sz|bj)", "", raw_code, flags=re.I).zfill(6)
            industry = mapping.get(code, {}).get("industry")
            pct = number(row.get("涨跌幅", row.get("change_pct")))
            if not industry or code in seen or pct is None:
                continue
            seen.add(code)
            breadth[industry]["covered"] += 1
            breadth[industry]["up" if pct > 0 else "down" if pct < 0 else "flat"] += 1
        klines = {}
        for path in kline_paths:
            rows = _last_kline_rows(path)
            if rows:
                klines[path.stem] = rows
        data = []
        for name in sorted(set(klines) | set(flows)):
            rows, flow = klines.get(name, []), flows.get(name, {})
            last = rows[-1] if rows else {}
            quote_date = last.get("日期", last.get("date"))
            pct = number(last.get("今日涨跌幅", last.get("pct_change")))
            close = number(last.get("收盘价", last.get("close")))
            if pct is None and len(rows) > 1:
                previous = number(rows[-2].get("收盘价", rows[-2].get("close")))
                if close is not None and previous and previous > 0:
                    pct = (close / previous - 1) * 100
            if flow and (not quote_date or flow_date >= quote_date):
                pct, close, quote_date = number(flow.get("行业-涨跌幅")), number(flow.get("行业指数")), flow_date
            counts = breadth.get(name, {})
            covered = counts.get("covered", 0)
            data.append({
                "name": name, "code": last.get("板块代码"), "quote_date": quote_date,
                "index_close": close, "pct_change": pct,
                "amount": number(last.get("成交额", last.get("amount"))),
                "volume": number(last.get("成交量", last.get("volume"))),
                "amount_date": last.get("日期", last.get("date")),
                "flow_date": flow_date if flow else None,
                "inflow": industry_money(flow.get("流入资金")),
                "outflow": industry_money(flow.get("流出资金")),
                "net_inflow": industry_money(flow.get("净额")),
                "net_rank": rank_by_name.get(name), "rank_total": len(ranks),
                "company_count": number(flow.get("公司家数")),
                "leader": flow.get("领涨股"), "leader_pct": number(flow.get("领涨股-涨跌幅")),
                "leader_price": number(flow.get("当前价")),
                "breadth_date": breadth_date if covered else None,
                "up_count": counts.get("up", 0) if covered else None,
                "down_count": counts.get("down", 0) if covered else None,
                "flat_count": counts.get("flat", 0) if covered else None,
                "breadth_covered": covered, "breadth_expected": max(expected.get(name, 0), int(number(flow.get("公司家数")) or 0)),
            })
        _cache = {"data": data, "total": len(data), "flow_date": flow_date,
                  "source": "同花顺行业资金流快照 + 板块本地日K",
                  "breadth_source": "同花顺个股资金流快照涨跌幅，按本地行业成员映射统计",
                  "note": "资金为整个行业板块的净额；资金排名按有效板块净额降序。涨跌家数为已覆盖成员统计，未覆盖成员不计入。"}
        _signature = signature
        return _cache
