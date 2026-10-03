"""Daily filters for the five-minute selection group and chart frame helpers.

Each complete five-minute bar uses 2.5 times the median volume of the previous
20 complete trading days. Consecutive qualifying bars form one chart frame.
"""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal, ROUND_HALF_UP
from collections import deque
from collections import Counter
import gzip
import io
import json
from pathlib import Path

import numpy as np
import pandas as pd

from intraday_bars import aggregate_five_minutes, clean_minute_price_outliers
import rising_structure_service
import watchlist_service


BASE_DIR = Path(__file__).resolve().parent
RAW_DIR = BASE_DIR / "个股" / "原始数据"
MINUTE_DIR = BASE_DIR / "个股" / "一分钟"


def daily_ma20_rising(rows: list[dict]) -> bool:
    """Require the latest daily MA20 to be higher than the previous MA20."""
    if len(rows) < 21:
        return False
    closes = np.asarray([row["close"] for row in rows], dtype=float)
    if not np.isfinite(closes).all():
        return False
    current = float(closes[-20:].mean())
    previous = float(closes[-21:-1].mean())
    return bool(current > previous)


# Kept as a compatibility alias for callers from earlier versions.
daily_uptrend = daily_ma20_rising


def recent_limit_up_date(rows: list[dict], recent_dates: list[str]) -> str | None:
    """Find the latest close at the main-board 10% price limit in the window."""
    eligible_dates = set(recent_dates)
    result = None
    for previous, current in zip(rows, rows[1:]):
        if current["date"] not in eligible_dates:
            continue
        previous_close, close = float(previous["close"]), float(current["close"])
        if not np.isfinite(previous_close) or not np.isfinite(close) or previous_close <= 0:
            continue
        limit = (Decimal(str(previous_close)) * Decimal("1.10")).quantize(
            Decimal("0.01"), rounding=ROUND_HALF_UP)
        if abs(close - float(limit)) < 0.005:
            result = current["date"]
    return result


def recent_three_day_pullback(rows: list[dict], recent_dates: list[str]) -> dict | None:
    """Latest consecutive three bearish/down sessions entirely within 12 sessions."""
    window = recent_dates[-12:]
    if len(window) < 3:
        return None
    qualifies = {}
    for previous, current in zip(rows, rows[1:]):
        if current["date"] not in window:
            continue
        opening, closing, previous_close = (float(current["open"]),
                                             float(current["close"]), float(previous["close"]))
        if not all(np.isfinite(value) for value in (opening, closing, previous_close)):
            qualifies[current["date"]] = False
            continue
        qualifies[current["date"]] = closing < opening or closing < previous_close
    for end in range(len(window)-1, 1, -1):
        days = window[end-2:end+1]
        if all(qualifies.get(day, False) for day in days):
            return {"pullback_start_date": days[0], "pullback_end_date": days[-1]}
    return None


def main_force_frames(bars: pd.DataFrame) -> list[dict]:
    """Match the existing five-minute chart's baseline and contiguous boxes."""
    if bars.empty:
        return []
    dates = bars.datetime.dt.strftime("%Y-%m-%d")
    by_date = {day: part.volume.to_numpy(dtype=float)
               for day, part in bars.groupby(dates, sort=True)}
    all_dates = sorted(by_date)
    complete_days = [day for day in all_dates if len(by_date[day]) >= 48]
    baselines = {}
    for day in all_dates:
        previous = [prior for prior in complete_days if prior < day][-20:]
        if len(previous) == 20:
            baselines[day] = float(np.median(np.concatenate([by_date[prior] for prior in previous])))
    frames = []
    for index, bar in enumerate(bars.itertuples(index=False)):
        baseline = baselines.get(str(bar.datetime)[:10], 0)
        if baseline <= 0 or not np.isfinite(bar.volume) or bar.volume / baseline < 2.5:
            continue
        previous = frames[-1] if frames else None
        adjoining = (previous is not None and index == previous["end_index"] + 1
                     and bar.datetime.date() == previous["end_time"].date()
                     and bar.datetime - previous["end_time"] == pd.Timedelta(minutes=5))
        if adjoining:
            previous["end_index"] = index
            previous["end_time"] = bar.datetime
            previous["end_price"] = float(bar.close)
            previous["count"] += 1
        else:
            frames.append({"start_index": index, "end_index": index,
                           "start_time": bar.datetime, "end_time": bar.datetime,
                           "start_price": float(bar.open), "end_price": float(bar.close),
                           "low": float(bar.low), "high": float(bar.high), "count": 1})
        if adjoining:
            previous["low"] = min(previous["low"], float(bar.low))
            previous["high"] = max(previous["high"], float(bar.high))
    for frame in frames:
        frame["direction"] = (1 if frame["end_price"] > frame["start_price"]
                              else -1 if frame["end_price"] < frame["start_price"] else 0)
    return frames


