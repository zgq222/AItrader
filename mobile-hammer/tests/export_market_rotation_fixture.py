"""Read desktop data without changing watchlists; export its calculation as truth."""
from pathlib import Path
import json
import math
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
import pandas as pd
import industry_strength_service as strength
import stock_industry_service
import watchlist_service as watchlists
import market_position_service as market

ranking = strength.get_ranked_industries()
dates = strength._cache["dates"][-30:]
paths, names, _ = strength._sources()
mapping = stock_industry_service.load_industry_map()
stocks = []
for code, name in sorted(names.items()):
    if not strength._eligible(code, name):
        continue
    raw = watchlists._latest_rows(paths[code], 100) if code in paths else []
    raw = [row for row in raw if row.get("date", "") <= dates[-1]]
    bars = []
    for row in raw:
        if row.get("date", "") < dates[0]:
            continue
        values = []
        for field in ("open", "high", "low", "close"):
            try:
                value = float(row[field])
            except (KeyError, TypeError, ValueError):
                value = float("nan")
            values.append(value if math.isfinite(value) else None)
        bars.append([row["date"], *values])
    stocks.append({"code": code, "name": name, "industry": mapping.get(code, {}).get("industry", "待分类"),
                   "bars": bars, "gain": watchlists.low_to_latest_close_gain(raw, dates)})
index = pd.read_csv(ROOT / "指数/000001_上证指数.csv").tail(100)
expected = market.assess_market_position(index)
expected["flight_height"] = market.assess_flight_height(index)
payload = {"dates": dates, "stocks": stocks, "ranking": ranking["data"],
           "index": index[["date", "high", "low", "close"]].values.tolist(), "market": expected}
target = ROOT / "outputs/mobile-market-rotation-fixture.json"
target.write_text(json.dumps(payload, ensure_ascii=False, allow_nan=False, separators=(",", ":")), encoding="utf-8")
print(f"Desktop reference: {dates[-1]}, {len(stocks)} stocks, {len(ranking['data'])} industries -> {target}")
