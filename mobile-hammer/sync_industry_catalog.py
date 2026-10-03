"""Update the APK's main-board universe and one-industry-per-stock catalog."""
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
import stock_industry_service
import watchlist_service

assets = Path(__file__).resolve().parent / "app/src/main/assets"
mapping = stock_industry_service.load_industry_map()
payload = {"source": "同花顺行业成分股", "stocks": {code: item["industry"] for code, item in sorted(mapping.items())}}
(assets / "stock_industries.json").write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
codes = sorted({p.name[:6] for p in watchlist_service.RAW_DIR.glob("*_原始数据.csv") if watchlist_service._is_main_board(p.name[:6])})
(assets / "mainboard_codes.txt").write_text("\n".join(codes) + "\n", encoding="utf-8")
print(f"已更新 {len(codes)} 个主板代码、{len(mapping)} 条行业映射")