def merge_contained_frames(frames: list[dict]) -> list[dict]:
    """Treat transitively contained adjacent frames as one behavior."""
    merged = []
    cluster = None
    for source in frames:
        current = dict(source)
        current["contained"] = False
        direction = current.get("direction", 0)
        if not direction:
            merged.append(current)
            continue
        if cluster is None:
            merged.append(current)
            cluster = {"low": current["low"], "high": current["high"],
                       "members": [current]}
            continue
        current_inside = current["low"] >= cluster["low"] and current["high"] <= cluster["high"]
        cluster_inside = cluster["low"] >= current["low"] and cluster["high"] <= current["high"]
        if current_inside:
            current["contained"] = True
            cluster["members"].append(current)
            merged.append(current)
        elif cluster_inside:
            for member in cluster["members"]:
                member["contained"] = True
            merged.append(current)
            cluster = {"low": current["low"], "high": current["high"],
                       "members": [*cluster["members"], current]}
        else:
            merged.append(current)
            cluster = {"low": current["low"], "high": current["high"],
                       "members": [current]}
    return merged


def find_recent_rising_structure(bars: pd.DataFrame, frames: list[dict], recent_dates: list[str]) -> dict | None:
    """Find the newest complete five-minute upward structure.

    Only solid behavior frames participate. Dashed contained frames and yellow
    (direction 0) frames are ignored. The pattern is 3+ attacks, a reduction
    breaking the prior attack low, 3+ reductions, then an attack breaking the
    prior reduction high.
    """
    del bars  # Kept in the signature for callers that already pass the bars.
    recent = {str(day)[:10] for day in recent_dates}
    if not recent:
        return None
    recent_start = min(recent)
    behaviors = [frame for frame in frames
                 if frame.get("direction", 0) in (-1, 1) and not frame.get("contained")]
    matches = []
    for start in range(len(behaviors)):
        if behaviors[start]["direction"] != 1:
            continue
        attack_end = start
        while attack_end + 1 < len(behaviors) and behaviors[attack_end + 1]["direction"] == 1:
            attack_end += 1
        if attack_end - start + 1 < 3 or attack_end + 1 >= len(behaviors):
            continue
        first_reduction = behaviors[attack_end + 1]
        if first_reduction["direction"] != -1 or first_reduction["low"] >= behaviors[attack_end]["low"]:
            continue
        reduction_end = attack_end + 1
        while reduction_end + 1 < len(behaviors) and behaviors[reduction_end + 1]["direction"] == -1:
            reduction_end += 1
        if reduction_end - attack_end < 3 or reduction_end + 1 >= len(behaviors):
            continue
        breakout = behaviors[reduction_end + 1]
        if breakout["direction"] != 1 or breakout["high"] <= behaviors[reduction_end]["high"]:
            continue
        start_date = str(behaviors[start]["start_time"])[:10]
        end_date = str(breakout["end_time"])[:10]
        if start_date < recent_start or end_date not in recent:
            continue
        matches.append({
            "up_attack_count": attack_end - start + 1,
            "down_reduction_count": reduction_end - attack_end,
            "attack_count": attack_end - start + 1,
            "reduction_count": reduction_end - attack_end,
            "attack_origin": round(float(behaviors[start]["low"]), 3),
            "break_price": round(float(breakout["high"]), 3),
            "structure_start_time": str(behaviors[start]["start_time"]),
            "structure_end_time": str(breakout["end_time"]),
            "signal_time": str(breakout["end_time"]),
            "structure_count": 1,
            "signal": "5分钟上涨结构",
        })
    return max(matches, key=lambda item: item["structure_end_time"]) if matches else None


def match_latest_sequence(bars: pd.DataFrame, frames: list[dict], trade_date: str) -> dict | None:
    """Require A{2,} R{2,} A at the newest frame, with no intervening other frame."""
    # Yellow frames are display-only and do not interrupt a behavior sequence.
    frames = [frame for frame in frames if frame.get("direction", 0)]
    if not frames or frames[-1]["direction"] != 1 or str(frames[-1]["end_time"])[:10] != trade_date:
        return None
    index = len(frames) - 2
    reduction_end = index
    while index >= 0 and frames[index]["direction"] == -1:
        index -= 1
    reductions = frames[index + 1:reduction_end + 1]
    if len(reductions) < 2:
        return None
    attack_end = index
    while index >= 0 and frames[index]["direction"] == 1:
        index -= 1
    attacks = frames[index + 1:attack_end + 1]
    if len(attacks) < 2:
        return None
    origin = attacks[0]["start_price"]
    # Include quiet bars between the previous attacks, reductions and resumed attack.
    retreat = bars.iloc[attacks[-1]["end_index"] + 1:frames[-1]["start_index"]]
    if retreat.empty:
        return None
    trough = float(retreat.low.min())
    if not np.isfinite(trough) or trough < origin:
        return None
    return {"attack_count": len(attacks), "reduction_count": len(reductions),
            "attack_origin": round(origin, 3), "pullback_low": round(trough, 3),
            "signal_time": str(frames[-1]["end_time"]), "resumed_start": str(frames[-1]["start_time"])}


