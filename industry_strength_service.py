"""Local main-board non-ST industry strength and breadth, on a shared calendar."""
from __future__ import annotations

import re
import threading
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
import pandas as pd

import industry_board_summary_service as summaries
import stock_industry_service

BASE_DIR = Path(__file__).resolve().parent
RAW_DIR = BASE_DIR / "个股" / "原始数据"
CATALOG_FILE = BASE_DIR / "个股" / "A股股票列表.csv"
STOCK_FLOW_DIR = BASE_DIR / "个股" / "资金流"
BOARD_DIR = BASE_DIR / "板块" / "行业板块"
_lock = threading.Lock()
_cache = None
_signature = None


def _code(value):
    value = re.sub(r"^(sh|sz|bj)", "", str(value).strip(), flags=re.I)
    return value.removesuffix(".0").zfill(6)


def _eligible(code, name):
    return (bool(re.fullmatch(r"\d{6}", code))
            and code.startswith(("000", "001", "002", "003", "600", "601", "603", "605"))
            and bool(name) and "ST" not in name.upper())


def _sources():
    paths, names = {}, {}
    for path in sorted(RAW_DIR.glob("*_原始数据.csv")):
        match = re.fullmatch(r"(\d{6})_(.+)_原始数据", path.stem)
        if match:
            code, name = match.groups()
            if code not in paths or path.stat().st_mtime_ns > paths[code].stat().st_mtime_ns:
                paths[code], names[code] = path, name
    for row in summaries._read(CATALOG_FILE if CATALOG_FILE.exists() else None):
        code = _code(row.get("code", row.get("股票代码", "")))
        name = row.get("name", row.get("股票简称", ""))
        if name:
            names[code] = name
    flow = summaries._latest(STOCK_FLOW_DIR, "stock_moneyflow")
    for row in summaries._read(flow):
        code = _code(row.get("股票代码", row.get("code", "")))
        name = row.get("股票简称", row.get("name", ""))
        if name:
            names[code] = name
    dependencies = [*paths.values(), *BOARD_DIR.glob("*.csv")]
    if CATALOG_FILE.exists():
        dependencies.append(CATALOG_FILE)
    if flow:
        dependencies.append(flow)
    signature = tuple(sorted((str(p), p.stat().st_mtime_ns, p.stat().st_size) for p in dependencies))
    return paths, names, signature


def _file_dates(path):
    frame = pd.read_csv(path, usecols=lambda name: name in {"日期", "date"}, dtype=str, encoding="utf-8-sig")
    return set(frame.iloc[:, 0].dropna()) if len(frame.columns) else set()


def _calendar(paths):
    # Full board history, with an extra 20 sessions to initialise rolling windows.
    board_dates = set()
    for path in BOARD_DIR.glob("*.csv"):
        try:
            board_dates.update(_file_dates(path))
        except (OSError, UnicodeError, ValueError, pd.errors.ParserError):
            continue
    board_dates = {value for value in board_dates if re.fullmatch(r"\d{4}-\d{2}-\d{2}", value)}
    raw_dates = set()
    calendar_paths = [paths["000001"]] if "000001" in paths else list(paths.values())
    for path in calendar_paths:
        try:
            raw_dates.update(_file_dates(path))
        except (OSError, UnicodeError, ValueError, pd.errors.ParserError):
            continue
    raw_dates = {value for value in raw_dates if re.fullmatch(r"\d{4}-\d{2}-\d{2}", value)}
    if board_dates:
        first = min(board_dates)
        warmup = sorted(value for value in raw_dates if value < first)[-20:]
        return warmup + sorted(board_dates), first
    dates = sorted(raw_dates)
    return dates, dates[0] if dates else None


def _average(total, count):
    return np.divide(total, count, out=np.full(total.shape, np.nan), where=count > 0)


