"""Scan all locally available daily OHLC history for Chan chart structures.

Usage: python scan_chan_fractals.py
Unchanged source files reuse their previously computed structures.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import re
import threading
from datetime import date
from pathlib import Path


ROOT = Path(__file__).resolve().parent
RAW_DIR = ROOT / "个股" / "原始数据"
DEFAULT_OUTPUT = ROOT / "data" / "research" / "chan_fractals_all.csv"
STRUCTURE_OUTPUT = ROOT / "data" / "research" / "chan_structure_all.jsonl"
MANIFEST_OUTPUT = ROOT / "data" / "research" / "chan_structure_all_manifest.json"
ALGORITHM_VERSION = 4
FILE_PATTERN = re.compile(r"^(\d{6})_(.+?)_原始数据\.csv$")
FIELDS = ("code", "name", "type", "fractal_date", "confirmed_date", "price",
          "middle_start", "middle_end", "left_date", "right_date")
_cache_lock = threading.RLock()
_scan_lock = threading.Lock()
_cache_offsets: dict[str, tuple[int, int]] | None = None


def read_bars(path: Path) -> list[dict]:
    """Read the entire local daily history for one stock."""
    bars = []
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            try:
                day = date.fromisoformat(row["date"])
                high, low = float(row["high"]), float(row["low"])
                close = float(row["close"])
                if high < low or not low <= close <= high:
                    continue
            except (KeyError, TypeError, ValueError):
                continue
            bars.append({"date": day, "start": day, "end": day,
                         "high": high, "low": low, "close": close})
    return list({bar["date"]: bar for bar in sorted(bars, key=lambda bar: bar["date"])}.values())


def contains(a: dict, b: dict) -> bool:
    return ((a["high"] >= b["high"] and a["low"] <= b["low"])
            or (b["high"] >= a["high"] and b["low"] <= a["low"]))


def append_merged_bar(merged: list[dict], bar: dict) -> None:
    """Append one raw bar using the same inclusion rule as the full scan."""
    current = bar.copy()
    while merged and contains(merged[-1], current):
        prior = merged.pop()
        if merged:
            direction_up = prior["high"] > merged[-1]["high"] and prior["low"] > merged[-1]["low"]
        else:
            # A leading inclusive pair has no established direction.
            direction_up = current["close"] >= prior["close"]
        current["high"] = max(prior["high"], current["high"]) if direction_up else min(prior["high"], current["high"])
        current["low"] = max(prior["low"], current["low"]) if direction_up else min(prior["low"], current["low"])
        current["start"] = prior["start"]
    merged.append(current)


def merge_inclusions(bars: list[dict]) -> list[dict]:
    merged = []
    for bar in bars:
        append_merged_bar(merged, bar)
    return merged


def fractals(bars: list[dict], code: str, name: str) -> list[dict]:
    result = []
    for index in range(1, len(bars) - 1):
        left, middle, right = bars[index - 1:index + 2]
        is_bottom = (middle["high"] < left["high"] and middle["high"] < right["high"]
                     and middle["low"] < left["low"] and middle["low"] < right["low"])
        is_top = (middle["high"] > left["high"] and middle["high"] > right["high"]
                  and middle["low"] > left["low"] and middle["low"] > right["low"])
        if not (is_bottom or is_top):
            continue
        result.append({"code": code, "name": name, "type": "底分型" if is_bottom else "顶分型",
                       "fractal_date": middle["end"].isoformat(),
                       "confirmed_date": right["end"].isoformat(),
                       "price": middle["low"] if is_bottom else middle["high"],
                       "middle_start": middle["start"].isoformat(),
                       "middle_end": middle["end"].isoformat(),
                       "left_date": left["end"].isoformat(),
                       "right_date": right["end"].isoformat(),
                       "merged_index": index})
    return result


def select_fractals(candidates: list[dict]) -> list[dict]:
    """Alternate extrema and leave a whole merged K line between fractal windows."""
    selected = []
    for candidate in candidates:
        if not selected:
            selected.append(candidate)
            continue
        previous = selected[-1]
        if candidate["type"] == previous["type"]:
            more_extreme = (candidate["price"] > previous["price"] if candidate["type"] == "顶分型"
                            else candidate["price"] < previous["price"])
            if more_extreme:
                selected[-1] = candidate
        elif (candidate["merged_index"] - previous["merged_index"] >= 4
              and previous["right_date"] < candidate["left_date"]):
            selected.append(candidate)
    return selected


def make_pens(points: list[dict]) -> list[dict]:
    return [{"start_date": a["fractal_date"], "end_date": b["fractal_date"],
             "start_price": a["price"], "end_price": b["price"],
             "direction": "up" if a["type"] == "底分型" else "down"}
            for a, b in zip(points, points[1:])]


def make_segments(pens: list[dict]) -> list[dict]:
    """Build alternating swing segments from pen extrema, at least three pens each.

    This is a deterministic daily-chart convention. Full feature-sequence gap
    confirmation requires another level of recursive structure.
    """
    if len(pens) < 5:
        return []
    vertices = [(pens[0]["start_date"], pens[0]["start_price"])]
    vertices.extend((pen["end_date"], pen["end_price"]) for pen in pens)
    candidates = []
    for index in range(2, len(vertices) - 2):
        price = vertices[index][1]
        is_top = price > vertices[index - 2][1] and price > vertices[index + 2][1]
        is_bottom = price < vertices[index - 2][1] and price < vertices[index + 2][1]
        # Pen endpoints alternate top/bottom, so only the matching kind applies.
        kind = "top" if ((pens[0]["direction"] == "up") == (index % 2 == 1)) else "bottom"
        if (kind == "top" and is_top) or (kind == "bottom" and is_bottom):
            candidates.append((index, kind, price))
    pivots = [(0, "bottom" if pens[0]["direction"] == "up" else "top", vertices[0][1])]
    for point in candidates:
        previous = pivots[-1]
        if point[1] == previous[1]:
            if (point[2] > previous[2] if point[1] == "top" else point[2] < previous[2]):
                pivots[-1] = point
        elif point[0] - previous[0] >= 3:
            pivots.append(point)
    return [{"start_date": vertices[a[0]][0], "end_date": vertices[b[0]][0],
             "start_price": a[2], "end_price": b[2],
             "direction": "up" if a[1] == "bottom" else "down",
             "pen_count": b[0] - a[0]}
            for a, b in zip(pivots, pivots[1:])]


def make_centers(segments: list[dict]) -> list[dict]:
    """Mark maximal overlap of at least three consecutive confirmed segments."""
    centers = []
    index = 0
    while index + 2 < len(segments):
        group = segments[index:index + 3]
        low = max(min(item["start_price"], item["end_price"]) for item in group)
        high = min(max(item["start_price"], item["end_price"]) for item in group)
        if low >= high:
            index += 1
            continue
        end = index + 2
        while end + 1 < len(segments):
            next_low = max(low, min(segments[end + 1]["start_price"], segments[end + 1]["end_price"]))
            next_high = min(high, max(segments[end + 1]["start_price"], segments[end + 1]["end_price"]))
            if next_low >= next_high:
                break
            low, high = next_low, next_high
            end += 1
        centers.append({"start_date": segments[index]["start_date"],
                        "end_date": segments[end]["end_date"],
                        "low": low, "high": high, "segment_count": end - index + 1,
                        "start_segment": index, "end_segment": end})
        index = end + 1
    return centers


def make_trend_types(centers: list[dict]) -> list[dict]:
    """Classify nonoverlapping center chains as trends; isolated centers as ranges."""
    result = []
    index = 0
    while index < len(centers):
        direction = None
        if index + 1 < len(centers):
            if centers[index + 1]["low"] > centers[index]["high"]:
                direction = "上涨"
            elif centers[index + 1]["high"] < centers[index]["low"]:
                direction = "下跌"
        if direction:
            end = index + 1
            while end + 1 < len(centers):
                follows = (centers[end + 1]["low"] > centers[end]["high"] if direction == "上涨"
                           else centers[end + 1]["high"] < centers[end]["low"])
                if not follows:
                    break
                end += 1
            group = centers[index:end + 1]
            result.append({"type": direction, "start_date": group[0]["start_date"],
                           "end_date": group[-1]["end_date"], "center_count": len(group),
                           "low": min(item["low"] for item in group),
                           "high": max(item["high"] for item in group)})
            index = end + 1
        else:
            center = centers[index]
            result.append({"type": "盘整", "start_date": center["start_date"],
                           "end_date": center["end_date"], "center_count": 1,
                           "low": center["low"], "high": center["high"]})
            index += 1
    return result


def macd_area_prefix(bars: list[dict]) -> tuple[dict[str, int], list[float], list[float]]:
    """Return cumulative positive/negative MACD histogram areas."""
    dates = {}
    positive = [0.0]
    negative = [0.0]
    ema12 = ema26 = bars[0]["close"]
    dea = 0.0
    for index, bar in enumerate(bars):
        close = bar["close"]
        ema12 = ema12 * (11 / 13) + close * (2 / 13)
        ema26 = ema26 * (25 / 27) + close * (2 / 27)
        dif = ema12 - ema26
        dea = dea * (8 / 10) + dif * (2 / 10)
        histogram = 2 * (dif - dea)
        dates[bar["date"].isoformat()] = index
        positive.append(positive[-1] + max(histogram, 0.0))
        negative.append(negative[-1] + max(-histogram, 0.0))
    return dates, positive, negative


def make_divergences(bars: list[dict], pens: list[dict], trend_types: list[dict]) -> list[dict]:
    if not bars:
        return []
    dates, positive, negative = macd_area_prefix(bars)
    result = []
    for index in range(2, len(pens)):
        a, c = pens[index - 2], pens[index]
        up = c["direction"] == "up"
        if not (c["end_price"] > a["end_price"] if up else c["end_price"] < a["end_price"]):
            continue
        try:
            a_start, a_end = dates[a["start_date"]], dates[a["end_date"]]
            c_start, c_end = dates[c["start_date"]], dates[c["end_date"]]
        except KeyError:
            continue
        prefix = positive if up else negative
        a_area = prefix[a_end + 1] - prefix[a_start]
        c_area = prefix[c_end + 1] - prefix[c_start]
        if a_area <= 1e-9 or c_area >= a_area:
            continue
        covering = next((item for item in trend_types
                         if item["start_date"] <= c["end_date"] <= item["end_date"]), None)
        context = ("趋势" if covering and covering["type"] == ("上涨" if up else "下跌")
                   else "盘整" if covering and covering["type"] == "盘整" else "局部")
        result.append({"type": "顶背离" if up else "底背离",
                       "context": context, "level": "笔级MACD",
                       "date": c["end_date"], "price": c["end_price"],
                       "a_start": a["start_date"], "a_end": a["end_date"],
                       "c_start": c["start_date"], "c_end": c["end_date"],
                       "a_area": round(a_area, 4), "c_area": round(c_area, 4),
                       "area_ratio": round(c_area / a_area, 4)})
    return result


def structure_for_bars(bars: list[dict], code: str, name: str) -> dict:
    merged = merge_inclusions(bars)
    points = select_fractals(fractals(merged, code, name))
    pens = make_pens(points)
    segments = make_segments(pens)
    centers = make_centers(segments)
    trend_types = make_trend_types(centers)
    return {"code": code, "name": name,
            "history_start": bars[0]["date"].isoformat() if bars else None,
            "history_end": bars[-1]["date"].isoformat() if bars else None,
            "fractals": points, "pens": pens, "segments": segments,
            "centers": centers, "trend_types": trend_types,
            "divergences": make_divergences(bars, pens, trend_types)}


def _build_offsets(path: Path, has_header: bool = True) -> dict[str, tuple[int, int]]:
    offsets = {}
    if not path.exists():
        return offsets
    with path.open("rb") as handle:
        if has_header:
            handle.readline()
        while True:
            start = handle.tell()
            line = handle.readline()
            if not line:
                break
            code = (line[9:15] if not has_header else line[:6]).decode("ascii", errors="ignore")
            if re.fullmatch(r"\d{6}", code):
                if code in offsets:
                    offsets[code] = (offsets[code][0], handle.tell())
                else:
                    offsets[code] = (start, handle.tell())
    return offsets


def stock_events(code: str) -> dict:
    """Read previously calculated fractals; never calculate during stock switching."""
    global _cache_offsets
    if not re.fullmatch(r"\d{6}", code):
        raise ValueError("股票代码必须为6位数字")
    with _cache_lock:
        if _cache_offsets is None:
            _cache_offsets = _build_offsets(STRUCTURE_OUTPUT, has_header=False)
        bounds = _cache_offsets.get(code)
        if not bounds:
            return {"code": code, "events": []}
        with STRUCTURE_OUTPUT.open("rb") as handle:
            handle.seek(bounds[0])
            payload = json.loads(handle.read(bounds[1] - bounds[0]).decode("utf-8"))
    payload["events"] = payload["fractals"]
    return payload


def _scan_impl(output: Path) -> None:
    files = []
    for path in sorted(RAW_DIR.glob("*_原始数据.csv")):
        match = FILE_PATTERN.match(path.name)
        if match:
            files.append((path, *match.groups()))
    signatures = {code: [path.stat().st_mtime_ns, path.stat().st_size, path.name]
                  for path, code, _ in files}
    try:
        manifest = json.loads(MANIFEST_OUTPUT.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        manifest = {}
    old_signatures = manifest.get("sources", {}) if manifest.get("version") == ALGORITHM_VERSION else {}
    old_offsets = _build_offsets(STRUCTURE_OUTPUT, has_header=False) if STRUCTURE_OUTPUT.exists() else {}
    if (output.exists() and STRUCTURE_OUTPUT.exists() and old_offsets
            and old_signatures == signatures):
        with _cache_lock:
            global _cache_offsets
            _cache_offsets = old_offsets
        print(f"缠论全历史缓存未变化：{len(files)} 只股票", flush=True)
        return
    counts = {"底分型": 0, "顶分型": 0}
    errors = []
    reused = 0
    output.parent.mkdir(parents=True, exist_ok=True)
    temp = output.with_name(f".{output.stem}_{os.getpid()}.tmp")
    structure_temp = STRUCTURE_OUTPUT.with_name(f".{STRUCTURE_OUTPUT.stem}_{os.getpid()}.tmp")
    with temp.open("w", encoding="utf-8-sig", newline="") as handle, structure_temp.open("w", encoding="utf-8", newline="") as structure_handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS)
        writer.writeheader()
        old_handle = STRUCTURE_OUTPUT.open("rb") if old_offsets else None
        try:
            for number, (path, code, name) in enumerate(files, 1):
                try:
                    if old_handle and old_signatures.get(code) == signatures[code] and code in old_offsets:
                        start, end = old_offsets[code]
                        old_handle.seek(start)
                        structure = json.loads(old_handle.read(end - start).decode("utf-8"))
                        reused += 1
                    else:
                        structure = structure_for_bars(read_bars(path), code, name)
                    structure_handle.write(json.dumps(structure, ensure_ascii=False, separators=(",", ":")) + "\n")
                    for row in structure["fractals"]:
                        writer.writerow({field: row[field] for field in FIELDS})
                        counts[row["type"]] += 1
                except (OSError, UnicodeError, ValueError, KeyError) as exc:
                    errors.append(f"{code}: {exc}")
                    signatures.pop(code, None)
                if number % 500 == 0:
                    print(f"缠论全历史扫描 {number}/{len(files)}，复用 {reused}", flush=True)
        finally:
            if old_handle:
                old_handle.close()
    offsets = _build_offsets(structure_temp, has_header=False)
    manifest_temp = MANIFEST_OUTPUT.with_name(f".{MANIFEST_OUTPUT.stem}_{os.getpid()}.tmp")
    manifest_temp.write_text(json.dumps({"version": ALGORITHM_VERSION, "sources": signatures}, ensure_ascii=False), encoding="utf-8")
    with _cache_lock:
        os.replace(temp, output)
        os.replace(structure_temp, STRUCTURE_OUTPUT)
        os.replace(manifest_temp, MANIFEST_OUTPUT)
        _cache_offsets = offsets
    print(f"扫描股票: {len(files)}；复用 {reused}；底分型: {counts['底分型']}；顶分型: {counts['顶分型']}")
    print(f"结果: {output}")
    if errors:
        print(f"失败文件: {len(errors)}; 示例: {'; '.join(errors[:5])}")


def scan(output: Path) -> None:
    with _scan_lock:
        _scan_impl(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    scan(parser.parse_args().output)
