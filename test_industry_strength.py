import csv
import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path
from unittest.mock import patch

import industry_strength_service as service


class IndustryStrengthTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.raw, self.boards, self.flows = [self.root / part for part in ("raw", "boards", "flows")]
        self.catalog = self.root / "catalog.csv"
        self.mapping = {code: {"industry": industry} for code, industry in
                        [("000001", "行业A"), ("600001", "行业A"), ("600002", "行业B"),
                         ("600003", "行业A"), ("300001", "行业A"), ("688001", "行业A")]}
        self.dates = []
        current = date(2026, 8, 3)
        while len(self.dates) < 32:
            if current.weekday() < 5:
                self.dates.append(current.isoformat())
            current += timedelta(days=1)
        for target, value in [("RAW_DIR", self.raw), ("BOARD_DIR", self.boards),
                              ("STOCK_FLOW_DIR", self.flows), ("CATALOG_FILE", self.catalog),
                              ("_cache", None), ("_signature", None)]:
            context = patch.object(service, target, value)
            context.start()
            self.addCleanup(context.stop)
        context = patch.object(service.stock_industry_service, "load_industry_map", return_value=self.mapping)
        context.start()
        self.addCleanup(context.stop)

    def write(self, path, fields, rows):
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)

    def stock(self, code, name, values, missing=()):
        path = self.raw / f"{code}_{name}_原始数据.csv"
        self.write(path, ["date", "close"], [{"date": date, "close": value}
                   for i, (date, value) in enumerate(zip(self.dates, values)) if i not in missing])
        return path

    def fixture(self, missing=()):
        self.write(self.boards / "行业A.csv", ["日期", "收盘价"],
                   [{"日期": day, "收盘价": 100} for day in self.dates])
        a = self.stock("000001", "股票A", [100 + 2 * i for i in range(32)], missing)
        self.stock("600001", "股票B", [100 - i for i in range(32)])
        self.stock("600002", "股票C", [100] * 32)
        self.stock("600003", "旧股票名", [100 + 20 * i for i in range(32)])
        self.stock("300001", "创业板股", [100 + 20 * i for i in range(32)])
        self.stock("688001", "科创板股", [100 + 20 * i for i in range(32)])
        self.write(self.flows / f"stock_moneyflow_{self.dates[-1]}.csv", ["股票代码", "股票简称"],
                   [{"股票代码": "600003", "股票简称": "*ST旧股票名"}])
        return a

    def test_equal_weighted_returns_benchmark_scope_and_breadth(self):
        self.fixture()
        result = service.get_industry_strength_history("行业A")
        row = result["data"][-1]
        a5, b5 = (162 / 152 - 1) * 100, (69 / 74 - 1) * 100
        a20, b20 = (162 / 122 - 1) * 100, (69 / 89 - 1) * 100
        self.assertEqual(row["eligible_count"], 2)
        self.assertEqual(row["benchmark_expected"], 3)
        self.assertAlmostEqual(row["return_5d"], (a5 + b5) / 2)
        self.assertAlmostEqual(row["benchmark_5d"], (a5 + b5) / 3)
        self.assertAlmostEqual(row["relative_5d"], (a5 + b5) / 2 - (a5 + b5) / 3)
        self.assertAlmostEqual(row["relative_20d"], (a20 + b20) / 2 - (a20 + b20) / 3)
        self.assertEqual(row["outperform_5d_pct"], 50)
        self.assertEqual(row["above_ma20_pct"], 50)
        self.assertEqual(row["up_pct"], 50)
        self.assertEqual(row["covered_20d"], 2)
        self.assertEqual(row["benchmark_covered_20d"], 3)
        self.assertEqual(result["latest_date"], self.dates[-1])
        self.assertIsNone(result["data"][0]["relative_5d"])
        self.assertIsNone(result["data"][18]["above_ma20_pct"])

    def test_missing_sessions_do_not_shorten_windows_or_become_zero(self):
        self.fixture(missing=(29,))
        row = service.get_industry_strength_history("行业A")["data"][-1]
        self.assertEqual(row["eligible_count"], 2)
        self.assertEqual(row["covered_5d"], 1)
        self.assertEqual(row["covered_20d"], 1)
        self.assertEqual(row["ma20_covered"], 1)
        self.assertEqual(row["outperform_5d_pct"], 0)
        self.assertEqual(row["above_ma20_pct"], 0)
        self.assertAlmostEqual(row["return_5d"], (69 / 74 - 1) * 100)
        self.assertEqual(service.get_industry_strength_history("未知行业")["data"], [])

    def test_zero_returns_equal_benchmark_are_valid_but_not_outperformers(self):
        self.fixture()
        row = service.get_industry_strength_history("行业B")["data"][-1]
        self.assertEqual(row["return_5d"], 0)
        self.assertEqual(row["above_ma20_pct"], 0)
        self.assertEqual(row["ma20_covered"], 1)

    def test_cache_invalidates_when_prices_names_or_members_change(self):
        path = self.fixture()
        first = service.get_industry_strength_history("行业A")["data"]
        self.assertIs(service.get_industry_strength_history("行业A")["data"], first)
        self.stock("000001", "股票A", [100 + 3 * i for i in range(32)])
        second = service.get_industry_strength_history("行业A")["data"]
        self.assertIsNot(second, first)
        self.assertNotEqual(second[-1]["relative_5d"], first[-1]["relative_5d"])
        self.mapping["000001"]["industry"] = "行业B"
        self.assertEqual(service.get_industry_strength_history("行业A")["data"][-1]["eligible_count"], 1)
        self.assertTrue(path.exists())

    def test_no_future_prices_and_missing_industry_samples(self):
        path = self.fixture()
        with path.open("a", encoding="utf-8") as handle:
            handle.write("2099-01-01,999999\n")
        result = service.get_industry_strength_history("行业A")
        self.assertEqual(result["data"][-1]["date"], self.dates[-1])
        self.assertAlmostEqual(result["data"][-1]["return_5d"], ((162 / 152 - 1) + (69 / 74 - 1)) * 50)

    def test_full_history_exceeds_120_sessions_and_old_prices_invalidate_cache(self):
        self.dates = [(date(2025, 1, 1) + timedelta(days=i)).isoformat() for i in range(240)]
        self.write(self.boards / "行业A.csv", ["日期"], [{"日期": day} for day in self.dates])
        self.stock("000001", "股票A", [100 + i for i in range(240)])
        self.stock("600002", "股票C", [100] * 240)
        result = service.get_industry_strength_history("行业A")
        self.assertEqual(result["total"], 240)
        self.assertEqual(result["first_date"], self.dates[0])
        self.assertEqual(result["first_valid_date"], self.dates[5])
        self.assertEqual(result["latest_date"], self.dates[-1])
        self.assertAlmostEqual(result["data"][20]["relative_20d"], (120 / 100 - 1) * 50)
        values = [100 + i for i in range(240)]
        values[0] = 90
        self.stock("000001", "股票A", values)
        updated = service.get_industry_strength_history("行业A")
        self.assertNotEqual(updated["data"][20]["relative_20d"], result["data"][20]["relative_20d"])
        self.assertEqual(updated["data"][-1], result["data"][-1])

    def test_full_history_uses_prior_prices_to_initialise_first_board_day(self):
        self.stock("000001", "股票A", [100 + i for i in range(32)])
        self.stock("600002", "股票C", [100] * 32)
        self.write(self.boards / "行业A.csv", ["日期"], [{"日期": day} for day in self.dates[20:]])
        result = service.get_industry_strength_history("行业A")
        self.assertEqual(result["first_date"], self.dates[20])
        self.assertEqual(result["first_valid_date"], self.dates[20])
        self.assertEqual(result["total"], 12)
        row = result["data"][0]
        self.assertAlmostEqual(row["relative_20d"], (120 / 100 - 1) * 50)
        self.assertEqual(row["above_ma20_pct"], 100)

    def test_limit_counts_use_half_up_cents_and_exclude_st_growth_and_star(self):
        self.fixture()
        self.stock("000001", "股票A", [10.05] * 31 + [11.06])
        self.stock("600001", "股票B", [5.05] * 31 + [4.55])
        row = service.get_industry_strength_history("行业A")["data"][-1]
        self.assertEqual(row["limit_up_count"], 1)
        self.assertEqual(row["limit_down_count"], 1)
        self.assertEqual(row["limit_covered"], 2)

    def test_limit_counts_use_adjusted_reference_and_skip_zero_volume(self):
        self.fixture()
        self.write(self.raw / "000001_股票A_原始数据.csv", ["date", "close", "pct_change", "volume"],
                   [{"date": day, "close": 8.8 if i == 31 else 10, "pct_change": 10 if i == 31 else 0,
                     "volume": 100} for i, day in enumerate(self.dates)])
        self.write(self.raw / "600001_股票B_原始数据.csv", ["date", "close", "volume"],
                   [{"date": day, "close": 11 if i == 31 else 10, "volume": 0 if i == 31 else 100}
                    for i, day in enumerate(self.dates)])
        row = service.get_industry_strength_history("行业A")["data"][-1]
        self.assertEqual(row["limit_up_count"], 1)
        self.assertEqual(row["limit_down_count"], 0)
        self.assertEqual(row["limit_covered"], 1)

    def test_ipo_first_five_days_and_no_coverage_stay_null(self):
        self.write(self.boards / "行业A.csv", ["日期"], [{"日期": day} for day in self.dates[:6]])
        self.stock("000001", "股票A", [10, 11, 12.1, 13.31, 14.64, 16.1])
        data = service.get_industry_strength_history("行业A")["data"]
        self.assertTrue(all(row["limit_up_count"] is None and row["limit_down_count"] is None for row in data[:5]))
        self.assertEqual(data[5]["limit_up_count"], 1)
        self.assertEqual(data[5]["limit_down_count"], 0)

if __name__ == "__main__":
    unittest.main()
