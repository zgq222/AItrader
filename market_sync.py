# -*- coding: utf-8 -*-
"""免费优先的 A 股数据同步层。

默认使用依赖安装的 AKShare 与已有板块文件。Tushare/TickFlow 的字段契约
已经由统一输出列预留；后续配置 Token 时可以添加 provider，而无需改动 API 或前端。
"""
from __future__ import annotations

import json
import importlib.util
import os
import re
import threading
import time
import warnings
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Callable

import akshare as ak
import pandas as pd
import requests

from update_stocks import calculate_indicators
from market_database import import_csv as archive_csv_to_database
from financial_reports import sync_cached_financial_reports

BASE_DIR = Path(__file__).resolve().parent
try:
    import levistock as lk
except ImportError:
    lk = None
STOCK_DIR = BASE_DIR / "个股"
RAW_DIR = STOCK_DIR / "原始数据"
INDICATOR_DIR = STOCK_DIR / "完整指标"
FLOW_DIR = STOCK_DIR / "资金流"
SNAPSHOT_DIR = STOCK_DIR / "每日快照"
BOARD_DIR = BASE_DIR / "板块"
BOARD_FLOW_DIR = BOARD_DIR / "资金流"
BOARD_MAINFLOW_INTRADAY_DIR = BOARD_DIR / "分时主力资金"
BOARD_MEMBER_DIR = BOARD_DIR / "成分股"
INDEX_DIR = BASE_DIR / "指数"
INDEX_MEMBER_DIR = INDEX_DIR / "成分股"
MACRO_DIR = BASE_DIR / "宏观"
YIELD_DIR = MACRO_DIR / "国债收益率"
ASSET_DIR = MACRO_DIR / "大宗商品与加密资产"
FX_DIR = MACRO_DIR / "汇率"
FUTURES_DIR = BASE_DIR / "期货" / "股指期货持仓"
FUTURES_DAILY_DIR = FUTURES_DIR / "每日排名"
FUTURES_SUMMARY_FILE = FUTURES_DIR / "中信期货多空历史.csv"
FUTURES_MEMBER_TOTAL_FILE = FUTURES_DIR / "会员累计多空历史.csv"
LHB_DIR = BASE_DIR / "龙虎榜"
LHB_DAILY_DIR = LHB_DIR / "每日榜单"
LHB_INSTITUTION_DIR = LHB_DIR / "机构统计"
LHB_SEAT_DIR = LHB_DIR / "席位明细"
LHB_SPECIAL_DIR = LHB_DIR / "重点席位"
LHB_EVENT_INDEX = LHB_DIR / "近两年个股上榜索引.csv"
STATE_DIR = BASE_DIR / "data"
STATE_FILE = STATE_DIR / "sync_status.json"
SCHEDULE_STATE_FILE = STATE_DIR / "daily_schedule_status.json"
LOCK_FILE = STATE_DIR / "sync.lock"

_thread: threading.Thread | None = None
_thread_lock = threading.Lock()
_schedule_thread: threading.Thread | None = None
_schedule_thread_lock = threading.Lock()
_scheduled_job_lock = threading.Lock()
_status_write_lock = threading.Lock()
_kph_rankings: dict[str, list[dict]] = {}


def _now() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


def _ensure_dirs() -> None:
    for path in (RAW_DIR, INDICATOR_DIR, FLOW_DIR, SNAPSHOT_DIR, BOARD_FLOW_DIR, BOARD_MAINFLOW_INTRADAY_DIR / "industry", BOARD_MAINFLOW_INTRADAY_DIR / "concept", BOARD_MEMBER_DIR, INDEX_DIR, INDEX_MEMBER_DIR, YIELD_DIR, ASSET_DIR, FX_DIR, FUTURES_DAILY_DIR, LHB_DAILY_DIR, LHB_INSTITUTION_DIR, LHB_SEAT_DIR, LHB_SPECIAL_DIR, STATE_DIR):
        path.mkdir(parents=True, exist_ok=True)


def _default_status() -> dict:
    return {
        "mode": "free",
        "provider": "akshare",
        "state": "idle",
        "started_at": None,
        "finished_at": None,
        "last_completed_trade_date": None,
        "current_step": None,
        "progress": {"completed": 0, "total": 0, "failed": 0},
        "datasets": {},
        "recent_errors": [],
        "message": "尚未同步",
    }


def get_sync_status() -> dict:
    try:
        return {**_default_status(), **json.loads(STATE_FILE.read_text(encoding="utf-8"))}
    except (OSError, json.JSONDecodeError):
        return _default_status()


def _write_status(**updates: object) -> None:
    """原子更新进度；Windows 上被浏览器/安全软件短暂占用时不让同步任务失败。"""
    _ensure_dirs()
    with _status_write_lock:
        for attempt in range(5):
            temp = STATE_FILE.with_name(f".{STATE_FILE.stem}_{os.getpid()}_{threading.get_ident()}.tmp")
            try:
                status = get_sync_status()
                status.update(updates)
                temp.write_text(json.dumps(status, ensure_ascii=False, indent=2), encoding="utf-8")
                os.replace(temp, STATE_FILE)
                return
            except PermissionError:
                time.sleep(0.15 * (attempt + 1))
            except OSError as exc:
                # 临时状态无法写入不应中止耗时的数据抓取；下一次进度更新会重试。
                print(f"[同步] 状态文件暂时无法写入：{str(exc)[:100]}", flush=True)
                return
            finally:
                try:
                    temp.unlink(missing_ok=True)
                except OSError:
                    pass
        print("[同步] 状态文件被占用，已跳过本次进度写入。", flush=True)


def _expected_trade_date(today: date | None = None) -> date:
    """免费模式的保守收盘日期：周末回退到周五，交易日当天只在 16:30 后纳入。"""
    now = datetime.now()
    target = today or now.date()
    if target.weekday() >= 5 or (target == now.date() and now.hour < 16) or (target == now.date() and now.hour == 16 and now.minute < 30):
        target -= timedelta(days=1)
    while target.weekday() >= 5:
        target -= timedelta(days=1)
    return target


def _safe_name(name: object) -> str:
    return re.sub(r'[\\/*?:"<>|]', "", str(name))


def _standardize_history(df: pd.DataFrame) -> pd.DataFrame:
    mapping = {
        "日期": "date", "开盘": "open", "最高": "high", "最低": "low", "收盘": "close",
        "成交量": "volume", "成交额": "amount", "振幅": "amplitude", "涨跌幅": "pct_change",
        "涨跌额": "change_amount", "换手率": "turnover",
    }
    out = df.rename(columns={key: value for key, value in mapping.items() if key in df.columns}).copy()
    required = {"date", "open", "high", "low", "close", "volume"}
    if not required.issubset(out.columns):
        raise ValueError(f"日线字段不完整: 缺少 {sorted(required - set(out.columns))}")
    out["date"] = pd.to_datetime(out["date"], errors="coerce")
    for col in ("open", "high", "low", "close", "volume", "amount", "amplitude", "pct_change", "change_amount", "turnover"):
        if col in out.columns:
            out[col] = pd.to_numeric(out[col], errors="coerce")
    if "amount" not in out.columns:
        out["amount"] = pd.NA
    if "pct_change" not in out.columns:
        out["pct_change"] = out["close"].pct_change() * 100
    if "amplitude" not in out.columns:
        out["amplitude"] = (out["high"] - out["low"]) / out["close"].shift(1) * 100
    return out.dropna(subset=["date", "close"]).sort_values("date")


def _read_csv(path: Path) -> pd.DataFrame:
    try:
        return pd.read_csv(path, encoding="utf-8-sig")
    except UnicodeDecodeError:
        return pd.read_csv(path, encoding="gbk")


def _stock_files() -> dict[str, tuple[str, Path, Path]]:
    result: dict[str, tuple[str, Path, Path]] = {}
    for raw in RAW_DIR.glob("*_原始数据.csv"):
        match = re.match(r"^(\d{6})_(.+?)_原始数据\.csv$", raw.name)
        if not match:
            continue
        code, name = match.groups()
        indicator = INDICATOR_DIR / f"{code}_{name}_完整指标.csv"
        result[code] = (name, raw, indicator)
    return result


def _sina_symbol(code: str) -> str:
    """新浪日线的交易所前缀；北交所不支持时由东财路径兜底。"""
    if code.startswith(("6", "9")):
        return f"sh{code}"
    if code.startswith(("4", "8")):
        return f"bj{code}"
    return f"sz{code}"


