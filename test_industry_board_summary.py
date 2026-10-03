import csv
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import industry_board_summary_service as service


class IndustrySummaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.board = self.root / "boards"
        self.stock = self.root / "stocks"
        self.mapping = {"000001": {"industry": "行业A"}, "600001": {"industry": "行业A"},
                        "600002": {"industry": "行业B"}, "600003": {"industry": "行业A"}}
        for target, value in [("BOARD_DIR", self.board), ("STOCK_FLOW_DIR", self.stock),
                              ("_cache", None), ("_signature", None)]:
            context = patch.object(service, target, value)
            context.start()
            self.addCleanup(context.stop)
        context = patch.object(service.stock_industry_service, "load_industry_map", return_value=self.mapping)
        context.start()
        self.addCleanup(context.stop)

    def write(self, path, fields, rows):
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w", encoding="utf-8-sig", newline="") as file:
            writer = csv.DictWriter(file, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)

    def fixture(self):
        self.write(self.board / "行业板块" / "行业A.csv", ["日期", "收盘价", "成交额"],
                   [{"日期": "2026-09-28", "收盘价": 100, "成交额": 1e8},
                    {"日期": "2026-09-29", "收盘价": 110, "成交额": 2e8}])
        self.write(self.board / "资金流" / "industry_moneyflow_2026-09-30.csv",
                   ["行业", "行业指数", "行业-涨跌幅", "流入资金", "流出资金", "净额", "公司家数", "领涨股"],
                   [{"行业": "行业A", "行业指数": 111, "行业-涨跌幅": "1%", "净额": -2.5,
                     "流入资金": 3, "流出资金": 5.5, "公司家数": 5, "领涨股": "领先股"},
                    {"行业": "行业B", "净额": 0}, {"行业": "行业C", "净额": 1.2},
                    {"行业": "行业D", "净额": "--"}])
        self.write(self.stock / "stock_moneyflow_2026-09-29.csv", ["股票代码", "涨跌幅"],
                   [{"股票代码": 1, "涨跌幅": "2%"}, {"股票代码": "600001", "涨跌幅": "-1%"},
                    {"股票代码": "600001", "涨跌幅": "-1%"}, {"股票代码": "600002", "涨跌幅": "0%"},
                    {"股票代码": "600003", "涨跌幅": "--"}])

    def test_full_board_money_units_rank_dates_and_partial_breadth(self):
        self.fixture()
        payload = service.get_industry_summaries()
        rows = {row["name"]: row for row in payload["data"]}
        a = rows["行业A"]
        self.assertEqual(a["net_inflow"], -250000000)
        self.assertEqual(a["inflow"], 300000000)
        self.assertEqual((a["net_rank"], a["rank_total"]), (3, 3))
        self.assertEqual((a["quote_date"], a["amount_date"], a["flow_date"], a["breadth_date"]),
                         ("2026-09-30", "2026-09-29", "2026-09-30", "2026-09-29"))
        self.assertEqual(a["pct_change"], 1)
        self.assertEqual((a["up_count"], a["down_count"], a["flat_count"]), (1, 1, 0))
        self.assertEqual((a["breadth_covered"], a["breadth_expected"]), (2, 5))
        self.assertEqual(rows["行业B"]["net_inflow"], 0)
        self.assertEqual(rows["行业B"]["flat_count"], 1)
        self.assertIsNone(rows["行业D"]["net_inflow"])
        self.assertIsNone(rows["行业D"]["net_rank"])
        self.assertIsNone(rows["行业C"]["up_count"])
        self.assertIs(service.get_industry_summaries(), payload)

    def test_new_snapshot_invalidates_cache_and_keeps_latest_kline(self):
        self.fixture()
        service.get_industry_summaries()
        self.write(self.board / "资金流" / "industry_moneyflow_2026-10-01.csv", ["行业", "净额"],
                   [{"行业": "行业A", "净额": 2}])
        self.assertEqual(service.get_industry_summaries()["flow_date"], "2026-10-01")

    def test_kline_only_preserves_unknown_funds_and_computes_pct(self):
        self.write(self.board / "行业板块" / "行业A.csv", ["日期", "收盘价", "成交额"],
                   [{"日期": "2026-09-29", "收盘价": 100}, {"日期": "2026-09-30", "收盘价": 110, "成交额": 0}])
        row = service.get_industry_summaries()["data"][0]
        self.assertAlmostEqual(row["pct_change"], 10)
        self.assertEqual(row["amount"], 0)
        for field in ("net_inflow", "flow_date", "up_count", "breadth_date", "net_rank"):
            self.assertIsNone(row[field])

    def test_units_and_missing_values(self):
        self.assertEqual(service.industry_money("1.2亿"), 120000000)
        self.assertEqual(service.industry_money("300万"), 3000000)
        self.assertEqual(service.industry_money(0), 0)
        self.assertIsNone(service.industry_money("--"))

    def test_flow_history_dates_board_filter_units_and_refresh(self):
        self.fixture()
        path = self.board / "资金流" / "industry_moneyflow_2026-09-28.csv"
        self.write(path, ["行业", "净额"], [{"行业": "行业A", "净额": 0}, {"行业": "行业B", "净额": 9}])
        self.write(self.board / "资金流" / "industry_moneyflow_2026-09-29.csv",
                   ["行业", "净额"], [{"行业": "行业A", "净额": "--"}])
        result = service.get_industry_flow_history("行业A")
        self.assertEqual([row["date"] for row in result["data"]], ["2026-09-28", "2026-09-29", "2026-09-30"])
        self.assertEqual([row["net_inflow"] for row in result["data"]], [0, None, -2.5e8])
        self.assertEqual((result["total"], result["first_date"], result["latest_date"]), (2, "2026-09-28", "2026-09-30"))
        self.write(path, ["行业", "净额"], [{"行业": "行业A", "净额": 3}])
        self.assertEqual(service.get_industry_flow_history("行业A")["data"][0]["net_inflow"], 3e8)
        self.assertEqual(service.get_industry_flow_history("未知行业")["data"], [])
        self.assertIsNone(service.number("NaN"))

    def test_tail_reads_complete_utf8_rows_with_chinese_board_names(self):
        for length in range(100, 110):
            path = self.board / "行业板块" / "文化传媒.csv"
            self.write(path, ["日期", "板块名称", "收盘价", "成交额"],
                       [{"日期": str(i), "板块名称": "文化传媒" * 5, "收盘价": i, "成交额": i * 100}
                        for i in range(length)])
            rows = service._last_kline_rows(path)
            self.assertEqual([row["日期"] for row in rows], [str(length-2), str(length-1)])
            self.assertEqual(rows[-1]["板块名称"], "文化传媒" * 5)


if __name__ == "__main__":
    unittest.main()
