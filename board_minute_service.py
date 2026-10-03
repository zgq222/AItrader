"""同花顺原生板块分钟K线：按需下载、保存历史，保留实际覆盖范围。"""
from __future__ import annotations

import csv
import json
import os
import re
import threading
import time
from bisect import bisect_left
from datetime import date
from pathlib import Path

import pandas as pd
import requests

BASE_DIR = Path(__file__).resolve().parent
BOARD_DIR = BASE_DIR / "板块"
INDEX_DIR = BASE_DIR / "指数"
CATALOG_FILE = BOARD_DIR / "板块列表" / "同花顺板块列表.csv"
MINUTE_DIR = BOARD_DIR / "分钟K线"
INDEX_MINUTE_DIR = INDEX_DIR / "分钟K线"
INTERVALS = {1, 5, 10, 15, 30, 60}
# Verified native endpoint periods: 60=1 minute, 30=5, 40=30, 50=60.
PERIODS = {1: "60", 5: "30", 30: "40", 60: "50"}
BENCHMARKS = {"000001": ("hs_1A0001", "上证指数"),
              "399001": ("hs_399001", "深证成指"),
              "000300": ("hs_1B0300", "沪深300")}
COLUMNS = ["datetime", "open", "high", "low", "close", "volume", "amount"]
_locks: dict[tuple, threading.Lock] = {}
_guard = threading.Lock()
_attempts: dict[tuple, tuple[float, str | None]] = {}


class MinuteUnavailable(RuntimeError):
    pass


