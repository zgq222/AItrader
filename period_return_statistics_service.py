"""Compare the September decline window with its following return window."""
from __future__ import annotations

from datetime import datetime
from concurrent.futures import ThreadPoolExecutor
from html import escape
import json
import math
from pathlib import Path
from statistics import mean, median
import requests

import watchlist_service

BASE_DIR = Path(__file__).resolve().parent
GROUP_ID = "period_return_20260916_20260928"
GROUP_NAME = "9.16—9.21下跌及后续涨幅"
REPORT_DIR = BASE_DIR / "data" / "research"


def _supplement_trend_history(codes: list[str]) -> dict:
    """Read enough unadjusted daily closes to confirm the 20-day average."""
    cache_path = REPORT_DIR / f"{GROUP_ID}_trend_history.json"
    try:
        cache = json.loads(cache_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        cache = {}
    def fetch(code):
        symbol = ("sh" if code.startswith("6") else "sz") + code
        try:
            response = requests.get("https://web.ifzq.gtimg.cn/appstock/app/fqkline/get",
                                    params={"param": f"{symbol},day,2026-08-01,2026-09-28,80,"}, timeout=12)
            response.raise_for_status()
            payload = response.json()
            if payload.get("code") != 0:
                return code, {}
            closes = {bar[0]: float(bar[2]) for bar in payload["data"][symbol].get("day", [])}
            return code, closes
        except (requests.RequestException, ValueError, KeyError, IndexError):
            return code, {}
    with ThreadPoolExecutor(max_workers=8) as pool:
        for code, closes in pool.map(fetch, [code for code in codes if code not in cache]):
            if closes:
                cache[code] = closes
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(json.dumps(cache, ensure_ascii=False, indent=2), encoding="utf-8")
    return cache


def daily_ma20_values(closes: dict[str, float]) -> tuple[float, float] | None:
    """Latest and prior MA20, anchored to the report end date."""
    values = [value for day, value in sorted(closes.items()) if day <= "2026-09-28"]
    if "2026-09-28" not in closes or len(values) < 21:
        return None
    values = values[-21:]
    if not all(math.isfinite(value) and value > 0 for value in values):
        return None
    return math.fsum(values[-20:])/20, math.fsum(values[:-1])/20


def _supplement_interval_closes(code: str) -> dict[str, float]:
    """Confirm sparse sessions against historical bars and carry suspended closes."""
    cache_path = REPORT_DIR / f"{GROUP_ID}_historical_quotes.json"
    try:
        cache = json.loads(cache_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        cache = {}
    if code in cache:
        return cache[code]
    symbol = ("sh" if code.startswith("6") else "sz") + code
    try:
        response = requests.get("https://web.ifzq.gtimg.cn/appstock/app/fqkline/get",
                                params={"param": f"{symbol},day,2026-09-01,2026-09-28,40,"}, timeout=12)
        response.raise_for_status()
        payload = response.json()
        if payload.get("code") != 0:
            return {}
        bars = payload["data"][symbol].get("day", [])
        result = {}
        for target in ("2026-09-15", "2026-09-21", "2026-09-28"):
            previous = [bar for bar in bars if bar[0] <= target]
            if previous:
                value = float(max(previous, key=lambda bar: bar[0])[2])
                if math.isfinite(value) and value > 0:
                    result[target] = value
        if result:
            cache[code] = result
            REPORT_DIR.mkdir(parents=True, exist_ok=True)
            cache_path.write_text(json.dumps(cache, ensure_ascii=False, indent=2), encoding="utf-8")
        return result
    except (requests.RequestException, ValueError, KeyError, IndexError):
        return {}


def _supplement_end_closes(codes: list[str]) -> dict:
    """Cache closing quotes only when their timestamp confirms the target session."""
    cache_path = REPORT_DIR / f"{GROUP_ID}_closing_quotes.json"
    try:
        cache = json.loads(cache_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        cache = {}
    wanted = [code for code in codes if code not in cache]
    for offset in range(0, len(wanted), 40):
        batch = wanted[offset:offset+40]
        symbols = ",".join(("sh" if code.startswith("6") else "sz") + code for code in batch)
        try:
            response = requests.get("https://qt.gtimg.cn/q=" + symbols, timeout=12)
            response.raise_for_status()
            response.encoding = "gbk"
            for line in response.text.splitlines():
                if '"' not in line:
                    continue
                fields = line.split('"')[1].split("~")
                if len(fields) < 31:
                    continue
                code, timestamp, close = fields[2], fields[30], float(fields[3])
                if code in batch and timestamp[:8] == "20260928" and timestamp[8:12] >= "1500" and math.isfinite(close) and close > 0:
                    cache[code] = {"close": close, "timestamp": timestamp, "source": "腾讯收盘行情"}
        except (requests.RequestException, ValueError, IndexError):
            continue
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(json.dumps(cache, ensure_ascii=False, indent=2), encoding="utf-8")
    return cache


def period_returns(closes: dict[str, float]) -> dict | None:
    """Include returns on the named dates using the preceding session's close."""
    baseline = closes.get("2026-09-15")
    middle = closes.get("2026-09-21")
    end = closes.get("2026-09-28")
    if baseline is None or middle is None:
        return None
    if not all(math.isfinite(value) and value > 0 for value in (baseline, middle)):
        return None
    decline = (middle / baseline - 1) * 100
    if decline >= 0:
        return None
    rebound = ((end / middle - 1) * 100
               if end is not None and math.isfinite(end) and end > 0 else None)
    return {"period_decline_pct": decline, "period_rebound_pct": rebound,
            "baseline_close": baseline, "middle_close": middle, "end_close": end,
            "decline_start": "2026-09-16", "decline_end": "2026-09-21",
            "rebound_start": "2026-09-22", "rebound_end": "2026-09-28"}


def _write_report(stocks: list[dict], summary: dict) -> None:
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    result = {"summary": summary, "stocks": stocks}
    (REPORT_DIR / f"{GROUP_ID}.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    def pct(value):
        return "待补齐9.28" if value is None else f"{value:+.2f}%"
    table = "".join(
        f'<tr><td>{stock["decline_rank"]}</td><td>{stock["code"]}</td>'
        f'<td>{escape(stock["name"])}</td><td>{stock["baseline_close"]:.2f}</td>'
        f'<td>{stock["middle_close"]:.2f}</td><td>{pct(stock["period_decline_pct"])}</td>'
        f'<td>{"—" if stock["end_close"] is None else format(stock["end_close"], ".2f")}</td>'
        f'<td>{pct(stock["period_rebound_pct"])}</td><td>{stock["ma20_current"]:.4f}</td>'
        f'<td>{stock["ma20_previous"]:.4f}</td></tr>' for stock in stocks)
    html = f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<title>{GROUP_NAME}｜2026</title><style>
body{{font:15px system-ui;max-width:1100px;margin:32px auto;padding:0 20px;color:#18212b}}
h1{{font-size:25px}}.note{{color:#596675;line-height:1.7}}table{{border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums}}
th,td{{padding:9px;border-bottom:1px solid #dce2e8;text-align:right}}th{{position:sticky;top:0;background:#eaf0f5}}td:nth-child(3){{text-align:left}}tr:nth-child(even){{background:#f6f8fa}}
</style><h1>2026年9.16—9.21下跌及9.22—9.28涨幅统计</h1>
<p>主板、非ST、9月28日20日均线向上（MA20高于前一交易日）；按前一段跌幅从大到小排序。</p>
<p>下跌股票 {summary["stock_count"]} 只；后续数据完整 {summary["rebound_available"]} 只；9.28待补齐 {summary["rebound_pending"]} 只。</p>
<p>已知后续区间平均涨幅 {pct(summary["mean_rebound_pct"])}，中位数 {pct(summary["median_rebound_pct"])}；上涨 {summary["rebound_positive"]} 只。</p>
<p class="note">前段涨跌幅＝9.21收盘价÷9.15收盘价−1；后段涨跌幅＝9.28收盘价÷9.21收盘价−1。后段下跌也保留负值。
来源：本地个股/原始数据日K收盘价，未复权；本地缺少9.28时用时间戳明确为9.28收盘后的腾讯行情补齐（{summary["supplemented_close_count"]}只）。稀疏日线经腾讯历史日线核对，停牌日沿用前一有效收盘价。
符合前段下跌且20日均线向上的股票入组；缺少前段基准或终点数据的 {summary["missing_decline_data"]} 只未参与统计。均线历史不足 {summary["ma20_unavailable"]} 只。更新：{summary["updated_at"]}。</p>
<table><thead><tr><th>跌幅排名</th><th>代码</th><th>名称</th><th>9.15收盘</th><th>9.21收盘</th><th>9.16—9.21涨跌幅</th><th>9.28收盘</th><th>9.22—9.28涨跌幅</th><th>9.28 MA20</th><th>前日 MA20</th></tr></thead><tbody>{table}</tbody></table></html>'''
    (REPORT_DIR / f"{GROUP_ID}.html").write_text(html, encoding="utf-8")


def scan_all() -> dict:
    stocks = []
    eligible_count = missing_decline_data = 0
    latest_files = {}
    for path in sorted(watchlist_service.RAW_DIR.glob("*_原始数据.csv")):
        code = path.name[:6]
        name = path.stem[7:].removesuffix("_原始数据")
        if not watchlist_service._is_main_board(code):
            continue
        rows = watchlist_service._latest_rows(path, 100) or []
        key = (str(rows[-1].get("date", "")) if rows else "", path.stat().st_mtime)
        previous = latest_files.get(code)
        if previous is None or key > previous[0]:
            latest_files[code] = (key, name, rows)
    for code, (_, name, rows) in latest_files.items():
        if "ST" in name.upper():
            continue
        eligible_count += 1
        closes = {}
        for row in rows:
            try:
                closes[row["date"]] = float(row["close"])
            except (KeyError, ValueError, TypeError):
                continue
        if not all(day in closes for day in ("2026-09-15", "2026-09-21")):
            for day, value in _supplement_interval_closes(code).items():
                closes.setdefault(day, value)
            if not all(day in closes for day in ("2026-09-15", "2026-09-21")):
                missing_decline_data += 1
        returns = period_returns(closes)
        if returns:
            stocks.append({"code": code, "name": name, "_daily_closes": closes, **returns})
    quotes = _supplement_end_closes([stock["code"] for stock in stocks if stock["period_rebound_pct"] is None])
    supplemented = 0
    for stock in stocks:
        quote = quotes.get(stock["code"])
        if stock["period_rebound_pct"] is None and quote:
            stock["end_close"] = quote["close"]
            stock["period_rebound_pct"] = (quote["close"] / stock["middle_close"] - 1) * 100
            stock["end_close_source"] = quote["source"]
            stock["end_close_timestamp"] = quote["timestamp"]
            supplemented += 1
    before_ma20 = len(stocks)
    history = _supplement_trend_history([
        stock["code"] for stock in stocks
        if max(stock["_daily_closes"], default="") < "2026-09-24"])
    filtered = []
    ma20_unavailable = 0
    for stock in stocks:
        closes = stock.pop("_daily_closes")
        closes.update(history.get(stock["code"], {}))
        if stock["end_close"] is not None:
            closes["2026-09-28"] = stock["end_close"]
        averages = daily_ma20_values(closes)
        if averages is None:
            ma20_unavailable += 1
            continue
        current, previous = averages
        if current <= previous:
            continue
        stock.update(ma20_current=round(current, 6), ma20_previous=round(previous, 6))
        filtered.append(stock)
    stocks = filtered
    supplemented = sum(stock.get("end_close_source") == "腾讯收盘行情" for stock in stocks)
    stocks.sort(key=lambda stock: (stock["period_decline_pct"], stock["code"]))
    for index, stock in enumerate(stocks, 1):
        stock["decline_rank"] = index
        stock["period_decline_pct"] = round(stock["period_decline_pct"], 6)
        if stock["period_rebound_pct"] is not None:
            stock["period_rebound_pct"] = round(stock["period_rebound_pct"], 6)
    known = [stock["period_rebound_pct"] for stock in stocks if stock["period_rebound_pct"] is not None]
    summary = {"updated_at": datetime.now().isoformat(timespec="seconds"),
               "group_id": GROUP_ID, "group_name": GROUP_NAME,
               "eligible_count": eligible_count, "missing_decline_data": missing_decline_data,
               "daily_ma20_rising": True, "ma20_as_of": "2026-09-28",
               "before_ma20_stock_count": before_ma20, "ma20_unavailable": ma20_unavailable,
               "stock_count": len(stocks), "rebound_available": len(known),
               "rebound_pending": len(stocks)-len(known),
               "supplemented_close_count": supplemented,
               "rebound_positive": sum(value > 0 for value in known),
               "mean_rebound_pct": mean(known) if known else None,
               "median_rebound_pct": median(known) if known else None}
    with watchlist_service.WATCHLISTS_LOCK:
        payload = watchlist_service.load_watchlists()
        group = next((group for group in payload["groups"] if group["id"] == GROUP_ID), None)
        if group is None:
            group = {"id": GROUP_ID, "name": GROUP_NAME, "system": False, "auto_refresh": False}
            payload["groups"].append(group)
        group["stocks"] = stocks
        watchlist_service.save_watchlists(payload)
    _write_report(stocks, summary)
    return summary
