"""Bundle an explicit snapshot of desktop holdings and noted stocks for one-time APK import."""
import hashlib
import json
from pathlib import Path
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
import watchlist_service

def export():
    # Read and derive the same note group as the web workbench, without writing desktop data.
    payload = watchlist_service.load_watchlists()
    watchlist_service._sync_noted_stocks(payload)
    holdings = [g for g in payload["groups"] if g["id"] == "holdings" or g["name"].strip() in {"持仓", "持仓股", "持仓股票"}]
    if len(holdings) != 1:
        raise ValueError(f"Expected one desktop holdings group, found {len(holdings)}")
    held = holdings[0]
    noted = next(g for g in payload["groups"] if g["id"] == "noted_stocks")
    catalog = json.loads((ROOT / "mobile-hammer/app/src/main/assets/stock_industries.json").read_text(encoding="utf-8"))["stocks"]
    merged = {}
    for group, starred in [(held, True), (noted, False)]:
        for stock in group["stocks"]:
            code = stock["code"]
            if len(code) != 6 or not code.isdigit():
                raise ValueError(f"Invalid stock code: {code}")
            if code not in merged:
                merged[code] = {"code": code, "name": stock.get("name") or code,
                                "industry": catalog.get(code, stock.get("industry") or "待分类"), "starred": starred}
            elif starred:
                merged[code]["starred"] = True
    stocks = list(merged.values())
    digest = hashlib.sha256(json.dumps(stocks, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()[:16]
    held_codes = {s["code"] for s in held["stocks"]}
    noted_codes = {s["code"] for s in noted["stocks"]}
    stats = {"holdings": len(held_codes), "noted": len(noted_codes), "overlap": len(held_codes & noted_codes),
             "total": len(stocks), "starred": sum(s["starred"] for s in stocks)}
    snapshot = {"id": "web-watchlist-" + digest, "exported_at": datetime.now(ZoneInfo("Asia/Shanghai")).isoformat(timespec="seconds"),
                "source_groups": [{"id": g["id"], "name": g["name"]} for g in [held, noted]], "counts": stats, "stocks": stocks}
    target = ROOT / "mobile-hammer/app/src/main/assets/web_watchlist_seed.json"
    target.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    assert {s["code"] for s in stocks} == held_codes | noted_codes
    assert {s["code"] for s in stocks if s["starred"]} == held_codes
    print(json.dumps(stats, ensure_ascii=False))

if __name__ == "__main__":
    export()
