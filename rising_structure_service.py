"""日线上涨结构及回调末端的五分钟反转扫描。阈值是可调整的首版规则。"""
from __future__ import annotations

import gzip
import json
from datetime import datetime
from pathlib import Path

import pandas as pd

import watchlist_service

BASE_DIR = Path(__file__).resolve().parent
RAW_DIR = BASE_DIR / "个股" / "原始数据"
MINUTE_DIR = BASE_DIR / "个股" / "一分钟"


def _daily_rows(path: Path, count: int = 160) -> list[dict]:
    rows = watchlist_service._latest_rows(path, count) or []
    result = []
    for row in rows:
        try:
            result.append({key: float(row[key]) for key in ("open", "high", "low", "close", "volume")}
                          | {"date": row["date"]})
        except (KeyError, TypeError, ValueError):
            continue
    return result


def _daily_candidate(rows: list[dict]) -> dict | None:
    if len(rows) < 35:
        return None
    structures = daily_structure_boxes(rows)
    if not structures:
        return None
    return {"structures": structures, "trade_date": rows[-1]["date"]}


def daily_structure_boxes(rows: list[dict]) -> list[dict]:
    """Split recent moves at qualifying upward starts, then follow each pullback to its trough."""
    if len(rows) < 35:
        return []
    n = len(rows)
    starts = []
    for start in range(max(3, n - 100), n - 3):
        current, previous = rows[start], rows[start - 1]
        if current["close"] <= current["open"] or previous["close"] >= previous["open"]:
            continue
        origin = current["low"]
        peak = start
        top = current["high"]
        for index in range(start + 1, min(start + 8, n)):
            if rows[index]["low"] < top * 0.93:
                break
            if rows[index]["high"] > top:
                peak, top = index, rows[index]["high"]
        if peak <= start:
            continue
        if rows[peak]["high"] < origin * 1.08:
            continue
        if max(row["close"] for row in rows[start:min(start + 4, n)]) < current["close"] * 1.02:
            continue
        baseline = sum(row["volume"] for row in rows[start - 3:start]) / 3
        rise_volumes = sorted((row["volume"] for row in rows[start:peak + 1]), reverse=True)
        if not baseline or sum(rise_volumes[:2]) / min(2, len(rise_volumes)) < baseline * 1.15:
            continue
        starts.append((start, peak, current["volume"] / baseline))
    # Several green days can launch the same short impulse. Prefer its quietest origin.
    by_peak = {}
    for start, peak, start_volume_ratio in starts:
        previous = by_peak.get(peak)
        if previous is None or (start_volume_ratio, -start) < (previous[1], -previous[0]):
            by_peak[peak] = (start, start_volume_ratio)
    starts = sorted((start, peak, by_peak[peak][1]) for peak, (start, _) in by_peak.items())

    def valid_retreat(origin: int, peak: int, end: int) -> bool:
        if end - peak < 2:
            return False
        retreat = rows[peak + 1:end + 1]
        late_volume = sum(row["volume"] for row in retreat[-2:]) / min(2, len(retreat))
        return (min(row["low"] for row in retreat) > rows[origin]["low"]
                and rows[end]["close"] < rows[peak]["close"] * 0.98
                and late_volume <= max(row["volume"] for row in retreat) * 0.85)

    candidates = []
    for start, initial_peak, ratio in starts:
        segments = [(start, initial_peak)]
        peak = initial_peak
        while True:
            following = [(other_start, other_peak) for other_start, other_peak, _ in starts if other_start > peak]
            next_start, next_peak = following[0] if following else (None, None)
            limit = next_start - 1 if next_start is not None else n - 1
            ends = [index for index in range(peak + 2, limit + 1)
                    if index + 1 < n and rows[index + 1]["close"] > rows[index]["close"]]
            if next_start is not None:
                ends = [limit] + [index for index in ends if index != limit]
            elif rows[limit]["close"] < rows[limit - 1]["close"]:
                ends.append(limit)
            end = next((index for index in ends if valid_retreat(start, peak, index)), None)
            if end is not None:
                candidates.append((start, peak, end, segments, ratio))
                break
            if (next_start is None or next_peak <= peak
                    or rows[next_start]["low"] < rows[peak]["high"] * 0.9
                    or min(row["low"] for row in rows[peak + 1:next_start + 1]) <= rows[start]["low"]):
                break
            segments.append((next_start, next_peak))
            peak = next_peak

    # The same completed structure can be found from several starts. Keep the
    # quieter launch while retaining subsequent short impulses inside the box.
    by_end = {}
    for candidate in candidates:
        end = candidate[2]
        old = by_end.get(end)
        if old is None or (candidate[4], -candidate[0]) < (old[4], -old[0]):
            by_end[end] = candidate
    result = []
    for start, peak, end, segments, _ in sorted(by_end.values(), key=lambda item: item[0]):
        up_rows = [row for segment_start, segment_peak in segments
                   for row in rows[segment_start:segment_peak + 1]]
        retreat = rows[peak + 1:end + 1]
        up_avg = sum(row["volume"] for row in up_rows) / len(up_rows)
        down_avg = sum(row["volume"] for row in retreat) / len(retreat)
        result.append({"start_date": rows[start]["date"], "peak_date": rows[peak]["date"],
                       "origin_low": rows[start]["low"],
                       "up_segments": [{"start_date": rows[a]["date"], "end_date": rows[b]["date"],
                                        "low": min(row["low"] for row in rows[a:b + 1]),
                                        "high": max(row["high"] for row in rows[a:b + 1])}
                                       for a, b in segments],
                       "pullback_start_date": rows[peak + 1]["date"], "end_date": rows[end]["date"],
                       "low": min(row["low"] for row in rows[start:end + 1]),
                       "high": max(row["high"] for row in rows[start:end + 1]),
                       "up_low": min(row["low"] for row in up_rows),
                       "up_high": max(row["high"] for row in up_rows),
                       "down_low": min(row["low"] for row in retreat),
                       "down_high": max(row["high"] for row in retreat),
                       "up_avg_volume": round(up_avg, 2), "down_avg_volume": round(down_avg, 2),
                       "volume_confirmed": down_avg < up_avg})
    return sorted(result + daily_reversal_boxes(rows), key=lambda box: (box["start_date"], box["end_date"]))