def _recent_minute_bars(path: Path) -> pd.DataFrame:
    """Read the last ~50 sessions without parsing years of older minute rows."""
    with gzip.open(path, "rt", encoding="utf-8", newline="") as stream:
        header = next(stream)
        tail = deque(stream, maxlen=12000)
    minute = pd.read_csv(io.StringIO(header + "".join(tail)),
                         usecols=["datetime", "open", "high", "low", "close", "volume"])
    return aggregate_five_minutes(clean_minute_price_outliers(minute, path.name[:6]))


def _latest_trading_date() -> str:
    try:
        coverage = json.loads((BASE_DIR / "data" / "minute_file_coverage.json").read_text(encoding="utf-8"))
        dates = Counter(str(item.get("last_bar") or "")[:10] for item in coverage.values()
                        if isinstance(item, dict))
        majority = [day for day, count in dates.items() if day and count >= len(coverage) / 2]
        if majority:
            return max(majority)
    except (OSError, ValueError, AttributeError):
        pass
    daily_dates = watchlist_service.recent_market_dates(1)
    daily_date = daily_dates[-1] if daily_dates else ""
    try:
        status = json.loads((BASE_DIR / "data" / "minute_kline_status.json").read_text(encoding="utf-8"))
        minute_date = (str(status.get("target_bar") or "")[:10]
                       if status.get("state") in {"complete", "partial"} else "")
    except (OSError, ValueError):
        minute_date = ""
    return max(daily_date, minute_date)


def scan_all() -> dict:
    """Refresh the group using the daily filters; no minute structure required."""
    recent_dates = watchlist_service.recent_market_dates(30)
    if len(recent_dates) < 30:
        raise ValueError("本地日线没有可用交易日")
    trade_date = recent_dates[-1]
    stocks = []
    daily_scanned = 0
    for daily_path in sorted(RAW_DIR.glob("*_原始数据.csv")):
        code = daily_path.name[:6]
        name = daily_path.stem[7:].removesuffix("_原始数据")
        if not watchlist_service._is_main_board(code) or "ST" in name.upper():
            continue
        rows = [row for row in rising_structure_service._daily_rows(daily_path, 75)
                if row["date"] <= trade_date]
        if len(rows) < 21 or rows[-1]["date"] != trade_date:
            continue
        daily_scanned += 1
        if not daily_ma20_rising(rows):
            continue
        limit_up_date = recent_limit_up_date(rows, recent_dates)
        if not limit_up_date:
            continue
        pullback = recent_three_day_pullback(rows, recent_dates)
        if not pullback:
            continue
        gain = watchlist_service.low_to_latest_close_gain(rows, recent_dates)
        if gain is None:
            continue
        stocks.append({"code": code, "name": name, "trade_date": trade_date,
                       "limit_up_date": limit_up_date, "signal": "日K回调筛选", **pullback, **gain})
    stocks.sort(key=lambda stock: (stock["pullback_end_date"], stock["code"]), reverse=True)
    screen = {"updated_at": datetime.now().isoformat(timespec="seconds"),
              "trade_date": trade_date, "daily_scanned": daily_scanned,
              "daily_candidates": len(stocks), "stock_count": len(stocks), "lookback_days": 30,
              "window_start": recent_dates[0], "window_end": recent_dates[-1],
              "require_five_minute_structure": False,
              "main_board": True, "exclude_st": True, "daily_ma20_rising": True,
              "require_recent_limit_up": True, "limit_up_lookback_days": 30,
              "pullback_lookback_days": 12, "pullback_consecutive_days": 3,
              "pullback_window_start": recent_dates[-12],
              "gain_method": "lowest_low_to_latest_close",
              "pullback_sort": "pullback_end_date_desc",
              "limit_up_rule": "收盘价等于前收盘价×1.10，涨停价四舍五入至分"}
    watchlist_service.replace_five_minute_selection_group(stocks, screen)
    return screen
