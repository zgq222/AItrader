"""观察池持久化与收盘后涨停股筛选。"""
from __future__ import annotations

import csv
import json
import math
import os
import re
import threading
from datetime import datetime
from pathlib import Path
from uuid import uuid4
import stock_industry_service


BASE_DIR = Path(__file__).resolve().parent
RAW_DIR = BASE_DIR / "个股" / "原始数据"
WATCHLISTS_FILE = BASE_DIR / "data" / "watchlists.json"
HAMMER_NOTES_FILE = BASE_DIR / "data" / "inverted_hammer_notes.json"
WATCHLISTS_LOCK = threading.RLock()
DAILY_GROUP_ID = "daily_observation"
WEEKLY_GROUP_ID = "weekly_observation"
NOTED_STOCKS_GROUP_ID = "noted_stocks"
INVERTED_HAMMER_GROUP_ID = "inverted_hammer"
RISING_STRUCTURE_GROUP_ID = "rising_structure"
FIVE_MINUTE_SELECTION_GROUP_ID = "five_minute_selection"
MAIN_BOARD_30D_GROUP_ID = "main_board_30d_top150"
SECTOR_ROTATION_GROUP_ID = "sector_rotation"
CONCEPT_ROTATION_GROUP_ID = "concept_rotation"
DAILY_INVERTED_HAMMER_GROUP_ID = "daily_inverted_hammer"
RECENT_INVERTED_HAMMER_GROUP_IDS = tuple(f"inverted_hammer_day_{index}" for index in range(4))
OBSOLETE_INVERTED_HAMMER_GROUP_IDS = frozenset(
    (DAILY_INVERTED_HAMMER_GROUP_ID, *RECENT_INVERTED_HAMMER_GROUP_IDS)
)
SYSTEM_GROUPS = (
    (DAILY_GROUP_ID, "每日观察股票池", True),
    (WEEKLY_GROUP_ID, "每周观察股票池", False),
    (NOTED_STOCKS_GROUP_ID, "已添加笔记", True),
    (INVERTED_HAMMER_GROUP_ID, "倒垂线选股", False),
    (RISING_STRUCTURE_GROUP_ID, "上涨结构选股", False),
    (FIVE_MINUTE_SELECTION_GROUP_ID, "5分钟选股", False),
    (MAIN_BOARD_30D_GROUP_ID, "主板非ST·强势行业各前50", True),
    (SECTOR_ROTATION_GROUP_ID, "板块轮动·主板非ST", True),
    (CONCEPT_ROTATION_GROUP_ID, "概念轮动·主板非ST", True),
)


def _defaults():
    return {
        "version": 2,
        "groups": [
            {"id": group_id, "name": name, "system": True, "auto_refresh": auto, "stocks": []}
            for group_id, name, auto in SYSTEM_GROUPS
        ],
        "notes": {},
        "kline_notes": {},
        "daily_screen": {},
    }