def daily_reversal_boxes(rows: list[dict]) -> list[dict]:
    """先跌后涨：反弹高点越过起跌日高点，保留跌段和涨段的边界。"""
    boxes = []
    n = len(rows)
    for start in range(max(0, n - 100), n - 4):
        if start == 0 or rows[start]["close"] >= rows[start - 1]["close"]:
            continue
        origin_high = rows[start]["high"]
        # 至少两根日K形成下行，随后在八个交易日内完成上攻。
        for trough in range(start + 2, min(start + 6, n - 1)):
            fall = rows[start:trough + 1]
            if rows[trough]["low"] != min(row["low"] for row in fall):
                continue
            if rows[trough]["low"] > origin_high * 0.95:
                continue
            if (rows[start + 1]["close"] >= rows[start]["close"]
                    or max(row["close"] for row in fall[1:]) > rows[start]["close"]):
                continue
            if sum(fall[i]["close"] < fall[i - 1]["close"] for i in range(1, len(fall))) < 2:
                continue
            peak = max(range(trough + 1, min(trough + 9, n)), key=lambda i: rows[i]["high"])
            if rows[peak]["high"] <= origin_high or rows[peak]["high"] < rows[trough]["low"] * 1.08:
                continue
            if any(rows[i]["low"] < rows[trough]["low"] for i in range(trough + 1, peak + 1)):
                continue
            end = peak
            for i in range(peak + 1, min(peak + 3, n)):
                if rows[i]["low"] <= rows[trough]["low"] or rows[i]["close"] < origin_high * 0.97:
                    break
                end = i
            rise = rows[trough + 1:peak + 1]
            fall_avg = sum(row["volume"] for row in fall) / len(fall)
            rise_avg = sum(row["volume"] for row in rise) / len(rise)
            boxes.append({"type": "fall_then_breakout", "start_date": rows[start]["date"],
                          "trough_date": rows[trough]["date"], "peak_date": rows[peak]["date"],
                          "end_date": rows[end]["date"], "origin_high": origin_high,
                          "origin_low": rows[trough]["low"],
                          "fall_low": rows[trough]["low"],
                          "fall_high": max(row["high"] for row in fall),
                          "up_low": min(row["low"] for row in rise),
                          "up_high": rows[peak]["high"],
                          "low": min(row["low"] for row in rows[start:end + 1]),
                          "high": max(row["high"] for row in rows[start:end + 1]),
                          "up_segments": [{"start_date": rows[trough + 1]["date"],
                                           "end_date": rows[peak]["date"],
                                           "low": min(row["low"] for row in rise),
                                           "high": rows[peak]["high"]}],
                          "up_avg_volume": round(rise_avg, 2),
                          "down_avg_volume": round(fall_avg, 2),
                          "volume_confirmed": rise_avg > fall_avg})
            break
    # 同一个突破高点只留最早、最完整的起跌段。
    by_peak = {}
    for box in boxes:
        old = by_peak.get(box["peak_date"])
        if old is None or box["start_date"] < old["start_date"]:
            by_peak[box["peak_date"]] = box
    return sorted(by_peak.values(), key=lambda box: box["start_date"])


