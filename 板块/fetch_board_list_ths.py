# -*- coding: utf-8 -*-
"""
扫描同花顺所有行业板块(881xxx)和概念板块(885xxx)的代码+名称

数据源：
  1. d.10jqka.com.cn/v6/line/bk_{code}/01/{year}.js  判断代码是否有效
  2. stockpage.10jqka.com.cn/{code}/                  从页面标题获取板块名称

输出：
  板块/板块列表/同花顺板块列表.csv  (板块代码, 板块名称, 板块类型, 记录日期)
  板块/板块列表/行业_YYYY-MM-DD.csv
  板块/板块列表/概念_YYYY-MM-DD.csv

扫描策略：
  - 行业板块: 881000 - 881999
  - 概念板块: 885000 - 885999
  - 用并发加速（线程池）
  - 已存在的列表会作为缓存复用，--force 可强制重新扫描
"""
import os
import sys
import argparse
import concurrent.futures
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import (
    ensure_dirs, today_str, safe_name,
    get_session, fetch_board_name, fetch_board_kline_year,
    save_csv, polite_sleep,
    BOARD_LIST_COLS,
    BOARD_TYPE_INDUSTRY, BOARD_TYPE_CONCEPT,
    INDUSTRY_CODE_RANGE, CONCEPT_CODE_RANGE,
    BOARD_LIST_DIR,
)


def _probe_code(code: int, year: int) -> bool:
    """探测板块代码是否有效（有K线数据）"""
    try:
        df = fetch_board_kline_year(code, year)
        return not df.empty
    except Exception:
        return False


def _scan_range(code_range: tuple, board_type: str, year: int,
                max_workers: int = 8) -> list:
    """
    扫描指定代码范围，返回 [(code, name), ...]
    """
    start, end = code_range
    codes = list(range(start, end))
    print(f"  扫描 {board_type} 板块代码范围 {start}-{end-1} ({len(codes)} 个)")

    # 第一阶段：并行探测哪些代码有K线数据
    valid_codes = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as ex:
        futures = {ex.submit(_probe_code, c, year): c for c in codes}
        for i, fut in enumerate(concurrent.futures.as_completed(futures), 1):
            code = futures[fut]
            try:
                if fut.result():
                    valid_codes.append(code)
            except Exception:
                pass
            if i % 200 == 0 or i == len(codes):
                print(f"    探测进度: {i}/{len(codes)}，有效: {len(valid_codes)}")

    print(f"  {board_type} 有效代码: {len(valid_codes)} 个")

    # 第二阶段：并行获取板块名称
    results = []
    session = get_session()
    with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as ex:
        futures = {ex.submit(fetch_board_name, c, session): c for c in valid_codes}
        for i, fut in enumerate(concurrent.futures.as_completed(futures), 1):
            code = futures[fut]
            try:
                name = fut.result()
            except Exception:
                name = None
            if name:
                results.append((code, name))
            else:
                # 名称获取失败也保留，名称留空
                results.append((code, ""))
            if i % 50 == 0 or i == len(valid_codes):
                print(f"    取名进度: {i}/{len(valid_codes)}")

    # 按代码排序
    results.sort(key=lambda x: x[0])
    return results


def scan_all_boards(max_workers: int = 8, force: bool = False) -> str:
    """
    扫描所有同花顺板块，返回保存的 CSV 路径
    """
    ensure_dirs()
    date_str = today_str()

    # 缓存文件
    cache_path = os.path.join(BOARD_LIST_DIR, "同花顺板块列表.csv")
    if not force and os.path.exists(cache_path):
        import pandas as pd
        cache_df = pd.read_csv(cache_path, encoding="utf-8-sig", dtype=str)
        if not cache_df.empty:
            print(f"使用缓存的板块列表: {cache_path} ({len(cache_df)} 条)")
            print("  如需重新扫描，请加 --force 参数")
            return cache_path

    print("=" * 60)
    print(f"扫描同花顺板块列表 [{date_str}]")
    print("=" * 60)

    # 用当前年份探测
    year = datetime.now().year

    print("\n>>> 扫描行业板块")
    industry_results = _scan_range(INDUSTRY_CODE_RANGE, BOARD_TYPE_INDUSTRY,
                                   year, max_workers)

    print("\n>>> 扫描概念板块")
    concept_results = _scan_range(CONCEPT_CODE_RANGE, BOARD_TYPE_CONCEPT,
                                  year, max_workers)

    # 合并
    all_rows = []
    for code, name in industry_results:
        all_rows.append({
            "板块代码": str(code),
            "板块名称": name,
            "板块类型": BOARD_TYPE_INDUSTRY,
            "记录日期": date_str,
        })
    for code, name in concept_results:
        all_rows.append({
            "板块代码": str(code),
            "板块名称": name,
            "板块类型": BOARD_TYPE_CONCEPT,
            "记录日期": date_str,
        })

    import pandas as pd
    df = pd.DataFrame(all_rows)

    # 保存合并文件（按"板块代码"去重，避免同日期合并）
    save_csv(df, cache_path, BOARD_LIST_COLS, dedup_cols=["板块代码"])
    print(f"\n保存合并板块列表: {cache_path} ({len(df)} 条)")

    # 按类型分别保存
    for btype, sub_df in df.groupby("板块类型"):
        path = os.path.join(BOARD_LIST_DIR, f"{btype}_{date_str}.csv")
        save_csv(sub_df.copy(), path, BOARD_LIST_COLS, dedup_cols=["板块代码"])
        print(f"保存 {btype} 板块列表: {path} ({len(sub_df)} 条)")

    # 统计未取到名称的板块
    unnamed = df[df["板块名称"] == ""]
    if not unnamed.empty:
        print(f"\n警告: {len(unnamed)} 个板块未取到名称（已保留代码，名称为空）")

    print("\n" + "=" * 60)
    print(f"扫描完成！行业={len(industry_results)} 概念={len(concept_results)} 共={len(df)}")
    print("=" * 60)
    return cache_path


def main():
    parser = argparse.ArgumentParser(description="扫描同花顺所有板块代码+名称")
    parser.add_argument("--force", action="store_true", help="强制重新扫描，忽略缓存")
    parser.add_argument("--workers", type=int, default=8, help="并发线程数")
    args = parser.parse_args()

    scan_all_boards(max_workers=args.workers, force=args.force)


if __name__ == "__main__":
    main()