def _stock_closes(path, dates, include_limits=False):
    frame = pd.read_csv(path, usecols=lambda name: name in {"date", "close", "pct_change", "volume"}, dtype={"date": str}, encoding="utf-8-sig")
    if not {"date", "close"}.issubset(frame.columns):
        raise ValueError("日K缺少date或close列")
    frame = frame.drop_duplicates("date", keep="last").set_index("date").sort_index()
    values = pd.to_numeric(frame["close"], errors="coerce").reindex(dates).to_numpy(dtype=float)
    values[~np.isfinite(values) | (values <= 0)] = np.nan
    if not include_limits:
        return values
    close = pd.to_numeric(frame["close"], errors="coerce")
    reference = close.shift(1)
    if "pct_change" in frame:
        pct = pd.to_numeric(frame["pct_change"], errors="coerce")
        with np.errstate(divide="ignore", invalid="ignore"):
            adjusted_reference = close / (1 + pct / 100)
        adjusted_reference = adjusted_reference.round(2)
        reference = adjusted_reference.where(np.isfinite(adjusted_reference) & (adjusted_reference > 0), reference)
    covered = np.isfinite(close) & (close > 0) & np.isfinite(reference) & (reference > 0)
    if "volume" in frame:
        covered &= pd.to_numeric(frame["volume"], errors="coerce") > 0
    # Registered main-board IPOs have no price limits in their first five sessions.
    # https://investor.sse.org.cn/knowledge/qa/t20230306_599093.html
    if len(frame) and str(frame.index[0]) >= "2023-04-10":
        covered.iloc[:5] = False
    # Half-up rounding to one cent, including the minimum one-tick movement rule.
    # https://investor.szse.cn/knowledge/stock/deal/t20180801_553961.html
    upper = np.floor(reference * 110 + 0.5 + 1e-7) / 100
    lower = np.floor(reference * 90 + 0.5 + 1e-7) / 100
    upper = upper.where(upper - reference >= 0.01 - 1e-9, reference + 0.01)
    lower = lower.where(reference - lower >= 0.01 - 1e-9, reference - 0.01)
    limits = pd.DataFrame({"covered": covered, "up": covered & ((close - upper).abs() < 1e-6),
                           "down": covered & ((close - lower).abs() < 1e-6)}, index=frame.index)
    return values, limits.reindex(dates, fill_value=False)


def _empty_sector(size):
    return {**{f"sum_{period}d": np.zeros(size) for period in (5, 20)},
            **{field: np.zeros(size, dtype=np.int32) for field in
               ("covered_5d", "covered_20d", "ma20_covered", "above_ma20_count", "day_covered", "up_count", "outperform_5d_count", "limit_covered", "limit_up_count", "limit_down_count")}}


def _memberships(mapping, code):
    item = mapping.get(code, {})
    return tuple(dict.fromkeys(item.get("industries") or ([item["industry"]] if item.get("industry") else [])))


def _build(paths, names, mapping):
    eligible = {code for code, name in names.items() if _eligible(code, name)}
    expected = Counter(name for code in eligible for name in _memberships(mapping, code))
    dates, first_date = _calendar(paths)
    size = len(dates)
    market = {period: {"sum": np.zeros(size), "count": np.zeros(size, dtype=np.int32)} for period in (5, 20)}
    sectors = {name: _empty_sector(size) for name in expected}
    pending = defaultdict(list)
    for code in eligible & paths.keys():
        try:
            closes, limits = _stock_closes(paths[code], dates, include_limits=True)
        except (OSError, UnicodeError, ValueError, pd.errors.ParserError):
            continue
        member_names = _memberships(mapping, code)
        member_sectors = [sectors[name] for name in member_names]
        valid = np.isfinite(closes)
        counts = np.concatenate(([0], np.cumsum(valid, dtype=np.int32)))
        for period in (5, 20):
            gains = np.full(size, np.nan)
            if size > period:
                complete = counts[period + 1:] - counts[:-(period + 1)] == period + 1
                with np.errstate(invalid="ignore", divide="ignore", over="ignore"):
                    gains[period:] = np.where(complete, (closes[period:] / closes[:-period] - 1) * 100, np.nan)
            gain_valid = np.isfinite(gains)
            gains[~gain_valid] = np.nan
            totals = np.where(gain_valid, gains, 0)
            market[period]["sum"] += totals
            market[period]["count"] += gain_valid
            for name, sector in zip(member_names, member_sectors):
                sector[f"sum_{period}d"] += totals
                sector[f"covered_{period}d"] += gain_valid
                if period == 5:
                    pending[name].append(gains)
        if not member_sectors:
            continue
        for sector in member_sectors:
            sector["limit_covered"] += limits["covered"].to_numpy(dtype=np.int32)
            sector["limit_up_count"] += limits["up"].to_numpy(dtype=np.int32)
            sector["limit_down_count"] += limits["down"].to_numpy(dtype=np.int32)
            if size >= 20:
                complete = counts[20:] - counts[:-20] == 20
                average = np.convolve(np.where(valid, closes, 0), np.ones(20), mode="valid") / 20
                sector["ma20_covered"][19:] += complete
                sector["above_ma20_count"][19:] += complete & (closes[19:] > average + np.maximum(np.abs(average) * 1e-12, 1e-12))
            if size > 1:
                complete = valid[1:] & valid[:-1]
                sector["day_covered"][1:] += complete
                sector["up_count"][1:] += complete & (closes[1:] > closes[:-1])
    for period in (5, 20):
        market[period]["average"] = _average(market[period]["sum"], market[period]["count"])
    for industry, returns in pending.items():
        for gains in returns:
            sectors[industry]["outperform_5d_count"] += gains > market[5]["average"]
    # Keep compact arrays for all sectors; materialise JSON rows only on demand.
    return {"dates": dates, "first_date": first_date, "market": market, "sectors": sectors,
            "expected": expected, "benchmark_expected": len(eligible), "histories": {}}


