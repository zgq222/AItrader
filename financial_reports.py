"""A-share historical financial summaries with local CSV + SQLite persistence."""
from __future__ import annotations

import math
import os
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta
from pathlib import Path

import akshare as ak
import pandas as pd
import requests

from market_database import import_csv as archive_csv_to_database, read_dataframe as db_read_csv

BASE_DIR = Path(__file__).resolve().parent
FINANCIAL_DIR = BASE_DIR / "财报"
SUMMARY_FILE = "历史关键指标.csv"
REFRESH_DAYS = max(1, int(os.getenv("AITRADER_FINANCIAL_REFRESH_DAYS", "7")))
SYNC_WORKERS = max(1, min(8, int(os.getenv("AITRADER_FINANCIAL_WORKERS", "4"))))
_locks_guard = threading.Lock()
_code_locks: dict[str, threading.Lock] = {}

# 输出字段、指标类别、摘要接口指标名。
METRICS = (
    ("parent_net_profit", "常用指标", "归母净利润"), ("revenue", "常用指标", "营业总收入"),
    ("deduct_net_profit", "常用指标", "扣非净利润"), ("total_equity", "常用指标", "股东权益合计(净资产)"),
    ("goodwill", "常用指标", "商誉"), ("operating_cashflow", "常用指标", "经营现金流量净额"),
    ("basic_eps", "常用指标", "基本每股收益"), ("net_asset_per_share", "常用指标", "每股净资产"),
    ("cashflow_per_share", "常用指标", "每股现金流"), ("roe", "常用指标", "净资产收益率(ROE)"),
    ("roa", "常用指标", "总资产报酬率(ROA)"), ("gross_margin", "常用指标", "毛利率"),
    ("net_margin", "常用指标", "销售净利率"), ("debt_ratio", "常用指标", "资产负债率"),
    ("revenue_yoy", "成长能力", "营业总收入增长率"),
    ("parent_net_profit_yoy", "成长能力", "归属母公司净利润增长率"),
    ("cash_to_profit", "收益质量", "经营活动净现金/归属母公司的净利润"),
    ("cash_to_revenue", "收益质量", "经营性现金净流量/营业总收入"),
    ("current_ratio", "财务风险", "流动比率"), ("quick_ratio", "财务风险", "速动比率"),
    ("receivable_turnover", "营运能力", "应收账款周转率"),
    ("inventory_turnover", "营运能力", "存货周转率"),
    ("total_asset_turnover", "营运能力", "总资产周转率"),
    ("free_cashflow_per_share", "每股指标", "每股企业自由现金流量"),
)


def _code_lock(code: str) -> threading.Lock:
    with _locks_guard:
        return _code_locks.setdefault(code, threading.Lock())


def _normalize_code(code: str) -> str:
    value = str(code or "").strip().replace(".0", "")
    if not value.isdigit() or len(value) > 6:
        raise ValueError("股票代码必须是6位数字")
    return value.zfill(6)


def _period_type(report_date: str) -> str:
    return {"03-31": "一季报", "06-30": "半年报", "09-30": "三季报", "12-31": "年报"}.get(str(report_date)[5:10], "其他")


def _number(value):
    try:
        number = float(value)
        return None if math.isnan(number) or math.isinf(number) else number
    except (TypeError, ValueError):
        return None


def _summary_path(code: str) -> Path:
    return FINANCIAL_DIR / code / SUMMARY_FILE


def _eastmoney_disclosure_rows(url: str, report_name: str, code: str, sort_column: str) -> list[dict]:
    params = {
        "sortColumns": sort_column, "sortTypes": "1", "pageSize": "500", "pageNumber": "1",
        "reportName": report_name, "columns": "ALL", "filter": f'(SECURITY_CODE="{code}")',
    }
    response = requests.get(url, params=params, timeout=25)
    response.raise_for_status()
    result = response.json().get("result") or {}
    rows = list(result.get("data") or [])
    for page in range(2, int(result.get("pages") or 1) + 1):
        params["pageNumber"] = str(page)
        response = requests.get(url, params=params, timeout=25)
        response.raise_for_status()
        rows.extend((response.json().get("result") or {}).get("data") or [])
    return rows


