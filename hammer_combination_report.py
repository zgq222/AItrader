"""Summarize the current inverted-hammer pool by full and single-label groups."""
from __future__ import annotations

import csv
import html
import itertools
import json
import math
import re
from collections import Counter
from pathlib import Path

import pandas as pd


BASE_DIR = Path(__file__).resolve().parent
SCREEN_FILE = BASE_DIR / "data" / "inverted_hammer_screen.json"
CSV_FILE = BASE_DIR / "data" / "inverted_hammer_64_report.csv"
HTML_FILE = BASE_DIR / "data" / "inverted_hammer_64_report.html"
REPORT_FILE = BASE_DIR / "data" / "inverted_hammer_combination_report.json"
RAW_DIR = BASE_DIR / "个股" / "原始数据"
REPORT_ID = "inverted-hammer-label-combinations"

PAIR_GROUPS = (
    ("包含关系", "阳包阴", "不包"),
    ("个股成交量", "当日成交量放量", "当日成交量缩量"),
    ("大盘涨跌", "当日大盘涨", "当日大盘跌"),
    ("大盘成交量", "当日大盘成交量放量", "当日大盘成交量缩量"),
)
SINGLE_LABELS = (
    "阳包阴", "不包", "突破均线", "未突破均线", "底分型右侧", "非底分型右侧",
    "当日成交量放量", "当日成交量缩量",
    "当日大盘涨", "当日大盘跌",
    "当日大盘成交量放量", "当日大盘成交量缩量",
)
HEADERS = ("包含关系", "均线", "底分型", "个股成交量", "大盘涨跌", "大盘成交量")
HORIZONS = range(1, 6)


def _forward_event_data(stock: dict, paths: dict[str, Path]) -> dict[str, dict]:
    """Calculate close returns and stop exits from each inverted-hammer event."""
    path = paths.get(str(stock.get("code", "")))
    if path is None:
        return {}
    try:
        frame = pd.read_csv(path, usecols=lambda column: column in {"date", "close", "low"})
        if not {"date", "close", "low"}.issubset(frame.columns):
            return {}
        frame["date"] = pd.to_datetime(frame["date"], errors="coerce")
        frame["close"] = pd.to_numeric(frame["close"], errors="coerce")
        frame["low"] = pd.to_numeric(frame["low"], errors="coerce")
        frame = frame.dropna(subset=["date"]).sort_values("date").reset_index(drop=True)
        positions = {day.strftime("%Y-%m-%d"): index for index, day in enumerate(frame["date"])}
        result = {}
        for event in stock.get("events", []):
            position = positions.get(event.get("date"))
            if position is None:
                continue
            base_close = float(frame.iloc[position]["close"])
            base_low = float(frame.iloc[position]["low"])
            values = []
            stopped = []
            exit_day = None
            exit_date = None
            for day in HORIZONS:
                target = position + day
                target_close = float(frame.iloc[target]["close"]) if target < len(frame) else float("nan")
                value = (target_close / base_close - 1) * 100 if math.isfinite(base_close) and base_close > 0 and math.isfinite(target_close) else float("nan")
                values.append(value if math.isfinite(value) else None)
                if exit_day is not None or target >= len(frame):
                    stopped.append(None)
                    continue
                target_low = float(frame.iloc[target]["low"])
                if not math.isfinite(base_low) or not math.isfinite(target_low) or not math.isfinite(value):
                    stopped.append(None)
                    # An unknown low prevents a reliable stop decision for later days.
                    exit_day = -1
                    continue
                if target_low < base_low:
                    exit_day = day
                    exit_date = frame.iloc[target]["date"].strftime("%Y-%m-%d")
                    stopped.append((base_low / base_close - 1) * 100)
                else:
                    stopped.append(value)
            result[event["date"]] = {
                "forward": values, "stop_forward": stopped,
                "exit_day": exit_day if exit_day and exit_day > 0 else None,
                "exit_date": exit_date,
                "stop_loss_pct": (base_close - base_low) / base_close * 100
                if exit_date and math.isfinite(base_close) and base_close > 0 else None,
            }
        return result
    except (OSError, ValueError, pd.errors.ParserError):
        return {}


