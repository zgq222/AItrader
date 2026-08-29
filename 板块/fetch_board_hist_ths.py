# -*- coding: utf-8 -*-
"""
拉取同花顺板块历史日K线数据（多年度合并）

数据源: d.10jqka.com.cn/v6/line/bk_{code}/01/{year}.js
输出:   板块/行业板块/<板块名>.csv
        板块/概念板块/<板块名>.csv

CSV 字段(11列):
  板块代码, 板块名称, 板块类型, 日期,
  开盘价, 最高价, 最低价, 收盘价,
  成交量, 成交额, 今日涨跌幅, 最新价

说明:
  - 历史最早到 2007 年（同花顺板块K线起始）
  - 今日涨跌幅由前后日收盘价计算（同花顺K线接口不直接返回涨跌幅）
  - 最新价 = 收盘价
  - 增量更新：根据已有数据的最大日期，仅拉取需要的年份
"""
import os
import sys
import argparse
import pandas as pd
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import (
    ensure_dirs, safe_name, today_str,
    get_session, fetch_board_kline_year, compute_pct_change,
    get_kline_year_range, load_csv, save_csv, get_max_date, polite_sleep,
    BOARD_DAILY_COLS, BOARD_LIST_COLS,
    BOARD_TYPE_INDUSTRY, BOARD_TYPE_CONCEPT,
    board_dir, BOARD_LIST_DIR,
)

# 同花顺板块K线数据起始年份
HIST_START_YEAR = 2007


def _load_board_list(board_type: str = None) -> pd.DataFrame:
    """
    加载板块列表（优先用缓存文件）
    返回 DataFrame: 板块代码, 板块名称, 板块类型
    """
    cache_path = os.path.join(BOARD_LIST_DIR, "同花顺板块列表.csv")
    if not os.path.exists(cache_path):
        raise FileNotFoundError(
            f"未找到板块列表: {cache_path}\n请先运行: python fetch_board_list_ths.py"
        )
    df = pd.read_csv(cache_path, encoding="utf-8-sig", dtype=str)
    if board_type:
        df = df[df["板块类型"] == board_type]
    return df.reset_index(drop=True)


def _build_board_daily(code: int, name: str, board_type: str,
                       years: list) -> pd.DataFrame:
    """
    拉取指定板块多个年份的K线，合并并计算涨跌幅
    """
    session = get_session()
    all_dfs = []
    for year in years:
        df = fetch_board_kline_year(code, year, session)
        if not df.empty:
            all_dfs.append(df)
        polite_sleep(0.05, 0.15)

    if not all_dfs:
        return pd.DataFrame()

    combined = pd.concat(all_dfs, ignore_index=True)
    combined = combined.drop_duplicates(subset=["日期"], keep="last")
    combined = compute_pct_change(combined)

    # 加上板块标识列
    combined.insert(0, "板块代码", str(code))
    combined.insert(1, "板块名称", name)
    combined.insert(2, "板块类型", board_type)
    return combined


def fetch_board_hist(board_type: str, incremental: bool = True,
                     start_year: int = None, limit: int = None):
    """
    拉取指定类型(行业/概念)所有板块的历史K线
    """
    ensure_dirs()
    today = today_str()
    out_dir = board_dir(board_type)
    os.makedirs(out_dir, exist_ok=True)
    current_year = datetime.now().year

    print("=" * 60)
    print(f"[{today}] 拉取{board_type}板块历史K线 (数据源: 同花顺)")
    print(f"  模式: {'增量' if incremental else '全量'}")
    print("=" * 60)

    # 1. 加载板块列表
    try:
        board_list = _load_board_list(board_type)
    except FileNotFoundError as e:
        print(f"错误: {e}")
        return

    if board_list.empty:
        print(f"{board_type}板块列表为空")
        return

    if limit:
        board_list = board_list.head(limit)
        print(f"调试模式：仅处理前 {limit} 个板块")

    total = len(board_list)
    print(f"待拉取板块数: {total}\n")

    success, fail, skip = 0, 0, 0
    for idx, row in board_list.iterrows():
        code = int(row["板块代码"])
        name = row["板块名称"]
        display_name = name if name else f"代码{code}"
        out_file = os.path.join(out_dir, f"{safe_name(display_name)}.csv")
        try:
            # 确定需要拉取的年份
            existing_df = pd.DataFrame()
            existing_max_year = None
            if incremental and os.path.exists(out_file):
                existing_df = load_csv(out_file)
                max_d = get_max_date(existing_df)
                if max_d:
                    existing_max_year = int(max_d[:4])

            if start_year:
                years_to_fetch = list(range(start_year, current_year + 1))
            elif existing_max_year:
                # 增量：从已有最大日期的年份开始重拉（覆盖当年最新数据）
                years_to_fetch = list(range(existing_max_year, current_year + 1))
            else:
                years_to_fetch = list(range(HIST_START_YEAR, current_year + 1))

            new_df = _build_board_daily(code, name, board_type, years_to_fetch)
            if new_df.empty:
                if existing_df.empty:
                    fail += 1
                else:
                    skip += 1
                polite_sleep(0.1, 0.2)
                continue

            # 合并历史+新数据
            if not existing_df.empty:
                combined = pd.concat([existing_df, new_df], ignore_index=True)
            else:
                combined = new_df

            save_csv(combined, out_file, BOARD_DAILY_COLS, date_col="日期")

            success += 1
        except Exception as e:
            fail += 1

        completed = idx + 1
        if completed == 1 or completed % 10 == 0 or completed == total:
            progress = f"[进度] {completed}/{total} | 成功 {success} | 无数据 {skip} | 失败 {fail}"
            print("\r" + progress.ljust(100), end="", flush=True)

        polite_sleep(0.15, 0.35)

    print(f"\n{'=' * 60}")
    print(f"[{today}] {board_type}板块完成: 成功={success}, 失败={fail}, 无数据={skip}")
    print(f"{'=' * 60}\n")


def main():
    parser = argparse.ArgumentParser(description="拉取同花顺板块历史K线")
    parser.add_argument("--no-industry", action="store_true", help="跳过行业板块")
    parser.add_argument("--no-concept", action="store_true", help="跳过概念板块")
    parser.add_argument("--full", action="store_true", help="全量拉取（忽略已有数据）")
    parser.add_argument("--start-year", type=int, default=None,
                        help=f"起始年份(默认 {HIST_START_YEAR})")
    parser.add_argument("--limit", type=int, default=None,
                        help="仅处理前 N 个板块（调试用）")
    args = parser.parse_args()

    incremental = not args.full
    start_year = args.start_year

    if not args.no_industry:
        fetch_board_hist(BOARD_TYPE_INDUSTRY, incremental=incremental,
                         start_year=start_year, limit=args.limit)
    if not args.no_concept:
        fetch_board_hist(BOARD_TYPE_CONCEPT, incremental=incremental,
                         start_year=start_year, limit=args.limit)

    print("全部完成！")


if __name__ == "__main__":
    main()
