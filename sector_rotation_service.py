"""Derived all-industry watchlist, retaining every eligible constituent."""
from __future__ import annotations

import hashlib
import threading
from datetime import datetime

import industry_strength_service as strength
import stock_industry_service
import watchlist_service as watchlists

_lock = threading.Lock()
METHOD = "all_industries_equal_percentile_30d_low_to_close_v1"


def refresh(force=False):
    with _lock:
        paths, names, source_signature = strength._sources()
        mapping = stock_industry_service.load_industry_map()
        signature = hashlib.sha256(repr((source_signature, sorted((code, item.get("industry"), item.get("industry_date"))
                                                                  for code, item in mapping.items()), METHOD)).encode()).hexdigest()
        with watchlists.WATCHLISTS_LOCK:
            previous = watchlists.load_watchlists().get("sector_rotation_screen", {})
        if not force and previous.get("signature") == signature:
            return previous
        ranking = strength.get_ranked_industries()
        if not ranking["date"] or not ranking["data"]:
            raise ValueError("暂无可用的本地板块行情")
        dates = [day for day in watchlists.recent_market_dates(60) if day <= ranking["date"]][-30:]
        buckets = {row["name"]: [] for row in ranking["data"]}
        for code, name in names.items():
            industry = mapping.get(code, {}).get("industry")
            if industry not in buckets or not strength._eligible(code, name):
                continue
            gain = watchlists.low_to_latest_close_gain(watchlists._latest_rows(paths[code], 100), dates) if code in paths and len(dates) == 30 else None
            buckets[industry].append({"code": code, "name": name, "return_30d_pct": None,
                                      **(gain or {}), "return_start": dates[0] if dates else None})
        stocks = []
        for row in ranking["data"]:
            members = buckets[row["name"]]
            members.sort(key=lambda item: (item["return_30d_pct"] is None, -(item["return_30d_pct"] or 0), item["code"]))
            for rank, stock in enumerate(members, 1):
                stock["sector_rank"] = rank
            row["member_count"] = len(members)
            stocks.extend(members)
        screen = {"updated_at": datetime.now().isoformat(timespec="seconds"), "trade_date": ranking["date"],
                  "method": METHOD, "signature": signature, "industries": ranking["data"],
                  "industry_count": ranking["total"], "stock_count": len(stocks),
                  "board_rules": ranking["rules"], "stock_sort": "return_30d_low_to_close",
                  "stock_rules": "暂按最近30个交易日最低日K低点至最新收盘价涨幅降序，缺少有效行情排末尾；全部沪深主板非ST成分股，不设前N名限制。"}
        with watchlists.WATCHLISTS_LOCK:
            payload = watchlists.load_watchlists()
            group = next(item for item in payload["groups"] if item["id"] == watchlists.SECTOR_ROTATION_GROUP_ID)
            group["stocks"] = stocks
            payload["sector_rotation_screen"] = screen
            watchlists.save_watchlists(payload)
        return screen
