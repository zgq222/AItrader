"""通达信一分钟K线批量下载与本地读取。"""
from __future__ import annotations

import argparse
import asyncio
import gzip
import json
import os
import time
from datetime import datetime
from pathlib import Path

import pandas as pd
from pytdxdata import Adjust, KlinePeriod, TdxData

from intraday_bars import aggregate_five_minutes, clean_minute_price_outliers
import watchlist_service
from market_database import list_stock_catalog, read_dataframe


BASE_DIR = Path(__file__).resolve().parent
MINUTE_DIR = BASE_DIR / "个股" / "一分钟"
STATUS_FILE = BASE_DIR / "data" / "minute_kline_status.json"
COVERAGE_FILE = BASE_DIR / "data" / "minute_file_coverage.json"
DEFAULT_COUNT = 4800  # 约20个完整交易日；后续可增大，落盘时自动去重合并。
RAW_DIR = BASE_DIR / "个股" / "原始数据"


def _add_daily_returns(frame: pd.DataFrame, code: str) -> pd.DataFrame:
    """Use the previous daily close, independent of minute interval/window."""
    frame = frame.copy()
    previous_by_date = {}
    path = next(RAW_DIR.glob(f"{code}_*_原始数据.csv"), None)
    if path:
        try:
            daily = read_dataframe(path, usecols=["date", "close"])
            daily["date"] = pd.to_datetime(daily["date"], errors="coerce")
            daily["close"] = pd.to_numeric(daily["close"], errors="coerce")
            daily = daily.dropna(subset=["date"]).drop_duplicates("date", keep="last").sort_values("date")
            previous_by_date = dict(zip(daily["date"].dt.strftime("%Y-%m-%d"), daily["close"].shift(1)))
        except (OSError, ValueError, KeyError):
            pass
    dates = frame["datetime"].astype(str).str.slice(0, 10)
    previous = pd.to_numeric(dates.map(previous_by_date), errors="coerce")
    previous = previous.where(previous.gt(0))
    frame["pre_close"] = previous
    frame["pct_change"] = (pd.to_numeric(frame["close"], errors="coerce") / previous - 1) * 100
    return frame


def _write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(f".{path.stem}_{os.getpid()}.tmp")
    temp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    # Windows readers may briefly lock the destination while the UI polls status.
    # Status persistence must not terminate a long-running download.
    for attempt in range(30):
        try:
            os.replace(temp, path)
            return
        except PermissionError:
            time.sleep(min(0.05 * (attempt + 1), 0.5))
    try:
        temp.unlink(missing_ok=True)
    except OSError:
        pass