def _fetch_free_daily(code: str, start_date: str, end_date: str) -> pd.DataFrame:
    """免费日线优先新浪，东方财富作为失败回退。

    当前 Windows 环境的系统代理会拦截部分 EastMoney HTTPS 请求；新浪接口与项目
    原有更新脚本兼容且能稳定补数，因此放在首位。
    """
    errors: list[str] = []
    try:
        data = ak.stock_zh_a_daily(symbol=_sina_symbol(code), start_date=start_date, end_date=end_date, adjust="")
        if data is not None and not data.empty:
            return data
        errors.append("新浪接口返回空数据")
    except Exception as exc:
        errors.append(f"新浪接口: {str(exc)[:100]}")
    try:
        data = ak.stock_zh_a_hist(symbol=code, period="daily", start_date=start_date, end_date=end_date, adjust="")
        if data is not None and not data.empty:
            return data
        errors.append("东方财富接口返回空数据")
    except Exception as exc:
        errors.append(f"东方财富接口: {str(exc)[:100]}")
    raise RuntimeError("; ".join(errors))


def sync_stock_daily(max_stocks: int = 0, delay_seconds: float = 0.12) -> dict:
    """以 AKShare 增量补齐本地日线；0 表示全部已有股票。"""
    files = _stock_files()
    target = _expected_trade_date()
    candidates: list[tuple[str, str, Path, Path, date]] = []
    for code, (name, raw_path, indicator_path) in files.items():
        try:
            raw = _standardize_history(_read_csv(raw_path))
            last = raw["date"].max().date()
            if last < target:
                candidates.append((code, name, raw_path, indicator_path, last))
        except Exception:
            candidates.append((code, name, raw_path, indicator_path, date(1990, 1, 1)))
    if max_stocks > 0:
        candidates = candidates[:max_stocks]

    total, updated, failed = len(candidates), 0, 0
    recent_errors: list[str] = []
    print(f"[同步] 个股日线待更新: {total} / 已检查本地股票: {len(files)} / 目标日期: {target.isoformat()}", flush=True)
    for index, (code, _name, raw_path, indicator_path, last) in enumerate(candidates, 1):
        _write_status(current_step=f"更新个股日线 {code}", progress={"completed": index - 1, "total": total, "failed": failed})
        try:
            start = (last + timedelta(days=1)).strftime("%Y%m%d")
            fetched = _fetch_free_daily(code, start, target.strftime("%Y%m%d"))
            if fetched is None or fetched.empty:
                continue
            fresh = _standardize_history(fetched)
            old = _standardize_history(_read_csv(raw_path))
            combined = pd.concat([old, fresh], ignore_index=True).drop_duplicates(subset=["date"], keep="last").sort_values("date")
            combined["date"] = combined["date"].dt.strftime("%Y-%m-%d")
            combined.to_csv(raw_path, index=False, encoding="utf-8-sig")
            archive_csv_to_database(raw_path, force=True)
            enriched = calculate_indicators(combined)
            if enriched is None:
                raise ValueError("指标计算未返回结果")
            enriched.to_csv(indicator_path, index=False, encoding="utf-8-sig")
            archive_csv_to_database(indicator_path, force=True)
            updated += 1
        except Exception as exc:
            failed += 1
            recent_errors.append(f"{code}: {str(exc)[:160]}")
            recent_errors = recent_errors[-20:]
        finally:
            _write_status(progress={"completed": index, "total": total, "failed": failed}, recent_errors=recent_errors)
            if index == 1 or index % 10 == 0 or index == total:
                print(
                    f"[同步] 日线进度 {index}/{total} | 成功更新 {updated} | 失败 {failed} | 当前 {code}",
                    flush=True,
                )
            time.sleep(delay_seconds)
    return {"target_date": target.isoformat(), "checked": len(files), "updated": updated, "failed": failed}


def _save_snapshot(df: pd.DataFrame, directory: Path, prefix: str, trade_date: date) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / f"{prefix}_{trade_date.isoformat()}.csv"
    df.to_csv(path, index=False, encoding="utf-8-sig")
    archive_csv_to_database(path, force=True)
    return path


