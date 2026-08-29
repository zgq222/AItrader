# -*- coding: utf-8 -*-
"""
板块数据采集通用工具模块（数据源：同花顺）

背景：
  本机网络环境下，东方财富 push2/push2his API 子域被反爬封禁，
  同花顺 q./data. 子域返回 403，仅有以下同花顺接口可用：
    1. d.10jqka.com.cn/v6/line/bk_{code}/01/{year}.js   板块历史K线（按年）
    2. d.10jqka.com.cn/v6/line/hs_{code}/01/{year}.js   个股历史K线（按年）
    3. stockpage.10jqka.com.cn/{code}/                  板块/个股页面标题含名称

故本次实现仅支持「板块K线」数据采集，无法获取：
  - 板块成分股（同花顺 q. 子域被封）
  - 板块/个股资金流（同花顺 data. 子域被封，东财 push2/push2his 被封）
  - 个股数据（无成分股来源）

字段说明（本次实现）：
  板块日线: 板块代码,板块名称,板块类型,日期,开盘价,最高价,最低价,收盘价,
           成交量,成交额,今日涨跌幅,最新价
  其余需求字段（主力净流入等、成分股数量等）因数据源不可用而省略
"""
import os
import re
import time
import random
import requests
import pandas as pd
from datetime import datetime

# ============================================================================
# 路径与常量
# ============================================================================
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
INDUSTRY_DIR = os.path.join(BASE_DIR, "行业板块")
CONCEPT_DIR = os.path.join(BASE_DIR, "概念板块")
BOARD_LIST_DIR = os.path.join(BASE_DIR, "板块列表")

# 同花顺板块代码段
# 行业: 881xxx (二级)  概念: 885xxx
INDUSTRY_CODE_RANGE = (881000, 882000)
CONCEPT_CODE_RANGE = (885000, 886000)

BOARD_TYPE_INDUSTRY = "行业"
BOARD_TYPE_CONCEPT = "概念"

# 板块日线 CSV 列顺序（11列；本次实现仅含 K线相关字段）
BOARD_DAILY_COLS = [
    "板块代码", "板块名称", "板块类型", "日期",
    "开盘价", "最高价", "最低价", "收盘价",
    "成交量", "成交额", "今日涨跌幅", "最新价",
]

# 板块列表 CSV 列顺序
BOARD_LIST_COLS = [
    "板块代码", "板块名称", "板块类型", "记录日期",
]

# 文件名非法字符
_BAD_CHARS = r'/\:*?"<>|'


# ============================================================================
# 通用工具函数
# ============================================================================

def safe_name(name: str) -> str:
    if name is None:
        return "unknown"
    s = str(name)
    for c in _BAD_CHARS:
        s = s.replace(c, "_")
    return s.strip() or "unknown"


def board_dir(board_type: str) -> str:
    return INDUSTRY_DIR if board_type == BOARD_TYPE_INDUSTRY else CONCEPT_DIR


def today_str(fmt: str = "%Y-%m-%d") -> str:
    return datetime.now().strftime(fmt)


def ensure_dirs():
    for d in [INDUSTRY_DIR, CONCEPT_DIR, BOARD_LIST_DIR]:
        os.makedirs(d, exist_ok=True)


def clear_proxy_env():
    """清除所有代理环境变量，避免本机代理(未启动)导致连接失败"""
    for k in ['HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy',
              'ALL_PROXY', 'all_proxy']:
        os.environ.pop(k, None)
    os.environ['NO_PROXY'] = '*'
    os.environ['no_proxy'] = '*'


def make_session() -> requests.Session:
    """创建不走代理的 Session"""
    clear_proxy_env()
    s = requests.Session()
    s.trust_env = False
    s.headers.update({
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
                      'AppleWebKit/537.36 (KHTML, like Gecko) '
                      'Chrome/120.0.0.0 Safari/537.36',
    })
    return s


def load_csv(path: str) -> pd.DataFrame:
    if not os.path.exists(path):
        return pd.DataFrame()
    try:
        return pd.read_csv(path, encoding="utf-8-sig", dtype=str)
    except Exception:
        return pd.DataFrame()


def save_csv(df: pd.DataFrame, path: str, cols: list, date_col: str = "日期",
             dedup_cols: list = None):
    """
    按指定列顺序保存 CSV。
    - 若提供 dedup_cols: 按 dedup_cols 去重(每组取最后一个非空值)
    - 否则若存在 date_col: 按 date_col 去重(每组取最后一个非空值)并升序排序
    - 否则: 按全部列去重
    """
    os.makedirs(os.path.dirname(path), exist_ok=True)
    for c in cols:
        if c not in df.columns:
            df[c] = None
    df = df[cols].copy()

    if dedup_cols:
        # 按指定列分组，每组取最后一个非空值（合并多行同键数据）
        df = df.sort_values(dedup_cols).groupby(dedup_cols, sort=False, dropna=False).last().reset_index()
        df = df.sort_values(dedup_cols).reset_index(drop=True)
    elif date_col in df.columns and not df.empty:
        df[date_col] = pd.to_datetime(df[date_col], errors="coerce").dt.strftime("%Y-%m-%d")
        df = df.dropna(subset=[date_col])
        # 按 date_col 分组，对每列取最后一个非空值
        df = df.sort_values(date_col).groupby(date_col, sort=False, dropna=False).last().reset_index()
        df = df.sort_values(date_col).reset_index(drop=True)
    else:
        df = df.drop_duplicates().reset_index(drop=True)
    df.to_csv(path, index=False, encoding="utf-8-sig")


