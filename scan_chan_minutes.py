"""Precompute Chan chart structures for local intraday K lines."""
from __future__ import annotations

import gzip
import json
import os
import re
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import pandas as pd

import scan_chan_fractals


ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "个股" / "一分钟"
CACHE = ROOT / "data" / "research" / "chan_minutes"
INTERVALS = (10, 15, 30)
VERSION = 1


def _target(code: str, interval: int) -> Path:
    return CACHE / str(interval) / f"{code}.json.gz"


def read_structure(code: str, interval: int) -> dict:
    if not re.fullmatch(r"\d{6}", code) or interval not in INTERVALS:
        raise ValueError("无效股票代码或分钟周期")
    path = _target(code, interval)
    if not path.exists():
        return {"code": code, "interval": interval, "available": False}
    with gzip.open(path, "rt", encoding="utf-8") as handle:
        return json.load(handle)


def _resample(frame: pd.DataFrame, interval: int) -> pd.DataFrame:
    frame = frame.set_index("datetime")
    return frame.groupby(frame.index.date, group_keys=False).apply(
        lambda day: day.resample(f"{interval}min", origin="start_day", offset="9h30min",
                                 label="left", closed="left")
        .agg({"high": "max", "low": "min", "close": "last"}).dropna(),
        include_groups=False,
    ).reset_index()


def _current(target: Path, signature: list[int]) -> bool:
    if not target.exists():
        return False
    try:
        with gzip.open(target, "rt", encoding="utf-8") as handle:
            old = json.load(handle)
        return old.get("version") == VERSION and old.get("source_signature") == signature
    except (OSError, ValueError):
        return False


def scan_one(source: Path, interval: int, frame: pd.DataFrame | None = None) -> bool:
    code = source.name[:6]
    target = _target(code, interval)
    signature = [source.stat().st_size, source.stat().st_mtime_ns]
    if _current(target, signature):
        return False
    if frame is None:
        frame = _load_frame(source)
    sampled = _resample(frame, interval)
    bars = [{"date": row.datetime.to_pydatetime(), "start": row.datetime.to_pydatetime(),
             "end": row.datetime.to_pydatetime(), "high": float(row.high),
             "low": float(row.low), "close": float(row.close)}
            for row in sampled.itertuples(index=False) if row.low <= row.close <= row.high]
    structure = scan_chan_fractals.structure_for_bars(bars, code, "")
    structure.update({"interval": interval, "available": True, "version": VERSION,
                      "source_signature": signature})
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(f".{target.name}.{os.getpid()}.tmp")
    with gzip.open(temporary, "wt", encoding="utf-8") as handle:
        json.dump(structure, handle, ensure_ascii=False, separators=(",", ":"))
    os.replace(temporary, target)
    return True


def _load_frame(source: Path) -> pd.DataFrame:
    frame = pd.read_csv(source, compression="gzip", usecols=["datetime", "high", "low", "close"])
    frame["datetime"] = pd.to_datetime(frame["datetime"], errors="coerce")
    return frame.dropna().sort_values("datetime").drop_duplicates("datetime", keep="last")


def _scan_source(source: Path) -> tuple[int, int, int]:
    signature = [source.stat().st_size, source.stat().st_mtime_ns]
    missing = [interval for interval in INTERVALS if not _current(_target(source.name[:6], interval), signature)]
    if not missing:
        return 0, len(INTERVALS), 0
    try:
        frame = _load_frame(source)
    except Exception as exc:
        print(f"[分钟缠论] {source.name}：{exc}", flush=True)
        return 0, len(INTERVALS)-len(missing), len(missing)
    processed = failed = 0
    for interval in missing:
        try:
            processed += int(scan_one(source, interval, frame))
        except Exception as exc:
            failed += 1
            print(f"[分钟缠论] {source.name} {interval}分钟：{exc}", flush=True)
    return processed, len(INTERVALS)-len(missing), failed


def scan_all() -> dict:
    processed = skipped = failed = 0
    files = [source for source in sorted(SOURCE.glob("*_1min.csv.gz"))
             if re.fullmatch(r"\d{6}_1min\.csv\.gz", source.name)]
    with ProcessPoolExecutor(max_workers=min(6, os.cpu_count() or 1)) as pool:
        for index, counts in enumerate(pool.map(_scan_source, files), 1):
            processed += counts[0]
            skipped += counts[1]
            failed += counts[2]
            if index % 250 == 0:
                print(f"[分钟缠论] {index}/{len(files)} 股票，新增 {processed}，失败 {failed}", flush=True)
    return {"processed": processed, "skipped": skipped, "failed": failed}


if __name__ == "__main__":
    print(scan_all(), flush=True)