def _return_stats(values: list[list[float | None]]) -> list[dict]:
    stats = []
    for day in HORIZONS:
        valid = [row[day - 1] for row in values if row[day - 1] is not None]
        rising = sum(value > 0 for value in valid)
        stats.append({"day": day, "samples": len(valid),
                      "average_pct": round(sum(valid) / len(valid), 4) if valid else None,
                      "rising_samples": rising,
                      "rising_pct": round(rising / len(valid) * 100, 4) if valid else None})
    return stats


def build_report(payload: dict | None = None) -> dict:
    if payload is None:
        payload = json.loads(SCREEN_FILE.read_text(encoding="utf-8"))
    events = [event for stock in payload.get("matches", []) for event in stock.get("events", [])]
    counts: Counter[tuple[str, ...]] = Counter()
    singles: Counter[str] = Counter()
    combo_returns: dict[tuple[str, ...], list[list[float | None]]] = {}
    combo_stop_returns: dict[tuple[str, ...], list[list[float | None]]] = {}
    single_returns: dict[str, list[list[float | None]]] = {label: [] for label in SINGLE_LABELS}
    single_stop_returns: dict[str, list[list[float | None]]] = {label: [] for label in SINGLE_LABELS}
    incomplete = 0
    paths = {}
    for path in RAW_DIR.glob("*_原始数据.csv"):
        match = re.match(r"(\d{6})_", path.name)
        if match and (match.group(1) not in paths or path.stat().st_mtime > paths[match.group(1)].stat().st_mtime):
            paths[match.group(1)] = path
    for stock in payload.get("matches", []):
      forward = _forward_event_data(stock, paths)
      for event in stock.get("events", []):
        labels = set(event.get("auto_labels") or [])
        if event.get("bottom_fractal_right") is True:
            labels.add("底分型右侧")
        else:
            labels.add("非底分型右侧")
        if "突破均线" not in labels:
            labels.add("未突破均线")
        singles.update(label for label in SINGLE_LABELS if label in labels)
        event_data = forward.get(event.get("date"), {})
        returns = event_data.get("forward", [None] * 5)
        stop_returns = event_data.get("stop_forward", [None] * 5)
        for label in SINGLE_LABELS:
            if label in labels:
                single_returns[label].append(returns)
                single_stop_returns[label].append(stop_returns)
        selected = []
        for _, first, second in PAIR_GROUPS:
            present = [label for label in (first, second) if label in labels]
            if len(present) != 1:
                break
            selected.append(present[0])
        if len(selected) != len(PAIR_GROUPS):
            incomplete += 1
            continue
        key = (selected[0], "突破均线" if "突破均线" in labels else "未突破均线",
                "底分型右侧" if "底分型右侧" in labels else "非底分型右侧",
                selected[1], selected[2], selected[3])
        counts[key] += 1
        combo_returns.setdefault(key, []).append(returns)
        combo_stop_returns.setdefault(key, []).append(stop_returns)

    choices = (
        ("阳包阴", "不包"), ("突破均线", "未突破均线"),
        ("底分型右侧", "非底分型右侧"),
        ("当日成交量放量", "当日成交量缩量"),
        ("当日大盘涨", "当日大盘跌"),
        ("当日大盘成交量放量", "当日大盘成交量缩量"),
    )
    combinations = [
        {"number": number, "values": list(values), "samples": counts[values],
         "forward": _return_stats(combo_returns.get(values, [])),
         "stop_forward": _return_stats(combo_stop_returns.get(values, []))}
        for number, values in enumerate(itertools.product(*choices), 1)
    ]
    single_rows = [{"label": label, "samples": singles[label],
                    "forward": _return_stats(single_returns[label]),
                    "stop_forward": _return_stats(single_stop_returns[label])} for label in SINGLE_LABELS]
    return {
        "id": REPORT_ID, "title": "倒垂线标签组合与单标签样本统计",
        "created_at": payload.get("updated_at", "")[:10],
        "updated_at": payload.get("updated_at"),
        "summary": f"近三年倒垂线池：64 种完整组合、{len(SINGLE_LABELS)} 种单标签；共 {len(events)} 个事件样本。展示后 1 至 5 日普通持有与止损规则下的平均累计收益。",
        "return_basis": "cumulative_close",
        "report_schema": 4,
        "tags": ["倒垂线", "标签统计"],
        "conditions": ["完整组合：每个事件按六个维度归入至多一种组合。", "单标签：独立计数，同一事件可计入多个标签。"],
        "methodology": ["数据源：当前倒垂线扫描结果及个股原始日 K；样本为倒垂线事件（股票与日期）。", "底分型右侧使用事件的布尔判定值；其余标签使用事件自动标签。", "进场价为倒垂线第 n 日收盘价，止损价为该日最低价。后续第 1 至 5 个交易日，只要当日最低价严格低于止损价，就在首次触发当日以止损价卖出；不计成交限制。", "止损口径：触发当日以（止损价 ÷ 进场价 − 1）计入累计收益；此后各日不再纳入均值。未触发时以该日收盘价计算累计收益。每一日均值仅使用当日尚在持有或当日刚止损的有效样本。", "普通口径后 k 日累计收益 =（第 n+k 日收盘价 ÷ 第 n 日收盘价 − 1）× 100%；上涨样本占比仍按普通口径累计收益严格大于 0 计算。", "每个观察日列示有效样本数；尚无后续行情的样本不参与该日统计。"],
        "sample": {"起始日期": payload.get("window_start"), "截止日期": payload.get("window_end"),
                   "总事件数": len(events), "完整组合已归类": len(events) - incomplete,
                   "标签缺失或冲突": incomplete},
        "combination_headers": list(HEADERS), "combination_rows": combinations,
        "single_label_rows": single_rows,
    }