def _board_identity(board_type: str, name: str) -> str:
    labels = {"industry": "行业", "concept": "概念"}
    if board_type not in labels:
        raise ValueError("type 必须为 industry 或 concept")
    with CATALOG_FILE.open(encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            if row["板块名称"] == name and row["板块类型"] == labels[board_type]:
                code = row["板块代码"]
                if re.fullmatch(r"88\d{4}", code):
                    return code
    raise ValueError("未找到对应的同花顺板块代码")


def _request(symbol: str, period: str, file: str) -> dict:
    url = f"https://d.10jqka.com.cn/v6/line/{symbol}/{period}/{file}.js"
    with requests.Session() as session:
        session.trust_env = False
        response = session.get(url, timeout=(5, 15), headers={
            "User-Agent": "Mozilla/5.0", "Referer": "https://q.10jqka.com.cn/"})
        response.raise_for_status()
        text = response.content.decode("gb18030")
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end < start:
        raise MinuteUnavailable("同花顺分钟行情返回格式异常")
    payload = json.loads(text[start:end + 1])
    if not isinstance(payload, dict):
        raise MinuteUnavailable("同花顺分钟行情返回格式异常")
    return payload


def _parse(payload: dict) -> pd.DataFrame:
    records = []
    for item in str(payload.get("data") or "").split(";"):
        values = item.split(",")
        if len(values) < 7 or not re.fullmatch(r"\d{12}", values[0]):
            continue  # A daily endpoint must never masquerade as minute data.
        try:
            stamp = pd.to_datetime(values[0], format="%Y%m%d%H%M")
            numbers = [float(value) for value in values[1:7]]
            op, high, low, close, volume, amount = numbers
            if (not all(pd.notna(number) and abs(number) != float("inf") for number in numbers)
                    or min(op, high, low, close) <= 0 or volume < 0 or amount < 0
                    or high < max(op, close, low) or low > min(op, close, high)):
                continue
            clock = stamp.hour * 60 + stamp.minute
            if not (570 <= clock <= 690 or 780 <= clock <= 900):
                continue
            records.append([stamp.strftime("%Y-%m-%d %H:%M:%S"), *numbers])
        except (ValueError, OverflowError):
            continue
    return pd.DataFrame(records, columns=COLUMNS).drop_duplicates("datetime", keep="last").sort_values("datetime")


def _read(path: Path) -> pd.DataFrame:
    if not path.exists():
        return pd.DataFrame(columns=COLUMNS)
    frame = pd.read_csv(path, compression="gzip")
    return frame[COLUMNS].drop_duplicates("datetime", keep="last").sort_values("datetime")


def _history(symbol: str, name: str, interval: int, path: Path, refresh: bool = False) -> tuple[pd.DataFrame, str | None]:
    key = (symbol, interval)
    with _guard:
        lock = _locks.setdefault(key, threading.Lock())
    with lock:
        frame = _read(path)
        attempt, warning = _attempts.get(key, (0, None))
        if not refresh and time.monotonic() - attempt < 60:
            if frame.empty:
                raise MinuteUnavailable(warning or "同花顺暂无该板块分钟数据")
            return frame, warning
        if not refresh and not frame.empty and time.time() - path.stat().st_mtime < 60:
            return frame, None
        try:
            period = PERIODS[interval]
            latest = _request(symbol, period, "last")
            if latest.get("name") != name:
                raise MinuteUnavailable("分钟行情名称与所选指数不一致")
            recent = _parse(latest)
            if recent.empty:
                raise MinuteUnavailable("同花顺暂无该指数分钟K线")
            parts = [frame]
            warnings = []
            years = latest.get("year") or {}
            if not isinstance(years, dict):
                years = {}
                warnings.append("分钟历史目录暂不可用，仅保留已保存历史及最近分钟段")
            for year in sorted(years, key=str):
                if not re.fullmatch(r"20\d{2}", str(year)):
                    continue
                # Archive old years once; refresh the newest year and rolling tail.
                if str(year) != max(map(str, years)) and not frame.empty and frame.datetime.str.startswith(str(year)).any():
                    continue
                try:
                    archive = _parse(_request(symbol, period, str(year)))
                    if not archive.empty:
                        parts.append(archive)
                except (requests.RequestException, ValueError, MinuteUnavailable) as error:
                    warnings.append(f"{year}年历史暂不可取：{type(error).__name__}")
            parts.append(recent)
            nonempty = [part for part in parts if not part.empty]
            frame = pd.concat(nonempty, ignore_index=True).drop_duplicates("datetime", keep="last").sort_values("datetime")
            path.parent.mkdir(parents=True, exist_ok=True)
            temporary = path.with_name(f".{path.name}.{os.getpid()}.{threading.get_ident()}.tmp")
            try:
                frame.to_csv(temporary, index=False, encoding="utf-8", compression="gzip")
                os.replace(temporary, path)
            finally:
                temporary.unlink(missing_ok=True)
            warning = "；".join(warnings) or None
        except (requests.RequestException, ValueError, MinuteUnavailable) as error:
            warning = f"同花顺分钟数据更新失败（{type(error).__name__}），仅显示已保存的实际数据"
            if frame.empty:
                _attempts[key] = (time.monotonic(), warning)
                raise MinuteUnavailable(f"{name}分钟行情暂不可获取，请稍后重试") from error
        _attempts[key] = (time.monotonic(), warning)
        return frame.reset_index(drop=True), warning


def _aggregate(frame: pd.DataFrame, interval: int) -> pd.DataFrame:
    """Aggregate complete native five-minute blocks, never crossing lunch/days."""
    if frame.empty:
        return frame.copy()
    frame = frame.copy()
    stamp = pd.to_datetime(frame.datetime)
    clock = stamp.dt.hour * 60 + stamp.dt.minute
    valid = (clock.between(575, 690) | clock.between(785, 900)) & clock.mod(5).eq(0)
    frame = frame.loc[valid].copy()
    clock, stamp = clock[valid], stamp[valid]
    base = pd.Series(570, index=clock.index).where(clock <= 690, 780)
    frame["day"] = stamp.dt.normalize()
    frame["session"] = (clock > 690).astype(int)
    frame["slot"] = ((clock - base - 1) // interval).astype(int)
    grouped = frame.groupby(["day", "session", "slot"], sort=True).agg(
        open=("open", "first"), high=("high", "max"), low=("low", "min"), close=("close", "last"),
        volume=("volume", "sum"), amount=("amount", "sum"), samples=("datetime", "size")).reset_index()
    grouped = grouped[grouped.samples == interval // 5].copy()
    minute = grouped.session.map({0: 570, 1: 780}) + (grouped.slot + 1) * interval
    grouped["datetime"] = (grouped.day + pd.to_timedelta(minute, unit="m")).dt.strftime("%Y-%m-%d %H:%M:%S")
    return grouped[COLUMNS].reset_index(drop=True)


def _with_metrics(frame: pd.DataFrame, daily_path: Path | None, macd: bool = True) -> pd.DataFrame:
    frame = frame.copy()
    closes = {}
    if daily_path and daily_path.exists():
        daily = pd.read_csv(daily_path).rename(columns={"日期": "date", "收盘价": "close"})
        if {"date", "close"}.issubset(daily):
            closes = {str(row.date)[:10]: float(row.close) for row in daily.itertuples() if pd.notna(row.close) and float(row.close) > 0}
    # Local daily history supplies yesterday's close even for the first cached minute day.
    minute_closes = frame.groupby(frame.datetime.str[:10]).close.last().to_dict()
    closes = {**minute_closes, **closes}
    dates = sorted(closes)
    previous = {}
    for day in frame.datetime.str[:10].unique():
        index = bisect_left(dates, day)
        previous[day] = closes[dates[index - 1]] if index else None
    base = frame.datetime.str[:10].map(previous)
    frame["pct_change"] = (frame.close / base - 1) * 100
    if macd:
        frame["dif"] = frame.close.ewm(span=12, adjust=False).mean() - frame.close.ewm(span=26, adjust=False).mean()
        frame["dea"] = frame.dif.ewm(span=9, adjust=False).mean()
        frame["macd"] = (frame.dif - frame.dea) * 2
    return frame


def get_board_minute_kline(board_type: str, name: str, interval: int = 1, trade_date: str | None = None,
                           benchmark: str = "000001", refresh: bool = False) -> dict:
    interval = int(interval)
    if interval not in INTERVALS:
        raise ValueError("interval 必须为 1/5/10/15/30/60")
    if benchmark not in BENCHMARKS:
        raise ValueError("benchmark 必须为 000001/399001/000300")
    if trade_date and (not re.fullmatch(r"\d{4}-\d{2}-\d{2}", trade_date) or date.fromisoformat(trade_date).isoformat() != trade_date):
        raise ValueError("trade_date 必须为有效的 YYYY-MM-DD 日期")
    code = _board_identity(board_type, name)
    native = 5 if interval in {10, 15} else interval
    frame, warning = _history(f"bk_{code}", name, native, MINUTE_DIR / board_type / f"{code}_{native}min.csv.gz", refresh)
    if interval != native:
        frame = _aggregate(frame, interval)
    folder = "行业板块" if board_type == "industry" else "概念板块"
    frame = _with_metrics(frame, BOARD_DIR / folder / f"{name}.csv")
    available_dates = sorted(frame.datetime.str[:10].unique())
    selected_date = trade_date or (available_dates[-1] if available_dates else None)
    selected = frame.loc[frame.datetime.str[:10] == selected_date]
    symbol, market_name = BENCHMARKS[benchmark]
    market_warning = None
    market = pd.DataFrame()
    try:
        market, market_warning = _history(symbol, market_name, native, INDEX_MINUTE_DIR / f"{benchmark}_{native}min.csv.gz", refresh)
        if interval != native:
            market = _aggregate(market, interval)
        daily_path = next(INDEX_DIR.glob(f"{benchmark}_*.csv"), None)
        market = _with_metrics(market, daily_path, macd=False)
        market = market.loc[market.datetime.str[:10] == selected_date]
    except MinuteUnavailable as error:
        market_warning = str(error)
    return {"type": board_type, "code": code, "name": name, "interval": interval, "date": selected_date,
            "total": len(selected), "history_total": len(frame), "available_dates": available_dates,
            "first_datetime": str(frame.datetime.iloc[0]) if not frame.empty else None,
            "latest_datetime": str(frame.datetime.iloc[-1]) if not frame.empty else None,
            "source": f"同花顺原生板块{interval}分钟K线" if interval == native else f"同花顺5分钟K线聚合为{interval}分钟",
            "warning": warning, "data": selected.astype(object).where(pd.notna(selected), None).to_dict("records"),
            "benchmark": {"code": benchmark, "name": market_name, "warning": market_warning,
                          "data": market.astype(object).where(pd.notna(market), None).to_dict("records")}}