def _date_text(value) -> str | None:
    parsed = pd.to_datetime(value, errors="coerce")
    return None if pd.isna(parsed) else parsed.strftime("%Y-%m-%d")


def _fetch_disclosure_timeline(code: str) -> dict[str, dict]:
    """Earliest earnings forecast before the formal report, otherwise actual publication date."""
    forecasts = _eastmoney_disclosure_rows(
        "https://datacenter.eastmoney.com/securities/api/data/v1/get",
        "RPT_PUBLIC_OP_NEWPREDICT", code, "NOTICE_DATE",
    )
    formal_reports = _eastmoney_disclosure_rows(
        "https://datacenter-web.eastmoney.com/api/data/v1/get",
        "RPT_PUBLIC_BS_APPOIN", code, "FIRST_APPOINT_DATE",
    )
    forecast_dates: dict[str, list[str]] = {}
    for row in forecasts:
        # Only profit forecasts reveal the earnings metric used by this chart.
        if "净利润" not in str(row.get("PREDICT_FINANCE") or ""):
            continue
        report_date, notice_date = _date_text(row.get("REPORT_DATE")), _date_text(row.get("NOTICE_DATE"))
        if report_date and notice_date:
            forecast_dates.setdefault(report_date, []).append(notice_date)
    formal_dates = {}
    for row in formal_reports:
        report_date = _date_text(row.get("REPORT_DATE"))
        publish_date = _date_text(row.get("ACTUAL_PUBLISH_DATE"))
        if report_date and publish_date:
            formal_dates[report_date] = min(publish_date, formal_dates.get(report_date, publish_date))
    result = {}
    for report_date in set(forecast_dates) | set(formal_dates):
        formal_date = formal_dates.get(report_date)
        candidates = sorted(set(forecast_dates.get(report_date, [])))
        forecast_date = next((item for item in candidates if not formal_date or item < formal_date), None)
        info_date = forecast_date or formal_date
        result[report_date] = {
            "forecast_date": forecast_date,
            "formal_publish_date": formal_date,
            "info_date": info_date,
            "info_source": "业绩预告" if forecast_date else ("正式财报" if formal_date else None),
        }
    return result


def _attach_disclosure_timeline(code: str, frame: pd.DataFrame) -> pd.DataFrame:
    if frame.empty:
        return frame
    try:
        timeline = _fetch_disclosure_timeline(code)
    except Exception:
        timeline = {}
    if not timeline:
        return frame
    result = frame.copy()
    for column in ("forecast_date", "formal_publish_date", "info_date", "info_source"):
        result[column] = result["report_date"].map(lambda value: timeline.get(_date_text(value), {}).get(column))
    return result


def _download_summary(code: str) -> pd.DataFrame:
    """One request returns every available report period for one company."""
    source = ak.stock_financial_abstract(symbol=code)
    if source is None or source.empty or len(source.columns) < 3:
        return pd.DataFrame()
    category_col, metric_col = source.columns[:2]
    lookup = {(str(row[category_col]).strip(), str(row[metric_col]).strip()): row for _, row in source.iterrows()}
    records = []
    for raw_date in source.columns[2:]:
        digits = "".join(ch for ch in str(raw_date) if ch.isdigit())[:8]
        if len(digits) != 8:
            continue
        report_date = f"{digits[:4]}-{digits[4:6]}-{digits[6:8]}"
        record = {"code": code, "report_date": report_date, "period_type": _period_type(report_date)}
        for output, category, label in METRICS:
            row = lookup.get((category, label))
            record[output] = _number(row.get(raw_date)) if row is not None else None
        records.append(record)
    if not records:
        return pd.DataFrame()
    frame = pd.DataFrame(records).drop_duplicates("report_date").sort_values("report_date", ascending=False)
    return _attach_disclosure_timeline(code, frame)