def daily_down_boxes(rows: list[dict]) -> list[dict]:
    """Two falling patterns: failed rebound, or a rally followed by a break of its origin."""
    n = len(rows)
    if n < 12:
        return []
    boxes = []
    first = max(3, n - 100)
    # A short rally followed by consecutive lower closes below its starting low.
    for start in range(first, n - 5):
        if rows[start]["close"] <= rows[start]["open"] or rows[start - 1]["close"] >= rows[start - 1]["open"]:
            continue
        peak = max(range(start + 1, min(start + 7, n - 3)), key=lambda index: rows[index]["high"])
        if rows[peak]["high"] < rows[start]["low"] * 1.05:
            continue
        end = peak
        for index in range(peak + 1, min(peak + 12, n)):
            if rows[index]["close"] >= rows[index - 1]["close"]:
                break
            end = index
        if end - peak < 3 or min(row["low"] for row in rows[peak + 1:end + 1]) >= rows[start]["low"]:
            continue
        boxes.append({"type": "break_origin", "start_date": rows[start]["date"],
                      "peak_date": rows[peak]["date"], "end_date": rows[end]["date"],
                      "low": min(row["low"] for row in rows[start:end + 1]),
                      "high": max(row["high"] for row in rows[start:end + 1]),
                      "origin_low": rows[start]["low"]})
    # Falling volume expansion, followed by a lighter rebound below the fall origin.
    for start in range(first, n - 5):
        if rows[start]["close"] >= rows[start]["open"]:
            continue
        baseline = sum(row["volume"] for row in rows[start - 3:start]) / 3
        if baseline <= 0:
            continue
        for bottom in range(start + 2, min(start + 8, n - 2)):
            fall = rows[start:bottom + 1]
            fall_volume = sum(row["volume"] for row in fall) / len(fall)
            if (rows[bottom]["low"] > rows[start]["high"] * 0.94
                    or fall_volume < baseline * 1.15):
                continue
            for end in range(bottom + 1, min(bottom + 5, n)):
                rebound = rows[bottom + 1:end + 1]
                rebound_volume = sum(row["volume"] for row in rebound) / len(rebound)
                if (rows[end]["close"] > rows[bottom]["close"]
                        and max(row["high"] for row in rebound) < rows[start]["high"]
                        and rebound_volume < fall_volume * 0.85):
                    boxes.append({"type": "weak_rebound", "start_date": rows[start]["date"],
                                  "peak_date": rows[bottom]["date"], "end_date": rows[end]["date"],
                                  "low": min(row["low"] for row in rows[start:end + 1]),
                                  "high": max(row["high"] for row in rows[start:end + 1]),
                                  "origin_low": rows[start]["high"]})
                    break
            else:
                continue
            break
    # Prefer a break-of-origin pattern when two boxes describe the same decline.
    boxes.sort(key=lambda box: (box["end_date"], box["type"] == "break_origin"))
    deduped = []
    for box in boxes:
        if deduped and box["start_date"] <= deduped[-1]["end_date"] and box["end_date"] >= deduped[-1]["end_date"]:
            if box["type"] == "break_origin":
                deduped[-1] = box
            continue
        deduped.append(box)
    return deduped


def _breaks_previous_uptrend(up_boxes: list[dict], down_boxes: list[dict], latest_date: str) -> bool:
    recent_down = [box for box in down_boxes if box["end_date"] >= latest_date]
    if not recent_down:
        return False
    down = recent_down[-1]
    previous = [box for box in up_boxes if box["end_date"] < down["start_date"]]
    return bool(previous and down["low"] < previous[-1]["origin_low"])


def get_daily_structures(code: str) -> dict:
    if not code.isdigit() or len(code) != 6:
        raise ValueError("股票代码必须为6位数字")
    path = next(RAW_DIR.glob(f"{code}_*_原始数据.csv"), None)
    if path is None:
        return {"code": code, "structures": []}
    rows = _daily_rows(path)
    structures = daily_structure_boxes(rows)
    return {"code": code, "structures": _attach_minute_confirmation(code, rows, structures),
            "down_structures": daily_down_boxes(rows)}


