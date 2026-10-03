"""Prepare per-event audit rows for the inverted-hammer workbook."""
from __future__ import annotations

import json
import re
from pathlib import Path

import hammer_combination_report as report


OUTPUT = Path(__file__).resolve().parent / "data" / "hammer_sample_export_input.json"


def main() -> None:
    payload = json.loads(report.SCREEN_FILE.read_text(encoding="utf-8"))
    summary = report.get_report()
    if summary["updated_at"] != payload["updated_at"]:
        raise RuntimeError("倒垂线池在准备数据时更新，请重试")

    combinations = summary["combination_rows"]
    by_values = {tuple(item["values"]): item["number"] for item in combinations}
    combo_rows: dict[int, list] = {item["number"]: [] for item in combinations}
    single_rows: dict[str, list] = {item["label"]: [] for item in summary["single_label_rows"]}
    paths: dict[str, Path] = {}
    for path in report.RAW_DIR.glob("*_原始数据.csv"):
        match = re.match(r"(\d{6})_", path.name)
        if match and (match.group(1) not in paths or path.stat().st_mtime > paths[match.group(1)].stat().st_mtime):
            paths[match.group(1)] = path

    for stock in payload["matches"]:
        forward = report._forward_event_data(stock, paths)
        for event in stock["events"]:
            labels = set(event.get("auto_labels") or [])
            labels.add("底分型右侧" if event.get("bottom_fractal_right") is True else "非底分型右侧")
            if "突破均线" not in labels:
                labels.add("未突破均线")
            values = []
            for _, first, second in report.PAIR_GROUPS:
                choices = [label for label in (first, second) if label in labels]
                if len(choices) != 1:
                    raise RuntimeError(f"标签缺失或冲突：{stock['code']} {event['date']}")
                values.append(choices[0])
            key = (values[0], "突破均线" if "突破均线" in labels else "未突破均线",
                   "底分型右侧" if "底分型右侧" in labels else "非底分型右侧",
                   values[1], values[2], values[3])
            # Returns are stored as numeric fractions for Excel's percentage format.
            event_data = forward.get(event["date"], {})
            returns = event_data.get("forward", [None] * 5)
            stop_returns = event_data.get("stop_forward", [None] * 5)
            exit_day = event_data.get("exit_day")
            exit_text = f"第{exit_day}日（{event_data['exit_date']}）" if exit_day else "未触发止损"
            stop_loss = event_data.get("stop_loss_pct")
            row = [stock["code"], stock["name"], event["date"],
                   *[value / 100 if value is not None else None for value in returns],
                   *[value / 100 if value is not None else None for value in stop_returns],
                   exit_text, stop_loss / 100 if stop_loss is not None else None]
            combo_rows[by_values[key]].append(row)
            for label in report.SINGLE_LABELS:
                if label in labels:
                    single_rows[label].append(row)

    for item in combinations:
        if len(combo_rows[item["number"]]) != item["samples"]:
            raise RuntimeError(f"组合 {item['number']} 样本数不一致")
    for item in summary["single_label_rows"]:
        if len(single_rows[item["label"]]) != item["samples"]:
            raise RuntimeError(f"标签 {item['label']} 样本数不一致")
    for item in combinations:
        rows = combo_rows[item["number"]]
        for day in report.HORIZONS:
            values = [row[8 + day - 1] for row in rows if row[8 + day - 1] is not None]
            expected = item["stop_forward"][day - 1]
            if len(values) != expected["samples"]:
                raise RuntimeError(f"组合 {item['number']} 后{day}日止损样本数不一致")

    data = {
        "updated_at": payload["updated_at"],
        "window_start": payload["window_start"],
        "window_end": payload["window_end"],
        "combinations": [
            {"number": item["number"], "title": "、".join(item["values"]), "rows": combo_rows[item["number"]]}
            for item in combinations
        ],
        "labels": [{"label": item["label"], "rows": single_rows[item["label"]]} for item in summary["single_label_rows"]],
    }
    OUTPUT.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(json.dumps({"timestamp": data["updated_at"], "sheets": len(combinations) + len(single_rows),
                      "combination_rows": sum(map(len, combo_rows.values())),
                      "single_label_rows": sum(map(len, single_rows.values()))}, ensure_ascii=False))


if __name__ == "__main__":
    main()