def write_files(report: dict) -> None:
    CSV_FILE.parent.mkdir(parents=True, exist_ok=True)
    with CSV_FILE.open("w", newline="", encoding="utf-8-sig") as stream:
        writer = csv.writer(stream)
        forward_headers = [part for day in HORIZONS for part in (f"后{day}日平均累计收益(%)", f"后{day}日有效样本数", f"后{day}日上涨样本数", f"后{day}日上涨样本占比(%)")]
        stop_headers = [f"后{day}日止损后平均累计收益(%)" for day in HORIZONS]
        writer.writerow(["类型", "序号", *HEADERS, "标签", "样本数", *forward_headers, *stop_headers])
        def stats_cells(row: dict) -> list:
            return [part for stat in row["forward"] for part in (stat["average_pct"] if stat["average_pct"] is not None else "", stat["samples"], stat["rising_samples"], stat["rising_pct"] if stat["rising_pct"] is not None else "")]
        def stop_cells(row: dict) -> list:
            return [stat["average_pct"] if stat["average_pct"] is not None else "" for stat in row["stop_forward"]]
        for row in report["combination_rows"]:
            writer.writerow(["完整组合", row["number"], *row["values"], "", row["samples"], *stats_cells(row), *stop_cells(row)])
        for number, row in enumerate(report["single_label_rows"], 1):
            writer.writerow(["单标签", number, *([""] * len(HEADERS)), row["label"], row["samples"], *stats_cells(row), *stop_cells(row)])

    esc = lambda value: html.escape(str(value))
    def stat_cells(row: dict) -> str:
        return "".join(f"<td>{stat['average_pct']:+.4f}%<small>有效 n={stat['samples']}</small><small>上涨 {stat['rising_samples']} / {stat['samples']} = {stat['rising_pct']:.2f}%</small></td>" if stat["average_pct"] is not None else "<td>—<small>有效 n=0；上涨占比 —</small></td>" for stat in row["forward"])
    def stop_stat_cells(row: dict) -> str:
        return "".join(f"<td>{stat['average_pct']:+.4f}%<small>有效 n={stat['samples']}</small><small>上涨 {stat['rising_samples']} / {stat['samples']} = {stat['rising_pct']:.2f}%</small></td>" if stat["average_pct"] is not None else "<td>—<small>有效 n=0；上涨占比 —</small></td>" for stat in row["stop_forward"])
    single_rows = "".join(f"<tr><td>{esc(row['label'])}</td><td>{row['samples']}</td>{stat_cells(row)}{stop_stat_cells(row)}</tr>" for row in report["single_label_rows"])
    combo_rows = "".join(
        f"<tr><td>{row['number']}</td>" + "".join(f"<td>{esc(value)}</td>" for value in row["values"])
        + f"<td>{row['samples']}</td>{stat_cells(row)}{stop_stat_cells(row)}</tr>" for row in report["combination_rows"]
    )
    style = "body{font:14px Arial,sans-serif;margin:24px;color:#222}table{border-collapse:collapse;margin:12px 0 28px;width:100%}th,td{border:1px solid #ccc;padding:7px 10px;text-align:left;white-space:nowrap}th{background:#eee}small{display:block;color:#666} .table-wrap{overflow-x:auto}"
    day_headers = "".join(f"<th>后{day}日平均累计收益<br>上涨样本占比</th>" for day in HORIZONS)
    stop_day_headers = "".join(f"<th>后{day}日止损后平均累计收益<br>上涨样本占比</th>" for day in HORIZONS)
    HTML_FILE.write_text(
        f"<!doctype html><html lang='zh'><meta charset='utf-8'><title>{esc(report['title'])}</title><style>{style}</style>"
        f"<h1>{esc(report['title'])}</h1><p>{esc(report['summary'])}</p>"
        f"<p>统计区间：{esc(report['sample']['起始日期'])} 至 {esc(report['sample']['截止日期'])}；更新时间：{esc(report['updated_at'])}；完整组合未归类：{report['sample']['标签缺失或冲突']}</p>"
        f"<h2>单标签（{len(report['single_label_rows'])} 类，可重叠）</h2><div class='table-wrap'><table><tr><th>标签</th><th>样本数</th>{day_headers}{stop_day_headers}</tr>{single_rows}</table></div>"
        f"<h2>完整组合（64 类，互斥）</h2><div class='table-wrap'><table><tr><th>序号</th>{''.join(f'<th>{esc(h)}</th>' for h in HEADERS)}<th>样本数</th>{day_headers}{stop_day_headers}</tr>{combo_rows}</table></div>"
        "<p>止损价为倒垂线当日最低价；后续交易日最低价严格低于止损价时，当日按止损价计入收益，此后各日排除。各列上涨占比 = 该列累计收益严格大于 0 的样本数 ÷ 该列有效样本数。单标签可重叠。</p></html>",
        encoding="utf-8",
    )
    temporary = REPORT_FILE.with_suffix(".tmp")
    temporary.write_text(json.dumps(report, ensure_ascii=False), encoding="utf-8")
    temporary.replace(REPORT_FILE)


def get_report() -> dict:
    payload = json.loads(SCREEN_FILE.read_text(encoding="utf-8"))
    if REPORT_FILE.exists():
        try:
            cached = json.loads(REPORT_FILE.read_text(encoding="utf-8"))
            if cached.get("updated_at") == payload.get("updated_at") and cached.get("return_basis") == "cumulative_close" and cached.get("report_schema") == 4 and all("stop_forward" in row for row in cached.get("combination_rows", [])):
                return cached
        except (OSError, json.JSONDecodeError):
            pass
    report = build_report(payload)
    write_files(report)
    return report


if __name__ == "__main__":
    result = build_report()
    write_files(result)
    print(json.dumps({"events": result["sample"]["总事件数"], "classified": result["sample"]["完整组合已归类"], "single_labels": len(result["single_label_rows"]), "combos": len(result["combination_rows"])}, ensure_ascii=False))