def _minute_up_structure(bars: list[dict]) -> str | None:
    """Find a five-minute rising impulse with lighter pullback above its origin."""
    if len(bars) < 8:
        return None
    for start in range(3, len(bars) - 3):
        baseline = sum(bar["volume"] for bar in bars[start - 3:start]) / 3
        if baseline <= 0:
            continue
        for peak in range(start + 2, min(start + 7, len(bars) - 1)):
            rise = bars[start:peak + 1]
            up_volume = sum(bar["volume"] for bar in rise) / len(rise)
            if (bars[peak]["close"] < bars[start]["open"] * 1.015
                    or up_volume < baseline * 1.5
                    or sum(bars[index]["close"] > bars[index - 1]["close"]
                           for index in range(start + 1, peak + 1)) < 2):
                continue
            for end in range(peak + 1, min(peak + 4, len(bars))):
                retreat = bars[peak + 1:end + 1]
                down_volume = sum(bar["volume"] for bar in retreat) / len(retreat)
                if (bars[end]["close"] < bars[peak]["close"]
                        and min(bar["low"] for bar in retreat) >= bars[start]["low"]
                        and down_volume < up_volume * 0.65):
                    return str(bars[end]["datetime"])
    return None


def _minute_up_dates(code: str, dates: set[str]) -> dict[str, str]:
    path = MINUTE_DIR / f"{code}_1min.csv.gz"
    if not path.exists() or not dates:
        return {}
    try:
        frame = pd.read_csv(path, compression="gzip",
                            usecols=["datetime", "open", "high", "low", "close", "volume"])
        frame = frame[frame["datetime"].str[:10].isin(dates)].copy()
        if frame.empty:
            return {}
        frame["datetime"] = pd.to_datetime(frame["datetime"])
        found = {}
        for trade_date, day in frame.groupby(frame["datetime"].dt.strftime("%Y-%m-%d")):
            bars = (day.set_index("datetime").resample("5min", origin="start_day", offset="9h30min")
                    .agg({"open": "first", "high": "max", "low": "min", "close": "last", "volume": "sum"})
                    .dropna(subset=["open", "close"]).reset_index().to_dict("records"))
            signal = _minute_up_structure(bars)
            if signal:
                found[trade_date] = signal
        return found
    except (OSError, EOFError, KeyError, ValueError):
        return {}


def _attach_minute_confirmation(code: str, rows: list[dict], structures: list[dict]) -> list[dict]:
    requested = {row["date"] for row in rows for box in structures
                 if any(segment["start_date"] <= row["date"] <= segment["end_date"]
                        for segment in box["up_segments"])}
    minute_dates = _minute_up_dates(code, requested)
    for box in structures:
        matches = {date: signal for date, signal in minute_dates.items()
                   if any(segment["start_date"] <= date <= segment["end_date"]
                          for segment in box["up_segments"])}
        box["minute_up_date"] = min(matches) if matches else ""
        box["minute_up_time"] = matches[box["minute_up_date"]] if matches else ""
    return structures


def _minute_signal(code: str, trade_date: str) -> str | None:
    """Return the confirmed five-minute up/pullback timestamp, if present today."""
    path = MINUTE_DIR / f"{code}_1min.csv.gz"
    if not path.exists():
        return None
    try:
        with gzip.open(path, "rt", encoding="utf-8") as stream:
            frame = pd.read_csv(stream, usecols=["datetime", "open", "high", "low", "close", "volume"])
        frame = frame[frame["datetime"].str[:10] == trade_date].copy()
        if len(frame) < 25:
            return None
        frame["datetime"] = pd.to_datetime(frame["datetime"])
        bars = (frame.set_index("datetime").resample("5min", origin="start_day", offset="9h30min")
                .agg({"open": "first", "high": "max", "low": "min", "close": "last", "volume": "sum"})
                .dropna(subset=["open", "close"]).reset_index().to_dict("records"))
    except (OSError, EOFError, KeyError, ValueError):
        return None
    # A falling leg with volume, then a weak rebound below its origin; subsequently
    # a stronger rising leg and a light-volume pullback holding that new origin.
    for down_start in range(max(0, len(bars) - 35), len(bars) - 6):
        for down_end in range(down_start + 2, min(down_start + 7, len(bars) - 4)):
            fall = bars[down_start + 1:down_end + 1]
            if bars[down_end]["close"] >= bars[down_start]["close"] * 0.995:
                continue
            fall_vol = sum(bar["volume"] for bar in fall) / len(fall)
            for rebound_end in range(down_end + 1, min(down_end + 4, len(bars) - 3)):
                rebound = bars[down_end + 1:rebound_end + 1]
                if max(bar["high"] for bar in rebound) >= bars[down_start]["high"]:
                    continue
                if sum(bar["volume"] for bar in rebound) / len(rebound) >= fall_vol * 0.9:
                    continue
                for up_end in range(rebound_end + 2, min(rebound_end + 7, len(bars) - 1)):
                    rise = bars[rebound_end + 1:up_end + 1]
                    rise_vol = sum(bar["volume"] for bar in rise) / len(rise)
                    if bars[up_end]["close"] <= bars[rebound_end]["close"] * 1.006 or rise_vol < fall_vol * 0.8:
                        continue
                    for back_end in range(up_end + 1, min(up_end + 4, len(bars))):
                        back = bars[up_end + 1:back_end + 1]
                        if (sum(bar["volume"] for bar in back) / len(back) <= rise_vol * 0.85
                                and min(bar["low"] for bar in back) >= bars[rebound_end]["close"] * 0.997
                                and bars[back_end]["close"] < bars[up_end]["close"]):
                            return str(bars[back_end]["datetime"])
    return None


