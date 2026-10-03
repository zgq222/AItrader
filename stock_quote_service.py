"""Small cached Tencent quote request for the workbench valuation header."""
from __future__ import annotations

import json
import math
import os
import re
import threading
import time
from datetime import datetime
from pathlib import Path

import requests

CACHE_DIR = Path(__file__).resolve().parent / "data" / "stock_quotes"
_locks: dict[str, threading.Lock] = {}
_lock_guard = threading.Lock()
_attempts: dict[str, float] = {}
_quotes: dict[str, dict] = {}
TTL = 60


class QuoteUnavailable(RuntimeError):
    pass


def _number(value):
    try:
        result = float(value)
        return result if math.isfinite(result) else None
    except (ValueError, TypeError):
        return None


def parse_quote(text: str, code: str, symbol: str) -> dict:
    match = re.search(rf'v_{re.escape(symbol)}="([^"]*)"', text)
    fields = match.group(1).split("~") if match else []
    if len(fields) < 46 or fields[2] != code:
        raise QuoteUnavailable("行情代码不匹配或返回格式不完整")
    price = _number(fields[3])
    if price is None or price <= 0:
        raise QuoteUnavailable("暂无有效个股行情")
    try:
        timestamp = datetime.strptime(fields[30], "%Y%m%d%H%M%S").strftime("%Y-%m-%d %H:%M:%S")
    except ValueError as exc:
        raise QuoteUnavailable("行情日期不可用") from exc
    cap = _number(fields[45])
    return {"code": code, "name": fields[1], "price": price,
            "pe": _number(fields[39]) or None, "market_cap": cap * 1e8 if cap is not None and cap > 0 else None,
            "quote_time": timestamp, "source": "腾讯财经", "warning": None}


def get_stock_quote(code: str) -> dict:
    if not re.fullmatch(r"\d{6}", code):
        raise ValueError("code 必须为6位股票代码")
    if code.startswith("6"):
        prefix = "sh"
    elif code.startswith(("0", "3")):
        prefix = "sz"
    elif code.startswith(("4", "8", "9")):
        prefix = "bj"
    else:
        raise ValueError("暂不支持该证券代码")
    with _lock_guard:
        lock = _locks.setdefault(code, threading.Lock())
    with lock:
        path = CACHE_DIR / f"{code}.json"
        cached = _quotes.get(code)
        try:
            candidate = json.loads(path.read_text(encoding="utf-8"))
            if cached is None and candidate.get("code") == code and candidate.get("source") == "腾讯财经":
                cached = candidate
        except (OSError, ValueError, AttributeError):
            pass
        if time.monotonic() - _attempts.get(code, -TTL) < TTL:
            if cached:
                return cached
            raise QuoteUnavailable("个股行情暂不可用，请稍后重试")
        _attempts[code] = time.monotonic()
        try:
            symbol = prefix + code
            with requests.Session() as session:
                session.trust_env = False
                response = session.get(f"https://qt.gtimg.cn/q={symbol}", timeout=(5, 8))
                response.raise_for_status()
                payload = parse_quote(response.content.decode("gbk"), code, symbol)
        except (requests.RequestException, UnicodeError, QuoteUnavailable) as exc:
            if cached:
                _quotes[code] = {**cached, "warning": f"行情更新失败，显示缓存：{exc}"}
                return _quotes[code]
            raise QuoteUnavailable("个股估值行情获取失败") from exc
        _quotes[code] = payload
        try:
            CACHE_DIR.mkdir(parents=True, exist_ok=True)
            temporary = path.with_name(f".{code}.{os.getpid()}.{threading.get_ident()}.tmp")
            temporary.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            os.replace(temporary, path)
        except OSError:
            pass
        return payload
