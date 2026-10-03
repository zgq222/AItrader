"""Trading-session-aligned aggregation of local one-minute A-share bars."""
from __future__ import annotations

from pathlib import Path

import pandas as pd


BASE_DIR = Path(__file__).resolve().parent
RAW_DIR = BASE_DIR / "个股" / "原始数据"


def clean_minute_price_outliers(frame: pd.DataFrame, code: str | None = None) -> pd.DataFrame:
    """Drop minute rows whose OHLC is outside that day's local daily range.

    This protects charts and five-minute aggregation from a source file that
    switches price scales in the middle of a session. Volume-only rows are not
    retained because they would create a false volume frame after aggregation.
    """
    if frame.empty or not code:
        return frame
    try:
        path = next(RAW_DIR.glob(f"{str(code).zfill(6)}_*_原始数据.csv"), None)
        if path is None:
            return frame
        daily = pd.read_csv(path, usecols=["date", "high", "low"])
        daily["date"] = daily["date"].astype(str).str.slice(0, 10)
        daily["high"] = pd.to_numeric(daily["high"], errors="coerce")
        daily["low"] = pd.to_numeric(daily["low"], errors="coerce")
        ranges = daily.dropna(subset=["date", "high", "low"]).drop_duplicates("date").set_index("date")
        result = frame.copy()
        dates = result["datetime"].astype(str).str.slice(0, 10)
        upper = dates.map(ranges["high"])
        lower = dates.map(ranges["low"])
        prices = result[["open", "high", "low", "close"]].apply(pd.to_numeric, errors="coerce")
        tolerance = (upper - lower).abs() * 0.01 + 0.02
        invalid = (upper.notna() & lower.notna() & prices.notna().all(axis=1)
                   & ((prices.max(axis=1) > upper + tolerance)
                      | (prices.min(axis=1) < lower - tolerance)))
        return result.loc[~invalid].reset_index(drop=True)
    except (OSError, KeyError, StopIteration, ValueError):
        return frame


def aggregate_five_minutes(frame: pd.DataFrame) -> pd.DataFrame:
    """Return complete 09:31–09:35, …, 13:01–13:05 bars, labeled by end time."""
    columns = [name for name in ("open", "high", "low", "close", "volume", "amount") if name in frame]
    if frame.empty:
        return pd.DataFrame(columns=["datetime", *columns])
    minute = frame[["datetime", *columns]].copy()
    minute["datetime"] = pd.to_datetime(minute["datetime"], errors="coerce")
    minute = minute.dropna(subset=["datetime"]).sort_values("datetime").drop_duplicates("datetime", keep="last")
    clock = minute.datetime.dt.hour * 60 + minute.datetime.dt.minute
    morning = clock.between(571, 690)
    afternoon = clock.between(781, 900)
    minute = minute.loc[morning | afternoon].copy()
    if minute.empty:
        return pd.DataFrame(columns=["datetime", *columns])
    clock = clock.loc[minute.index]
    minute["slot"] = ((clock - 571) // 5).where(clock <= 690, 24 + (clock - 781) // 5).astype(int)
    minute["date"] = minute.datetime.dt.normalize()
    operations = {name: operation for name, operation in (
        ("open", "first"), ("high", "max"), ("low", "min"),
        ("close", "last"), ("volume", "sum"), ("amount", "sum"),
    ) if name in columns}
    bars = minute.groupby(["date", "slot"], sort=True).agg(**{
        name: (name, operation) for name, operation in operations.items()
    }, samples=("datetime", "size")).reset_index()
    bars = bars.loc[bars.samples.eq(5)].copy()
    end_minute = (575 + bars.slot * 5).where(bars.slot < 24, 785 + (bars.slot - 24) * 5)
    bars["datetime"] = bars.date + pd.to_timedelta(end_minute, unit="m")
    return bars[["datetime", *columns]].reset_index(drop=True)