def scan_all() -> dict:
    market_dates = watchlist_service.recent_market_dates(1)
    latest_date = market_dates[-1] if market_dates else ""
    try:
        status = json.loads((BASE_DIR / "data" / "sync_status.json").read_text(encoding="utf-8"))
        completed = str(status.get("last_completed_trade_date") or "")
        if completed and completed < latest_date:
            latest_date = completed
    except (OSError, ValueError):
        pass
    if not latest_date:
        raise ValueError("本地日线没有可用交易日")
    stocks, scanned, daily_candidates = [], 0, 0
    for path in RAW_DIR.glob("*_原始数据.csv"):
        code = path.name[:6]
        name = path.stem[7:].removesuffix("_原始数据")
        if (not code.isdigit() or not watchlist_service._is_main_board(code)
                or "ST" in name.upper()):
            continue
        rows = [row for row in _daily_rows(path) if row["date"] <= latest_date]
        if not rows or rows[-1]["date"] != latest_date:
            continue
        scanned += 1
        finding = _daily_candidate(rows)
        if not finding:
            continue
        recent_start = rows[max(0, len(rows) - 20)]["date"]
        recent_structures = [item for item in finding["structures"] if item["end_date"] >= recent_start]
        if not recent_structures:
            continue
        daily_candidates += 1
        qualified = [item for item in _attach_minute_confirmation(code, rows, recent_structures)
                     if item["minute_up_date"]]
        if not qualified:
            continue
        down_structures = daily_down_boxes(rows)
        if _breaks_previous_uptrend(finding["structures"], down_structures,
                                   rows[max(0, len(rows) - 10)]["date"]):
            continue
        recent_down = [item for item in down_structures
                       if item["end_date"] >= rows[max(0, len(rows) - 10)]["date"]]
        current = qualified[-1]["end_date"] == latest_date
        signal_time = _minute_signal(code, latest_date) if current else None
        stocks.append({"code": code, "name": name, "trade_date": latest_date,
                       "structure_count": len(qualified),
                       "volume_confirmed_count": sum(item["volume_confirmed"] for item in qualified),
                       "down_structure_count": len(recent_down),
                       "minute_up_date": qualified[-1]["minute_up_date"],
                       "signal": "分时上涨结构" if signal_time else (
                           "先跌后涨·突破起跌点" if current and qualified[-1].get("type") == "fall_then_breakout"
                           else "日线缩量回调" if current and qualified[-1]["volume_confirmed"]
                           else "日线回调·量未确认" if current
                           else "下跌结构·守住上涨起点" if recent_down else "近期上涨结构"),
                       "signal_time": signal_time or ""})
    stocks.sort(key=lambda item: (item["signal"] == "分时上涨结构", item["signal"] == "日线缩量回调",
                                  item["volume_confirmed_count"], item["structure_count"]), reverse=True)
    screen = {"updated_at": datetime.now().isoformat(timespec="seconds"), "trade_date": latest_date,
              "lookback_bars": 100, "recent_window_days": 20,
              "scanned": scanned, "daily_candidate_count": daily_candidates, "stock_count": len(stocks),
              "current_pullback_count": sum(item["signal"] in {"分时上涨结构", "日线缩量回调", "日线回调·量未确认"} for item in stocks),
              "down_watch_count": sum(item["signal"] == "下跌结构·守住上涨起点" for item in stocks),
              "volume_confirmed_count": sum(item["volume_confirmed_count"] > 0 for item in stocks),
              "minute_signal_count": sum(item["signal"] == "分时上涨结构" for item in stocks)}
    watchlist_service.replace_rising_structure_group(stocks, screen)
    return screen


if __name__ == "__main__":
    import json
    print(json.dumps(scan_all(), ensure_ascii=False))