def _normalise(payload):
    source_groups = payload.get("groups", []) if isinstance(payload, dict) else []
    groups, seen_groups = [], set()
    for group in source_groups:
        if not isinstance(group, dict) or not group.get("id") or not str(group.get("name", "")).strip():
            continue
        group_id = str(group["id"])
        if group_id in seen_groups or group_id in OBSOLETE_INVERTED_HAMMER_GROUP_IDS:
            continue
        stocks, seen_stocks = [], set()
        for stock in group.get("stocks", []):
            if not isinstance(stock, dict):
                continue
            code = str(stock.get("code", "")).strip().zfill(6)
            member_key = (stock.get("concept_code") or stock.get("concept"), code) if group_id == CONCEPT_ROTATION_GROUP_ID else code
            if not re.fullmatch(r"\d{6}", code) or member_key in seen_stocks:
                continue
            seen_stocks.add(member_key)
            item = {"code": code, "name": str(stock.get("name") or code)}
            if group_id == CONCEPT_ROTATION_GROUP_ID:
                for key in ("concept", "concept_code", "concept_member_date"):
                    if stock.get(key) is not None:item[key] = stock[key]
            for key in ("return_30d_pct", "return_rank", "sector_rank", "return_start", "return_end", "return_low_date", "return_low_price", "trade_date", "market_cap", "pct_change", "added_at", "match_count", "structure_count", "volume_confirmed_count", "down_structure_count", "minute_up_date", "signal", "signal_time", "attack_count", "reduction_count", "up_attack_count", "down_reduction_count", "pullback_low", "attack_origin", "break_price", "structure_start_time", "structure_end_time", "limit_up_date", "pullback_start_date", "pullback_end_date", "period_decline_pct", "period_rebound_pct", "decline_rank", "decline_start", "decline_end", "rebound_start", "rebound_end", "baseline_close", "middle_close", "end_close", "end_close_source", "end_close_timestamp", "ma20_current", "ma20_previous"):
                if stock.get(key) is not None:
                    item[key] = stock[key]
            stocks.append(item)
        stock_industry_service.enrich_stocks(stocks)
        system = group_id in {item[0] for item in SYSTEM_GROUPS}
        groups.append({
            "id": group_id, "name": str(group["name"]).strip(), "system": system,
            "auto_refresh": group_id in {DAILY_GROUP_ID, MAIN_BOARD_30D_GROUP_ID, SECTOR_ROTATION_GROUP_ID, CONCEPT_ROTATION_GROUP_ID}, "stocks": stocks,
        })
        seen_groups.add(group_id)
    for group_id, name, auto in reversed(SYSTEM_GROUPS):
        if group_id not in seen_groups:
            groups.insert(0, {"id": group_id, "name": name, "system": True, "auto_refresh": auto, "stocks": []})
    notes = {}
    for code, value in (payload.get("notes", {}) if isinstance(payload, dict) else {}).items():
        code = str(code).zfill(6)
        if re.fullmatch(r"\d{6}", code) and isinstance(value, str):
            notes[code] = value[:10000]
    kline_notes = {}
    for key, value in (payload.get("kline_notes", {}) if isinstance(payload, dict) else {}).items():
        if re.fullmatch(r"\d{6}:(day|week|month|year):\d{4}-\d{2}-\d{2}", str(key)) and isinstance(value, str) and value.strip():
            kline_notes[str(key)] = value[:10000]
    return {
        "version": 3, "groups": groups, "notes": notes, "kline_notes": kline_notes,
        "daily_screen": payload.get("daily_screen", {}) if isinstance(payload, dict) else {},
        "inverted_hammer_screen": payload.get("inverted_hammer_screen", {}) if isinstance(payload, dict) else {},
        "rising_structure_screen": payload.get("rising_structure_screen", {}) if isinstance(payload, dict) else {},
        "five_minute_selection_screen": payload.get("five_minute_selection_screen", {}) if isinstance(payload, dict) else {},
        "main_board_30d_screen": payload.get("main_board_30d_screen", {}) if isinstance(payload, dict) else {},
        "sector_rotation_screen": payload.get("sector_rotation_screen", {}) if isinstance(payload, dict) else {},
        "concept_rotation_screen": payload.get("concept_rotation_screen", {}) if isinstance(payload, dict) else {},
    }