def load_status() -> dict:
    try:
        return json.loads(STATUS_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {"state": "not_started"}


def _target_stocks(group_id: str) -> list[dict]:
    if group_id == "all_stocks":
        return list_stock_catalog()
    payload = watchlist_service.load_watchlists()
    if group_id == "daily_inverted_hammer":
        # Keep minute downloads prioritizing today's matches without a duplicate UI group.
        hammer = next((item for item in payload.get("groups", [])
                       if item.get("id") == watchlist_service.INVERTED_HAMMER_GROUP_ID), None)
        latest = payload.get("inverted_hammer_screen", {}).get("window_end")
        return [stock for stock in (hammer or {}).get("stocks", [])
                if stock.get("trade_date") == latest] if latest else []
    group = next((item for item in payload.get("groups", []) if item.get("id") == group_id), None)
    if not group:
        raise ValueError(f"自选股分组不存在：{group_id}")
    return list(group.get("stocks", []))


def _path_for(code: str) -> Path:
    return MINUTE_DIR / f"{code}_1min.csv.gz"


def _load_coverage() -> dict:
    try:
        payload = json.loads(COVERAGE_FILE.read_text(encoding="utf-8"))
        return payload if isinstance(payload, dict) else {}
    except (OSError, json.JSONDecodeError):
        return {}


def _last_local_bar(code: str, path: Path, coverage: dict) -> str | None:
    """Read old files once; cache the last bar against their exact file mtime."""
    try:
        mtime_ns = path.stat().st_mtime_ns
        cached = coverage.get(code)
        if isinstance(cached, dict) and cached.get("mtime_ns") == mtime_ns:
            return cached.get("last_bar")
        last_line = None
        with gzip.open(path, "rt", encoding="utf-8", newline="") as stream:
            if next(stream, "").split(",", 1)[0] != "datetime":
                return None
            for last_line in stream:
                pass
        last_bar = last_line.split(",", 1)[0] if last_line else None
        coverage[code] = {"mtime_ns": mtime_ns, "last_bar": last_bar}
        return last_bar
    except (OSError, EOFError, UnicodeError):
        return None


def _needs_update(code: str, path: Path, target: str, target_epoch: float, coverage: dict) -> bool:
    if not path.exists():
        return True
    # A file written before the newest market bar cannot contain that bar.
    if path.stat().st_mtime < target_epoch:
        return True
    last_bar = _last_local_bar(code, path, coverage)
    return not last_bar or last_bar < target


async def _latest_market_bar(client: TdxData) -> str:
    for market, code in ((1, "600519"), (0, "000001")):
        try:
            bars = await asyncio.wait_for(client.get_kline(
                market, code, KlinePeriod.MIN_1, start=0, count=1, adjust=Adjust.NONE), timeout=45)
            if bars:
                return str(bars[-1].datetime)
        except Exception:
            continue
    raise RuntimeError("通达信无法确定最新一分钟交易时间，本次不把旧文件算作完成")


def _bars_frame(bars) -> pd.DataFrame:
    rows = [{
        "datetime": bar.datetime, "open": bar.open, "high": bar.high, "low": bar.low,
        "close": bar.close, "volume": bar.vol, "amount": bar.amount,
    } for bar in bars]
    if not rows:
        return pd.DataFrame(columns=["datetime", "open", "high", "low", "close", "volume", "amount"])
    frame = pd.DataFrame(rows)
    frame["datetime"] = pd.to_datetime(frame["datetime"], errors="coerce")
    for column in ("open", "high", "low", "close"):
        frame[column] = pd.to_numeric(frame[column], errors="coerce").round(4)
    for column in ("volume", "amount"):
        frame[column] = pd.to_numeric(frame[column], errors="coerce").round(2)
    return frame.dropna(subset=["datetime"]).drop_duplicates("datetime").sort_values("datetime")


def _merge_save(code: str, frame: pd.DataFrame) -> int:
    path = _path_for(code)
    if path.exists():
        try:
            old = pd.read_csv(path, compression="gzip")
            old["datetime"] = pd.to_datetime(old["datetime"], errors="coerce")
            frame = pd.concat([old, frame], ignore_index=True)
        except Exception:
            pass
    frame = frame.dropna(subset=["datetime"]).drop_duplicates("datetime").sort_values("datetime")
    for column in ("open", "high", "low", "close"):
        frame[column] = pd.to_numeric(frame[column], errors="coerce").round(4)
    for column in ("volume", "amount"):
        frame[column] = pd.to_numeric(frame[column], errors="coerce").round(2)
    frame["datetime"] = frame["datetime"].dt.strftime("%Y-%m-%d %H:%M:%S")
    MINUTE_DIR.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    frame.to_csv(temp, index=False, encoding="utf-8", compression="gzip")
    os.replace(temp, path)
    return len(frame), str(frame["datetime"].iloc[-1])


async def download_group(group_id: str = "inverted_hammer", count: int = DEFAULT_COUNT, resume: bool = False,
                         update: bool = False) -> dict:
    stocks = _target_stocks(group_id)
    coverage = _load_coverage()
    _write_json(STATUS_FILE, {"state": "checking", "group_id": group_id, "total": len(stocks),
                              "pid": os.getpid(), "started_at": datetime.now().isoformat(timespec="seconds")})
    async with TdxData() as client:
        try:
            target_bar = await _latest_market_bar(client)
        except Exception as exc:
            _write_json(STATUS_FILE, {"state": "failed", "group_id": group_id, "total": len(stocks),
                                      "pid": os.getpid(), "failed": 1,
                                      "errors": [{"error": str(exc)[:160]}],
                                      "updated_at": datetime.now().isoformat(timespec="seconds")})
            raise
        target_epoch = datetime.fromisoformat(target_bar).timestamp()
        pending_stocks = stocks
        if resume:
            def pending(stock):
                code = str(stock["code"]).zfill(6)
                path = _path_for(code)
                return _needs_update(code, path, target_bar, target_epoch, coverage) if update else not path.exists()
            pending_stocks = [stock for stock in stocks if pending(stock)]
        resume_from = len(stocks) - len(pending_stocks)
        _write_json(COVERAGE_FILE, coverage)
        status = {
            "state": "running", "group_id": group_id, "requested_bars_per_stock": count,
            "target_bar": target_bar, "total": len(stocks), "completed": resume_from, "failed": 0,
            "pid": os.getpid(), "started_at": datetime.now().isoformat(timespec="seconds"),
            "current": None, "errors": [],
        }
        _write_json(STATUS_FILE, status)
        for index, stock in enumerate(pending_stocks, resume_from + 1):
            code = str(stock["code"]).zfill(6)
            market = 1 if code.startswith("6") else 0
            status["current"] = {"code": code, "name": stock.get("name", code), "index": index}
            status["updated_at"] = datetime.now().isoformat(timespec="seconds")
            _write_json(STATUS_FILE, status)
            try:
                bars = await asyncio.wait_for(
                    client.get_kline(market, code, KlinePeriod.MIN_1, start=0, count=count, adjust=Adjust.NONE),
                    timeout=180,
                )
                frame = _bars_frame(bars)
                if frame.empty:
                    raise RuntimeError("服务器未返回一分钟K线")
                _, last_bar = _merge_save(code, frame)
                coverage[code] = {"mtime_ns": _path_for(code).stat().st_mtime_ns, "last_bar": last_bar}
                if last_bar[:10] < target_bar[:10]:
                    raise RuntimeError(f"服务器只返回到 {last_bar}，未覆盖最新交易日 {target_bar[:10]}")
                status["completed"] += 1
            except Exception as exc:
                status["failed"] += 1
                status["errors"].append({"code": code, "error": str(exc)[:160]})
                status["errors"] = status["errors"][-100:]
            status["updated_at"] = datetime.now().isoformat(timespec="seconds")
            _write_json(STATUS_FILE, status)
            if index % 20 == 0:
                _write_json(COVERAGE_FILE, coverage)
            await asyncio.sleep(0.15)
    _write_json(COVERAGE_FILE, coverage)
    status["state"] = "complete" if status["failed"] == 0 else "partial"
    status["current"] = None
    status["finished_at"] = datetime.now().isoformat(timespec="seconds")
    _write_json(STATUS_FILE, status)
    return status


async def download_pipeline(count: int = DEFAULT_COUNT, resume: bool = True, update: bool = True) -> dict:
    """Prioritize latest-day inverted hammers, then the wider group, then all stocks."""
    daily = await download_group("daily_inverted_hammer", count, resume=resume, update=update)
    inverted = await download_group("inverted_hammer", count, resume=resume, update=update)
    full = await download_group("all_stocks", count, resume=resume, update=update)
    return {"state": "complete" if all(item["state"] == "complete" for item in (daily, inverted, full)) else "partial",
            "daily_inverted_hammer": daily, "inverted_hammer": inverted, "all_stocks": full}


def read_minute_kline(code: str, limit: int = 2400, trade_date: str | None = None, interval: int = 1,
                      focus_date: str | None = None, context_days: int = 10) -> dict | None:
    path = _path_for(code)
    if not path.exists():
        return None
    frame = pd.read_csv(path, compression="gzip")
    original_rows = len(frame)
    frame = clean_minute_price_outliers(frame, code)
    cleaned_rows = original_rows - len(frame)
    interval = int(interval)
    if interval not in {1, 5, 10, 15, 30}:
        raise ValueError("interval 必须为 1/5/10/15/30")
    for value, label in ((trade_date, "date"), (focus_date, "focus_date")):
        if value and not pd.Series([value]).str.fullmatch(r"\d{4}-\d{2}-\d{2}").iloc[0]:
            raise ValueError(f"{label} 必须为 YYYY-MM-DD")
    if trade_date:
        frame = frame[frame["datetime"].astype(str).str.slice(0, 10) == trade_date]
    elif focus_date:
        row_dates = frame["datetime"].astype(str).str.slice(0, 10)
        dates = sorted(row_dates.dropna().unique().tolist())
        if focus_date in dates:
            center = dates.index(focus_date)
            radius = max(1, min(int(context_days), 60))
            selected_dates = set(dates[max(0, center-radius):center+radius+1])
            frame = frame[row_dates.isin(selected_dates)]
        else:
            frame = frame.iloc[0:0]
    else:
        frame = frame.tail(max(1, min(int(limit), 200000)))
    if interval == 5 and not frame.empty:
        frame = aggregate_five_minutes(frame)
        frame["datetime"] = frame["datetime"].dt.strftime("%Y-%m-%d %H:%M:%S")
    elif interval > 1 and not frame.empty:
        frame["datetime"] = pd.to_datetime(frame["datetime"], errors="coerce")
        frame = frame.dropna(subset=["datetime"]).set_index("datetime")
        frame = frame.groupby(frame.index.date, group_keys=False).apply(
            lambda day: day.resample(
                f"{interval}min", origin="start_day", offset="9h30min", label="left", closed="left"
            ).agg({"open":"first", "high":"max", "low":"min", "close":"last", "volume":"sum", "amount":"sum"}).dropna(subset=["open", "close"]),
            include_groups=False,
        )
        frame = frame.reset_index()
        frame["datetime"] = frame["datetime"].dt.strftime("%Y-%m-%d %H:%M:%S")
    frame = _add_daily_returns(frame, code)
    frame = frame.astype(object).where(pd.notnull(frame), None)
    return {"code": code, "date": trade_date, "focus_date": focus_date, "interval": interval,
            "total": len(frame), "data": frame.to_dict(orient="records"),
            "cleaned_outlier_rows": cleaned_rows,
            "source": f"通达信{interval}分钟未复权K线（由本地1分钟数据聚合）" if interval > 1 else "通达信一分钟原始未复权K线"}


def main() -> None:
    parser = argparse.ArgumentParser(description="下载自选股分组的一分钟K线")
    parser.add_argument("--group", default="inverted_hammer")
    parser.add_argument("--count", type=int, default=DEFAULT_COUNT)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--update", action="store_true", help="补缺并更新未覆盖最新一分钟交易时间的文件")
    parser.add_argument("--then-all", action="store_true")
    args = parser.parse_args()
    count = max(1, args.count)
    result = asyncio.run(download_pipeline(count, resume=args.resume, update=args.update)) if args.then_all else asyncio.run(download_group(args.group, count, resume=args.resume, update=args.update))
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
