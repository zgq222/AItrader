"""Detect single 5-minute bars with volume above a prior-20-day median.

The label is a volume-based activity proxy, not proof of an investor's identity.
Only completed bars are used; each day's baseline uses all 5-minute bars from
the previous 20 trading days. No single-bar spike or auction is excluded.
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd


ROOT = Path(__file__).resolve().parent
SESSIONS = ((571, 690), (781, 900))  # 09:31–11:30, 13:01–15:00


def make_five_minute_bars(source: Path) -> pd.DataFrame:
    minute = pd.read_csv(source, compression="gzip", parse_dates=["datetime"])
    minute = minute.drop_duplicates("datetime", keep="last").sort_values("datetime")
    clock = minute.datetime.dt.hour * 60 + minute.datetime.dt.minute
    slot = pd.Series(-1, index=minute.index)
    for session_index, (start, end) in enumerate(SESSIONS):
        mask = clock.between(start, end)
        slot.loc[mask] = session_index * 24 + ((clock.loc[mask] - start) // 5)
    minute = minute.loc[slot.ge(0)].copy()
    minute["slot"] = slot.loc[minute.index].astype(int)
    minute["date"] = minute.datetime.dt.strftime("%Y-%m-%d")
    bars = minute.groupby(["date", "slot"], sort=True).agg(
        open=("open", "first"), high=("high", "max"), low=("low", "min"),
        close=("close", "last"), volume=("volume", "sum"),
        amount=("amount", "sum"), samples=("datetime", "size"),
    ).reset_index()
    bars = bars[bars.samples.eq(5)].copy()
    bars["time"] = bars.slot.map(lambda s: (
        f"{(575 + 5 * s) // 60:02d}:{(575 + 5 * s) % 60:02d}"
        if s < 24 else
        f"{(785 + 5 * (s - 24)) // 60:02d}:{(785 + 5 * (s - 24)) % 60:02d}"
    ))
    return bars.reset_index(drop=True)


def detect(bars: pd.DataFrame, lookback: int = 20,
           min_bar_ratio: float = 2.5) -> tuple[pd.DataFrame, pd.DataFrame]:
    bars = bars.copy()
    days = [(date, day.copy()) for date, day in bars.groupby("date", sort=True)]
    baselines = {}
    for index in range(lookback, len(days)):
        # Exclude the current day so the threshold is fixed before it opens.
        history = np.concatenate([day.volume.to_numpy() for _, day in days[index - lookback:index]])
        baselines[days[index][0]] = float(np.median(history))
    bars["baseline_volume"] = bars.date.map(baselines)
    bars["volume_ratio"] = bars.volume / bars.baseline_volume.replace(0, np.nan)
    signals = bars[bars.volume_ratio.ge(min_bar_ratio)].copy()
    start_minutes = np.where(
        signals.slot.lt(24), 571 + 5 * signals.slot,
        781 + 5 * (signals.slot - 24),
    )
    signals["start_time"] = [f"{minute // 60:02d}:{minute % 60:02d}" for minute in start_minutes]
    signals = signals.rename(columns={"time": "end_time"})
    signals = signals[["date", "slot", "start_time", "end_time", "open", "high", "low",
                       "close", "volume", "amount", "baseline_volume", "volume_ratio"]]

    # Adjacent flagged bars form one display interval; a single bar is valid.
    groups = []
    for date, day in signals.groupby("date", sort=True):
        run = []
        for row in day.itertuples(index=False):
            if run and (row.slot != run[-1].slot + 1 or row.slot // 24 != run[-1].slot // 24):
                groups.append(run)
                run = []
            run.append(row)
        if run:
            groups.append(run)

    events = []
    for run in groups:
        first, last = run[0], run[-1]
        volume = sum(row.volume for row in run)
        change = 100 * (last.close / first.open - 1)
        direction = "放量上涨" if change >= 0.5 else "放量下跌" if change <= -0.5 else "放量震荡"
        events.append({
            "date": first.date, "start_time": first.start_time,
            "confirmation_time": first.end_time, "end_time": last.end_time,
            "bars": len(run), "volume_shares": int(volume),
            "amount_yuan": round(float(sum(row.amount for row in run)), 2),
            "volume_ratio": round(float(volume / (len(run) * first.baseline_volume)), 2),
            "price_change_pct": round(float(change), 2), "direction": direction,
        })
    return signals.reset_index(drop=True), pd.DataFrame(events)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--code", default="002811")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    source = ROOT / "个股" / "一分钟" / f"{args.code}_1min.csv.gz"
    bars = make_five_minute_bars(source)
    signals, events = detect(bars)
    output = args.output or ROOT / "data" / "research" / f"{args.code}_volume_pileup.csv"
    bars_output = output.with_name(f"{output.stem}_bars.csv")
    output.parent.mkdir(parents=True, exist_ok=True)
    events.to_csv(output, index=False, encoding="utf-8-sig")
    signals.to_csv(bars_output, index=False, encoding="utf-8-sig")
    print(f"source={source} days={bars.date.nunique()} bars={len(bars)} first={bars.date.min()} last={bars.date.max()}")
    print(f"signals={len(signals)} events={len(events)} output={output} bars_output={bars_output}")
    if not events.empty:
        print(events.tail(30).to_string(index=False))


if __name__ == "__main__":
    main()