def load_watchlists():
    try:
        payload = json.loads(WATCHLISTS_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        payload = _defaults()
    return _normalise(payload)


def save_watchlists(payload):
    WATCHLISTS_FILE.parent.mkdir(parents=True, exist_ok=True)
    temp = WATCHLISTS_FILE.with_name(f".{WATCHLISTS_FILE.stem}_{os.getpid()}.tmp")
    temp.write_text(json.dumps(_normalise(payload), ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(temp, WATCHLISTS_FILE)


def _sync_noted_stocks(payload, stock_catalog=None):
    """Keep the system note group in sync with non-empty stock notes."""
    group = next(item for item in payload["groups"] if item["id"] == NOTED_STOCKS_GROUP_ID)
    names = {
        str(stock.get("code", "")).zfill(6): str(stock.get("name") or stock.get("code"))
        for source_group in payload["groups"]
        for stock in source_group.get("stocks", [])
        if isinstance(stock, dict)
    }
    if stock_catalog is not None:
        items = stock_catalog() if callable(stock_catalog) else stock_catalog
        names.update({str(item.get("code", "")).zfill(6): str(item.get("name") or item.get("code"))
                      for item in items if isinstance(item, dict)})
    noted_codes = {code for code, note in payload.get("notes", {}).items() if isinstance(note, str) and note.strip()}
    noted_codes.update(key[:6] for key in payload.get("kline_notes", {}))
    noted_codes.update(key[:6] for key in _legacy_hammer_notes())
    group["stocks"] = [
        {"code": code, "name": names.get(code, code)}
        for code in sorted(noted_codes)
    ]
    return payload


def _legacy_hammer_notes():
    try:
        notes = json.loads(HAMMER_NOTES_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return {key: note for key, note in notes.items()
            if re.fullmatch(r"\d{6}:\d{4}-\d{2}-\d{2}", str(key))
            and isinstance(note, str) and note.strip()} if isinstance(notes, dict) else {}


def get_kline_notes(payload, code):
    """Return notes for a stock, including existing inverted-hammer notes."""
    code = str(code).zfill(6)
    result = {f"day:{key.split(':', 1)[1]}": value for key, value in _legacy_hammer_notes().items()
              if key.startswith(code + ":")}
    result.update({key[len(code) + 1:]: value for key, value in payload.get("kline_notes", {}).items()
                   if key.startswith(code + ":")})
    return result


def refresh_noted_stocks(stock_catalog=None):
    """Refresh and persist the derived note group; safe to call after any run."""
    with WATCHLISTS_LOCK:
        payload = load_watchlists()
        before = next(item for item in payload["groups"] if item["id"] == NOTED_STOCKS_GROUP_ID).get("stocks", [])
        _sync_noted_stocks(payload, stock_catalog)
        after = next(item for item in payload["groups"] if item["id"] == NOTED_STOCKS_GROUP_ID)["stocks"]
        if before != after:
            save_watchlists(payload)
        return payload


def replace_inverted_hammer_group(stocks, screen):
    """Replace the single inverted-hammer selection group."""
    with WATCHLISTS_LOCK:
        payload = load_watchlists()
        group = next(item for item in payload["groups"] if item["id"] == INVERTED_HAMMER_GROUP_ID)
        group["stocks"] = stocks
        payload["inverted_hammer_screen"] = {
            key: screen.get(key) for key in (
                "updated_at", "window_start", "window_end", "stock_count", "event_count", "scanned_main_board"
            )
        }
        payload["inverted_hammer_screen"]["recent_trade_dates"] = list(screen.get("recent_trade_dates") or [])
        _sync_noted_stocks(payload)
        save_watchlists(payload)


def replace_rising_structure_group(stocks, screen):
    with WATCHLISTS_LOCK:
        payload = load_watchlists()
        group = next(item for item in payload["groups"] if item["id"] == RISING_STRUCTURE_GROUP_ID)
        group["stocks"] = stocks
        payload["rising_structure_screen"] = screen
        save_watchlists(payload)


def replace_five_minute_selection_group(stocks, screen):
    with WATCHLISTS_LOCK:
        payload = load_watchlists()
        group = next(item for item in payload["groups"] if item["id"] == FIVE_MINUTE_SELECTION_GROUP_ID)
        group["stocks"] = stocks
        payload["five_minute_selection_screen"] = screen
        save_watchlists(payload)


def _latest_rows(path, count=2):
    try:
        with path.open("rb") as handle:
            header = handle.readline().decode("utf-8-sig").rstrip("\r\n")
            handle.seek(0, os.SEEK_END)
            end = handle.tell()
            if end <= 0:
                return None
            pos, chunk, tail = end, 4096, b""
            while pos > 0 and tail.count(b"\n") < count + 1:
                size = min(chunk, pos)
                pos -= size
                handle.seek(pos)
                tail = handle.read(size) + tail
        lines = [line for line in tail.decode("utf-8").splitlines() if line.strip()]
        if not lines:
            return []
        keys = next(csv.reader([header]))
        return [dict(zip(keys, next(csv.reader([line])))) for line in lines[-count:]]
    except (OSError, csv.Error, UnicodeDecodeError):
        return []


def _latest_row(path):
    rows = _latest_rows(path, 1)
    return rows[-1] if rows else None


def _is_main_board(code):
    return code.startswith(("000", "001", "002", "003", "600", "601", "603", "605"))


def stock_update_sort_key(code, name):
    """Update main-board non-ST stocks first, then all others, in code order."""
    code = str(code).strip().zfill(6)
    priority = _is_main_board(code) and "ST" not in str(name).upper()
    return (0 if priority else 1, code)


def _recent_market_dates(count=20):
    """Use a continuously traded main-board benchmark as the local trading calendar."""
    benchmark = next(RAW_DIR.glob("000001_*_原始数据.csv"), None)
    if benchmark:
        dates = [str(row.get("date", "")) for row in _latest_rows(benchmark, count) if row.get("date")]
        if dates:
            return dates[-count:]
    latest = []
    for path in list(RAW_DIR.glob("*_原始数据.csv"))[:100]:
        latest.extend(str(row.get("date", "")) for row in _latest_rows(path, count) if row.get("date"))
    return sorted(set(latest))[-count:]


def recent_market_dates(count=4):
    return _recent_market_dates(count)


def low_to_latest_close_gain(rows, market_dates):
    """Thirty-session lowest daily low to the latest session's close."""
    if not market_dates:
        return None
    by_date = {str(row.get("date", "")): row for row in rows}
    try:
        closing = float(by_date[market_dates[-1]]["close"])
    except (KeyError, TypeError, ValueError):
        return None
    if not math.isfinite(closing) or closing <= 0:
        return None
    lows = []
    for day in market_dates:
        row = by_date.get(day)
        if row is None:
            continue
        try:
            low = float(row["low"])
        except (KeyError, TypeError, ValueError):
            continue
        if math.isfinite(low) and low > 0:
            lows.append((low, day))
    if not lows:
        return None
    lowest, low_date = min(lows)
    return {"return_30d_pct": (closing / lowest - 1) * 100,
            "return_low_price": lowest, "return_low_date": low_date,
            "return_end": market_dates[-1], "end_close": closing}


def screen_main_board_30d_top150():
    """Seed industries from the global top 150, then keep their positive top 50."""
    dates = recent_market_dates(30)
    if len(dates) < 30:
        raise ValueError("本地交易日历不足30个交易日，无法计算30日低点涨幅")
    start_date, end_date = dates[0], dates[-1]
    candidates = {}
    seen_codes = set()
    for path in sorted(RAW_DIR.glob("*_原始数据.csv"), key=lambda path: (path.stat().st_mtime_ns, path.name), reverse=True):
        match = re.fullmatch(r"(\d{6})_(.+)_原始数据\.csv", path.name)
        if not match:
            continue
        code, name = match.groups()
        if code in seen_codes:
            continue
        seen_codes.add(code)
        if not _is_main_board(code) or "ST" in name.upper():
            continue
        gain = low_to_latest_close_gain(_latest_rows(path, 100), dates)
        if gain is None:
            continue
        candidates[code] = {
            "code": code, "name": name, "trade_date": end_date,
            "return_start": start_date, **gain,
        }
    ranked = sorted(candidates.values(), key=lambda stock: (-stock["return_30d_pct"], stock["code"]))
    if not ranked:
        raise ValueError("本地没有同时具备30日内最低价和最新收盘价的主板非ST股票，请先同步日K数据")
    industries = stock_industry_service.load_industry_map()
    def industry(stock):
        return industries.get(stock["code"], {}).get("industry", "待分类")
    seed_industries = list(dict.fromkeys(industry(stock) for stock in ranked[:150]))
    buckets = {name: [] for name in seed_industries}
    for rank, stock in enumerate(ranked, 1):
        name = industry(stock)
        bucket = buckets.get(name)
        if stock["return_30d_pct"] <= 0 or bucket is None or len(bucket) >= 50:
            continue
        stock["return_rank"] = rank
        stock["sector_rank"] = len(bucket) + 1
        bucket.append(stock)
    stocks = [stock for name in seed_industries for stock in buckets[name]]
    screen = {
        "updated_at": datetime.now().isoformat(timespec="seconds"),
        "trade_date": end_date, "window_start": start_date, "window_end": end_date,
        "lookback_days": 30, "method": "top150_seed_industries_top50_positive",
        "stock_count": len(stocks), "eligible_count": len(candidates),
        "seed_stock_count": min(150, len(ranked)), "seed_industry_count": len(seed_industries),
        "seed_industries": seed_industries, "per_industry_limit": 50,
        "rules": "主板、非ST；最近30个交易日（含最新交易日），最低日K低点至最新收盘价的涨幅；先用全市场涨幅前150只确定行业，再在每个入选行业取涨幅大于0的前50只，本地不复权日K",
    }
    with WATCHLISTS_LOCK:
        payload = load_watchlists()
        group = next(item for item in payload["groups"] if item["id"] == MAIN_BOARD_30D_GROUP_ID)
        group["name"] = "主板非ST·强势行业各前50"
        group["stocks"] = stocks
        payload["main_board_30d_screen"] = screen
        save_watchlists(payload)
    return screen


def screen_daily_limitups():
    """Add qualified limit-ups from the latest 20 trading days; never remove existing stocks."""
    market_dates = _recent_market_dates(20)
    eligible_dates = set(market_dates)
    latest_date = market_dates[-1] if market_dates else ""
    with WATCHLISTS_LOCK:
        initial_payload = load_watchlists()
    previous_screen = initial_payload.get("daily_screen", {})
    is_accumulating = previous_screen.get("mode") == "accumulate"
    previous_trade_date = str(previous_screen.get("trade_date") or "")
    # First run backfills 20 trading days. Later runs only process dates not scanned before,
    # while still catching multiple missed sessions after the service was offline.
    scan_dates = {day for day in eligible_dates if not is_accumulating or day > previous_trade_date}
    candidates = []
    for path in RAW_DIR.glob("*_原始数据.csv"):
        match = re.match(r"(\d{6})_(.+?)_原始数据\.csv$", path.name)
        if not match:
            continue
        code, name = match.groups()
        if not _is_main_board(code) or "ST" in name.upper() or "退" in name:
            continue
        rows = _latest_rows(path, 21)
        if len(rows) < 2:
            continue
        row = rows[-1]
        try:
            close = float(row["close"])
            shares = float(row["outstanding_share"])
        except (KeyError, TypeError, ValueError):
            continue
        market_cap = close * shares
        if not 3_000_000_000 <= market_cap <= 50_000_000_000:
            continue
        limit_events = []
        for index in range(1, len(rows)):
            event = rows[index]
            trade_date = str(event.get("date", ""))
            if trade_date not in scan_dates:
                continue
            try:
                pct_change = float(event.get("pct_change"))
            except (TypeError, ValueError):
                try:
                    event_close = float(event["close"])
                    previous_close = float(rows[index - 1]["close"])
                    pct_change = (event_close / previous_close - 1) * 100
                except (KeyError, TypeError, ValueError, ZeroDivisionError):
                    continue
            if pct_change >= 9.5:
                limit_events.append((trade_date, pct_change))
        if limit_events:
            trade_date, pct_change = max(limit_events, key=lambda item: item[0])
            candidates.append({
                "code": code, "name": name, "trade_date": trade_date,
                "market_cap": round(market_cap, 2), "pct_change": round(pct_change, 3),
                "added_at": datetime.now().isoformat(timespec="seconds"),
            })
    with WATCHLISTS_LOCK:
        payload = load_watchlists()
        group = next(item for item in payload["groups"] if item["id"] == DAILY_GROUP_ID)
        existing = {item["code"]: item for item in group["stocks"]}
        new_count = 0
        for candidate in candidates:
            previous = existing.get(candidate["code"])
            if previous is None:
                existing[candidate["code"]] = candidate
                new_count += 1
            elif str(candidate["trade_date"]) > str(previous.get("trade_date", "")):
                candidate["added_at"] = previous.get("added_at") or candidate["added_at"]
                existing[candidate["code"]] = candidate
        group["stocks"] = sorted(existing.values(), key=lambda item: (str(item.get("trade_date", "")), item["code"]), reverse=True)
        payload["daily_screen"] = {
            "trade_date": latest_date or None, "updated_at": datetime.now().isoformat(timespec="seconds"),
            "window_start": market_dates[0] if market_dates else None,
            "mode": "accumulate", "initial_backfill_days": 20,
            "matched_in_scan": len(candidates), "new_count": new_count, "count": len(group["stocks"]),
            "rules": "首次回补最近20个交易日，之后仅追加新交易日；主板、非ST、流通市值30亿至500亿元、涨停涨幅不低于9.5%；永不自动删除",
        }
        _sync_noted_stocks(payload)
        save_watchlists(payload)
        return payload


def mutate_watchlists(action, body, stock_catalog):
    with WATCHLISTS_LOCK:
        payload = load_watchlists()
        groups = payload["groups"]
        group_id = str(body.get("group_id", ""))
        group = next((item for item in groups if item["id"] == group_id), None)
        if action == "refresh_daily":
            return screen_daily_limitups()
        if action == "update_kline_note":
            code = str(body.get("code", "")).strip().zfill(6)
            period = str(body.get("period", "day"))
            date = str(body.get("date", ""))
            if not re.fullmatch(r"\d{6}", code) or period not in {"day", "week", "month", "year"} or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date):
                raise ValueError("股票、周期或K线日期无效")
            note = str(body.get("note", ""))
            if len(note) > 10000:
                raise ValueError("单条笔记不能超过10000个字符")
            key = f"{code}:{period}:{date}"
            if note.strip():
                payload["kline_notes"][key] = note
            else:
                payload["kline_notes"].pop(key, None)
            legacy_key = f"{code}:{date}"
            if period == "day" and legacy_key in _legacy_hammer_notes():
                legacy_notes = _legacy_hammer_notes()
                if note.strip():
                    legacy_notes[legacy_key] = note
                else:
                    legacy_notes.pop(legacy_key, None)
                temp = HAMMER_NOTES_FILE.with_name(f".{HAMMER_NOTES_FILE.stem}_{os.getpid()}.tmp")
                temp.write_text(json.dumps(legacy_notes, ensure_ascii=False, indent=2), encoding="utf-8")
                os.replace(temp, HAMMER_NOTES_FILE)
        elif action == "update_note":
            code = str(body.get("code", "")).strip().zfill(6)
            if not re.fullmatch(r"\d{6}", code):
                raise ValueError("股票代码必须为6位数字")
            note = str(body.get("note", ""))
            if len(note) > 10000:
                raise ValueError("单只股票笔记不能超过10000个字符")
            if note.strip():
                payload["notes"][code] = note
            else:
                payload["notes"].pop(code, None)
        elif action == "create_group":
            name = str(body.get("name", "")).strip()
            if not name or len(name) > 30:
                raise ValueError("分组名称须为1至30个字符")
            if any(item["name"] == name for item in groups):
                raise ValueError("分组名称已存在")
            groups.append({"id": uuid4().hex[:12], "name": name, "system": False, "auto_refresh": False, "stocks": []})
        elif action in {"rename_group", "delete_group"}:
            if group is None:
                raise ValueError("分组不存在")
            if group.get("system"):
                raise ValueError("系统观察池不能改名或删除")
            if action == "delete_group":
                groups.remove(group)
            else:
                name = str(body.get("name", "")).strip()
                if not name or len(name) > 30:
                    raise ValueError("分组名称须为1至30个字符")
                group["name"] = name
        elif action in {"add_stock", "remove_stock"}:
            if group is None:
                raise ValueError("分组不存在")
            if group_id in {DAILY_GROUP_ID, INVERTED_HAMMER_GROUP_ID, RISING_STRUCTURE_GROUP_ID, FIVE_MINUTE_SELECTION_GROUP_ID, MAIN_BOARD_30D_GROUP_ID, SECTOR_ROTATION_GROUP_ID}:
                raise ValueError("该系统观察池由对应筛选自动维护")
            code = str(body.get("code", "")).strip().zfill(6)
            if action == "add_stock":
                items = stock_catalog() if callable(stock_catalog) else stock_catalog
                catalog = {item["code"]: item for item in items}
                if code not in catalog:
                    raise ValueError("本地股票目录中未找到该代码")
                if any(item["code"] == code for item in group["stocks"]):
                    raise ValueError("该股票已在当前分组")
                group["stocks"].append({"code": code, "name": catalog[code].get("name") or code, "added_at": datetime.now().isoformat(timespec="seconds")})
            else:
                before = len(group["stocks"])
                group["stocks"] = [item for item in group["stocks"] if item["code"] != code]
                if len(group["stocks"]) == before:
                    raise ValueError("当前分组中没有该股票")
        else:
            raise ValueError("不支持的自选股操作")
        _sync_noted_stocks(payload, stock_catalog)
        for source_group in payload["groups"]:
            stock_industry_service.enrich_stocks(source_group["stocks"])
        save_watchlists(payload)
        return payload