def _history(cache, name):
    if name not in cache["expected"]:
        return []
    if name in cache["histories"]:
        return cache["histories"][name]
    sector, market = cache["sectors"][name], cache["market"]
    means = {period: _average(sector[f"sum_{period}d"], sector[f"covered_{period}d"]) for period in (5, 20)}
    data = []
    finite = lambda value: float(value) if np.isfinite(value) else None
    for index, date in enumerate(cache["dates"]):
        if cache["first_date"] and date < cache["first_date"]:
            continue
        row = {"date": date, "eligible_count": cache["expected"][name], "benchmark_expected": cache["benchmark_expected"]}
        for period in (5, 20):
            mean, benchmark = means[period][index], market[period]["average"][index]
            row.update({f"return_{period}d": finite(mean), f"benchmark_{period}d": finite(benchmark),
                        f"relative_{period}d": finite(mean - benchmark),
                        f"covered_{period}d": int(sector[f"covered_{period}d"][index]),
                        f"benchmark_covered_{period}d": int(market[period]["count"][index])})
        covered, ma_count, day_count = row["covered_5d"], int(sector["ma20_covered"][index]), int(sector["day_covered"][index])
        outperform = int(sector["outperform_5d_count"][index]) if covered and row["benchmark_5d"] is not None else None
        above, up = int(sector["above_ma20_count"][index]), int(sector["up_count"][index])
        row.update({"outperform_5d_count": outperform, "outperform_5d_pct": outperform / covered * 100 if outperform is not None else None,
                    "above_ma20_count": above if ma_count else None, "ma20_covered": ma_count,
                    "above_ma20_pct": above / ma_count * 100 if ma_count else None,
                    "up_count": up if day_count else None, "day_covered": day_count,
                    "up_pct": up / day_count * 100 if day_count else None})
        limit_covered = int(sector["limit_covered"][index])
        row.update({"limit_covered": limit_covered,
                    "limit_up_count": int(sector["limit_up_count"][index]) if limit_covered else None,
                    "limit_down_count": int(sector["limit_down_count"][index]) if limit_covered else None})
        data.append(row)
    cache["histories"][name] = data
    return data