def _write_summary(code: str, frame: pd.DataFrame) -> Path:
    path = _summary_path(code)
    path.parent.mkdir(parents=True, exist_ok=True)
    frame.to_csv(path, index=False, encoding="utf-8-sig")
    archive_csv_to_database(path, force=True)
    return path


def _is_stale(path: Path) -> bool:
    return not path.exists() or datetime.fromtimestamp(path.stat().st_mtime) < datetime.now() - timedelta(days=REFRESH_DAYS)


def fetch_company_financials(code: str, force: bool = False) -> dict:
    code = _normalize_code(code)
    path = _summary_path(code)
    with _code_lock(code):
        if force or not path.exists():
            frame = _download_summary(code)
            if frame.empty:
                raise RuntimeError(f"{code} 暂无财务摘要数据")
            _write_summary(code, frame)
        frame = db_read_csv(path)
        if "info_date" not in frame.columns:
            frame = _attach_disclosure_timeline(code, frame)
            _write_summary(code, frame)
    return build_financial_payload(code, frame, path)


def build_financial_payload(code: str, frame: pd.DataFrame, path: Path | None = None) -> dict:
    reports = []
    if frame is not None and not frame.empty:
        for raw in frame.sort_values("report_date", ascending=False).to_dict(orient="records"):
            reports.append({key: (None if pd.isna(value) else value) for key, value in raw.items() if key != "code"})
    updated_at = datetime.fromtimestamp(path.stat().st_mtime).isoformat(timespec="seconds") if path and path.exists() else datetime.now().isoformat(timespec="seconds")
    return {"code": code, "source": "新浪财经历史关键指标 / AKShare / SQLite本地缓存",
            "cache_scope": "全部历史报告期", "updated_at": updated_at, "total": len(reports),
            "latest": reports[0] if reports else None, "reports": reports}


def _all_stock_codes() -> list[str]:
    codes = set()
    raw_dir = BASE_DIR / "个股" / "原始数据"
    if raw_dir.exists():
        for path in raw_dir.glob("*.csv"):
            if path.name[:6].isdigit():
                codes.add(path.name[:6])
    snapshot_dir = BASE_DIR / "个股" / "每日快照"
    latest = sorted(snapshot_dir.glob("*.csv"), reverse=True)[:1] if snapshot_dir.exists() else []
    for path in latest:
        try:
            snapshot = pd.read_csv(path, encoding="utf-8-sig", dtype=str,
                                   usecols=lambda col: col in {"代码", "股票代码", "code"})
            for value in snapshot.iloc[:, 0].dropna() if not snapshot.empty else []:
                text = str(value).replace(".0", "").zfill(6)
                if text.isdigit():
                    codes.add(text)
        except Exception:
            pass
    return sorted(codes)


def sync_cached_financial_reports() -> dict:
    """Fill every company's history, then refresh caches older than REFRESH_DAYS."""
    codes = _all_stock_codes()
    due = [code for code in codes if _is_stale(_summary_path(code))]
    updated = no_data = failed = completed = 0

    def update(code: str):
        frame = _download_summary(code)
        if frame.empty:
            return "no_data"
        _write_summary(code, frame)
        return "updated"

    if due:
        with ThreadPoolExecutor(max_workers=SYNC_WORKERS) as executor:
            futures = {executor.submit(update, code): code for code in due}
            for future in as_completed(futures):
                completed += 1
                try:
                    result = future.result()
                    if result == "updated": updated += 1
                    else: no_data += 1
                except Exception:
                    failed += 1
                if completed % 25 == 0 or completed == len(due):
                    message = f"[财报缓存] {completed}/{len(due)} | 成功 {updated} | 无数据 {no_data} | 失败 {failed}"
                    print("\r" + message.ljust(100), end="" if completed < len(due) else "\n", flush=True)
    return {"companies": len(codes), "due": len(due), "updated": updated, "no_data": no_data,
            "failed": failed, "refresh_days": REFRESH_DAYS, "scope": "每家公司全部历史报告期关键指标"}
