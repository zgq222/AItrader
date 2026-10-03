"""倒垂线固定策略回测。

直接修改 CONFIG 后重跑本文件即可调整策略。输出逐事件明细与条件成功率矩阵。
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

import pandas as pd


BASE_DIR = Path(__file__).resolve().parent
RAW_DIR = BASE_DIR / "个股" / "原始数据"
MINUTE_DIR = BASE_DIR / "个股" / "一分钟"
INDUSTRY_MEMBER_DIR = BASE_DIR / "板块" / "成分股" / "industry"
INDUSTRY_KLINE_DIR = BASE_DIR / "板块" / "行业板块"
INDEX_DIR = BASE_DIR / "指数"
SCREEN_FILE = BASE_DIR / "data" / "inverted_hammer_screen.json"
OUTPUT_DIR = BASE_DIR / "data" / "research"


@dataclass(frozen=True)
class StrategyConfig:
    holding_days: int = 5
    take_profit_discount_pct: float = 1.0
    stop_trigger_strictly_below: bool = True
    default_review: str = "不符合"
    target_reviews: tuple[str, ...] = ("不符合",)  # 改成三种即可全量比较


CONFIG = StrategyConfig()
REVIEW_TYPES = ("阳包阴", "不包", "不符合")


def _latest_code_files() -> dict[str, Path]:
    result = {}
    for path in RAW_DIR.glob("*_原始数据.csv"):
        match = re.match(r"(\d{6})_.*_原始数据\.csv$", path.name)
        if not match:
            continue
        code = match.group(1)
        if code not in result or path.stat().st_mtime > result[code].stat().st_mtime:
            result[code] = path
    return result


def _read_daily(path: Path) -> pd.DataFrame:
    frame = pd.read_csv(path, usecols=lambda c: c in {"date", "open", "high", "low", "close", "volume"})
    frame["date"] = pd.to_datetime(frame["date"], errors="coerce").dt.strftime("%Y-%m-%d")
    for column in ("open", "high", "low", "close", "volume"):
        frame[column] = pd.to_numeric(frame[column], errors="coerce")
    return frame.dropna(subset=["date", "open", "high", "low", "close"]).sort_values("date").reset_index(drop=True)


def _read_minute(code: str) -> pd.DataFrame | None:
    path = MINUTE_DIR / f"{code}_1min.csv.gz"
    if not path.exists():
        return None
    frame = pd.read_csv(path, compression="gzip")
    frame["datetime"] = pd.to_datetime(frame["datetime"], errors="coerce")
    for column in ("open", "high", "low", "close"):
        frame[column] = pd.to_numeric(frame[column], errors="coerce")
    frame = frame.dropna(subset=["datetime", "open", "high", "low", "close"]).sort_values("datetime")
    frame["date"] = frame["datetime"].dt.strftime("%Y-%m-%d")
    return frame


def _index_returns() -> dict[str, dict[str, float]]:
    result = {}
    for code in ("000001", "399001"):
        path = next(INDEX_DIR.glob(f"{code}_*.csv"), None)
        if not path:
            continue
        frame = pd.read_csv(path)
        date_col = "date" if "date" in frame else "日期"
        close_col = "close" if "close" in frame else "收盘"
        dates = pd.to_datetime(frame[date_col], errors="coerce").dt.strftime("%Y-%m-%d")
        close = pd.to_numeric(frame[close_col], errors="coerce")
        returns = close.pct_change(fill_method=None) * 100
        result[code] = dict(zip(dates, returns))
    return result


def _industry_context() -> tuple[dict[str, str], dict[str, dict[str, float]]]:
    # 使用最新行业成分关系；历史回测会因此包含当前成分口径的幸存者偏差。
    stock_industry = {}
    latest_by_name = {}
    for path in INDUSTRY_MEMBER_DIR.glob("*.csv"):
        name = re.sub(r"_\d{4}-\d{2}-\d{2}$", "", path.stem)
        if name not in latest_by_name or path.stat().st_mtime > latest_by_name[name].stat().st_mtime:
            latest_by_name[name] = path
    for name, path in latest_by_name.items():
        try:
            members = pd.read_csv(path, usecols=["code"])
            for value in members["code"]:
                stock_industry.setdefault(str(value).split(".")[0].zfill(6), name)
        except Exception:
            continue
    returns_by_industry = {}
    for name in set(stock_industry.values()):
        path = INDUSTRY_KLINE_DIR / f"{name}.csv"
        if not path.exists():
            continue
        try:
            frame = pd.read_csv(path)
            date_col = "日期" if "日期" in frame else "date"
            close_col = "收盘价" if "收盘价" in frame else "close"
            dates = pd.to_datetime(frame[date_col], errors="coerce").dt.strftime("%Y-%m-%d")
            close = pd.to_numeric(frame[close_col], errors="coerce")
            returns_by_industry[name] = dict(zip(dates, close.pct_change(fill_method=None) * 100))
        except Exception:
            continue
    return stock_industry, returns_by_industry


def _simulate_event(event: dict, daily: pd.DataFrame, minute: pd.DataFrame, config: StrategyConfig) -> dict | None:
    event_date = event["date"]
    positions = daily.index[daily["date"] == event_date].tolist()
    if not positions:
        return None
    index = positions[-1]
    holding = daily.iloc[index + 1:index + 1 + config.holding_days]
    if len(holding) < config.holding_days:
        return None
    holding_dates = holding["date"].tolist()
    bars = minute[minute["date"].isin(holding_dates)].copy()
    if bars.empty or not set(holding_dates).issubset(set(bars["date"])):
        return None
    buy = float(event["close"])
    stop = float(event["low"])
    breach = bars["low"].lt(stop) if config.stop_trigger_strictly_below else bars["low"].le(stop)
    stopped = bool(breach.any())
    if stopped:
        first = bars.loc[breach].iloc[0]
        exit_price = min(stop, float(first["open"]))
        return_pct = (exit_price / buy - 1) * 100
        exit_date = first["datetime"].strftime("%Y-%m-%d %H:%M:%S")
    else:
        highest = float(bars["high"].max())
        return_pct = (highest / buy - 1) * 100 - config.take_profit_discount_pct
        exit_price, exit_date = highest, holding_dates[-1]
    return {
        "holding_end": holding_dates[-1], "stopped": stopped, "exit_time": exit_date,
        "exit_price": round(exit_price, 4), "return_pct": round(return_pct, 4),
        "success": return_pct > 0,
    }


def run_backtest(config: StrategyConfig = CONFIG) -> dict:
    payload = json.loads(SCREEN_FILE.read_text(encoding="utf-8"))
    reviews = payload.get("reviews", {})
    raw_files = _latest_code_files()
    index_returns = _index_returns()
    stock_industry, industry_returns = _industry_context()
    detail = []
    skipped_no_minute = skipped_incomplete = 0
    for stock in payload.get("matches", []):
        code = stock["code"]
        events = []
        for event in stock.get("events", []):
            review = reviews.get(f"{code}:{event['date']}", config.default_review)
            if review in config.target_reviews:
                events.append((event, review))
        if not events or code not in raw_files:
            continue
        minute = _read_minute(code)
        if minute is None:
            skipped_no_minute += len(events)
            continue
        daily = _read_daily(raw_files[code])
        daily_by_date = daily.set_index("date")
        industry = stock_industry.get(code)
        market_code = "000001" if code.startswith("6") else "399001"
        for event, review in events:
            outcome = _simulate_event(event, daily, minute, config)
            if outcome is None:
                skipped_incomplete += 1
                continue
            date = event["date"]
            if date not in daily_by_date.index:
                continue
            position = daily.index[daily["date"] == date][-1]
            previous_volume = daily.iloc[position - 1]["volume"] if position > 0 else float("nan")
            current_volume = daily.iloc[position]["volume"]
            market_return = index_returns.get(market_code, {}).get(date)
            industry_return = industry_returns.get(industry, {}).get(date) if industry else None
            detail.append({
                "code": code, "name": stock.get("name"), "date": date, "kline_type": review,
                "buy_price": event["close"], "stop_price": event["low"], **outcome,
                "market_index": market_code, "market_return_pct": market_return,
                "market_direction": "大盘涨" if pd.notna(market_return) and market_return >= 0 else ("大盘跌" if pd.notna(market_return) else None),
                "industry": industry, "industry_return_pct": industry_return,
                "industry_direction": "板块涨" if pd.notna(industry_return) and industry_return >= 0 else ("板块跌" if pd.notna(industry_return) else None),
                "volume_ratio": current_volume / previous_volume if pd.notna(previous_volume) and previous_volume > 0 else None,
                "volume_direction": "放量" if pd.notna(previous_volume) and current_volume >= previous_volume else ("缩量" if pd.notna(previous_volume) else None),
            })
    frame = pd.DataFrame(detail)
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    detail_path = OUTPUT_DIR / "inverted_hammer_backtest_events.csv"
    matrix_path = OUTPUT_DIR / "inverted_hammer_return_matrix.csv"
    frame.to_csv(detail_path, index=False, encoding="utf-8-sig")
    factor_columns = {
        "大盘": "market_direction", "板块": "industry_direction", "成交量": "volume_direction"
    }
    matrix_rows = []
    if not frame.empty:
        for review in REVIEW_TYPES:
            typed = frame[frame["kline_type"] == review]
            for factor, column in factor_columns.items():
                for condition in ({"大盘": ("大盘涨", "大盘跌"), "板块": ("板块涨", "板块跌"), "成交量": ("放量", "缩量")}[factor]):
                    sample = typed[typed[column] == condition]
                    matrix_rows.append({
                        "kline_type": review, "factor": factor, "condition": condition,
                        "average_return_pct": round(float(sample["return_pct"].mean()), 4) if len(sample) else None,
                        "samples": len(sample),
                    })
    matrix = pd.DataFrame(matrix_rows)
    matrix.to_csv(matrix_path, index=False, encoding="utf-8-sig")
    wide_path = OUTPUT_DIR / "inverted_hammer_return_matrix_wide.csv"
    if not matrix.empty:
        wide = matrix.pivot(index="kline_type", columns="condition", values="average_return_pct")
        wide = wide.reindex(index=REVIEW_TYPES, columns=("大盘涨", "大盘跌", "板块涨", "板块跌", "放量", "缩量"))
        wide.to_csv(wide_path, encoding="utf-8-sig")
    else:
        pd.DataFrame(columns=("kline_type", "大盘涨", "大盘跌", "板块涨", "板块跌", "放量", "缩量")).to_csv(wide_path, index=False, encoding="utf-8-sig")
    summary = {
        "strategy": "倒垂线收盘买入、次日起分钟跌破最低价止损、五日最高收益减1%",
        "config": config.__dict__, "valid_events": len(frame),
        "skipped_no_minute_file": skipped_no_minute, "skipped_incomplete_five_days": skipped_incomplete,
        "detail_file": str(detail_path), "matrix_file": str(matrix_path), "wide_matrix_file": str(wide_path),
        "matrix": matrix.astype(object).where(pd.notnull(matrix), None).to_dict(orient="records"),
        "notes": ["大盘：沪股用上证指数，深股用深证成指。", "板块：使用最新行业成分映射，存在幸存者偏差。", "涨/跌中0涨跌归入涨；放量/缩量中等量归入放量。"],
    }
    (OUTPUT_DIR / "inverted_hammer_backtest_summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    return summary


if __name__ == "__main__":
    result = run_backtest()
    print(json.dumps(result, ensure_ascii=False, indent=2, default=str))