def _atomic_csv(frame: pd.DataFrame, path: Path) -> None:
    """数据采集写入期间网页仍可读取旧文件，避免暴露半写入 CSV。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(f".{path.stem}_{os.getpid()}_{threading.get_ident()}.tmp")
    try:
        frame.to_csv(temp, index=False, encoding="utf-8-sig")
        os.replace(temp, path)
        archive_csv_to_database(path, force=True)
    finally:
        try:
            temp.unlink(missing_ok=True)
        except OSError:
            pass


def sync_free_snapshots() -> dict:
    """免费快照：全市场行情、个股资金流、行业/概念资金流。"""
    trade_date = _expected_trade_date()
    datasets: dict[str, dict] = {}
    jobs: list[tuple[str, Callable[[], pd.DataFrame], Path, str]] = [
        ("stock_snapshot", ak.stock_zh_a_spot_em, SNAPSHOT_DIR, "stock_snapshot"),
        ("stock_moneyflow", lambda: ak.stock_fund_flow_individual(symbol="即时"), FLOW_DIR, "stock_moneyflow"),
        ("industry_moneyflow", lambda: ak.stock_fund_flow_industry(symbol="即时"), BOARD_FLOW_DIR, "industry_moneyflow"),
        ("concept_moneyflow", lambda: ak.stock_fund_flow_concept(symbol="即时"), BOARD_FLOW_DIR, "concept_moneyflow"),
    ]
    for key, getter, directory, prefix in jobs:
        _write_status(current_step=f"同步 {key}")
        try:
            frame = getter()
            path = _save_snapshot(frame, directory, prefix, trade_date)
            datasets[key] = {"state": "ok", "rows": len(frame), "path": str(path.relative_to(BASE_DIR)), "source": "akshare"}
            print(f"[同步] {key}: 完成，{len(frame)} 条", flush=True)
        except Exception as exc:
            datasets[key] = {"state": "failed", "error": str(exc)[:160], "source": "akshare"}
            print(f"[同步] {key}: 失败，{str(exc)[:160]}", flush=True)
    return datasets


def _money_to_yuan(value: object) -> float | None:
    if value is None or pd.isna(value):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    text = str(value).strip().replace(",", "")
    if text in {"", "--", "-"}:
        return None
    factor = 1.0
    if text.endswith("亿"):
        factor, text = 1e8, text[:-1]
    elif text.endswith("万"):
        factor, text = 1e4, text[:-1]
    try:
        return float(text.replace("%", "")) * factor
    except ValueError:
        return None


EASTMONEY_DELAY_HOST = "https://push2delay.eastmoney.com"
EASTMONEY_HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36", "Referer": "https://data.eastmoney.com/bkzj/hy.html"}


def _eastmoney_board_snapshot(board_type: str) -> pd.DataFrame:
    fs = {"industry": "m:90 t:2", "concept": "m:90 t:3"}.get(board_type)
    if not fs:
        raise ValueError("board_type 必须为 industry 或 concept")
    rows: list[dict] = []
    session = requests.Session()
    for page in range(1, 10):
        params = {"pn": page, "pz": 100, "po": 1, "np": 1, "ut": "b2884a393a59ad64002292a3e90d46a5", "fltt": 2, "invt": 2, "fid0": "f62", "fs": fs, "fields": "f12,f14,f3,f62"}
        response = session.get(f"{EASTMONEY_DELAY_HOST}/api/qt/clist/get", params=params, headers=EASTMONEY_HEADERS, timeout=25)
        response.raise_for_status()
        diff = response.json().get("data", {}).get("diff", [])
        items = list(diff.values()) if isinstance(diff, dict) else diff
        rows.extend(items)
        if len(items) < 100:
            break
        time.sleep(0.08)
    frame = pd.DataFrame(rows).rename(columns={"f12": "code", "f14": "name", "f3": "pct_change", "f62": "snapshot_main_net"})
    if frame.empty or not {"code", "name"}.issubset(frame.columns):
        raise RuntimeError("东方财富板块快照为空")
    return frame.drop_duplicates("code")


def _eastmoney_board_mainflow(code: str, board_type: str, trade_date: date) -> pd.DataFrame:
    params = {"secid": f"90.{code}", "klt": 1, "lmt": 300, "fields1": "f1,f2,f3,f7", "fields2": "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61,f62,f63,f64,f65", "ut": "b2884a393a59ad64002292a3e90d46a5"}
    response = requests.get(f"{EASTMONEY_DELAY_HOST}/api/qt/stock/fflow/kline/get", params=params, headers=EASTMONEY_HEADERS, timeout=25)
    response.raise_for_status()
    lines = response.json().get("data", {}).get("klines", [])
    records = []
    prefix = trade_date.isoformat()
    for line in lines:
        parts = str(line).split(",")
        if len(parts) < 2 or not parts[0].startswith(prefix):
            continue
        values = [_money_to_yuan(part) for part in parts[1:6]]
        records.append({"timestamp": parts[0] + ":00", "time": parts[0][-5:], "type": board_type, "code": str(code), "main_net": values[0], "super_big_net": values[1] if len(values) > 1 else None, "big_net": values[2] if len(values) > 2 else None, "mid_net": values[3] if len(values) > 3 else None, "small_net": values[4] if len(values) > 4 else None})
    return pd.DataFrame(records)


def sync_board_mainflow_intraday(
    trade_date: date | None = None,
    delay_seconds: float = 0.10,
    force_refresh: bool = False,
    cutoff_time: str | None = None,
) -> dict:
    """从东方财富当日分钟主力资金接口回补行业和概念板块曲线，支持断点续跑。"""
    _ensure_dirs()
    target = trade_date or date.today()
    result: dict[str, dict] = {}
    for board_type in ("industry", "concept"):
        boards = _eastmoney_board_snapshot(board_type)
        path = BOARD_MAINFLOW_INTRADAY_DIR / board_type / f"{target.isoformat()}.csv"
        cached = _read_csv(path) if path.exists() else pd.DataFrame()
        cached_codes = set(cached.get("code", pd.Series(dtype=str)).astype(str))
        pending = boards.reset_index(drop=True) if force_refresh else boards[~boards["code"].astype(str).isin(cached_codes)].reset_index(drop=True)
        batches = [cached] if not cached.empty else []
        completed, failed = 0, 0
        print(f"[分时] 东方财富 {board_type} 分钟主力资金待下载: {len(pending)} / 总板块 {len(boards)}", flush=True)
        for index, board in enumerate(pending.itertuples(index=False), 1):
            try:
                frame = _eastmoney_board_mainflow(str(board.code), board_type, target)
                if not frame.empty:
                    if cutoff_time:
                        frame = frame[frame["time"].astype(str) <= cutoff_time].copy()
                    frame["name"] = board.name
                    frame["pct_change"] = board.pct_change
                    batches.append(frame)
                completed += 1
            except Exception as exc:
                failed += 1
                if failed <= 3:
                    print(f"[分时] {board_type} 分钟资金失败 {board.name}: {str(exc)[:100]}", flush=True)
            if index % 20 == 0 or index == len(pending):
                if batches:
                    merged = pd.concat(batches, ignore_index=True).drop_duplicates(["code", "time"], keep="last").sort_values(["code", "timestamp"])
                    _atomic_csv(merged, path)
                    batches = [merged]
                print(f"[分时] 东方财富 {board_type} 分钟资金进度 {index}/{len(pending)} | 成功 {completed} | 失败 {failed}", flush=True)
            time.sleep(delay_seconds)
        result[board_type] = {"total": len(boards), "downloaded": completed, "failed": failed, "path": str(path.relative_to(BASE_DIR))}
    return result


def _read_schedule_state() -> dict:
    try:
        return json.loads(SCHEDULE_STATE_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {"last_midday_date": None, "last_evening_date": None, "last_job": None, "state": "idle"}


def get_daily_schedule_status() -> dict:
    midday = os.getenv("AITRADER_MIDDAY_SYNC_TIME", "11:35").strip()
    evening = os.getenv("AITRADER_EVENING_SYNC_TIME", "18:00").strip()
    return {**_read_schedule_state(), "enabled": os.getenv("AITRADER_DAILY_SCHEDULE", "1").strip().lower() not in {"0", "false", "no"}, "midday_time": midday, "evening_time": evening}


def _write_schedule_state(**updates: object) -> None:
    _ensure_dirs()
    state = _read_schedule_state()
    state.update(updates)
    temp = SCHEDULE_STATE_FILE.with_name(f".{SCHEDULE_STATE_FILE.stem}_{os.getpid()}.tmp")
    try:
        temp.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(temp, SCHEDULE_STATE_FILE)
    finally:
        temp.unlink(missing_ok=True)


def _parse_schedule_time(env_name: str, default: str) -> tuple[int, int]:
    value = os.getenv(env_name, default).strip()
    try:
        hour, minute = (int(part) for part in value.split(":", 1))
        if not (0 <= hour <= 23 and 0 <= minute <= 59):
            raise ValueError
        return hour, minute
    except (TypeError, ValueError):
        print(f"[定时] {env_name}={value!r} 无效，使用默认时间 {default}", flush=True)
        return tuple(int(part) for part in default.split(":", 1))


def _run_midday_scheduled_sync(target: date) -> None:
    with _scheduled_job_lock:
        _write_schedule_state(state="running", last_job="midday", started_at=_now(), message="午盘资金流向更新中")
        print(f"[定时] {target} 午盘资金流向更新启动（截至 11:30）", flush=True)
        try:
            result = sync_board_mainflow_intraday(target, force_refresh=True, cutoff_time="11:30")
            _write_schedule_state(state="completed", last_midday_date=target.isoformat(), finished_at=_now(), result=result, message="午盘资金流向更新完成")
            print(f"[定时] {target} 午盘资金流向更新完成", flush=True)
        except Exception as exc:
            _write_schedule_state(state="failed", finished_at=_now(), message=f"午盘更新失败：{str(exc)[:180]}")
            print(f"[定时] 午盘资金流向更新失败：{str(exc)[:180]}", flush=True)


def _run_evening_scheduled_sync(target: date) -> None:
    with _scheduled_job_lock:
        _write_schedule_state(state="running", last_job="evening", started_at=_now(), message="晚间全部数据更新中")
        print(f"[定时] {target} 晚间全部数据更新启动", flush=True)
        try:
            mainflow = sync_board_mainflow_intraday(target, force_refresh=True)
            already_current = get_sync_status().get("last_completed_trade_date") == target.isoformat()
            full = {"state": "completed", "source": "startup_sync"} if already_current else run_free_sync()
            if full.get("state") == "already_running":
                raise RuntimeError("已有完整同步正在运行，稍后自动重试")
            if full.get("state") != "completed":
                raise RuntimeError(str(full.get("error") or "完整同步未完成"))
            _write_schedule_state(state="completed", last_evening_date=target.isoformat(), finished_at=_now(), result={"mainflow": mainflow, "full": full.get("state")}, message="晚间全部数据更新完成")
            print(f"[定时] {target} 晚间全部数据更新完成", flush=True)
        except Exception as exc:
            _write_schedule_state(state="failed", finished_at=_now(), message=f"晚间更新失败：{str(exc)[:180]}")
            print(f"[定时] 晚间全部数据更新失败：{str(exc)[:180]}", flush=True)


def _run_daily_scheduler() -> None:
    midday_hour, midday_minute = _parse_schedule_time("AITRADER_MIDDAY_SYNC_TIME", "11:35")
    evening_hour, evening_minute = _parse_schedule_time("AITRADER_EVENING_SYNC_TIME", "18:00")
    print(f"[定时] 每日更新已启动：午盘 {midday_hour:02d}:{midday_minute:02d}，全部 {evening_hour:02d}:{evening_minute:02d}", flush=True)
    while True:
        now = datetime.now()
        today = now.date()
        if today.weekday() < 5:
            state = _read_schedule_state()
            current = (now.hour, now.minute)
            evening_due = current >= (evening_hour, evening_minute)
            midday_due = current >= (midday_hour, midday_minute)
            # After the evening threshold, the full job includes the day's complete flow,
            # so a missed midday job is not run redundantly.
            if evening_due and state.get("last_evening_date") != today.isoformat():
                if not (_thread and _thread.is_alive()):
                    _run_evening_scheduled_sync(today)
            elif midday_due and state.get("last_midday_date") != today.isoformat():
                if not (_thread and _thread.is_alive()):
                    _run_midday_scheduled_sync(today)
        time.sleep(20)


def maybe_start_daily_scheduler() -> bool:
    """Start persistent in-process noon/evening updates while the Web service is running."""
    global _schedule_thread
    if os.getenv("AITRADER_DAILY_SCHEDULE", "1").strip().lower() in {"0", "false", "no"}:
        return False
    with _schedule_thread_lock:
        if _schedule_thread and _schedule_thread.is_alive():
            return False
        _schedule_thread = threading.Thread(target=_run_daily_scheduler, name="aitrader-daily-scheduler", daemon=True)
        _schedule_thread.start()
        return True


INDEX_CONFIG = {
    "000001": {"name": "上证指数", "source": "sina", "symbol": "sh000001"},
    "000016": {"name": "上证50", "source": "sina", "symbol": "sh000016"},
    "000010": {"name": "上证180", "source": "sina", "symbol": "sh000010"},
    "399001": {"name": "深证成指", "source": "sina", "symbol": "sz399001"},
    "399330": {"name": "深证100", "source": "sina", "symbol": "sz399330"},
    "399005": {"name": "中小100", "source": "sina", "symbol": "sz399005"},
    "000300": {"name": "沪深300", "source": "sina", "symbol": "sh000300"},
    "000903": {"name": "中证100", "source": "sina", "symbol": "sh000903"},
    "000905": {"name": "中证500", "source": "sina", "symbol": "sh000905"},
    "000852": {"name": "中证1000", "source": "sina", "symbol": "sh000852"},
    "932000": {"name": "中证2000", "source": "csindex", "symbol": "932000"},
    "000985": {"name": "中证全指", "source": "sina", "symbol": "sh000985"},
    "399006": {"name": "创业板指", "source": "sina", "symbol": "sz399006"},
    "399673": {"name": "创业板50", "source": "sina", "symbol": "sz399673"},
    "000688": {"name": "科创50", "source": "sina", "symbol": "sh000688"},
    "000698": {"name": "科创100", "source": "sina", "symbol": "sh000698"},
    "899050": {"name": "北证50", "source": "sina", "symbol": "bj899050"},
}

INDEX_MEMBER_CODES = {
    "000016", "000010", "399330", "399005", "000300", "000903", "000905", "000852", "932000",
    "399006", "399673", "000688", "000698", "899050",
}


def sync_indices() -> dict:
    """下载主要指数完整日线；文件本地化后供前端直接读取。"""
    _ensure_dirs()
    updated, failed = 0, 0
    for code, config in INDEX_CONFIG.items():
        try:
            if config["source"] == "sina":
                frame = ak.stock_zh_index_daily(symbol=config["symbol"])
                frame = frame.rename(columns={"date": "date", "open": "open", "high": "high", "low": "low", "close": "close", "volume": "volume"})
                if "amount" not in frame.columns:
                    frame["amount"] = pd.NA
            else:
                frame = ak.stock_zh_index_hist_csindex(symbol=config["symbol"])
                frame = frame.rename(columns={"日期": "date", "开盘": "open", "最高": "high", "最低": "low", "收盘": "close", "成交量": "volume", "成交金额": "amount", "涨跌幅": "pct_change"})
            frame["date"] = pd.to_datetime(frame["date"], errors="coerce").dt.strftime("%Y-%m-%d")
            frame = frame.dropna(subset=["date", "close"]).sort_values("date")
            index_path = INDEX_DIR / f"{code}_{config['name']}.csv"
            frame.to_csv(index_path, index=False, encoding="utf-8-sig")
            archive_csv_to_database(index_path, force=True)
            updated += 1
            print(f"[同步] 指数 {config['name']}: 完成，{len(frame)} 条", flush=True)
        except Exception as exc:
            failed += 1
            print(f"[同步] 指数 {config['name']}: 失败，{str(exc)[:120]}", flush=True)
    return {"updated": updated, "failed": failed, "total": len(INDEX_CONFIG)}


def sync_index_members() -> dict:
    """下载支持指数的最新成分股映射；历史成分变更不与当前快照混用。"""
    _ensure_dirs()
    updated, failed = 0, 0
    for code in sorted(INDEX_MEMBER_CODES):
        config = INDEX_CONFIG[code]
        try:
            frame = ak.index_stock_cons(symbol=code).rename(columns={"品种代码": "code", "品种名称": "name", "纳入日期": "in_date"})
            if not {"code", "name"}.issubset(frame.columns):
                raise ValueError("成分股字段不完整")
            frame["code"] = frame["code"].astype(str).str.zfill(6)
            frame["index_code"] = code
            frame["index_name"] = config["name"]
            frame["snapshot_date"] = _expected_trade_date().isoformat()
            member_path = INDEX_MEMBER_DIR / f"{code}_{config['name']}_latest.csv"
            frame.to_csv(member_path, index=False, encoding="utf-8-sig")
            archive_csv_to_database(member_path, force=True)
            updated += 1
            print(f"[同步] 指数成分 {config['name']}: 完成，{len(frame)} 条", flush=True)
        except Exception as exc:
            failed += 1
            print(f"[同步] 指数成分 {config['name']}: 失败，{str(exc)[:120]}", flush=True)
    return {"updated": updated, "failed": failed, "total": len(INDEX_MEMBER_CODES)}


YIELD_SOURCES = {
    "us": {"name": "美国国债", "file": "US_美国国债收益率.csv", "source": "FRED/美国财政部"},
    "jp": {"name": "日本国债", "file": "JP_日本国债收益率.csv", "source": "日本财务省"},
    "cn": {"name": "中国国债", "file": "CN_中国国债收益率.csv", "source": "中国债券信息网"},
}
ASSET_SOURCES = {
    "gold": {"name": "黄金（COMEX）", "symbol": "GC", "file": "黄金_COMEX.csv"},
    "brent": {"name": "布伦特原油（ICE）", "symbol": "OIL", "file": "布伦特原油_ICE.csv"},
    "wti": {"name": "WTI 美油（NYMEX）", "symbol": "CL", "file": "WTI美油_NYMEX.csv"},
    "bitcoin": {"name": "比特币（BTC）", "symbol": "BTC", "file": "比特币_BTC.csv"},
}
FX_FILE = FX_DIR / "中日美韩汇率.csv"


def _save_yield_curve(country: str, frame: pd.DataFrame) -> dict:
    config = YIELD_SOURCES[country]
    if frame.empty or "date" not in frame.columns:
        raise ValueError("收益率数据为空或缺少日期字段")
    frame["date"] = pd.to_datetime(frame["date"], errors="coerce")
    frame = frame.dropna(subset=["date"]).drop_duplicates("date", keep="last").sort_values("date")
    frame["date"] = frame["date"].dt.strftime("%Y-%m-%d")
    for column in frame.columns:
        if column != "date":
            frame[column] = pd.to_numeric(frame[column], errors="coerce")
    path = YIELD_DIR / config["file"]
    frame.to_csv(path, index=False, encoding="utf-8-sig")
    archive_csv_to_database(path, force=True)
    return {"state": "ok", "rows": len(frame), "path": str(path.relative_to(BASE_DIR)), "source": config["source"], "from": frame.iloc[0]["date"], "to": frame.iloc[-1]["date"]}


def sync_yield_curves() -> dict:
    """完整下载美、日、中三国国债收益率期限结构；韩国不纳入项目。"""
    _ensure_dirs()
    results: dict[str, dict] = {}
    try:
        # FRED 的 DGS 系列源自美国财政部每日恒定期限收益率，单次 CSV 包含全历史。
        series = "DGS1MO,DGS3MO,DGS6MO,DGS1,DGS2,DGS3,DGS5,DGS7,DGS10,DGS20,DGS30"
        url = f"https://fred.stlouisfed.org/graph/fredgraph.csv?id={series}"
        frame = pd.read_csv(url).rename(columns={"DATE": "date", "observation_date": "date"})
        rename = {"DGS1MO": "1月", "DGS3MO": "3月", "DGS6MO": "6月", "DGS1": "1年", "DGS2": "2年", "DGS3": "3年", "DGS5": "5年", "DGS7": "7年", "DGS10": "10年", "DGS20": "20年", "DGS30": "30年"}
        results["us"] = _save_yield_curve("us", frame.rename(columns=rename))
        print(f"[同步] 美国国债收益率: 完成，{results['us']['rows']} 条", flush=True)
    except Exception as exc:
        results["us"] = {"state": "failed", "error": str(exc)[:160]}
        print(f"[同步] 美国国债收益率: 失败，{str(exc)[:160]}", flush=True)
    try:
        # 日本财务省直接提供 1974 年以来的全历史 CSV，字段按列名保留期限。
        raw = pd.read_csv("https://www.mof.go.jp/jgbs/reference/interest_rate/data/jgbcm_all.csv", encoding="shift_jis", skiprows=1)
        raw = raw.rename(columns={raw.columns[0]: "date"})
        keep = ["date"] + [column for column in raw.columns[1:] if re.fullmatch(r"\d+年", str(column))]
        # 日期为 S49.9.24 / H31.4.26 / R8.7.31 等日本年号，pandas 无法自动推断。
        era_base = {"M": 1867, "T": 1911, "S": 1925, "H": 1988, "R": 2018}
        def parse_jgb_date(value: object):
            match = re.fullmatch(r"([MTSHR])(\d{1,2})\.(\d{1,2})\.(\d{1,2})", str(value).strip())
            if not match:
                return pd.NaT
            era, year, month, day = match.groups()
            try:
                return pd.Timestamp(year=era_base[era] + int(year), month=int(month), day=int(day))
            except ValueError:
                return pd.NaT
        jp_frame = raw[keep].copy()
        jp_frame["date"] = jp_frame["date"].map(parse_jgb_date)
        results["jp"] = _save_yield_curve("jp", jp_frame)
        print(f"[同步] 日本国债收益率: 完成，{results['jp']['rows']} 条", flush=True)
    except Exception as exc:
        results["jp"] = {"state": "failed", "error": str(exc)[:160]}
        print(f"[同步] 日本国债收益率: 失败，{str(exc)[:160]}", flush=True)
    try:
        # 中债历史接口要求每次查询不超过一年，分段拼接以保留接口可得全历史。
        start, end, chunks = date(2002, 1, 1), _expected_trade_date(), []
        cursor = start
        while cursor <= end:
            chunk_end = min(cursor + timedelta(days=364), end)
            try:
                part = ak.bond_china_yield(start_date=cursor.strftime("%Y%m%d"), end_date=chunk_end.strftime("%Y%m%d"))
                if part is not None and not part.empty:
                    chunks.append(part)
            except Exception as exc:
                print(f"[同步] 中国国债收益率分段失败 {cursor}：{str(exc)[:100]}", flush=True)
            cursor = chunk_end + timedelta(days=1)
        if not chunks:
            raise RuntimeError("中债接口未返回任何数据")
        frame = pd.concat(chunks, ignore_index=True).rename(columns={"日期": "date"})
        results["cn"] = _save_yield_curve("cn", frame)
        print(f"[同步] 中国国债收益率: 完成，{results['cn']['rows']} 条", flush=True)
    except Exception as exc:
        results["cn"] = {"state": "failed", "error": str(exc)[:160]}
        print(f"[同步] 中国国债收益率: 失败，{str(exc)[:160]}", flush=True)
    return results


def sync_global_assets_and_fx() -> dict:
    """下载黄金、布伦特、WTI、比特币和中/日/韩兑美元完整可得历史。"""
    _ensure_dirs()
    results: dict[str, dict] = {}
    for key, config in ASSET_SOURCES.items():
        try:
            frame = ak.futures_foreign_hist(symbol=config["symbol"])
            frame["date"] = pd.to_datetime(frame["date"], errors="coerce")
            frame = frame.dropna(subset=["date", "close"]).sort_values("date")
            for column in frame.columns:
                if column != "date":
                    frame[column] = pd.to_numeric(frame[column], errors="coerce")
            frame["date"] = frame["date"].dt.strftime("%Y-%m-%d")
            path = ASSET_DIR / config["file"]
            frame.to_csv(path, index=False, encoding="utf-8-sig")
            archive_csv_to_database(path, force=True)
            results[key] = {"state": "ok", "rows": len(frame), "path": str(path.relative_to(BASE_DIR)), "source": "新浪外盘期货", "from": frame.iloc[0]["date"], "to": frame.iloc[-1]["date"]}
            print(f"[同步] {config['name']}: 完成，{len(frame)} 条", flush=True)
        except Exception as exc:
            results[key] = {"state": "failed", "error": str(exc)[:160]}
            print(f"[同步] {config['name']}: 失败，{str(exc)[:160]}", flush=True)
    try:
        # FRED 三条序列均为“1 美元可兑换多少本币”，直接保留该统一方向。
        # 分三次读取，避免网络代理截断合并后的较大 CSV 响应。
        frame: pd.DataFrame | None = None
        for code, label in (("DEXCHUS", "人民币_CNY"), ("DEXJPUS", "日元_JPY"), ("DEXKOUS", "韩元_KRW")):
            last_error: Exception | None = None
            raw = None
            for attempt in range(2):
                try:
                    raw = pd.read_csv(f"https://fred.stlouisfed.org/graph/fredgraph.csv?id={code}").rename(columns={"observation_date": "date", "DATE": "date"})
                    break
                except Exception as exc:
                    last_error = exc
                    time.sleep(1 + attempt)
            if raw is None:
                raise RuntimeError(f"{code} 下载失败: {last_error}")
            part = raw[["date", code]].copy()
            part[f"USD兑{label}"] = pd.to_numeric(part[code], errors="coerce")
            part = part.drop(columns=[code])
            frame = part if frame is None else frame.merge(part, on="date", how="outer")
        if frame is None:
            raise RuntimeError("汇率数据为空")
        frame["date"] = pd.to_datetime(frame["date"], errors="coerce")
        frame = frame.dropna(subset=["date"]).drop_duplicates("date", keep="last").sort_values("date")
        frame["date"] = frame["date"].dt.strftime("%Y-%m-%d")
        frame.to_csv(FX_FILE, index=False, encoding="utf-8-sig")
        archive_csv_to_database(FX_FILE, force=True)
        results["fx"] = {"state": "ok", "rows": len(frame), "path": str(FX_FILE.relative_to(BASE_DIR)), "source": "FRED", "quote": "1 美元可兑换本币"}
        print(f"[同步] 中日美韩汇率: 完成，{len(frame)} 条", flush=True)
    except Exception as exc:
        results["fx"] = {"state": "failed", "error": str(exc)[:160]}
        print(f"[同步] 中日美韩汇率: 失败，{str(exc)[:160]}", flush=True)
    return results


def _cffex_summary_rows(trade_date: date, tables: dict) -> list[dict]:
    """从交易所会员排名中抽取中信期货多、空持仓；原始全会员排名同时归档。"""
    rows: list[dict] = []
    for symbol, frame in tables.items():
        if frame is None or frame.empty:
            continue
        data = frame.copy()
        variety = str(data.get("variety", pd.Series([str(symbol)[:2]])).iloc[0])
        def member_total(member_col: str, value_col: str, change_col: str) -> tuple[float, float]:
            if member_col not in data.columns:
                return 0.0, 0.0
            picked = data[data[member_col].fillna("").astype(str).str.contains("中信期货", na=False)]
            return (float(pd.to_numeric(picked.get(value_col), errors="coerce").fillna(0).sum()), float(pd.to_numeric(picked.get(change_col), errors="coerce").fillna(0).sum()))
        long_value, long_change = member_total("long_party_name", "long_open_interest", "long_open_interest_chg")
        short_value, short_change = member_total("short_party_name", "short_open_interest", "short_open_interest_chg")
        rows.append({"date": trade_date.isoformat(), "variety": variety, "symbol": str(symbol), "long_position": long_value, "short_position": short_value, "net_position": long_value - short_value, "long_change": long_change, "short_change": short_change, "net_change": long_change - short_change})
    return rows


def refresh_cffex_summary_from_cache() -> int:
    """由已归档的每日会员排名重建中信期货摘要，支持全历史任务尚未结束时前端先行展示。"""
    rows: list[dict] = []
    member_totals: list[dict] = []
    for path in sorted(FUTURES_DAILY_DIR.glob("cffex_rank_*.csv")):
        try:
            frame = _read_csv(path)
            if frame.empty or "trade_date" not in frame.columns or "contract" not in frame.columns:
                continue
            trade_day = pd.to_datetime(frame["trade_date"].iloc[0], errors="coerce")
            if pd.isna(trade_day):
                continue
            tables = {str(symbol): part for symbol, part in frame.groupby("contract")}
            rows.extend(_cffex_summary_rows(trade_day.date(), tables))
            for symbol, part in tables.items():
                variety = str(part.get("variety", pd.Series([str(symbol)[:2]])).iloc[0])
                def positions(member_col: str, position_col: str) -> tuple[float, float]:
                    values = pd.to_numeric(part.get(position_col), errors="coerce").fillna(0)
                    all_total = float(values.sum())
                    is_citic = part.get(member_col, pd.Series("", index=part.index)).fillna("").astype(str).str.contains("中信期货", na=False)
                    citic_total = float(values[is_citic].sum())
                    return citic_total, all_total
                citic_long, all_long = positions("long_party_name", "long_open_interest")
                citic_short, all_short = positions("short_party_name", "short_open_interest")
                member_totals.append({"date": trade_day.date().isoformat(), "variety": variety, "symbol": str(symbol), "citic_long": citic_long, "citic_short": citic_short, "other_long": all_long - citic_long, "other_short": all_short - citic_short, "all_long": all_long, "all_short": all_short})
        except Exception:
            continue
    if not rows:
        return 0
    summary = pd.DataFrame(rows).drop_duplicates(["date", "symbol"], keep="last").sort_values(["date", "symbol"])
    summary.to_csv(FUTURES_SUMMARY_FILE, index=False, encoding="utf-8-sig")
    archive_csv_to_database(FUTURES_SUMMARY_FILE, force=True)
    totals = pd.DataFrame(member_totals)
    totals = totals.groupby(["date", "variety"], as_index=False)[["citic_long", "citic_short", "other_long", "other_short", "all_long", "all_short"]].sum()
    for prefix in ("citic", "other", "all"):
        totals[f"{prefix}_net"] = totals[f"{prefix}_long"] - totals[f"{prefix}_short"]
    totals = totals.sort_values(["date", "variety"])
    totals.to_csv(FUTURES_MEMBER_TOTAL_FILE, index=False, encoding="utf-8-sig")
    archive_csv_to_database(FUTURES_MEMBER_TOTAL_FILE, force=True)
    return len(summary)


def sync_cffex_positions(start_date: date = date(2010, 4, 16)) -> dict:
    """回补中金所 IF/IH/IC/IM 全部可得交易日会员排名；本地已有日期不会重复请求。"""
    _ensure_dirs()
    cached_rows = refresh_cffex_summary_from_cache()
    if cached_rows:
        print(f"[同步] 股指期货中信摘要已从缓存生成，{cached_rows} 条", flush=True)
    target = _expected_trade_date()
    days = pd.bdate_range(start_date, target).date
    pending = [day for day in days if not (FUTURES_DAILY_DIR / f"cffex_rank_{day.isoformat()}.csv").exists()]
    total, saved, failed, new_summary = len(pending), 0, 0, []
    print(f"[同步] 股指期货会员持仓待回补: {total} / 全部交易日范围 {start_date} 至 {target}", flush=True)
    for index, trade_day in enumerate(pending, 1):
        _write_status(current_step=f"回补股指期货持仓 {trade_day}", progress={"completed": index - 1, "total": total, "failed": failed})
        try:
            # 交易所休市日是预期状态，静默处理 AKShare 的逐日提示并写入空缓存。
            with warnings.catch_warnings():
                warnings.filterwarnings("ignore", message=r"\d+非交易日", category=UserWarning)
                tables = ak.get_cffex_rank_table(date=trade_day.strftime("%Y%m%d"), vars_list=["IF", "IH", "IC", "IM"])
            raw = []
            for symbol, frame in (tables or {}).items():
                if frame is None or frame.empty:
                    continue
                item = frame.copy(); item["trade_date"] = trade_day.isoformat(); item["contract"] = str(symbol)
                raw.append(item)
            if raw:
                daily_path = FUTURES_DAILY_DIR / f"cffex_rank_{trade_day.isoformat()}.csv"
                pd.concat(raw, ignore_index=True).to_csv(daily_path, index=False, encoding="utf-8-sig")
                archive_csv_to_database(daily_path, force=True)
                new_summary.extend(_cffex_summary_rows(trade_day, tables))
            else:
                # 交易所休市日也标记为空缓存，避免每次启动重复请求。
                daily_path = FUTURES_DAILY_DIR / f"cffex_rank_{trade_day.isoformat()}.csv"
                pd.DataFrame(columns=["trade_date", "contract"]).to_csv(daily_path, index=False, encoding="utf-8-sig")
                archive_csv_to_database(daily_path, force=True)
            saved += 1
        except Exception as exc:
            failed += 1
            if failed <= 3 or index % 100 == 0:
                print(f"[同步] 股指期货持仓失败 {trade_day}: {str(exc)[:120]}", flush=True)
        finally:
            _write_status(progress={"completed": index, "total": total, "failed": failed})
            if index == 1 or index % 10 == 0 or index == total:
                # 任务很长，分批落盘摘要，使运行中的网页也能读取已完成的历史。
                summary_rows = refresh_cffex_summary_from_cache()
                print(f"[同步] 股指期货持仓进度 {index}/{total} | 已归档 {saved} | 失败 {failed} | 当前 {trade_day}", flush=True)
    summary_rows = refresh_cffex_summary_from_cache()
    return {"state": "ok", "pending": total, "archived": saved, "failed": failed, "summary_rows": summary_rows, "path": str(FUTURES_DIR.relative_to(BASE_DIR))}


def _kph_trade_date() -> str:
    """开盘红历史接口只接受已结束的交易日，固定使用上一自然日并回退周末。"""
    target = date.today() - timedelta(days=1)
    while target.weekday() >= 5:
        target -= timedelta(days=1)
    return target.isoformat()


def _board_code(board_type: str, board_name: str) -> str | None:
    folder = {"industry": "行业板块", "concept": "概念板块"}.get(board_type)
    path = BOARD_DIR / str(folder) / f"{board_name}.csv"
    if not path.exists():
        return None
    try:
        row = _read_csv(path).head(1)
        if row.empty or "板块代码" not in row.columns:
            return None
        return str(row.iloc[0]["板块代码"]).split(".")[0]
    except Exception:
        return None


def _get_kph_industry_members(board_name: str) -> pd.DataFrame:
    """通过开盘红获取同花顺行业板块的历史成分股，规避 EastMoney push2。"""
    if lk is None:
        raise RuntimeError("levistock 未安装")
    trade_date = _kph_trade_date()
    rankings = _kph_rankings.get(trade_date)
    if rankings is None:
        rankings = lk.sector_ranking_kph(date=trade_date, zs_type=lk.SECTOR_INDUSTRY, fetch_all=True)
        _kph_rankings[trade_date] = rankings
    code = _board_code("industry", board_name)
    plate = next((item for item in rankings if str(item.get("plate_id")) == str(code)), None)
    if plate is None:
        plate = next((item for item in rankings if item.get("plate_name") == board_name), None)
    if plate is None:
        raise RuntimeError(f"开盘红未找到行业板块: {board_name}")
    rows = lk.sector_stocks_his_kph(plate_id=str(plate["plate_id"]), date=trade_date)
    frame = pd.DataFrame(rows)
    if frame.empty:
        raise RuntimeError(f"开盘红未返回成分股: {board_name}")
    frame["数据日期"] = trade_date
    frame["数据源"] = "开盘红/levistock"
    return frame


def get_board_members(board_type: str, board_name: str) -> pd.DataFrame:
    """读取本地缓存；行业使用开盘红历史数据预载，不再请求被阻断的东财接口。"""
    if board_type not in {"industry", "concept"}:
        raise ValueError("board_type 必须为 industry 或 concept")
    cached = get_cached_board_members(board_type, board_name)
    if cached is not None:
        return cached
    if board_type != "industry":
        raise RuntimeError("概念成分股暂无可用的免费后备来源")
    frame = _get_kph_industry_members(board_name)
    _save_snapshot(frame, BOARD_MEMBER_DIR / board_type, _safe_name(board_name), date.fromisoformat(_kph_trade_date()))
    return frame


def get_cached_board_members(board_type: str, board_name: str) -> pd.DataFrame | None:
    """只读本地成分股缓存，供网页 API 使用，不触发在线请求。"""
    if board_type not in {"industry", "concept"}:
        raise ValueError("board_type 必须为 industry 或 concept")
    safe = _safe_name(board_name)
    cached = sorted((BOARD_MEMBER_DIR / board_type).glob(f"{safe}_*.csv"), reverse=True)
    if not cached:
        return None
    frame = _read_csv(cached[0])
    for column in ("code", "股票代码", "代码"):
        if column in frame.columns:
            frame[column] = frame[column].astype(str).str.replace(".0", "", regex=False).str.zfill(6)
    return frame


def sync_board_members(delay_seconds: float = 0.15) -> dict:
    """后台预加载行业成分股；概念成分股等待独立的 PIT 数据源接入。"""
    targets = [("industry", path.stem) for path in sorted((BOARD_DIR / "行业板块").glob("*.csv")) if get_cached_board_members("industry", path.stem) is None]
    concept_pending = sum(1 for path in (BOARD_DIR / "概念板块").glob("*.csv") if get_cached_board_members("concept", path.stem) is None)
    total, completed, cached, failed, consecutive_failures = len(targets), 0, 0, 0, 0
    print(f"[同步] 行业成分股待预载（开盘红）: {total}；概念成分股待接入 PIT 数据源: {concept_pending}", flush=True)
    for index, (board_type, name) in enumerate(targets, 1):
        _write_status(current_step=f"预载板块成分股 {name}", progress={"completed": completed, "total": total, "failed": failed})
        try:
            frame = get_board_members(board_type, name)
            if frame is not None and not frame.empty:
                cached += 1
                consecutive_failures = 0
            else:
                failed += 1
                consecutive_failures += 1
        except Exception as exc:
            failed += 1
            consecutive_failures += 1
            print(f"[同步] 成分股失败 {name}: {str(exc)[:100]}", flush=True)
        completed = index
        _write_status(progress={"completed": completed, "total": total, "failed": failed})
        if index == 1 or index % 10 == 0 or index == total:
            print(f"[同步] 成分股进度 {index}/{total} | 已缓存 {cached} | 失败 {failed} | 当前 {name}", flush=True)
        if consecutive_failures >= 3:
            print("[同步] 开盘红成分股源连续失败 3 次，已暂停本轮预载；下次启动会自动重试。", flush=True)
            break
        time.sleep(delay_seconds)
    return {"total": total, "completed": completed, "cached": cached, "failed": failed, "paused": consecutive_failures >= 3, "concept_pending": concept_pending}


def sync_ths_board_klines() -> dict:
    """Incrementally update and archive all Tonghuashun board daily bars locally."""
    script_path = BOARD_DIR / "fetch_board_hist_ths.py"
    if not script_path.exists():
        return {"state": "missing_script", "updated": 0, "message": str(script_path)}
    try:
        board_module_dir = str(BOARD_DIR)
        import sys
        if board_module_dir not in sys.path:
            sys.path.insert(0, board_module_dir)
        spec = importlib.util.spec_from_file_location("aitrader_fetch_board_hist_ths", script_path)
        if spec is None or spec.loader is None:
            raise RuntimeError("无法加载同花顺板块增量同步模块")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        print("[同步] 同花顺板块K线增量更新开始（已有历史仅补当前年份并合并存档）", flush=True)
        module.fetch_board_hist(module.BOARD_TYPE_INDUSTRY, incremental=True)
        module.fetch_board_hist(module.BOARD_TYPE_CONCEPT, incremental=True)
        files = list((BOARD_DIR / "行业板块").glob("*.csv")) + list((BOARD_DIR / "概念板块").glob("*.csv"))
        latest_dates = []
        for path in files:
            try:
                archive_csv_to_database(path)
                tail = pd.read_csv(path, encoding="utf-8-sig", usecols=["日期"]).tail(1)
                if not tail.empty:
                    latest_dates.append(str(tail.iloc[0]["日期"]))
            except Exception:
                continue
        latest = max(latest_dates) if latest_dates else None
        print(f"[同步] 同花顺板块K线本地存档完成：{len(files)} 个文件，最新 {latest or '--'}", flush=True)
        return {"state": "completed", "files": len(files), "latest_date": latest, "local_archive": str(BOARD_DIR)}
    except Exception as exc:
        print(f"[同步] 同花顺板块K线失败：{str(exc)[:180]}", flush=True)
        return {"state": "failed", "files": 0, "error": str(exc)[:300]}


def _acquire_lock() -> bool:
    _ensure_dirs()
    try:
        fd = os.open(LOCK_FILE, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        os.write(fd, str(os.getpid()).encode())
        os.close(fd)
        return True
    except FileExistsError:
        # 服务被强制关闭时遗留的锁不应永久阻塞下次自动同步。
        try:
            owner_pid = int(LOCK_FILE.read_text(encoding="utf-8").strip())
            if os.name == "nt":
                import ctypes
                process = ctypes.windll.kernel32.OpenProcess(0x1000, False, owner_pid)
                if process:
                    ctypes.windll.kernel32.CloseHandle(process)
                    return False
            else:
                os.kill(owner_pid, 0)
                return False
        except (OSError, ValueError):
            pass
        try:
            LOCK_FILE.unlink()
        except FileNotFoundError:
            pass
        return _acquire_lock()


def _release_lock() -> None:
    try:
        LOCK_FILE.unlink()
    except FileNotFoundError:
        pass


def sync_lhb_data(trade_date: date | None = None) -> dict:
    """同步每日龙虎榜与机构买卖统计；东方财富失败时每日榜单回退新浪。"""
    _ensure_dirs()
    target = trade_date or _expected_trade_date()
    compact = target.strftime("%Y%m%d")
    result: dict[str, object] = {"date": target.isoformat()}
    daily_path = LHB_DAILY_DIR / f"lhb_daily_{target.isoformat()}.csv"
    institution_path = LHB_INSTITUTION_DIR / f"lhb_institution_{target.isoformat()}.csv"
    try:
        daily = ak.stock_lhb_detail_em(start_date=compact, end_date=compact)
        if daily is None or daily.empty:
            raise ValueError("东方财富龙虎榜返回空数据")
        daily["数据源"] = "东方财富"
    except Exception as em_error:
        daily = ak.stock_lhb_detail_daily_sina(date=compact)
        if daily is None or daily.empty:
            raise RuntimeError(f"龙虎榜主备数据源均不可用: {em_error}")
        daily["数据源"] = "新浪财经（东方财富回退）"
    daily.to_csv(daily_path, index=False, encoding="utf-8-sig")
    archive_csv_to_database(daily_path, force=True)
    result["daily"] = {"rows": len(daily), "path": str(daily_path), "source": str(daily.iloc[0]["数据源"])}

    try:
        institution = ak.stock_lhb_jgmmtj_em(start_date=compact, end_date=compact)
        if institution is None:
            institution = pd.DataFrame()
        institution["数据源"] = "东方财富"
        institution.to_csv(institution_path, index=False, encoding="utf-8-sig")
        archive_csv_to_database(institution_path, force=True)
        result["institution"] = {"rows": len(institution), "path": str(institution_path)}
    except Exception as exc:
        result["institution"] = {"rows": 0, "error": str(exc)[:180]}
    try:
        result["event_index"] = rebuild_lhb_event_index()
    except Exception as exc:
        result["event_index"] = {"error": str(exc)[:180]}
    print(f"[同步] 龙虎榜 {target}: 每日 {len(daily)} 条，机构 {result['institution'].get('rows', 0)} 条", flush=True)
    return result


def get_lhb_seat_details(code: str, trade_date: str) -> pd.DataFrame:
    """读取或抓取单股当日龙虎榜买卖席位，并立即归档数据库。"""
    code = str(code).zfill(6)
    parsed = pd.Timestamp(trade_date).date()
    path = LHB_SEAT_DIR / f"{parsed.isoformat()}_{code}.csv"
    if path.exists():
        return _read_csv(path)
    compact = parsed.strftime("%Y%m%d")
    frames = []
    for side, flag in (("买入", "买入"), ("卖出", "卖出")):
        frame = ak.stock_lhb_stock_detail_em(symbol=code, date=compact, flag=flag)
        if frame is not None and not frame.empty:
            frame = frame.copy()
            frame["方向"] = side
            frames.append(frame)
    if not frames:
        return pd.DataFrame()
    result = pd.concat(frames, ignore_index=True)
    result["股票代码"] = code
    result["交易日期"] = parsed.isoformat()
    result["数据源"] = "东方财富"
    result.to_csv(path, index=False, encoding="utf-8-sig")
    archive_csv_to_database(path, force=True)
    return result


def backfill_lhb_history(start_date: date, end_date: date) -> dict:
    """按日期区间回补龙虎榜与机构统计，并拆分为每日文件写入数据库。"""
    _ensure_dirs()
    saved_daily = saved_institution = daily_rows = institution_rows = failed_months = 0

    def fetch_with_retry(getter, start: str, end: str):
        error = None
        for attempt in range(3):
            try:
                return getter(start_date=start, end_date=end)
            except Exception as exc:
                error = exc
                time.sleep(1.5 * (attempt + 1))
        raise error

    cursor = pd.Timestamp(start_date).replace(day=1)
    final = pd.Timestamp(end_date)
    while cursor <= final:
        month_start = max(pd.Timestamp(start_date), cursor)
        month_end = min(final, cursor + pd.offsets.MonthEnd(1))
        compact_start, compact_end = month_start.strftime("%Y%m%d"), month_end.strftime("%Y%m%d")
        try:
            daily = fetch_with_retry(ak.stock_lhb_detail_em, compact_start, compact_end)
            if daily is not None and not daily.empty:
                daily["数据源"] = "东方财富"
                date_column = "上榜日" if "上榜日" in daily.columns else "交易日期"
                daily[date_column] = pd.to_datetime(daily[date_column], errors="coerce")
                for trade_day, frame in daily.dropna(subset=[date_column]).groupby(daily[date_column].dt.date):
                    path = LHB_DAILY_DIR / f"lhb_daily_{trade_day.isoformat()}.csv"
                    frame.to_csv(path, index=False, encoding="utf-8-sig")
                    archive_csv_to_database(path, force=True)
                    saved_daily += 1
                daily_rows += len(daily)
            institution = fetch_with_retry(ak.stock_lhb_jgmmtj_em, compact_start, compact_end)
            if institution is not None and not institution.empty:
                institution["数据源"] = "东方财富"
                date_column = "上榜日期" if "上榜日期" in institution.columns else "交易日期"
                institution[date_column] = pd.to_datetime(institution[date_column], errors="coerce")
                for trade_day, frame in institution.dropna(subset=[date_column]).groupby(institution[date_column].dt.date):
                    path = LHB_INSTITUTION_DIR / f"lhb_institution_{trade_day.isoformat()}.csv"
                    frame.to_csv(path, index=False, encoding="utf-8-sig")
                    archive_csv_to_database(path, force=True)
                    saved_institution += 1
                institution_rows += len(institution)
            print(f"[龙虎榜回补] {month_start:%Y-%m} 完成", flush=True)
        except Exception as exc:
            failed_months += 1
            print(f"[龙虎榜回补] {month_start:%Y-%m} 失败: {str(exc)[:140]}", flush=True)
        cursor += pd.offsets.MonthBegin(1)
        time.sleep(0.4)
    index_result = rebuild_lhb_event_index()
    return {"start": start_date.isoformat(), "end": end_date.isoformat(), "daily_rows": daily_rows, "daily_dates": saved_daily, "institution_rows": institution_rows, "institution_dates": saved_institution, "failed_months": failed_months, "event_index": index_result}


def sync_ziyang_east_road_history(start_date: date, end_date: date) -> dict:
    """定向归档国泰海通武汉紫阳东路席位历史操作。"""
    _ensure_dirs()
    seat_code = "10026937"
    frame = ak.stock_lhb_yyb_detail_em(symbol=seat_code)
    if frame is None or frame.empty:
        raise RuntimeError("东方财富未返回紫阳东路席位历史")
    frame["交易日期"] = pd.to_datetime(frame["交易日期"], errors="coerce")
    frame = frame[(frame["交易日期"].dt.date >= start_date) & (frame["交易日期"].dt.date <= end_date)].copy()
    frame["交易日期"] = frame["交易日期"].dt.strftime("%Y-%m-%d")
    frame["席位标识"] = "紫阳东路"
    frame["数据源"] = "东方财富"
    path = LHB_SPECIAL_DIR / f"ziyang_east_road_{start_date.isoformat()}_{end_date.isoformat()}.csv"
    frame.to_csv(path, index=False, encoding="utf-8-sig")
    archive_csv_to_database(path, force=True)
    return {"seat_code": seat_code, "seat_name": "国泰海通证券股份有限公司武汉紫阳东路证券营业部", "rows": len(frame), "path": str(path), "start": start_date.isoformat(), "end": end_date.isoformat()}


def rebuild_lhb_event_index() -> dict:
    """合并每日榜单为个股事件索引，并标记紫阳东路参与日期。"""
    frames = [_read_csv(path) for path in sorted(LHB_DAILY_DIR.glob("lhb_daily_*.csv"))]
    if not frames:
        return {"rows": 0}
    events = pd.concat(frames, ignore_index=True)
    date_column = "上榜日" if "上榜日" in events.columns else "交易日期"
    code_column = "代码" if "代码" in events.columns else "股票代码"
    events[date_column] = pd.to_datetime(events[date_column], errors="coerce").dt.strftime("%Y-%m-%d")
    events[code_column] = events[code_column].astype(str).str.replace(".0", "", regex=False).str.zfill(6)
    ziyang_pairs: set[tuple[str, str]] = set()
    special_files = sorted(LHB_SPECIAL_DIR.glob("ziyang_east_road_*.csv"), reverse=True)
    if special_files:
        special = _read_csv(special_files[0])
        special["交易日期"] = pd.to_datetime(special["交易日期"], errors="coerce").dt.strftime("%Y-%m-%d")
        special["股票代码"] = special["股票代码"].astype(str).str.replace(".0", "", regex=False).str.zfill(6)
        ziyang_pairs = set(zip(special["股票代码"], special["交易日期"]))
    events["紫阳东路参与"] = [((code, day) in ziyang_pairs) for code, day in zip(events[code_column], events[date_column])]
    events.to_csv(LHB_EVENT_INDEX, index=False, encoding="utf-8-sig")
    archive_csv_to_database(LHB_EVENT_INDEX, force=True)
    return {"rows": len(events), "ziyang_events": int(events["紫阳东路参与"].sum()), "path": str(LHB_EVENT_INDEX)}


def run_free_sync(max_stocks: int = 0) -> dict:
    """执行一次完整免费同步，异常写入状态而不会令 Web 服务退出。"""
    if not _acquire_lock():
        print("[同步] 已有同步任务运行，跳过重复启动。", flush=True)
        return {"state": "already_running"}
    target = _expected_trade_date()
    print(f"[同步] 免费自动同步启动，目标收盘日: {target.isoformat()}", flush=True)
    _write_status(state="running", started_at=_now(), finished_at=None, current_step="准备同步", message="免费数据同步中")
    try:
        snapshots = sync_free_snapshots()
        lhb = sync_lhb_data(target)
        board_klines = sync_ths_board_klines()
        indices = sync_indices()
        index_members = sync_index_members()
        yields = sync_yield_curves()
        global_assets = sync_global_assets_and_fx()
        futures = sync_cffex_positions()
        financials = sync_cached_financial_reports()
        members = sync_board_members()
        stocks = sync_stock_daily(max_stocks=max_stocks)
        _write_status(
            state="completed", finished_at=_now(), last_completed_trade_date=target.isoformat(),
            current_step=None, datasets={**snapshots, "lhb": lhb, "board_klines": board_klines, "indices": indices, "index_members": index_members, "yield_curves": yields, "global_assets": global_assets, "cffex_positions": futures, "financial_reports": financials, "board_members": members}, message="免费同步完成；所有可增量数据均已合并写入本地存档",
        )
        print(f"[同步] 全部完成：日线更新 {stocks['updated']} 只，失败 {stocks['failed']} 只。", flush=True)
        return {"state": "completed", "snapshots": snapshots, "lhb": lhb, "board_klines": board_klines, "stocks": stocks}
    except Exception as exc:
        _write_status(state="failed", finished_at=_now(), current_step=None, message=str(exc)[:200])
        print(f"[同步] 任务失败：{str(exc)[:200]}", flush=True)
        return {"state": "failed", "error": str(exc)}
    finally:
        _release_lock()


def maybe_start_background_sync() -> bool:
    """服务启动时自动补数；环境变量 AITRADER_AUTO_SYNC=0 可禁用。"""
    global _thread
    if os.getenv("AITRADER_AUTO_SYNC", "1").strip().lower() in {"0", "false", "no"}:
        return False
    with _thread_lock:
        if _thread and _thread.is_alive():
            return False
        _thread = threading.Thread(target=run_free_sync, name="aitrader-free-sync", daemon=True)
        _thread.start()
        return True