def get_max_date(df: pd.DataFrame) -> str | None:
    if df is None or df.empty or "日期" not in df.columns:
        return None
    d = pd.to_datetime(df["日期"], errors="coerce").max()
    return d.strftime("%Y%m%d") if pd.notna(d) else None


def polite_sleep(min_s: float = 0.1, max_s: float = 0.3):
    time.sleep(random.uniform(min_s, max_s))


# ============================================================================
# 同花顺接口封装
# ============================================================================

# 模块级 Session 复用
_SESSION = None


def get_session() -> requests.Session:
    global _SESSION
    if _SESSION is None:
        _SESSION = make_session()
    return _SESSION


def call_with_retry(func, *args, retries: int = 3, **kwargs):
    """带重试的调用"""
    last_err = None
    for i in range(retries):
        try:
            return func(*args, **kwargs)
        except Exception as e:
            last_err = e
            time.sleep(0.5 * (i + 1))
    raise RuntimeError(f"调用失败({retries}次): {last_err}")


def fetch_board_name(code: int, session: requests.Session = None) -> str | None:
    """
    通过 stockpage.10jqka.com.cn/{code}/ 页面标题获取板块名称
    标题格式: "种植业与林业(881101)个股资金流向查询_个股行情_同花顺财经"
    """
    if session is None:
        session = get_session()
    url = f"http://stockpage.10jqka.com.cn/{code}/"
    try:
        r = session.get(url, timeout=10)
        if r.status_code != 200:
            return None
        m = re.search(r"<title>([^<\(]+)\(", r.text)
        if m:
            name = m.group(1).strip()
            if name and "个股" not in name and "板块" not in name[:2]:
                return name
        return None
    except Exception:
        return None


def fetch_board_kline_year(code: int, year: int, session: requests.Session = None) -> pd.DataFrame:
    """
    拉取同花顺板块某一年的日K线数据。
    返回 DataFrame: 日期, 开盘价, 最高价, 最低价, 收盘价, 成交量, 成交额
    数据格式: "20260105,2294.232,2346.574,2285.186,2346.347,3021978500,73607370000.000,,,,0"
    """
    if session is None:
        session = get_session()
    url = f"http://d.10jqka.com.cn/v6/line/bk_{code}/01/{year}.js"
    headers = {'Referer': 'http://q.10jqka.com.cn/'}
    try:
        r = session.get(url, headers=headers, timeout=15)
    except Exception:
        return pd.DataFrame()
    if r.status_code != 200 or len(r.text) < 100:
        return pd.DataFrame()

    # 解析 JSONP: quotebridge_v6_line_bk_xxx_01_yyy({"data":"...","info":{...}})
    text = r.text
    start = text.find("{")
    if start < 0:
        return pd.DataFrame()
    # 截取到最后一个 } 
    end = text.rfind("}")
    if end < start:
        return pd.DataFrame()
    json_str = text[start:end + 1]
    try:
        import json
        obj = json.loads(json_str)
    except Exception:
        return pd.DataFrame()

    data_str = obj.get("data", "")
    if not data_str:
        return pd.DataFrame()

    rows = []
    for item in data_str.split(";"):
        item = item.strip()
        if not item:
            continue
        parts = item.split(",")
        if len(parts) < 7:
            continue
        try:
            date_raw = parts[0]  # YYYYMMDD
            date = f"{date_raw[:4]}-{date_raw[4:6]}-{date_raw[6:8]}"
            rows.append({
                "日期": date,
                "开盘价": float(parts[1]),
                "最高价": float(parts[2]),
                "最低价": float(parts[3]),
                "收盘价": float(parts[4]),
                "成交量": float(parts[5]),
                "成交额": float(parts[6]),
            })
        except (ValueError, IndexError):
            continue

    if not rows:
        return pd.DataFrame()
    return pd.DataFrame(rows)


def compute_pct_change(df: pd.DataFrame) -> pd.DataFrame:
    """
    根据「收盘价」计算「今日涨跌幅」(%) 和「最新价」
    - 今日涨跌幅 = (今日收盘 - 昨日收盘) / 昨日收盘 * 100
    - 最新价 = 收盘价
    """
    if df.empty:
        return df
    df = df.sort_values("日期").reset_index(drop=True)
    prev_close = df["收盘价"].shift(1)
    df["今日涨跌幅"] = (df["收盘价"] - prev_close) / prev_close * 100
    df["今日涨跌幅"] = df["今日涨跌幅"].round(4)
    df["最新价"] = df["收盘价"]
    # 第一行无昨日收盘，涨跌幅留空
    df.loc[df.index[0], "今日涨跌幅"] = None
    return df


def get_kline_year_range(start_date: str, end_date: str) -> list:
    """根据起止日期(YYYYMMDD)返回需要拉取的年份列表"""
    try:
        s_year = int(start_date[:4])
        e_year = int(end_date[:4])
    except Exception:
        s_year, e_year = 2007, datetime.now().year
    return list(range(s_year, e_year + 1))
