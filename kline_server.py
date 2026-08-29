import os
import json
import hashlib
import re
import math
import socket
import subprocess
import tempfile
import time
import pandas as pd
import requests
from concurrent.futures import ThreadPoolExecutor
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs
from pathlib import Path

from market_sync import ASSET_DIR, ASSET_SOURCES, BOARD_DIR, BOARD_MAINFLOW_INTRADAY_DIR, BOARD_MEMBER_DIR, FLOW_DIR, FUTURES_DAILY_DIR, FUTURES_MEMBER_TOTAL_FILE, FUTURES_SUMMARY_FILE, FX_FILE, INDEX_CONFIG, INDEX_DIR, INDEX_MEMBER_DIR, LHB_DAILY_DIR, LHB_EVENT_INDEX, LHB_INSTITUTION_DIR, LHB_SPECIAL_DIR, SNAPSHOT_DIR, YIELD_DIR, YIELD_SOURCES, get_cached_board_members, get_daily_schedule_status, get_lhb_seat_details, get_sync_status, maybe_start_background_sync, maybe_start_daily_scheduler
from market_database import list_stock_catalog, migration_status, read_dataframe as db_read_csv, start_background_migration
from financial_reports import fetch_company_financials


def _clean_nan(obj):
    if isinstance(obj, float):
        if math.isnan(obj) or math.isinf(obj):
            return None
        return obj
    if isinstance(obj, dict):
        return {k: _clean_nan(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_clean_nan(v) for v in obj]
    if isinstance(obj, tuple):
        return tuple(_clean_nan(v) for v in obj)
    if pd.isna(obj):
        return None
    return obj

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
STOCK_DIR = os.path.join(BASE_DIR, "个股")
INDICATOR_DIR = os.path.join(STOCK_DIR, "完整指标")
RAW_DIR = os.path.join(STOCK_DIR, "原始数据")
FRONTEND_DIR = os.path.join(BASE_DIR, "frontend")
VIDEO_BOARD_EXCLUSIONS_FILE = Path(BASE_DIR) / "data" / "video_board_exclusions.json"
QUANT_BACKTEST_REPORTS_FILE = Path(BASE_DIR) / "data" / "quant_backtest_reports.json"

os.makedirs(FRONTEND_DIR, exist_ok=True)


def load_quant_backtest_reports():
    """Read assistant-approved backtest reports from the local report library."""
    if not QUANT_BACKTEST_REPORTS_FILE.exists():
        return []
    try:
        payload = json.loads(QUANT_BACKTEST_REPORTS_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return []
    reports = payload.get("reports", []) if isinstance(payload, dict) else payload
    if not isinstance(reports, list):
        return []
    valid = [item for item in reports if isinstance(item, dict) and item.get("id")]
    return sorted(valid, key=lambda item: str(item.get("created_at", "")), reverse=True)


def quant_backtest_report_list():
    fields = ("id", "title", "created_at", "updated_at", "summary", "tags")
    reports = [
        {key: report.get(key) for key in fields if report.get(key) is not None}
        for report in load_quant_backtest_reports()
    ]
    return {
        "total": len(reports),
        "data": reports,
        "write_policy": "仅在用户明确要求把该组对话加入量化回测模块时新增报告",
    }


def _parse_filename(fname):
    m = re.match(r"^(\d{6})_(.+?)_(完整指标|原始数据)\.csv$", fname)
    if m:
        return {"code": m.group(1), "name": m.group(2), "type": m.group(3)}
    return None


def list_stocks():
    stocks = {item["code"]: item for item in list_stock_catalog()}
    for folder in [INDICATOR_DIR, RAW_DIR]:
        if not os.path.isdir(folder):
            continue
        for fname in os.listdir(folder):
            info = _parse_filename(fname)
            if info:
                code = info["code"]
                if code not in stocks:
                    stocks[code] = {
                        "code": code,
                        "name": info["name"],
                        "has_indicator": False,
                        "has_raw": False,
                    }
                if info["type"] == "完整指标":
                    stocks[code]["has_indicator"] = True
                else:
                    stocks[code]["has_raw"] = True
    result = sorted(stocks.values(), key=lambda x: x["code"])
    return result


def _find_csv_path(code):
    for fname in os.listdir(INDICATOR_DIR) if os.path.isdir(INDICATOR_DIR) else []:
        info = _parse_filename(fname)
        if info and info["code"] == code and info["type"] == "完整指标":
            return os.path.join(INDICATOR_DIR, fname), "indicator"
    for fname in os.listdir(RAW_DIR) if os.path.isdir(RAW_DIR) else []:
        info = _parse_filename(fname)
        if info and info["code"] == code and info["type"] == "原始数据":
            return os.path.join(RAW_DIR, fname), "raw"
    return None, None


def _resample_kline(df, period):
    if df.empty:
        return df
    df = df.copy()
    df["date"] = pd.to_datetime(df["date"])
    df = df.sort_values("date").set_index("date")

    rule_map = {"day": "D", "week": "W-FRI", "month": "ME", "year": "YE"}
    rule = rule_map.get(period, "D")

    if period == "day":
        numeric_cols = df.select_dtypes(include="number").columns.tolist()
        df = df.reset_index()
        for c in numeric_cols:
            df[c] = df[c].round(4)
        return df

    # OHLCV 核心列的聚合规则
    agg_dict = {
        "open": "first",
        "high": "max",
        "low": "min",
        "close": "last",
        "volume": "sum",
        "amount": "sum",
    }

    # 对其余所有数值列，默认使用 last（适用于 MA/EMA/MACD/KDJ/RSI/BOLL 等指标）
    # 特殊列单独指定规则
    special_rules = {
        "outstanding_share": "last",
        "turnover": "last",
        "OBV": "last",
        "OBV_MA5": "last",
        "OBV_MA10": "last",
        "TR": "sum",
        "Price_Max_5": "max",
        "Price_Max_20": "max",
        "Price_Max_60": "max",
        "Price_Min_5": "min",
        "Price_Min_20": "min",
        "Price_Min_60": "min",
        "Vol_Max_20": "max",
        "Vol_Min_20": "min",
        "Vol_MA5": "mean",
        "Vol_MA10": "mean",
        "Vol_MA20": "mean",
        "Pct_Change": "sum",
        "VR": "last",
    }

    numeric_cols = df.select_dtypes(include="number").columns.tolist()
    for col in numeric_cols:
        if col in agg_dict:
            continue
        if col in special_rules:
            agg_dict[col] = special_rules[col]
        else:
            agg_dict[col] = "last"

    available_aggs = {k: v for k, v in agg_dict.items() if k in df.columns}
    resampled = df.resample(rule).agg(available_aggs).dropna(subset=["close"])
    resampled = resampled.reset_index()
    resampled["date"] = resampled["date"].dt.strftime("%Y-%m-%d")
    for c in resampled.select_dtypes(include="number").columns:
        resampled[c] = resampled[c].round(4)
    return resampled


def get_stock_kline(code, period="day"):
    path, dtype = _find_csv_path(code)
    if not path:
        return None

    # 基础必备列
    base_cols = {"date", "open", "high", "low", "close", "volume", "amount"}

    # 读取全部列（完整指标文件返回所有列，原始数据只返回基础列）
    try:
        all_cols = db_read_csv(path, nrows=1).columns.tolist()
    except Exception:
        all_cols = db_read_csv(path, nrows=1).columns.tolist()

    # 如果是完整指标文件，读取全部列；原始数据只读取基础列
    if "完整指标" in os.path.basename(path):
        usecols = all_cols
    else:
        usecols = [c for c in all_cols if c in base_cols]

    try:
        df = db_read_csv(path, usecols=lambda c: c in usecols)
    except UnicodeDecodeError:
        df = db_read_csv(path, usecols=lambda c: c in usecols)

    df = df.dropna(subset=["date", "close"])
    if period != "day":
        return _resample_kline(df, period)
    else:
        df["date"] = pd.to_datetime(df["date"]).dt.strftime("%Y-%m-%d")
        for c in df.select_dtypes(include="number").columns:
            df[c] = df[c].round(4)
        return df


def _normalize_stock_name(value):
    """用于跨数据源按股票名称匹配；不移除 ST 等具有区分意义的前缀。"""
    return re.sub(r"\s+", "", str(value or "")).upper()


def enrich_members_from_stock_workspace(members: pd.DataFrame) -> pd.DataFrame:
    """成分关系取板块缓存，行情字段统一复用个股工作台的本地日 K 数据。"""
    if members is None or members.empty:
        return members

    stock_by_name = {_normalize_stock_name(item["name"]): item for item in list_stocks()}
    enriched_rows = []
    for _, source_row in members.iterrows():
        row = source_row.to_dict()
        member_name = next((row.get(key) for key in ("股票名称", "名称", "股票简称", "name") if row.get(key) not in (None, "")), "")
        stock = stock_by_name.get(_normalize_stock_name(member_name))
        if stock:
            frame = get_stock_kline(stock["code"], "day")
            if frame is not None and not frame.empty:
                frame = frame.sort_values("date")
                last = frame.iloc[-1]
                previous = frame.iloc[-2] if len(frame) > 1 else None
                close = pd.to_numeric(last.get("close"), errors="coerce")
                previous_close = pd.to_numeric(previous.get("close"), errors="coerce") if previous is not None else float("nan")
                change_pct = ((close / previous_close) - 1) * 100 if pd.notna(close) and pd.notna(previous_close) and previous_close else None
                turnover = next((last.get(key) for key in ("turnover", "turnover_rate", "换手率") if key in frame.columns), None)
                if "turnover" in frame.columns and turnover is not None and pd.notna(turnover):
                    turnover = float(turnover) * 100
                row.update({
                    "code": stock["code"], "name": stock["name"],
                    "price": close if pd.notna(close) else None,
                    "change_pct": change_pct, "turnover_rate": turnover,
                    "amount": last.get("amount"), "quote_date": str(last.get("date")),
                    "quote_source": "个股工作台本地日K（按名称匹配）",
                })
        enriched_rows.append(row)
    enriched = pd.DataFrame(enriched_rows)
    if "change_pct" in enriched.columns:
        enriched["change_pct"] = pd.to_numeric(enriched["change_pct"], errors="coerce")
        enriched = enriched.sort_values("change_pct", ascending=False, na_position="last", kind="stable").reset_index(drop=True)
    return enriched


def _latest_snapshot(directory: Path, prefix: str):
    files = sorted(directory.glob(f"{prefix}_*.csv"), reverse=True)
    if not files:
        return None, None
    path = files[0]
    try:
        return db_read_csv(path), path.stem.rsplit("_", 1)[-1]
    except UnicodeDecodeError:
        return db_read_csv(path), path.stem.rsplit("_", 1)[-1]


def _money_to_number(value):
    """将同花顺快照中的 1.2亿/3200万 等值统一为元，供排序和汇总使用。"""
    if value is None or pd.isna(value):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    text = str(value).strip().replace(",", "")
    if not text or text in {"--", "-"}:
        return None
    multiplier = 1.0
    if text.endswith("亿"):
        multiplier, text = 1e8, text[:-1]
    elif text.endswith("万"):
        multiplier, text = 1e4, text[:-1]
    try:
        return float(text) * multiplier
    except ValueError:
        return None


def _page_frame(frame, page=1, page_size=50, sort=None, ascending=False):
    page = max(1, int(page))
    page_size = min(200, max(1, int(page_size)))
    data = frame.copy()
    if sort and sort in data.columns:
        data = data.sort_values(sort, ascending=ascending, na_position="last")
    total = len(data)
    start = (page - 1) * page_size
    return data.iloc[start:start + page_size], total, page, page_size


def _flow_frame(directory: Path, prefix: str):
    frame, snapshot_date = _latest_snapshot(directory, prefix)
    if frame is None:
        return None, snapshot_date
    for col in ("流入资金", "流出资金", "净额", "成交额"):
        if col in frame.columns:
            frame[f"{col}_数值"] = frame[col].map(_money_to_number)
    return frame, snapshot_date


def market_overview():
    stock, stock_date = _flow_frame(FLOW_DIR, "stock_moneyflow")
    industry, industry_date = _flow_frame(Path(BOARD_DIR) / "资金流", "industry_moneyflow")
    concept, concept_date = _flow_frame(Path(BOARD_DIR) / "资金流", "concept_moneyflow")

    def summary(frame):
        if frame is None:
            return {"available": False}
        out = {"available": True, "count": len(frame)}
        for col in ("流入资金", "流出资金", "净额", "成交额"):
            numeric = f"{col}_数值"
            if numeric in frame:
                out[col] = float(frame[numeric].fillna(0).sum())
        return out

    def top(frame, count=8):
        if frame is None:
            return []
        numeric = "净额_数值" if "净额_数值" in frame else None
        if not numeric:
            return frame.head(count).to_dict(orient="records")
        return frame.sort_values(numeric, ascending=False).head(count).to_dict(orient="records")

    return {
        "dates": {"stock_moneyflow": stock_date, "industry_moneyflow": industry_date, "concept_moneyflow": concept_date},
        "stock_moneyflow": summary(stock),
        "industry_moneyflow": summary(industry),
        "concept_moneyflow": summary(concept),
        "top_stock_inflow": top(stock),
        "top_industry": top(industry),
        "top_concept": top(concept),
        "sync": get_sync_status(),
    }


def data_coverage():
    raw_files = list(Path(STOCK_DIR).joinpath("原始数据").glob("*_原始数据.csv"))
    indicator_files = list(Path(STOCK_DIR).joinpath("完整指标").glob("*_完整指标.csv"))
    board_industry = list((Path(BOARD_DIR) / "行业板块").glob("*.csv"))
    board_concept = list((Path(BOARD_DIR) / "概念板块").glob("*.csv"))
    member_industry = list((Path(BOARD_DIR) / "成分股" / "industry").glob("*.csv"))
    member_concept = list((Path(BOARD_DIR) / "成分股" / "concept").glob("*.csv"))
    return {
        "database": migration_status(),
        "stocks": {"raw_files": len(raw_files), "indicator_files": len(indicator_files)},
        "boards": {
            "industry_kline_files": len(board_industry), "concept_kline_files": len(board_concept),
            "industry_member_files": len(member_industry), "concept_member_files": len(member_concept),
        },
        "snapshots": {
            "stock_moneyflow": _latest_snapshot(FLOW_DIR, "stock_moneyflow")[1],
            "stock_snapshot": _latest_snapshot(SNAPSHOT_DIR, "stock_snapshot")[1],
            "industry_moneyflow": _latest_snapshot(Path(BOARD_DIR) / "资金流", "industry_moneyflow")[1],
            "concept_moneyflow": _latest_snapshot(Path(BOARD_DIR) / "资金流", "concept_moneyflow")[1],
        },
        "macro": {
            "yield_curves": sum(1 for config in YIELD_SOURCES.values() if (YIELD_DIR / config["file"]).exists()),
            "global_asset_files": sum(1 for config in ASSET_SOURCES.values() if (ASSET_DIR / config["file"]).exists()),
            "fx_available": FX_FILE.exists(),
            "cffex_daily_files": len(list(FUTURES_DAILY_DIR.glob("cffex_rank_*.csv"))),
            "cffex_summary_rows": len(db_read_csv(FUTURES_SUMMARY_FILE)) if FUTURES_SUMMARY_FILE.exists() else 0,
            "cffex_member_total_rows": len(db_read_csv(FUTURES_MEMBER_TOTAL_FILE)) if FUTURES_MEMBER_TOTAL_FILE.exists() else 0,
        },
        "sync": get_sync_status(),
    }


def get_yield_curve(country: str):
    config = YIELD_SOURCES.get(country)
    if not config:
        return None
    path = YIELD_DIR / config["file"]
    if not path.exists():
        return None
    frame = db_read_csv(path)
    return frame.dropna(subset=["date"]).sort_values("date")


def get_global_asset(symbol: str):
    config = ASSET_SOURCES.get(symbol)
    if not config:
        return None
    path = ASSET_DIR / config["file"]
    if not path.exists():
        return None
    return db_read_csv(path).dropna(subset=["date", "close"]).sort_values("date")


def get_fx_history():
    if not FX_FILE.exists():
        return None
    return db_read_csv(FX_FILE).dropna(subset=["date"]).sort_values("date")


def get_cffex_summary(variety: str | None = None):
    if not FUTURES_SUMMARY_FILE.exists():
        return None
    frame = db_read_csv(FUTURES_SUMMARY_FILE)
    if variety and variety in {"IF", "IH", "IC", "IM"}:
        frame = frame[frame["variety"] == variety]
    return frame.sort_values(["date", "symbol"])


def get_cffex_member_totals(variety: str | None = None):
    if not FUTURES_MEMBER_TOTAL_FILE.exists():
        return None
    frame = db_read_csv(FUTURES_MEMBER_TOTAL_FILE)
    if variety and variety in {"IF", "IH", "IC", "IM"}:
        frame = frame[frame["variety"] == variety]
    return frame.sort_values(["date", "variety"])


def get_cffex_position_changes(scope: str = "citic"):
    """Return latest changes for Citic, all published members, or all excluding Citic."""
    if not FUTURES_MEMBER_TOTAL_FILE.exists():
        return None
    frame = db_read_csv(FUTURES_MEMBER_TOTAL_FILE)
    prefix = scope if scope in {"all", "other"} else "citic"
    long_column, short_column = f"{prefix}_long", f"{prefix}_short"
    required = {"date", "variety", long_column, short_column}
    if not required.issubset(frame.columns):
        return None

    labels = [("IH", "上证50"), ("IF", "沪深300"), ("IC", "中证500"), ("IM", "中证1000")]
    rows = []
    for variety, label in labels:
        history = (frame[frame["variety"] == variety]
                   .dropna(subset=["date", long_column, short_column])
                   .drop_duplicates(subset=["date"], keep="last")
                   .sort_values("date"))
        if len(history) < 2:
            continue
        previous, latest = history.iloc[-2], history.iloc[-1]
        long_change = int(round(float(latest[long_column]) - float(previous[long_column])))
        short_change = int(round(float(latest[short_column]) - float(previous[short_column])))
        rows.append({
            "variety": variety,
            "contract_type": label,
            "date": str(latest["date"]),
            "previous_date": str(previous["date"]),
            "long_change": long_change,
            "short_change": short_change,
            "net_long_short": long_change - short_change,
        })
    if not rows:
        return None
    rows.append({
        "variety": "TOTAL",
        "contract_type": "合计",
        "date": max(row["date"] for row in rows),
        "previous_date": "",
        "long_change": sum(row["long_change"] for row in rows),
        "short_change": sum(row["short_change"] for row in rows),
        "net_long_short": sum(row["net_long_short"] for row in rows),
    })
    return rows


def get_cffex_rank(trade_date: str, variety: str | None = None):
    try:
        pd.Timestamp(trade_date)
    except ValueError:
        return None
    path = FUTURES_DAILY_DIR / f"cffex_rank_{trade_date}.csv"
    if not path.exists():
        return None
    frame = db_read_csv(path)
    if variety and "variety" in frame.columns:
        frame = frame[frame["variety"] == variety]
    return frame


def _lhb_file(directory: Path, prefix: str, trade_date: str | None = None) -> tuple[Path | None, str | None]:
    if trade_date:
        candidate = directory / f"{prefix}_{trade_date}.csv"
        return (candidate, trade_date) if candidate.exists() else (None, None)
    files = sorted(directory.glob(f"{prefix}_*.csv"), reverse=True)
    if not files:
        return None, None
    return files[0], files[0].stem.replace(f"{prefix}_", "", 1)


def _lhb_value(row: dict, *keys):
    for key in keys:
        value = row.get(key)
        if value is not None and not pd.isna(value):
            return value
    return None


def get_lhb_daily(trade_date: str | None = None) -> dict | None:
    daily_path, actual_date = _lhb_file(LHB_DAILY_DIR, "lhb_daily", trade_date)
    if daily_path is None:
        return None
    daily = db_read_csv(daily_path)
    institution_path, _ = _lhb_file(LHB_INSTITUTION_DIR, "lhb_institution", actual_date)
    institution = db_read_csv(institution_path) if institution_path else pd.DataFrame()
    institution_by_code = {}
    for raw in institution.to_dict(orient="records"):
        code = str(_lhb_value(raw, "代码", "股票代码") or "").replace(".0", "").zfill(6)
        institution_by_code[code] = raw

    records = []
    for raw in daily.to_dict(orient="records"):
        code = str(_lhb_value(raw, "代码", "股票代码") or "").replace(".0", "").zfill(6)
        inst = institution_by_code.get(code, {})
        net = _lhb_value(raw, "龙虎榜净买额", "净额")
        deal = _lhb_value(raw, "龙虎榜成交额", "成交额")
        inst_net = _lhb_value(inst, "机构买入净额")
        net_number = pd.to_numeric(net, errors="coerce")
        deal_number = pd.to_numeric(deal, errors="coerce")
        inst_number = pd.to_numeric(inst_net, errors="coerce")
        intensity = ((0 if pd.isna(net_number) else net_number) + (0 if pd.isna(inst_number) else inst_number)) / deal_number * 100 if pd.notna(deal_number) and deal_number else None
        records.append({
            "code": code, "name": _lhb_value(raw, "名称", "股票名称"), "date": actual_date,
            "close": _lhb_value(raw, "收盘价"), "change_pct": _lhb_value(raw, "涨跌幅", "对应值"),
            "reason": _lhb_value(raw, "上榜原因", "指标"), "turnover_rate": _lhb_value(raw, "换手率"),
            "buy_amount": _lhb_value(raw, "龙虎榜买入额"), "sell_amount": _lhb_value(raw, "龙虎榜卖出额"),
            "net_amount": net, "deal_amount": deal, "deal_ratio": _lhb_value(raw, "龙虎榜成交额占总成交额"),
            "institution_buy": _lhb_value(inst, "机构买入总额"), "institution_sell": _lhb_value(inst, "机构卖出总额"),
            "institution_net": inst_net, "buy_institutions": _lhb_value(inst, "买方机构数"), "sell_institutions": _lhb_value(inst, "卖方机构数"),
            "strength": intensity, "source": _lhb_value(raw, "数据源"),
        })
    records.sort(key=lambda row: float(row["net_amount"] or 0), reverse=True)
    return {"date": actual_date, "total": len(records), "data": records, "source": "东方财富；每日榜单失败时回退新浪"}


def normalize_lhb_seats(frame: pd.DataFrame) -> list[dict]:
    rows = []
    for raw in frame.to_dict(orient="records"):
        rows.append({
            "side": _lhb_value(raw, "方向"), "rank": _lhb_value(raw, "序号"),
            "seat": _lhb_value(raw, "交易营业部名称", "营业部名称"),
            "buy_amount": _lhb_value(raw, "买入金额"), "buy_ratio": _lhb_value(raw, "买入金额-占总成交比例"),
            "sell_amount": _lhb_value(raw, "卖出金额"), "sell_ratio": _lhb_value(raw, "卖出金额-占总成交比例"),
            "net_amount": _lhb_value(raw, "净额"), "reason": _lhb_value(raw, "类型", "上榜原因"),
        })
    return rows


def get_stock_lhb_events(code: str) -> list[dict]:
    if not LHB_EVENT_INDEX.exists():
        return []
    frame = db_read_csv(LHB_EVENT_INDEX)
    code_column = "代码" if "代码" in frame.columns else "股票代码"
    date_column = "上榜日" if "上榜日" in frame.columns else "交易日期"
    frame[code_column] = frame[code_column].astype(str).str.replace(".0", "", regex=False).str.zfill(6)
    selected = frame[frame[code_column] == str(code).zfill(6)].copy()
    events = []
    for trade_date, group in selected.groupby(date_column, dropna=False):
        reasons = [str(value) for value in group.get("上榜原因", pd.Series(dtype=str)).dropna().unique()]
        events.append({
            "date": str(trade_date)[:10], "code": str(code).zfill(6),
            "name": _lhb_value(group.iloc[0].to_dict(), "名称", "股票名称"),
            "reasons": reasons, "ziyang": bool(group.get("紫阳东路参与", pd.Series(False, index=group.index)).astype(str).str.lower().isin({"true", "1"}).any()),
            "net_amount": float(pd.to_numeric(group.get("龙虎榜净买额", pd.Series(dtype=float)), errors="coerce").fillna(0).sum()),
        })
    return sorted(events, key=lambda row: row["date"])


def get_ziyang_analysis() -> dict | None:
    files = sorted(LHB_SPECIAL_DIR.glob("ziyang_east_road_*.csv"), reverse=True)
    if not files:
        return None
    frame = db_read_csv(files[0])
    if frame.empty:
        return None
    numeric_columns = ["涨跌幅", "买入金额", "卖出金额", "净额", "1日后涨跌幅", "2日后涨跌幅", "3日后涨跌幅", "5日后涨跌幅", "10日后涨跌幅", "20日后涨跌幅", "30日后涨跌幅"]
    for column in numeric_columns:
        if column in frame.columns:
            frame[column] = pd.to_numeric(frame[column], errors="coerce")
    frame["交易日期"] = pd.to_datetime(frame["交易日期"], errors="coerce")
    frame = frame.dropna(subset=["交易日期"]).sort_values("交易日期", ascending=False)
    frame["股票代码"] = frame["股票代码"].astype(str).str.replace(".0", "", regex=False).str.zfill(6)
    buy_actions = frame[frame["净额"] > 0].copy()

    horizons = []
    for days in (1, 2, 3, 5, 10, 20, 30):
        column = f"{days}日后涨跌幅"
        values = buy_actions[column].dropna() if column in buy_actions else pd.Series(dtype=float)
        horizons.append({"days": days, "samples": len(values), "average_return": float(values.mean()) if len(values) else None, "win_rate": float((values > 0).mean() * 100) if len(values) else None})

    monthly = frame.assign(month=frame["交易日期"].dt.strftime("%Y-%m")).groupby("month", as_index=False).agg(
        operations=("股票代码", "size"), buy_amount=("买入金额", "sum"), sell_amount=("卖出金额", "sum"), net_amount=("净额", "sum")
    ).sort_values("month")
    reasons = frame.groupby("上榜原因", dropna=False).agg(operations=("股票代码", "size"), net_amount=("净额", "sum")).reset_index().sort_values("operations", ascending=False).head(12)
    repeats = frame.groupby(["股票代码", "股票名称"], dropna=False).agg(operations=("交易日期", "size"), net_amount=("净额", "sum"), last_date=("交易日期", "max")).reset_index().sort_values(["operations", "net_amount"], ascending=False).head(30)

    records = []
    for raw in frame.to_dict(orient="records"):
        records.append({
            "date": raw["交易日期"].strftime("%Y-%m-%d"), "code": raw.get("股票代码"), "name": raw.get("股票名称"),
            "change_pct": raw.get("涨跌幅"), "buy_amount": raw.get("买入金额"), "sell_amount": raw.get("卖出金额"), "net_amount": raw.get("净额"),
            "reason": raw.get("上榜原因"), **{f"return_{days}d": raw.get(f"{days}日后涨跌幅") for days in (1, 2, 3, 5, 10, 20, 30)},
        })
    return {
        "seat_code": "10026937", "seat_name": "国泰海通证券股份有限公司武汉紫阳东路证券营业部",
        "start": frame["交易日期"].min().strftime("%Y-%m-%d"), "end": frame["交易日期"].max().strftime("%Y-%m-%d"),
        "summary": {"operations": len(frame), "unique_stocks": int(frame["股票代码"].nunique()), "buy_actions": len(buy_actions), "buy_amount": float(frame["买入金额"].fillna(0).sum()), "sell_amount": float(frame["卖出金额"].fillna(0).sum()), "net_amount": float(frame["净额"].fillna(0).sum())},
        "horizons": horizons,
        "monthly": monthly.to_dict(orient="records"),
        "reasons": [{"reason": row.get("上榜原因") or "未分类", "operations": row["operations"], "net_amount": row["net_amount"]} for row in reasons.to_dict(orient="records")],
        "repeats": [{**row, "last_date": row["last_date"].strftime("%Y-%m-%d")} for row in repeats.to_dict(orient="records")],
        "data": records,
        "note": "收益统计仅针对席位当日净买入记录；席位名称是公开营业部席位，不等同于对单一自然人身份的确认。",
    }


def list_indices():
    result = []
    for code, config in INDEX_CONFIG.items():
        path = Path(INDEX_DIR) / f"{code}_{config['name']}.csv"
        member_path = Path(INDEX_MEMBER_DIR) / f"{code}_{config['name']}_latest.csv"
        result.append({"code": code, "name": config["name"], "available": path.exists(), "has_members": member_path.exists()})
    return result


def get_index_kline(code: str, period: str = "day"):
    config = INDEX_CONFIG.get(code)
    if not config:
        return None
    path = Path(INDEX_DIR) / f"{code}_{config['name']}.csv"
    if not path.exists():
        return None
    frame = db_read_csv(path)
    required = ["date", "open", "high", "low", "close", "volume"]
    if not set(required).issubset(frame.columns):
        return None
    if period != "day":
        return _resample_kline(frame, period)
    return frame


def get_index_members(code: str):
    config = INDEX_CONFIG.get(code)
    if not config:
        return None
    path = Path(INDEX_MEMBER_DIR) / f"{code}_{config['name']}_latest.csv"
    if not path.exists():
        return None
    frame = db_read_csv(path)
    if "code" in frame.columns:
        frame["code"] = frame["code"].astype(str).str.replace(".0", "", regex=False).str.zfill(6)
    flow, _ = _flow_frame(FLOW_DIR, "stock_moneyflow")
    if flow is not None and "股票代码" in flow.columns:
        quote = flow.copy()
        quote["code"] = quote["股票代码"].astype(str).str.replace(".0", "", regex=False).str.zfill(6)
        quote = quote.rename(columns={"最新价": "price", "涨跌幅": "change_pct", "换手率": "turnover_rate", "成交额": "amount"})
        available = [column for column in ("code", "price", "change_pct", "turnover_rate", "amount") if column in quote.columns]
        frame = frame.merge(quote[available].drop_duplicates("code"), on="code", how="left")
    return frame


def list_boards(board_type=None):
    mapping = {"industry": "行业板块", "concept": "概念板块"}
    result = []
    for key, dirname in mapping.items():
        if board_type and board_type != key:
            continue
        folder = Path(BOARD_DIR) / dirname
        for path in folder.glob("*.csv"):
            result.append({"type": key, "name": path.stem, "has_kline": True})
    return sorted(result, key=lambda item: (item["type"], item["name"]))


def get_board_kline(board_type, name, period="day"):
    folder_map = {"industry": "行业板块", "concept": "概念板块"}
    folder = folder_map.get(board_type)
    if not folder:
        return None
    path = Path(BOARD_DIR) / folder / f"{name}.csv"
    if not path.exists():
        return None
    df = db_read_csv(path)
    rename_map = {"日期": "date", "开盘价": "open", "最高价": "high", "最低价": "low", "收盘价": "close", "成交量": "volume", "成交额": "amount", "今日涨跌幅": "pct_change"}
    df = df.rename(columns=rename_map)
    required = ["date", "open", "high", "low", "close", "volume", "amount"]
    if not set(required).issubset(df.columns):
        return None
    df = df[required + (["pct_change"] if "pct_change" in df.columns else [])]
    if period != "day":
        result = _resample_kline(df, period)
        result.attrs["source"] = "同花顺板块K线（本地历史）"
        return result
    df["date"] = pd.to_datetime(df["date"]).dt.strftime("%Y-%m-%d")
    df.attrs["source"] = "同花顺板块K线（本地历史）"
    return df


def get_eastmoney_board_catalog(board_type: str):
    """Build the board center catalog from the same Eastmoney minute-flow master."""
    if board_type not in {"industry", "concept"}:
        return None, None
    files = sorted((BOARD_MAINFLOW_INTRADAY_DIR / board_type).glob("*.csv"), reverse=True)
    if not files:
        return None, None
    frame = db_read_csv(files[0])
    required = {"time", "code", "name", "main_net"}
    if not required.issubset(frame.columns):
        return None, None
    latest = frame.sort_values("time").groupby("code", as_index=False).tail(1).copy()
    latest["main_net"] = pd.to_numeric(latest["main_net"], errors="coerce")
    if "pct_change" in latest.columns:
        latest["pct_change"] = pd.to_numeric(latest["pct_change"], errors="coerce")
    latest = latest.sort_values("main_net", ascending=False, na_position="last")
    return latest, files[0].stem


def get_eastmoney_board_kline(code: str, period: str = "day"):
    if not re.fullmatch(r"BK\d+", str(code or ""), re.I):
        return None
    params = {
        "secid": f"90.{str(code).upper()}", "klt": 101, "fqt": 1, "lmt": 1000,
        "fields1": "f1,f2,f3,f4,f5,f6", "fields2": "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61",
        "ut": "b2884a393a59ad64002292a3e90d46a5",
    }
    response = requests.get("https://push2his.eastmoney.com/api/qt/stock/kline/get", params=params, headers={"User-Agent": "Mozilla/5.0", "Referer": "https://quote.eastmoney.com/"}, timeout=25)
    response.raise_for_status()
    lines = response.json().get("data", {}).get("klines", [])
    rows = []
    for line in lines:
        parts = str(line).split(",")
        if len(parts) >= 11:
            rows.append({"date": parts[0], "open": parts[1], "close": parts[2], "high": parts[3], "low": parts[4], "volume": parts[5], "amount": parts[6], "pct_change": parts[8]})
    frame = pd.DataFrame(rows)
    if frame.empty:
        return None
    for column in ("open", "close", "high", "low", "volume", "amount", "pct_change"):
        frame[column] = pd.to_numeric(frame[column], errors="coerce")
    result = _resample_kline(frame, period) if period != "day" else frame
    result.attrs["source"] = "东方财富官方板块K线"
    return result


def get_eastmoney_board_members(code: str):
    if not re.fullmatch(r"BK\d+", str(code or ""), re.I):
        return None
    params = {"pn": 1, "pz": 500, "po": 1, "np": 1, "fid": "f3", "fs": f"b:{str(code).upper()}", "fields": "f12,f14,f2,f3,f8,f6", "fltt": 2, "invt": 2, "ut": "bd1d9ddb04089700cf9c27f6"}
    response = requests.get("https://push2delay.eastmoney.com/api/qt/clist/get", params=params, headers={"User-Agent": "Mozilla/5.0", "Referer": "https://quote.eastmoney.com/"}, timeout=25)
    response.raise_for_status()
    diff = response.json().get("data", {}).get("diff", [])
    rows = list(diff.values()) if isinstance(diff, dict) else diff
    frame = pd.DataFrame(rows).rename(columns={"f12": "code", "f14": "name", "f2": "price", "f3": "change_pct", "f8": "turnover_rate", "f6": "amount"})
    if frame.empty:
        return None
    frame["code"] = frame["code"].astype(str).str.zfill(6)
    frame["quote_format"] = "decimal_v2"
    return frame


def get_eastmoney_board_members_cached(code: str, board_type: str | None = None, name: str | None = None):
    """Return persistent Eastmoney constituents; video inclusion depends on this cache."""
    cache_dir = Path(BOARD_MEMBER_DIR) / "eastmoney"
    cache_path = cache_dir / f"{str(code).upper()}.csv"
    if cache_path.exists():
        frame = db_read_csv(cache_path, dtype={"code": str})
        if not frame.empty:
            frame["code"] = frame["code"].astype(str).str.zfill(6)
            # Caches written before fltt=2 stored f2/f3/f8 as integers scaled by 100.
            raw_eastmoney_columns = {"price", "change_pct", "amount", "turnover_rate", "code", "name"}
            if "quote_format" not in frame.columns and set(frame.columns).issubset(raw_eastmoney_columns):
                for column in ("price", "change_pct", "turnover_rate"):
                    frame[column] = pd.to_numeric(frame[column], errors="coerce") / 100
                frame["quote_format"] = "decimal_v2"
                frame.to_csv(cache_path, index=False, encoding="utf-8-sig")
            return frame
    if board_type and name:
        legacy = get_cached_board_members(board_type, name)
        if legacy is not None and not legacy.empty:
            cache_dir.mkdir(parents=True, exist_ok=True)
            legacy.to_csv(cache_path, index=False, encoding="utf-8-sig")
            return legacy
    try:
        frame = get_eastmoney_board_members(code)
    except Exception:
        frame = get_cached_board_members(board_type, name) if board_type and name else None
    if frame is not None and not frame.empty:
        cache_dir.mkdir(parents=True, exist_ok=True)
        frame.to_csv(cache_path, index=False, encoding="utf-8-sig")
    return frame


def get_board_composite_kline(code: str, board_type: str, name: str, period: str = "day"):
    """Build an up-to-date equal-weight index from cached board constituents and local stock daily bars."""
    members = get_eastmoney_board_members_cached(code, board_type, name)
    if members is None or members.empty or "code" not in members.columns:
        return None
    normalized = []
    for stock_code in members["code"].astype(str).str.zfill(6).unique():
        stock_path, _ = _find_csv_path(stock_code)
        if not stock_path:
            continue
        try:
            bars = db_read_csv(stock_path, usecols=lambda col: col in {"date", "open", "high", "low", "close", "volume", "amount"}).tail(500)
        except Exception:
            continue
        if bars.empty or not {"date", "open", "high", "low", "close"}.issubset(bars.columns):
            continue
        for column in ("open", "high", "low", "close", "volume", "amount"):
            if column in bars.columns:
                bars[column] = pd.to_numeric(bars[column], errors="coerce")
        bars = bars.dropna(subset=["date", "close"])
        if bars.empty or not float(bars.iloc[0]["close"]):
            continue
        base = float(bars.iloc[0]["close"])
        for column in ("open", "high", "low", "close"):
            bars[column] = bars[column] / base * 1000
        normalized.append(bars)
    if not normalized:
        return None
    combined = pd.concat(normalized, ignore_index=True)
    aggregations = {"open": "mean", "high": "mean", "low": "mean", "close": "mean"}
    if "volume" in combined.columns:
        aggregations["volume"] = "sum"
    if "amount" in combined.columns:
        aggregations["amount"] = "sum"
    frame = combined.groupby("date", as_index=False).agg(aggregations).sort_values("date")
    frame["pct_change"] = frame["close"].pct_change() * 100
    result = _resample_kline(frame, period) if period != "day" else frame
    result.attrs["source"] = f"东方财富成分股等权合成（{len(normalized)}只本地股票）"
    return result


BOARD_META_PATTERN = re.compile(
    r"融资融券|沪股通|深股通|陆股通|MSCI|富时|标普|沪深\d+|上证\d+|深证\d+|中证\d+|"
    r"预增|预减|扭亏|首亏|续亏|业绩|百元股|低价股|高价股|破净股|次新股|大盘股|小盘股|"
    r"成长|价值|风格|题材股|昨日|涨停|连板|转债标的|基金重仓|机构重仓|QFII|社保重仓|证金持股",
    re.I,
)
BOARD_BROAD_NAMES = {
    "电子", "计算机", "通信", "传媒", "医药生物", "有色金属", "基础化工", "机械设备", "电力设备",
    "汽车", "银行", "非银金融", "房地产", "食品饮料", "家用电器", "商贸零售", "社会服务", "国防军工",
    "公用事业", "交通运输", "建筑装饰", "建筑材料", "农林牧渔", "煤炭", "钢铁", "石油石化", "环保",
    "综合", "科技", "消费", "金融", "制造", "新能源", "人工智能", "数字经济", "国企改革",
}
BOARD_FINE_INDUSTRY_PATTERN = re.compile(
    r"Ⅲ|设备|材料|元件|器件|零部件|制剂|原料药|研发外包|电池|电机|光模块|服务器|PCB|"
    r"玻璃|玻纤|铜|铝|锂|钴|稀土|黄金|白银|光伏|风电|电网|化纤|农药|化肥|饲料|养殖|"
    r"半导体|软件|通信设备|家居|家电|白酒|调味品|医疗服务|医疗器械|中药|军工电子|航空装备|航天装备",
    re.I,
)


def get_video_board_exclusions() -> dict[str, set[str]]:
    """Load user-selected board exclusions; codes are stable even if display names change."""
    try:
        payload = json.loads(VIDEO_BOARD_EXCLUSIONS_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        payload = {}
    return {
        "industry": {str(code).upper() for code in payload.get("industry", [])},
        "concept": {str(code).upper() for code in payload.get("concept", [])},
    }


def classify_board_for_video(board_type: str, name: str) -> dict:
    """Classify Eastmoney's flat taxonomy and admit only investable fine-grained leaves."""
    label = str(name or "").strip()
    if not label or BOARD_META_PATTERN.search(label):
        return {"level": "辅助标签", "eligible": False, "reason": "指数/风格/交易/事件标签"}
    if label in BOARD_BROAD_NAMES:
        return {"level": "一级大类", "eligible": False, "reason": "宽基产业板块"}
    if board_type == "concept":
        return {"level": "细分概念", "eligible": True, "reason": "东方财富产业概念"}
    if "Ⅲ" in label or label.endswith("III"):
        return {"level": "三级细分", "eligible": True, "reason": "明确的三级行业"}
    if "Ⅱ" in label or label.endswith("II"):
        return {"level": "二级赛道", "eligible": False, "reason": "二级行业默认不进细分视频"}
    if BOARD_FINE_INDUSTRY_PATTERN.search(label):
        return {"level": "三级细分", "eligible": True, "reason": "名称具有细分产业特征"}
    return {"level": "二级赛道", "eligible": False, "reason": "无法确认是叶子行业"}


def _verified_video_candidates(candidates: list[dict], count: int) -> list[dict]:
    """Only return boards whose constituent lists are locally recoverable."""
    probe = candidates[:max(24, count * 4)]
    if not probe or count <= 0:
        return []
    def verify(row):
        try:
            members = get_eastmoney_board_members_cached(row["code"], row["kind"], row["name"])
            if members is None or members.empty:
                return None
            enriched = dict(row)
            enriched["member_total"] = int(len(members))
            enriched["_member_codes"] = set(members["code"].astype(str)) if "code" in members.columns else set()
            return enriched
        except Exception:
            return None
    with ThreadPoolExecutor(max_workers=min(8, len(probe))) as executor:
        checked = list(executor.map(verify, probe))
    selected = []
    for row in (item for item in checked if item is not None):
        # Extremely wide or tiny baskets are poor representations of a tradable fine-grained theme.
        if row["member_total"] < 5 or row["member_total"] > 120:
            continue
        duplicate = False
        for kept in selected:
            smaller = min(len(row["_member_codes"]), len(kept["_member_codes"]))
            if smaller and len(row["_member_codes"] & kept["_member_codes"]) / smaller >= 0.75:
                duplicate = True
                break
        if duplicate:
            continue
        selected.append(row)
        if len(selected) >= count:
            break
    for row in selected:
        row.pop("_member_codes", None)
    return selected


def get_board_intraday_flow(board_type: str, name: str, trade_date: str | None = None, code: str | None = None):
    if board_type not in {"industry", "concept"}:
        return None
    direct_files = sorted((BOARD_MAINFLOW_INTRADAY_DIR / board_type).glob("*.csv"), reverse=True)
    direct_path = (BOARD_MAINFLOW_INTRADAY_DIR / board_type / f"{trade_date}.csv") if trade_date else (direct_files[0] if direct_files else None)
    if direct_path is not None and direct_path.exists():
        frame = db_read_csv(direct_path)
        if code and "code" in frame.columns:
            frame = frame[frame["code"].astype(str) == str(code)].copy()
        else:
            frame = frame[frame["name"].astype(str) == str(name)].copy()
        frame = frame.sort_values("timestamp")
        if not frame.empty:
            frame["net"] = pd.to_numeric(frame["main_net"], errors="coerce")
            frame["net_delta"] = frame["net"].diff().fillna(0)
            frame["net_delta_15m"] = frame["net"].diff(15).fillna(0)
            frame["data_source"] = "eastmoney_minute_mainflow"
            return frame
    return None


_top_flow_payload_cache: dict[tuple, tuple[tuple, float, dict]] = {}


def get_top_board_intraday_flow(kind: str = "all", limit: int = 20, session: str = "full"):
    """Return aligned minute main-flow series for the hottest latest-day boards."""
    if kind not in {"all", "industry", "concept"} or limit not in {10, 20, 30}:
        raise ValueError("kind 必须为 all/industry/concept，limit 必须为 10、20 或 30")
    if session not in {"morning", "full"}:
        raise ValueError("session 必须为 morning 或 full")
    kinds = ["industry", "concept"] if kind == "all" else [kind]
    signature = []
    if VIDEO_BOARD_EXCLUSIONS_FILE.exists():
        stat = VIDEO_BOARD_EXCLUSIONS_FILE.stat()
        signature.append((str(VIDEO_BOARD_EXCLUSIONS_FILE), stat.st_mtime_ns, stat.st_size))
    for board_kind in kinds:
        latest_files = sorted((BOARD_MAINFLOW_INTRADAY_DIR / board_kind).glob("*.csv"), reverse=True)
        if latest_files:
            stat = latest_files[0].stat()
            signature.append((str(latest_files[0]), stat.st_mtime_ns, stat.st_size))
    cache_key, signature_value = (kind, limit, session), tuple(signature)
    cached = _top_flow_payload_cache.get(cache_key)
    if cached and cached[0] == signature_value and time.monotonic() - cached[1] < 300:
        return cached[2]
    ranked = []
    exclusions = get_video_board_exclusions()
    trade_dates = []
    source_frames = {}
    for board_kind in kinds:
        minute_files = sorted((BOARD_MAINFLOW_INTRADAY_DIR / board_kind).glob("*.csv"), reverse=True)
        if not minute_files:
            continue
        minute_path = minute_files[0]
        trade_dates.append(minute_path.stem)
        minute_frame = db_read_csv(minute_path, usecols=["time", "code", "name", "main_net"])
        minute_frame["time"] = minute_frame["time"].astype(str).str.slice(0, 5)
        if session == "morning":
            minute_frame = minute_frame[minute_frame["time"] <= "11:30"].copy()
        source_frames[board_kind] = minute_frame
        minute_frame["main_net"] = pd.to_numeric(minute_frame["main_net"], errors="coerce")
        latest = minute_frame.dropna(subset=["code", "name", "main_net"]).sort_values("time").groupby("code", as_index=False).tail(1)
        latest["_heat"] = latest["main_net"].abs()
        for row in latest.to_dict(orient="records"):
            if str(row["code"]).upper() in exclusions[board_kind]:
                continue
            taxonomy = classify_board_for_video(board_kind, str(row["name"]))
            if taxonomy["eligible"]:
                ranked.append({"kind": board_kind, "code": str(row["code"]), "name": str(row["name"]), "level": taxonomy["level"], "heat_score": float(row["_heat"]), "latest_value": float(row["main_net"]), "minute_path": minute_path})
    inflows = sorted((row for row in ranked if row["latest_value"] >= 0), key=lambda row: row["heat_score"], reverse=True)
    outflows = sorted((row for row in ranked if row["latest_value"] < 0), key=lambda row: row["heat_score"], reverse=True)
    side_count = limit // 2
    ranked = _verified_video_candidates(inflows, side_count) + _verified_video_candidates(outflows, side_count)
    ranked.sort(key=lambda row: row["heat_score"], reverse=True)
    if not ranked:
        payload = {"trade_date": None, "times": [], "sectors": []}
        _top_flow_payload_cache[cache_key] = (signature_value, time.monotonic(), payload)
        return payload

    frames = {}
    for board_kind in {row["kind"] for row in ranked}:
        frames[board_kind] = source_frames[board_kind]
    times = sorted(set().union(*(set(frames[row["kind"]].loc[frames[row["kind"]]["code"].astype(str) == row["code"], "time"].astype(str)) for row in ranked)))
    sectors = []
    for rank, row in enumerate(ranked, 1):
        frame = frames[row["kind"]]
        series = frame[frame["code"].astype(str) == row["code"]].copy()
        series["main_net"] = pd.to_numeric(series["main_net"], errors="coerce")
        values = series.drop_duplicates("time", keep="last").set_index("time")["main_net"].reindex(times).ffill().fillna(0)
        sectors.append({"rank": rank, "kind": row["kind"], "code": row["code"], "name": row["name"], "level": row["level"], "member_total": row["member_total"], "heat_score": round(row["heat_score"], 2), "values": values.tolist()})
    payload = {"trade_date": max(trade_dates) if trade_dates else None, "session": session, "session_label": "午盘" if session == "morning" else "全天", "metric": "main_net", "selection": "细分概念/三级细分；用户排除名单、指数、风格、交易、事件及一级大类已排除；仅纳入已缓存成分股的板块", "times": times, "sectors": sectors}
    _top_flow_payload_cache[cache_key] = (signature_value, time.monotonic(), payload)
    return payload


def render_top_board_flow_mp4(kind: str = "all", limit: int = 20, duration: int = 20, session: str = "full") -> Path:
    """Render a clean 9:16 H.264 bar-race video from latest board minute flow."""
    from PIL import Image, ImageDraw, ImageFont
    import imageio_ffmpeg

    payload = get_top_board_intraday_flow(kind, limit, session)
    if not payload["sectors"] or not payload["times"]:
        raise ValueError("暂无可用于视频的板块分钟资金数据")
    width, height, fps = 720, 1280, 24
    frames = max(1, duration * fps)
    font_path = Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts" / "msyh.ttc"
    bold_path = Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts" / "msyhbd.ttc"
    title_font = ImageFont.truetype(str(bold_path if bold_path.exists() else font_path), 39)
    time_font = ImageFont.truetype(str(font_path), 24)
    name_font = ImageFont.truetype(str(bold_path if bold_path.exists() else font_path), 28 if limit == 10 else (24 if limit == 20 else 20))
    value_font = ImageFont.truetype(str(font_path), 26 if limit == 10 else (22 if limit == 20 else 18))
    date_value = pd.Timestamp(payload["trade_date"])
    title = f"{date_value.month}月{date_value.day}日{'午盘' if session == 'morning' else '收盘'}板块资金流向"
    signature = hashlib.sha1(json.dumps({"v": 2, "duration": duration, "fps": fps, "times": payload["times"], "sectors": [(item["code"], item["values"]) for item in payload["sectors"]]}, separators=(",", ":")).encode()).hexdigest()[:12]
    out = Path(tempfile.gettempdir()) / f"board_flow_{payload['trade_date']}_{session}_{kind}_top{limit}_{signature}.mp4"
    if out.exists() and out.stat().st_size > 50_000:
        return out
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    command = [ffmpeg, "-y", "-f", "rawvideo", "-vcodec", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{width}x{height}", "-r", str(fps), "-i", "-", "-an", "-vcodec", "libx264", "-pix_fmt", "yuv420p", "-preset", "veryfast", "-crf", "20", "-movflags", "+faststart", str(out)]
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    sectors = payload["sectors"]
    previous_y = {sector["name"]: float(index) for index, sector in enumerate(sectors)}
    row_height = (height - 175) / limit
    try:
        for frame_no in range(frames):
            source_pos = frame_no / max(1, frames - 1) * (len(payload["times"]) - 1)
            left = int(source_pos)
            right = min(left + 1, len(payload["times"]) - 1)
            fraction = source_pos - left
            current = []
            for sector in sectors:
                a, b = float(sector["values"][left] or 0), float(sector["values"][right] or 0)
                current.append({"name": sector["name"], "value": a + (b - a) * fraction})
            current.sort(key=lambda item: item["value"], reverse=True)
            targets = {item["name"]: float(index) for index, item in enumerate(current)}
            for name in previous_y:
                previous_y[name] += (targets[name] - previous_y[name]) * 0.18
            max_abs = max(abs(item["value"]) for item in current) or 1
            image = Image.new("RGB", (width, height), "#08111f")
            draw = ImageDraw.Draw(image)
            draw.text((width / 2, 38), title, font=title_font, fill="#f1f5f9", anchor="ma")
            time_label = payload["times"][min(right, len(payload["times"]) - 1)]
            draw.text((width / 2, 96), time_label, font=time_font, fill="#94a3b8", anchor="ma")
            center_x, max_bar_width = 390, 245
            draw.line((center_x, 132, center_x, height - 25), fill="#64748b", width=2)
            for item in current:
                y = 145 + previous_y[item["name"]] * row_height
                bar_top, bar_bottom = y + 7, y + row_height - 9
                bar_width = max(3, abs(item["value"]) / max_abs * max_bar_width)
                color = "#ef4444" if item["value"] >= 0 else "#22c55e"
                draw.text((22, y + row_height / 2), item["name"][:8], font=name_font, fill="#e2e8f0", anchor="lm")
                if item["value"] >= 0:
                    box = (center_x, bar_top, center_x + bar_width, bar_bottom)
                else:
                    box = (center_x - bar_width, bar_top, center_x, bar_bottom)
                draw.rounded_rectangle(box, radius=5, fill=color)
                draw.text((695, y + row_height / 2), f"{item['value'] / 1e8:+.2f}亿", font=value_font, fill=color, anchor="rm")
            process.stdin.write(image.tobytes())
    finally:
        if process.stdin:
            process.stdin.close()
        code = process.wait()
    if code != 0 or not out.exists():
        raise RuntimeError("MP4 编码失败")
    return out


def render_top_board_flow_lines_mp4(kind: str = "all", limit: int = 20, duration: int = 20, session: str = "full") -> Path:
    """Render a 9:16 animated line chart with labels following each line head."""
    from PIL import Image, ImageDraw, ImageFont
    import imageio_ffmpeg

    payload = get_top_board_intraday_flow(kind, limit, session)
    if not payload["sectors"] or not payload["times"]:
        raise ValueError("暂无可用于视频的板块分钟资金数据")
    width, height, fps = 720, 1280, 24
    frame_count = max(1, duration * fps)
    font_path = Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts" / "msyh.ttc"
    bold_path = Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts" / "msyhbd.ttc"
    title_font = ImageFont.truetype(str(bold_path if bold_path.exists() else font_path), 40)
    eyebrow_font = ImageFont.truetype(str(font_path), 17)
    axis_font = ImageFont.truetype(str(font_path), 17)
    label_font = ImageFont.truetype(str(font_path), 20 if limit == 10 else (17 if limit == 20 else 15))
    date_value = pd.Timestamp(payload["trade_date"])
    title = f"{date_value.month}月{date_value.day}日{'午盘' if session == 'morning' else '收盘'}板块资金流向"
    signature = hashlib.sha1(json.dumps({"v": 5, "duration": duration, "fps": fps, "times": payload["times"], "sectors": [(item["code"], item["values"]) for item in payload["sectors"]]}, separators=(",", ":")).encode()).hexdigest()[:12]
    out = Path(tempfile.gettempdir()) / f"board_flow_lines_{payload['trade_date']}_{session}_{kind}_top{limit}_{signature}.mp4"
    if out.exists() and out.stat().st_size > 50_000:
        return out
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    command = [ffmpeg, "-y", "-f", "rawvideo", "-vcodec", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{width}x{height}", "-r", str(fps), "-i", "-", "-an", "-vcodec", "libx264", "-pix_fmt", "yuv420p", "-preset", "veryfast", "-crf", "20", "-movflags", "+faststart", str(out)]
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    sectors, times = payload["sectors"], payload["times"]
    all_values = [float(value or 0) / 1e8 for sector in sectors for value in sector["values"]]
    raw_limit = (max(abs(value) for value in all_values) or 1) * 1.08
    magnitude = 10 ** math.floor(math.log10(raw_limit))
    normalized = raw_limit / magnitude
    nice_factor = next(value for value in (1, 1.25, 1.5, 2, 2.5, 5, 10) if normalized <= value)
    y_limit = nice_factor * magnitude
    palette = ["#ff453a", "#ff9f0a", "#ffd60a", "#ff375f", "#ff6482", "#bf5af2", "#64d2ff", "#0a84ff", "#5e5ce6", "#30d158", "#66d4cf", "#32d74b", "#00c7be", "#40c8e0", "#ac8e68", "#d0fd3e"]
    left_x, plot_right, top_y, bottom_y = 78, 500, 174, 1138
    # Static layered background: restrained gradient and a glass-like chart surface.
    base = Image.new("RGB", (width, height))
    base_pixels = base.load()
    for y in range(height):
        ratio = y / max(1, height - 1)
        red = int(7 + 5 * ratio)
        green = int(12 + 9 * ratio)
        blue = int(25 + 17 * ratio)
        for x in range(width):
            cool_glow = max(0, 1 - math.hypot(x - 610, y - 80) / 560)
            base_pixels[x, y] = (red + int(4 * cool_glow), green + int(9 * cool_glow), blue + int(18 * cool_glow))
    base_draw = ImageDraw.Draw(base)
    base_draw.rounded_rectangle((36, 132, width - 20, 1194), radius=30, fill="#0d1728", outline="#263449", width=2)
    base_draw.line((62, 150, width - 46, 150), fill="#334155", width=1)
    # Use trading-minute positions so the 11:30-13:00 lunch break consumes no video time or chart width.
    x_at = lambda index: left_x + index / max(1, len(times) - 1) * (plot_right - left_x)
    y_at = lambda value: top_y + (y_limit - value) / (2 * y_limit) * (bottom_y - top_y)
    last_frame_bytes = None
    render_count = max(2, (frame_count + 1) // 2)  # 12次位置更新/秒，输出仍保持24fps。
    try:
        for frame_no in range(frame_count):
            render_index = min(frame_no // 2, render_count - 1)
            # 相邻输出帧复用一次；视觉位置仍以12fps连续插值，兼顾流畅度和导出速度。
            if frame_no % 2 == 1 and last_frame_bytes is not None:
                process.stdin.write(last_frame_bytes)
                continue
            source_pos = render_index / max(1, render_count - 1) * (len(times) - 1)
            end_index = int(source_pos)
            next_index = min(end_index + 1, len(times) - 1)
            fraction = source_pos - end_index
            image = base.copy()
            draw = ImageDraw.Draw(image)
            draw.text((42, 28), "MARKET FLOW  ·  MAIN CAPITAL", font=eyebrow_font, fill="#7d8ca3", anchor="la")
            draw.text((42, 58), title, font=title_font, fill="#f5f7fb", anchor="la")
            time_text = times[min(int(round(source_pos)), len(times) - 1)]
            draw.rounded_rectangle((558, 44, 686, 92), radius=24, fill="#17243a", outline="#34445d", width=1)
            draw.ellipse((575, 64, 585, 74), fill="#30d158")
            draw.text((596, 68), time_text, font=axis_font, fill="#e8eef8", anchor="lm")
            draw.text((62, 160), "净流入 / 净流出（亿元）", font=eyebrow_font, fill="#8391a7", anchor="ls")
            zero_y = y_at(0)
            for ratio in (-1, -.75, -.5, -.25, 0, .25, .5, .75, 1):
                value = y_limit * ratio
                y = y_at(value)
                draw.line((left_x, y, plot_right, y), fill="#526079" if ratio == 0 else "#202d42", width=2 if ratio == 0 else 1)
                decimals = 0 if abs(value) >= 10 else 1
                draw.text((left_x - 10, y), f"{value:.{decimals}f}", font=axis_font, fill="#8290a5", anchor="rm")
            for ratio in (.25, .5, .75):
                x = left_x + (plot_right - left_x) * ratio
                draw.line((x, top_y, x, bottom_y), fill="#17243a", width=1)
            end_label = "11:30" if session == "morning" else "15:00"
            for index, label in [(0, "09:30"), (len(times) // 2, times[len(times) // 2]), (len(times) - 1, end_label)]:
                draw.text((x_at(index), bottom_y + 18), label, font=axis_font, fill="#94a3b8", anchor="ma")
            heads = []
            for sector_index, sector in enumerate(sectors):
                points = [(x_at(i), y_at(float(sector["values"][i] or 0) / 1e8)) for i in range(end_index + 1)]
                left_value = float(sector["values"][end_index] or 0) / 1e8
                right_value = float(sector["values"][next_index] or 0) / 1e8
                value = left_value + (right_value - left_value) * fraction
                if next_index > end_index and fraction > 0:
                    points.append((left_x + source_pos / max(1, len(times) - 1) * (plot_right - left_x), y_at(value)))
                color = palette[sector_index % len(palette)]
                if len(points) > 1:
                    red, green, blue = tuple(int(color[offset:offset+2], 16) for offset in (1, 3, 5))
                    halo = (int(red*.28+13*.72), int(green*.28+23*.72), int(blue*.28+40*.72))
                    draw.line(points, fill=halo, width=9, joint="curve")
                    draw.line(points, fill=color, width=3, joint="curve")
                draw.ellipse((points[-1][0]-5, points[-1][1]-5, points[-1][0]+5, points[-1][1]+5), fill="#f8fafc", outline=color, width=3)
                heads.append({"name": sector["name"], "value": value, "original_x": points[-1][0], "original_y": points[-1][1], "y": points[-1][1], "color": color})
            heads.sort(key=lambda item: item["y"])
            min_gap = 34 if limit == 10 else (27 if limit == 20 else 21)
            for index in range(1, len(heads)):
                heads[index]["y"] = max(heads[index]["y"], heads[index-1]["y"] + min_gap)
            overflow = heads[-1]["y"] - bottom_y if heads else 0
            if overflow > 0:
                for item in heads:
                    item["y"] -= overflow
            for item in heads:
                label = f"{item['name'][:7]} {item['value']:+.2f}"
                label_left = min(item["original_x"] + 16, width - 206)
                label_right = min(width - 16, label_left + 190)
                draw.line((item["original_x"] + 5, item["original_y"], label_left, item["y"]), fill=item["color"], width=2)
                draw.rounded_rectangle((label_left, item["y"] - 12, label_right, item["y"] + 13), radius=8, fill="#132036", outline=item["color"], width=1)
                draw.text((label_left + 7, item["y"]), label, font=label_font, fill="#f1f5f9", anchor="lm")
            last_frame_bytes = image.tobytes()
            process.stdin.write(last_frame_bytes)
    finally:
        if process.stdin:
            process.stdin.close()
        code = process.wait()
    if code != 0 or not out.exists():
        raise RuntimeError("曲线 MP4 编码失败")
    return out


class KlineHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=FRONTEND_DIR, **kwargs)

    def end_headers(self):
        # The dashboard is a live local application. Never let desktop/mobile
        # browsers retain an old HTML shell after the service has been updated.
        if urlparse(self.path).path in {"/", "/index.html"}:
            self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
            self.send_header("Pragma", "no-cache")
            self.send_header("Expires", "0")
        super().end_headers()

    def _send_json(self, data, status=200):
        cleaned = _clean_nan(data)
        body = json.dumps(cleaned, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionAbortedError, ConnectionResetError):
            # 浏览器刷新或切换页面时可能主动取消长 JSON 请求，不影响服务或同步任务。
            pass

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)

        if path == "/api/stocks":
            try:
                stocks = list_stocks()
                self._send_json({"total": len(stocks), "data": stocks})
            except Exception as e:
                self._send_json({"error": str(e)}, 500)
            return

        if path == "/api/sync/status":
            self._send_json(get_sync_status())
            return

        if path == "/api/sync/schedule-status":
            self._send_json(get_daily_schedule_status())
            return

        if path == "/api/financial-reports":
            code = query.get("code", [None])[0]
            if not code:
                self._send_json({"error": "缺少 code 参数"}, 400)
                return
            try:
                force = query.get("refresh", ["0"])[0] in {"1", "true", "yes"}
                self._send_json(fetch_company_financials(code, force=force))
            except ValueError as exc:
                self._send_json({"error": str(exc)}, 400)
            except Exception as exc:
                self._send_json({"error": f"财报获取失败：{str(exc)[:180]}"}, 500)
            return

        if path == "/api/quant-backtests":
            self._send_json(quant_backtest_report_list())
            return

        if path == "/api/quant-backtests/report":
            report_id = query.get("id", [None])[0]
            if not report_id:
                self._send_json({"error": "缺少 id 参数"}, 400)
                return
            report = next((item for item in load_quant_backtest_reports() if str(item.get("id")) == str(report_id)), None)
            if report is None:
                self._send_json({"error": "未找到该回测报告"}, 404)
            else:
                self._send_json(report)
            return

        if path == "/api/database/status":
            self._send_json(migration_status())
            return

        if path == "/api/lhb/daily":
            trade_date = query.get("date", [None])[0]
            data = get_lhb_daily(trade_date)
            if data is None:
                self._send_json({"error": "暂无龙虎榜本地归档，等待收盘后自动同步"}, 404)
            else:
                self._send_json(data)
            return

        if path == "/api/lhb/stock-events":
            code = query.get("code", [None])[0]
            if not code:
                self._send_json({"error": "缺少 code 参数"}, 400)
            else:
                events = get_stock_lhb_events(code)
                self._send_json({"code": code, "total": len(events), "data": events, "source": "SQLite近两年龙虎榜索引"})
            return

        if path == "/api/lhb/seats":
            code = query.get("code", [None])[0]
            trade_date = query.get("date", [None])[0]
            if not code or not trade_date:
                self._send_json({"error": "缺少 code 或 date 参数"}, 400)
                return
            try:
                seats = get_lhb_seat_details(code, trade_date)
                self._send_json({"code": code, "date": trade_date, "total": len(seats), "data": normalize_lhb_seats(seats), "source": "东方财富/SQLite本地缓存"})
            except Exception as exc:
                self._send_json({"error": f"龙虎榜席位获取失败: {exc}"}, 500)
            return

        if path == "/api/lhb/ziyang-east-road":
            data = get_ziyang_analysis()
            if data is None:
                self._send_json({"error": "紫阳东路历史数据尚未归档"}, 404)
            else:
                self._send_json(data)
            return

        if path == "/api/market/overview":
            try:
                self._send_json(market_overview())
            except Exception as exc:
                self._send_json({"error": str(exc)}, 500)
            return

        if path == "/api/data/coverage":
            self._send_json(data_coverage())
            return

        if path == "/api/macro/yields":
            country = query.get("country", [None])[0]
            data = get_yield_curve(country) if country else None
            if data is None:
                self._send_json({"error": "该国债收益率数据尚未同步完成"}, 404)
            else:
                config = YIELD_SOURCES[country]
                self._send_json({"country": country, "name": config["name"], "source": config["source"], "total": len(data), "data": data.to_dict(orient="records")})
            return

        if path == "/api/macro/assets":
            symbol = query.get("symbol", [None])[0]
            data = get_global_asset(symbol) if symbol else None
            if data is None:
                self._send_json({"error": "该全球资产历史数据尚未同步完成"}, 404)
            else:
                config = ASSET_SOURCES[symbol]
                self._send_json({"symbol": symbol, "name": config["name"], "source": "新浪外盘期货", "total": len(data), "data": data.to_dict(orient="records")})
            return

        if path == "/api/macro/fx":
            data = get_fx_history()
            if data is None:
                self._send_json({"error": "中日美韩汇率历史尚未同步完成"}, 404)
            else:
                self._send_json({"quote": "1 美元可兑换本币", "source": "FRED", "total": len(data), "data": data.to_dict(orient="records")})
            return

        if path == "/api/futures/cffex/summary":
            variety = query.get("variety", [None])[0]
            data = get_cffex_summary(variety)
            if data is None:
                self._send_json({"error": "股指期货持仓历史正在首次回补"}, 404)
            else:
                self._send_json({"variety": variety or "all", "member": "中信期货", "total": len(data), "data": data.to_dict(orient="records")})
            return

        if path == "/api/futures/cffex/member-totals":
            variety = query.get("variety", [None])[0]
            data = get_cffex_member_totals(variety)
            if data is None:
                self._send_json({"error": "会员累计多空数据正在生成"}, 404)
            else:
                self._send_json({"variety": variety or "all", "total": len(data), "data": data.to_dict(orient="records"), "note": "统计范围为中金所当日公布的会员排名；其他会员=公布会员合计-中信期货。"})
            return

        if path == "/api/futures/cffex/position-changes":
            scope = query.get("scope", ["citic"])[0]
            if scope not in {"citic", "all", "other"}:
                self._send_json({"error": "scope 必须为 citic、all 或 other"}, 400)
                return
            data = get_cffex_position_changes(scope)
            if data is None:
                self._send_json({"error": "股指期货净增数据正在生成"}, 404)
            else:
                member = {"all": "所有机构（交易所公布会员合计）", "other": "其他机构（排除中信期货）"}.get(scope, "中信期货")
                note = "净增为最新交易日较上一交易日的持仓变化；净多空=多单净增-空单净增。"
                if scope == "all":
                    note += "所有机构指中金所当日公布排名中的会员合计。"
                elif scope == "other":
                    note += "其他机构=中金所当日公布会员合计-中信期货。"
                self._send_json({"date": data[-1]["date"], "member": member, "scope": scope, "data": data, "note": note})
            return

        if path == "/api/futures/cffex/rank":
            trade_date = query.get("date", [None])[0]
            variety = query.get("variety", [None])[0]
            data = get_cffex_rank(trade_date, variety) if trade_date else None
            if data is None:
                self._send_json({"error": "该日期的交易所会员排名尚未归档"}, 404)
            else:
                self._send_json({"date": trade_date, "variety": variety or "all", "total": len(data), "data": data.to_dict(orient="records"), "note": "交易所公布的是期货公司会员排名，不等同于最终投资者机构持仓。"})
            return

        if path == "/api/stock/snapshot":
            frame, snapshot_date = _latest_snapshot(SNAPSHOT_DIR, "stock_snapshot")
            if frame is None:
                self._send_json({"error": "暂无快照，等待自动同步完成"}, 404)
            else:
                self._send_json({"date": snapshot_date, "total": len(frame), "data": frame.to_dict(orient="records")})
            return

        if path == "/api/stock/moneyflow":
            frame, snapshot_date = _latest_snapshot(FLOW_DIR, "stock_moneyflow")
            if frame is None:
                self._send_json({"error": "暂无资金流快照，等待自动同步完成"}, 404)
            else:
                self._send_json({"date": snapshot_date, "total": len(frame), "data": frame.to_dict(orient="records"), "source": "akshare/同花顺", "note": "免费模式从首次同步日起保存完整流入和流出快照"})
            return

        if path == "/api/stocks/rank":
            frame, snapshot_date = _flow_frame(FLOW_DIR, "stock_moneyflow")
            if frame is None:
                self._send_json({"error": "No stock money-flow snapshot is available yet."}, 404)
                return
            keyword = query.get("q", [""])[0].strip().lower()
            if keyword:
                searchable = frame.astype(str).apply(lambda column: column.str.lower().str.contains(keyword, na=False))
                frame = frame[searchable.any(axis=1)]
            page = query.get("page", ["1"])[0]
            page_size = query.get("page_size", ["50"])[0]
            rows, total, current_page, current_size = _page_frame(frame, page, page_size, "净额_数值")
            self._send_json({
                "date": snapshot_date,
                "total": total,
                "page": current_page,
                "page_size": current_size,
                "data": rows.to_dict(orient="records"),
            })
            return

        if path == "/api/indices":
            indices = list_indices()
            self._send_json({"total": len(indices), "data": indices})
            return

        if path == "/api/index/kline":
            code = query.get("code", [None])[0]
            period = query.get("period", ["day"])[0]
            if not code or period not in {"day", "week", "month", "year"}:
                self._send_json({"error": "code 和 period 参数无效"}, 400)
                return
            data = get_index_kline(code, period)
            if data is None:
                self._send_json({"error": "指数数据尚未同步完成"}, 404)
            else:
                self._send_json({"code": code, "name": INDEX_CONFIG[code]["name"], "period": period, "total": len(data), "data": data.to_dict(orient="records")})
            return

        if path == "/api/index/members":
            code = query.get("code", [None])[0]
            data = get_index_members(code) if code else None
            if data is None:
                self._send_json({"error": "该指数暂无最新成分股缓存"}, 404)
            else:
                self._send_json({"code": code, "name": INDEX_CONFIG[code]["name"], "total": len(data), "data": data.to_dict(orient="records")})
            return

        if path == "/api/boards":
            board_type = query.get("type", [None])[0]
            if board_type not in {None, "industry", "concept"}:
                self._send_json({"error": "type 必须为 industry 或 concept"}, 400)
            else:
                boards = list_boards(board_type)
                self._send_json({"total": len(boards), "data": boards})
            return

        if path == "/api/board/kline":
            board_type = query.get("type", [None])[0]
            name = query.get("name", [None])[0]
            period = query.get("period", ["day"])[0]
            if not board_type or not name:
                self._send_json({"error": "缺少 type 或 name 参数"}, 400)
                return
            if period not in {"day", "week", "month", "year"}:
                self._send_json({"error": "period 必须为 day/week/month/year"}, 400)
                return
            data = get_board_kline(board_type, name, period)
            if data is None:
                self._send_json({"error": "未找到同花顺板块K线"}, 404)
            else:
                source = data.attrs.get("source", "本地兼容数据")
                latest_date = str(data.iloc[-1]["date"]) if not data.empty and "date" in data.columns else None
                self._send_json({"type": board_type, "name": name, "period": period, "total": len(data), "latest_date": latest_date, "data": data.to_dict(orient="records"), "source": source})
            return

        if path == "/api/board/moneyflow":
            board_type = query.get("type", [None])[0]
            prefix = {"industry": "industry_moneyflow", "concept": "concept_moneyflow"}.get(board_type)
            if not prefix:
                self._send_json({"error": "type 必须为 industry 或 concept"}, 400)
                return
            frame, snapshot_date = _latest_snapshot(Path(BOARD_DIR) / "资金流", prefix)
            if frame is None:
                self._send_json({"error": "暂无同花顺板块资金流快照，等待自动同步完成"}, 404)
            else:
                self._send_json({"date": snapshot_date, "type": board_type, "total": len(frame), "data": frame.to_dict(orient="records"), "source": "akshare/同花顺"})
            return

        if path == "/api/eastmoney/boards":
            board_type = query.get("type", ["concept"])[0]
            frame, snapshot_date = get_eastmoney_board_catalog(board_type)
            if frame is None:
                self._send_json({"error": "暂无东方财富当日板块数据"}, 404)
            else:
                self._send_json({"date": snapshot_date, "type": board_type, "total": len(frame), "data": frame.to_dict(orient="records"), "source": "东方财富当日板块资金"})
            return

        if path == "/api/eastmoney/board/members":
            board_type = query.get("type", ["concept"])[0]
            code = query.get("code", [None])[0]
            name = query.get("name", [""])[0]
            if not code:
                self._send_json({"error": "缺少东方财富板块代码"}, 400)
                return
            try:
                members = get_eastmoney_board_members_cached(code, board_type, name)
                if members is None or members.empty:
                    self._send_json({"error": "该板块成分股尚未成功获取或缓存"}, 404)
                else:
                    self._send_json({"type": board_type, "code": code, "name": name, "total": len(members), "data": members.to_dict(orient="records"), "source": "东方财富成分股/本地缓存"})
            except Exception as exc:
                self._send_json({"error": f"东方财富成分股获取失败: {exc}"}, 500)
            return

        if path == "/api/board/top-intraday-flow":
            kind = query.get("kind", ["all"])[0]
            session = query.get("session", ["full"])[0]
            try:
                limit = int(query.get("limit", ["20"])[0])
                self._send_json(get_top_board_intraday_flow(kind, limit, session))
            except ValueError as exc:
                self._send_json({"error": str(exc)}, 400)
            except Exception as exc:
                self._send_json({"error": f"Top 板块分时资金加载失败: {exc}"}, 500)
            return

        if path == "/api/board/top-flow-video.mp4":
            kind = query.get("kind", ["all"])[0]
            session = query.get("session", ["full"])[0]
            try:
                limit = int(query.get("limit", ["20"])[0])
                video = render_top_board_flow_mp4(kind, limit, 20, session)
                body = video.read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", "video/mp4")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Content-Disposition", f'attachment; filename="board_flow_{kind}_top{limit}.mp4"')
                self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                self.end_headers()
                self.wfile.write(body)
            except ValueError as exc:
                self._send_json({"error": str(exc)}, 400)
            except Exception as exc:
                self._send_json({"error": f"MP4 生成失败: {exc}"}, 500)
            return

        if path == "/api/board/top-flow-lines-video.mp4":
            kind = query.get("kind", ["all"])[0]
            session = query.get("session", ["full"])[0]
            try:
                limit = int(query.get("limit", ["20"])[0])
                video = render_top_board_flow_lines_mp4(kind, limit, 20, session)
                body = video.read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", "video/mp4")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Content-Disposition", f'attachment; filename="board_flow_lines_{kind}_top{limit}.mp4"')
                self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                self.end_headers()
                self.wfile.write(body)
            except ValueError as exc:
                self._send_json({"error": str(exc)}, 400)
            except Exception as exc:
                self._send_json({"error": f"曲线 MP4 生成失败: {exc}"}, 500)
            return

        if path == "/api/board/intraday-flow":
            board_type = query.get("type", [None])[0]
            name = query.get("name", [None])[0]
            code = query.get("code", [None])[0]
            trade_date = query.get("date", [None])[0]
            if not board_type or not name:
                self._send_json({"error": "缺少 type 或 name 参数"}, 400)
                return
            data = get_board_intraday_flow(board_type, name, trade_date, code)
            if data is None:
                self._send_json({"error": "暂无该板块的分时资金流；东方财富数据将在午盘或收盘后回补。"}, 404)
            else:
                is_direct = "data_source" in data.columns and data["data_source"].eq("eastmoney_minute_mainflow").any()
                self._send_json({"type": board_type, "name": name, "date": trade_date or (data.iloc[0]["timestamp"][:10] if not data.empty else None), "total": len(data), "data": data.to_dict(orient="records"), "source": "东方财富当日 1 分钟主力资金" if is_direct else "akshare/同花顺 5分钟快照差分", "note": "净流入为累计主力净流入；分钟净流入由相邻分钟差分计算。" if is_direct else "净流入为累计值；5分钟净流入由相邻快照差分计算。"})
            return

        if path == "/api/board/members":
            board_type = query.get("type", [None])[0]
            name = query.get("name", [None])[0]
            if not board_type or not name:
                self._send_json({"error": "缺少 type 或 name 参数"}, 400)
                return
            try:
                members = get_cached_board_members(board_type, name)
                if members is None:
                    self._send_json({
                        "type": board_type, "name": name, "total": 0, "data": [], "state": "pending",
                        "message": "成分股正在后台预加载；完成后刷新页面即可从本地缓存读取。",
                    })
                else:
                    enriched = enrich_members_from_stock_workspace(members)
                    matched = int(enriched.get("quote_source", pd.Series(dtype=str)).notna().sum())
                    self._send_json({"type": board_type, "name": name, "total": len(enriched), "matched": matched, "data": enriched.to_dict(orient="records"), "state": "cached", "source": "同花顺成分关系 + 个股工作台本地日K行情（按名称匹配）"})
            except Exception as exc:
                self._send_json({"error": str(exc)}, 500)
            return

        if path == "/api/kline":
            code = query.get("code", [None])[0]
            period = query.get("period", ["day"])[0]
            limit_raw = query.get("limit", [None])[0]
            if not code:
                self._send_json({"error": "缺少 code 参数"}, 400)
                return
            if period not in {"day", "week", "month", "year"}:
                self._send_json({"error": "period 必须为 day/week/month/year"}, 400)
                return
            try:
                df = get_stock_kline(code, period)
                if df is None:
                    self._send_json({"error": f"未找到股票 {code}"}, 404)
                    return
                total_rows = len(df)
                # 支持 limit=N：只取最后 N 条，大幅减少自选股列表行情刷新时的传输量
                if limit_raw is not None:
                    try:
                        limit = int(limit_raw)
                        if limit > 0 and limit < total_rows:
                            df = df.tail(limit).reset_index(drop=True)
                    except (ValueError, TypeError):
                        pass
                records = df.where(pd.notnull(df), None).to_dict(orient="records")
                self._send_json({
                    "code": code,
                    "period": period,
                    "total": total_rows,
                    "columns": df.columns.tolist(),
                    "data": records,
                })
            except Exception as e:
                self._send_json({"error": str(e)}, 500)
            return

        return super().do_GET()

    def log_message(self, format, *args):
        return


class ExclusiveHTTPServer(ThreadingHTTPServer):
    """Windows 下显式独占端口，避免重复启动后浏览器随机命中旧服务。"""

    allow_reuse_address = False
    daemon_threads = True

    def server_bind(self):
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()


def main():
    host = "0.0.0.0"
    port = 8000
    try:
        server = ExclusiveHTTPServer((host, port), KlineHandler)
    except OSError as exc:
        if getattr(exc, "winerror", None) == 10048:
            print(f"[启动失败] 端口 {port} 已被占用。请关闭已运行的 kline_server.py 窗口，或在 PowerShell 执行：")
            print(f"  Get-NetTCPConnection -LocalPort {port} -State Listen | Select-Object -Expand OwningProcess")
            print("  Stop-Process -Id <上一步显示的进程号>")
            return
        raise
    started = maybe_start_background_sync()
    schedule_started = maybe_start_daily_scheduler()
    database_started = start_background_migration()
    print(f"K线图服务启动: http://localhost:{port}")
    print(f"  股票列表API: http://localhost:{port}/api/stocks")
    print(f"  K线数据API:  http://localhost:{port}/api/kline?code=000001&period=day")
    print(f"  自动免费同步: {'已启动' if started else '未启动/已有任务'}")
    print("  板块分时资金: 东方财富午盘/收盘后回补")
    print(f"  每日定时更新: {'已启动（11:35 午盘资金，18:00 全部数据）' if schedule_started else '未启动/已禁用'}")
    print(f"  SQLite 数据库: {'后台迁移已启动' if database_started else '迁移任务已运行'}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n服务已停止")
        server.server_close()


if __name__ == "__main__":
    main()