def get_industry_strength_history(name):
    global _cache, _signature
    mapping = stock_industry_service.load_industry_map()
    with _lock:
        paths, names, signature = _sources()
        signature = (signature, tuple(sorted((code, item.get("industry"), item.get("industry_date"))
                                             for code, item in mapping.items())))
        if _cache is None or signature != _signature:
            _cache = _build(paths, names, mapping)
            _signature = signature
        data = _history(_cache, name)
    valid = [row for row in data if row["relative_5d"] is not None or row["above_ma20_pct"] is not None]
    return {"name": name, "data": data, "total": len(data),
            "first_date": data[0]["date"] if data else None,
            "first_valid_date": valid[0]["date"] if valid else None,
            "latest_date": valid[-1]["date"] if valid else None,
            "scope": "沪深主板非ST，按当前行业成员及最新股票名称过滤",
            "benchmark": "全部本地沪深主板非ST股票的同期等权平均涨幅",
            "source": "本地不复权日K；行业与市场使用相同交易日窗口，缺失不补零",
            "limit_rule": "主板非ST当前成员，按主板10%涨跌停价四舍五入至分，统计收盘封板家数；有涨跌幅数据时使用其还原参考价，排除注册制新股前5个交易日和零成交量日期。",
            "note": "相对强度=行业成员等权区间涨幅减市场等权区间涨幅，单位百分点；上涨广度=5日跑赢市场/站上MA20的有效成员占比。要求对应窗口交易日完整；按当前成员回看历史，非历史时点成分股回测。除权除息可能影响不复权指标。"}


def get_ranked_industries():
    """Rank all local THS industries on one date without materialising 90 histories."""
    global _cache, _signature
    mapping = stock_industry_service.load_industry_map()
    with _lock:
        paths, names, signature = _sources()
        signature = (signature, tuple(sorted((code, item.get("industry"), item.get("industry_date"))
                                             for code, item in mapping.items())))
        if _cache is None or signature != _signature:
            _cache = _build(paths, names, mapping)
            _signature = signature
        cache = _cache
        latest, rows = _ranked_rows(cache, [path.stem for path in BOARD_DIR.glob("*.csv")])
    return {"date": latest, "data": rows, "total": len(rows),
            "method": "four_metric_equal_percentile",
            "rules": "同一交易日，5日/20日相对强度、5日跑赢市场比例、站上MA20比例，分别转换为板块内排名百分位，再等权平均；强度50%、广度50%。四项齐全才评分，缺项排末尾。"}


def _ranked_rows(cache, board_names):
    latest = cache["dates"][-1] if cache["dates"] else None
    rows = []
    for name in sorted(board_names):
        row = {"name": name, "date": latest, "eligible_count": cache["expected"].get(name, 0)}
        sector = cache["sectors"].get(name)
        for period in (5, 20):
            count = int(sector[f"covered_{period}d"][-1]) if sector and latest else 0
            benchmark = cache["market"][period]["average"][-1] if latest else np.nan
            value = sector[f"sum_{period}d"][-1] / count - benchmark if count else np.nan
            row[f"relative_{period}d"] = float(value) if np.isfinite(value) else None
            row[f"covered_{period}d"] = count
        ma_count = int(sector["ma20_covered"][-1]) if sector and latest else 0
        row["ma20_covered"] = ma_count
        row["outperform_5d_pct"] = (int(sector["outperform_5d_count"][-1]) / row["covered_5d"] * 100
                                      if row["covered_5d"] and row["relative_5d"] is not None else None)
        row["above_ma20_pct"] = int(sector["above_ma20_count"][-1]) / ma_count * 100 if ma_count else None
        rows.append(row)
    rank_industries(rows)
    return latest, rows


def rank_industries(rows):
    fields = ("relative_5d", "relative_20d", "outperform_5d_pct", "above_ma20_pct")
    complete = [row for row in rows if all(row.get(field) is not None and np.isfinite(row[field]) for field in fields)]
    for row in rows:
        row.update(strength_score=None, breadth_score=None, score=None)
    if complete:
        scores = {field: pd.Series([row[field] for row in complete]).rank(method="average", pct=True).to_numpy() * 100
                  for field in fields}
        for index, row in enumerate(complete):
            row["strength_score"] = float((scores[fields[0]][index] + scores[fields[1]][index]) / 2)
            row["breadth_score"] = float((scores[fields[2]][index] + scores[fields[3]][index]) / 2)
            row["score"] = (row["strength_score"] + row["breadth_score"]) / 2
    rows.sort(key=lambda row: (row["score"] is None, -(row["score"] or 0), row["name"]))
    for rank, row in enumerate(rows, 1):
        row["rank"] = rank if row["score"] is not None else None
