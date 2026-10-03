"""倒垂线选股扫描、人工复核和自选股分组同步。"""
from __future__ import annotations

import json
import os
import re
import threading
from datetime import datetime
from pathlib import Path

import pandas as pd

import scan_chan_fractals
import watchlist_service


BASE_DIR = Path(__file__).resolve().parent
RAW_DIR = BASE_DIR / "个股" / "原始数据"
RESULT_FILE = BASE_DIR / "data" / "inverted_hammer_screen.json"
NOTES_FILE = BASE_DIR / "data" / "inverted_hammer_notes.json"
LOCK = threading.RLock()
NOTES_LOCK = threading.RLock()
GROUP_ID = "inverted_hammer"
GROUP_NAME = "倒垂线选股"
K2 = 0.2


def _is_main_board(code: str) -> bool:
    return code.startswith(("000", "001", "002", "003", "600", "601", "603", "605"))


def _is_st(name: str) -> bool:
    return "ST" in name.upper()


def _limit_rate(name: str) -> float:
    return 1.05 if _is_st(name) else 1.10


def _limit_price(previous_close: float, rate: float) -> float:
    # A股价格最小变动单位为0.01元；用实际可成交的两位价格判断涨停。
    return round(previous_close * rate + 1e-8, 2)


def _load_result() -> dict:
    try:
        payload = json.loads(RESULT_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        payload = {}
    return payload if isinstance(payload, dict) else {}


def _save_result(payload: dict) -> None:
    RESULT_FILE.parent.mkdir(parents=True, exist_ok=True)
    temp = RESULT_FILE.with_name(f".{RESULT_FILE.stem}_{os.getpid()}.tmp")
    temp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(temp, RESULT_FILE)


def _load_notes() -> dict:
    try:
        notes = json.loads(NOTES_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        notes = {}
    return notes if isinstance(notes, dict) else {}


def _save_notes(notes: dict) -> None:
    NOTES_FILE.parent.mkdir(parents=True, exist_ok=True)
    temp = NOTES_FILE.with_name(f".{NOTES_FILE.stem}_{os.getpid()}.tmp")
    temp.write_text(json.dumps(notes, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(temp, NOTES_FILE)


def _read_bars(path: Path) -> pd.DataFrame:
    wanted = {"date", "open", "high", "low", "close", "volume"}
    frame = pd.read_csv(path, usecols=lambda column: column in wanted)
    if not {"date", "open", "high", "low", "close"}.issubset(frame.columns):
        return pd.DataFrame()
    frame["date"] = pd.to_datetime(frame["date"], errors="coerce")
    for column in ("open", "high", "low", "close"):
        frame[column] = pd.to_numeric(frame[column], errors="coerce")
    if "volume" in frame.columns:
        frame["volume"] = pd.to_numeric(frame["volume"], errors="coerce").fillna(0)
    else:
        frame["volume"] = 0
    return frame.dropna().sort_values("date").reset_index(drop=True)


def _qualifying_prior_candle(frame: pd.DataFrame) -> pd.Series:
    """阴线，或收盘高于开盘但较前一交易日收盘下跌的阳线。"""
    close = frame["close"]
    open_price = frame["open"]
    return close.lt(open_price) | (close.gt(open_price) & close.lt(close.shift(1)))


def _events_for_frame(frame: pd.DataFrame, name: str, cutoff: pd.Timestamp) -> list[dict]:
    if len(frame) < 32:
        return []
    rate = _limit_rate(name)
    previous_close = frame["close"].shift(1)
    limit_prices = (previous_close * rate + 1e-8).round(2)
    limit_up = frame["close"].ge(limit_prices)
    had_limit_up_in_prior_30 = limit_up.rolling(30, min_periods=1).max().shift(1).fillna(False).astype(bool)
    body = frame["close"] - frame["open"]
    lower_shadow = frame["open"] - frame["low"]
    ma20 = frame["close"].rolling(20).mean()
    qualifying_prior = _qualifying_prior_candle(frame)
    prev1_qualifies = qualifying_prior.shift(1, fill_value=False)
    prev2_qualifies = qualifying_prior.shift(2, fill_value=False)
    mask = (
        frame["date"].ge(cutoff)
        & body.gt(0)
        & lower_shadow.le(K2 * body + 1e-9)
        & frame["high"].lt(limit_prices - 1e-9)
        & frame["close"].lt(limit_prices - 1e-9)
        & prev1_qualifies
        & prev2_qualifies
        & ma20.ge(ma20.shift(1) - 1e-9)
        & had_limit_up_in_prior_30
    )
    ma_max = pd.concat([frame["close"].rolling(period).mean() for period in (5, 10, 20, 30)], axis=1).max(axis=1)
    events = []
    for index in frame.index[mask & frame.index.to_series().ge(31)]:
        row = frame.iloc[index]
        body = row["close"] - row["open"]
        lower_shadow = row["open"] - row["low"]
        events.append({
            "date": row["date"].strftime("%Y-%m-%d"),
            "open": round(float(row["open"]), 4), "close": round(float(row["close"]), 4),
            "high": round(float(row["high"]), 4), "low": round(float(row["low"]), 4),
            "body": round(float(body), 4), "lower_shadow": round(float(lower_shadow), 4),
            "k2": K2,
        })
        # 自动标签：只使用截至当日可见的数据，避免未来函数。
        close_now = float(row["close"])
        close_2 = float(frame.iloc[index - 2]["close"])
        labels = ["阳包阴" if close_now > close_2 else "不包"]
        if pd.notna(ma_max.iloc[index]) and close_now > ma_max.iloc[index]:
            labels.append("突破均线")
        if index >= 1:
            if float(frame.iloc[index].get("volume", 0)) > float(frame.iloc[index - 1].get("volume", 0)):
                labels.append("当日成交量放量")
            elif float(frame.iloc[index].get("volume", 0)) < float(frame.iloc[index - 1].get("volume", 0)):
                labels.append("当日成交量缩量")
        events[-1]["auto_labels"] = labels
    return events


def _mark_bottom_fractal_right(events: list[dict], frame: pd.DataFrame) -> None:
    """At each event date, require its merged K line to be a bottom fractal's right bar."""
    by_date = {event["date"]: event for event in events}
    merged: list[dict] = []
    for row in frame.itertuples(index=False):
        day = row.date.date()
        scan_chan_fractals.append_merged_bar(merged, {
            "date": day, "start": day, "end": day,
            "high": float(row.high), "low": float(row.low), "close": float(row.close),
        })
        event = by_date.get(day.isoformat())
        if event is None:
            continue
        bottom = False
        if len(merged) >= 3:
            left, middle, right = merged[-3:]
            bottom = (middle["high"] < left["high"] and middle["high"] < right["high"]
                      and middle["low"] < left["low"] and middle["low"] < right["low"])
        event["bottom_fractal_right"] = bottom
        event["bottom_fractal_date"] = middle["end"].isoformat() if bottom else None
        event["bottom_fractal_left_date"] = left["end"].isoformat() if bottom else None
        event["bottom_fractal_middle_start"] = middle["start"].isoformat() if bottom else None
        event["bottom_fractal_middle_end"] = middle["end"].isoformat() if bottom else None
        event["bottom_fractal_right_start"] = right["start"].isoformat() if bottom else None
        event["bottom_fractal_right_end"] = right["end"].isoformat() if bottom else None

def _add_market_labels(events: list[dict]) -> None:
    path = BASE_DIR / "指数" / "000001_上证指数.csv"
    if not path.exists(): return
    try:
        idx = pd.read_csv(path, usecols=["date", "close", "volume"])
        idx["date"] = pd.to_datetime(idx["date"], errors="coerce")
        idx = idx.sort_values("date").reset_index(drop=True)
        by_date = {row.date.strftime("%Y-%m-%d"): row for row in idx.itertuples() if pd.notna(row.date)}
        for event in events:
            row = by_date.get(event["date"])
            if row is None: continue
            pos = idx.index[idx["date"] == row.date][0]
            labels = event.setdefault("auto_labels", [])
            if pos > 0:
                prev = idx.iloc[pos-1]
                if row.close > prev.close: labels.append("当日大盘涨")
                elif row.close < prev.close: labels.append("当日大盘跌")
                if row.volume > prev.volume: labels.append("当日大盘成交量放量")
                elif row.volume < prev.volume: labels.append("当日大盘成交量缩量")
    except Exception:
        return


def scan_all() -> dict:
    """扫描本地主板股票最近三个自然年的日K，并同步倒垂线自选股分组。"""
    with LOCK:
        previous_payload = _load_result()
        reviews = previous_payload.get("reviews", {}) if isinstance(previous_payload.get("reviews"), dict) else {}
        notes = previous_payload.get("notes", {}) if isinstance(previous_payload.get("notes"), dict) else {}
        files_by_code = {}
        for path in RAW_DIR.glob("*_原始数据.csv"):
            match = re.match(r"(\d{6})_(.+?)_原始数据\.csv$", path.name)
            if match and _is_main_board(match.group(1)) and not _is_st(match.group(2)):
                code = match.group(1)
                candidate = (path, code, match.group(2))
                previous = files_by_code.get(code)
                if previous is None or path.stat().st_mtime > previous[0].stat().st_mtime:
                    files_by_code[code] = candidate
        files = list(files_by_code.values())
        latest_dates = []
        for path, _, _ in files[:100]:
            try:
                tail = pd.read_csv(path, usecols=["date"]).tail(1)
                if not tail.empty:
                    latest_dates.append(pd.to_datetime(tail.iloc[0]["date"], errors="coerce"))
            except Exception:
                pass
        latest_date = max((value for value in latest_dates if pd.notna(value)), default=pd.Timestamp.today().normalize())
        cutoff = latest_date - pd.DateOffset(years=3)
        matches, errors = [], []
        for path, code, name in files:
            try:
                frame = _read_bars(path)
                events = _events_for_frame(frame, name, cutoff)
                if events:
                    _mark_bottom_fractal_right(events, frame)
                    _add_market_labels(events)
            except Exception as exc:
                errors.append({"code": code, "error": str(exc)[:120]})
                continue
            if events:
                matches.append({"code": code, "name": name, "events": events})
        matches.sort(key=lambda item: (item["events"][-1]["date"], item["code"]), reverse=True)
        payload = {
            "version": 2, "strategy": GROUP_NAME, "k2": K2,
            "window_start": cutoff.strftime("%Y-%m-%d"), "window_end": latest_date.strftime("%Y-%m-%d"),
            "updated_at": datetime.now().isoformat(timespec="seconds"),
            "stock_count": len(matches), "event_count": sum(len(item["events"]) for item in matches),
            "scanned_main_board": len(files), "matches": matches, "reviews": reviews, "notes": notes,
            "errors": errors[:50],
        }
        latest_day = latest_date.strftime("%Y-%m-%d")
        recent_dates = sorted({day for day in watchlist_service.recent_market_dates(8) + [latest_day]
                               if day and day <= latest_day})[-4:][::-1]
        payload["recent_trade_dates"] = recent_dates
        _save_result(payload)
        try:
            import hammer_combination_report
            hammer_combination_report.write_files(hammer_combination_report.build_report(payload))
        except Exception as exc:
            errors.append({"code": "REPORT", "error": str(exc)[:120]})
        stocks = [{
            "code": item["code"], "name": item["name"],
            "trade_date": item["events"][-1]["date"], "match_count": len(item["events"]),
            "added_at": datetime.now().isoformat(timespec="seconds"),
        } for item in matches]
        watchlist_service.replace_inverted_hammer_group(stocks, payload)
        return payload


def get_events(code: str) -> dict:
    payload = _load_result()
    match = next((item for item in payload.get("matches", []) if item.get("code") == code), None)
    reviews = payload.get("reviews", {})
    notes = payload.get("notes", {})
    with NOTES_LOCK:
        notes = {**notes, **_load_notes()}
    events = []
    for event in (match or {}).get("events", []):
        item = dict(event)
        item["review"] = reviews.get(f"{code}:{event['date']}")
        item["note"] = notes.get(f"{code}:{event['date']}", "")
        events.append(item)
    return {"code": code, "strategy": GROUP_NAME, "events": events, "updated_at": payload.get("updated_at")}


def save_review(code: str, date: str, review) -> dict:
    if not re.fullmatch(r"\d{6}", code):
        raise ValueError("股票代码必须为6位数字")
    allowed = {"阳包阴", "不包", "不符合", "到达技术位", "突破均线", "底分型右侧"}
    if isinstance(review, str):
        reviews = [review]
    elif isinstance(review, list):
        reviews = list(dict.fromkeys(str(item) for item in review))
    else:
        reviews = []
    if not reviews or any(item not in allowed for item in reviews):
        raise ValueError("复核选项必须为：阳包阴、不包、不符合、到达技术位、突破均线、底分型右侧")
    with LOCK:
        payload = _load_result()
        exists = any(
            item.get("code") == code and any(event.get("date") == date for event in item.get("events", []))
            for item in payload.get("matches", [])
        )
        if not exists:
            raise ValueError("未找到对应的倒垂线事件")
        payload.setdefault("reviews", {})[f"{code}:{date}"] = reviews
        _save_result(payload)
    return {"code": code, "date": date, "review": reviews}


def save_note(code: str, date: str, note: str) -> dict:
    if not re.fullmatch(r"\d{6}", code):
        raise ValueError("股票代码必须为6位数字")
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date):
        raise ValueError("倒垂线日期格式无效")
    if len(note) > 10000:
        raise ValueError("单条笔记不能超过10000个字符")
    payload = _load_result()
    exists = any(
        item.get("code") == code and any(event.get("date") == date for event in item.get("events", []))
        for item in payload.get("matches", [])
    )
    if not exists:
        raise ValueError("未找到对应的倒垂线事件")
    key = f"{code}:{date}"
    with NOTES_LOCK:
        notes = _load_notes()
        notes[key] = note
        _save_notes(notes)
    return {"code": code, "date": date, "note": note}
