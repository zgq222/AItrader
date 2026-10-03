"""Apply the user's position sizing rule to the latest 30 index trading days."""

import math
from bisect import bisect_right

import pandas as pd


LOOKBACK = 30
EDGE_FRACTION = 0.1
FLIGHT_WINDOWS = (10, 30, 90)
FLIGHT_AVERAGES = (5, 10, 20, 30)


def assess_market_position(frame):
    result = {"available": False, "code": "000001", "name": "上证指数",
              "lookback": LOOKBACK, "source": "本地指数日线 · 最新收盘价"}
    if frame is None or not {"date", "high", "low", "close"}.issubset(frame.columns):
        return {**result, "reason": "上证指数日线尚未就绪"}
    rows = frame[["date", "high", "low", "close"]].copy()
    rows["date"] = pd.to_datetime(rows["date"], errors="coerce").dt.normalize()
    rows = rows.dropna(subset=["date"]).sort_values("date", kind="stable").drop_duplicates("date", keep="last").tail(LOOKBACK).reset_index(drop=True)
    if len(rows) < LOOKBACK:
        return {**result, "reason": f"上证指数日线不足{LOOKBACK}个交易日（现有{len(rows)}日）"}
    for column in ("high", "low", "close"):
        rows[column] = pd.to_numeric(rows[column], errors="coerce")
    if not all(math.isfinite(float(value)) and value > 0
               for column in ("high", "low", "close") for value in rows[column]):
        return {**result, "reason": "最近30个交易日存在无效点位，暂不判断仓位"}
    if ((rows["low"] > rows["high"]) | (rows["close"] < rows["low"]) |
            (rows["close"] > rows["high"])).any():
        return {**result, "reason": "最近30个交易日日线价格范围异常，暂不判断仓位"}
    latest = rows.iloc[-1]
    low, high, close = float(rows["low"].min()), float(rows["high"].max()), float(latest["close"])
    lower = low + (high - low) * EDGE_FRACTION
    upper = high - (high - low) * EDGE_FRACTION
    result.update({"date": latest["date"].strftime("%Y-%m-%d"),
                   "start_date": rows.iloc[0]["date"].strftime("%Y-%m-%d"),
                   "days": len(rows), "close": close, "low": low, "high": high,
                   "low_date": rows.loc[rows["low"].idxmin(), "date"].strftime("%Y-%m-%d"),
                   "high_date": rows.loc[rows["high"].idxmax(), "date"].strftime("%Y-%m-%d"),
                   "lower_threshold": lower, "upper_threshold": upper})
    if high == low:
        return {**result, "reason": "最近30个交易日最高点与最低点相同，无法划分区间"}
    band = "low" if close < lower else "middle" if close < upper else "high"
    label, position, position_label = {
        "low": ("低位", 1.0, "满仓"),
        "middle": ("中位", 2 / 3, "2/3仓"),
        "high": ("高位", 1 / 3, "1/3仓"),
    }[band]
    return {**result, "available": True, "band": band, "band_label": label,
            "position": position, "position_label": position_label,
            "range_percent": (close - low) / (high - low) * 100}


def flight_intervals(levels, close):
    """Use distinct sorted levels; at an exact level, choose the interval above it."""
    split = bisect_right([level["price"] for level in levels], close)

    def level_at(index):
        return levels[index] if 0 <= index < len(levels) else None

    def interval(support_index, resistance_index):
        support, resistance = level_at(support_index), level_at(resistance_index)
        return {"support": support, "resistance": resistance,
                "complete": support is not None and resistance is not None}

    return {"first": interval(split - 1, split),
            "second": interval(split - 2, split + 1),
            "touching": next((level for level in levels if level["price"] == close), None)}


def assess_flight_height(frame):
    result = {"available": False, "code": "000001", "name": "上证指数",
              "source": "本地指数日线 · 最新收盘价", "lookback": 90}
    if frame is None or not {"date", "high", "low", "close"}.issubset(frame.columns):
        return {**result, "reason": "上证指数日线尚未就绪"}
    rows = frame[["date", "high", "low", "close"]].copy()
    rows["date"] = pd.to_datetime(rows["date"], errors="coerce").dt.normalize()
    rows = rows.dropna(subset=["date"]).sort_values("date", kind="stable").drop_duplicates("date", keep="last").tail(90).reset_index(drop=True)
    if len(rows) < 90:
        return {**result, "reason": f"飞行高度需要90个交易日日线（现有{len(rows)}日）"}
    for column in ("high", "low", "close"):
        rows[column] = pd.to_numeric(rows[column], errors="coerce")
    if not all(math.isfinite(float(value)) and value > 0
               for column in ("high", "low", "close") for value in rows[column]):
        return {**result, "reason": "最近90个交易日存在无效点位，暂不计算震荡区间"}
    if ((rows["low"] > rows["high"]) | (rows["close"] < rows["low"]) |
            (rows["close"] > rows["high"])).any():
        return {**result, "reason": "最近90个交易日日线价格范围异常，暂不计算震荡区间"}
    date = rows.iloc[-1]["date"].strftime("%Y-%m-%d")
    references = []
    for window in FLIGHT_WINDOWS:
        recent = rows.tail(window)
        for column, label in (("high", "最高点"), ("low", "最低点")):
            index = recent[column].idxmax() if column == "high" else recent[column].idxmin()
            references.append({"label": f"{window}日{label}", "price": round(float(recent.loc[index, column]), 3),
                               "date": recent.loc[index, "date"].strftime("%Y-%m-%d"),
                               "window": window, "kind": column})
    for window in FLIGHT_AVERAGES:
        references.append({"label": f"MA{window}", "price": round(float(rows.tail(window)["close"].mean()), 3),
                           "date": date, "window": window, "kind": "ma"})
    # Index points are shown to 0.001 precision. Merge identical levels while
    # keeping all ten original sources; duplicates must not consume a layer.
    grouped = {}
    for reference in references:
        price = reference["price"]
        grouped.setdefault(price, {"price": price, "sources": []})["sources"].append(reference)
    levels = [grouped[price] for price in sorted(grouped)]
    close = round(float(rows.iloc[-1]["close"]), 3)
    return {**result, "available": True, "date": date,
            "start_date": rows.iloc[0]["date"].strftime("%Y-%m-%d"), "close": close,
            "references": references, "reference_count": len(references), "levels": levels,
            "level_count": len(levels), **flight_intervals(levels, close)}
