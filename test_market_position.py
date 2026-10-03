import unittest

import pandas as pd

from market_position_service import assess_market_position, assess_flight_height, flight_intervals


class MarketPositionTest(unittest.TestCase):
    def fixture(self, close=3500):
        # Forty business days ensure the rule uses trading rows, not calendar days.
        frame = pd.DataFrame({"date": pd.bdate_range("2026-08-01", periods=40),
                              "high": 3900.0, "low": 3300.0, "close": 3600.0})
        frame.loc[0, ["high", "low"]] = [5000, 2000]
        frame.loc[39, "close"] = close
        return frame

    def test_latest_30_trading_days_ignore_older_extremes(self):
        frame = self.fixture()
        result = assess_market_position(frame.iloc[::-1])
        self.assertTrue(result["available"])
        self.assertEqual(result["days"], 30)
        self.assertEqual(result["start_date"], frame.loc[10, "date"].strftime("%Y-%m-%d"))
        self.assertEqual(result["date"], frame.loc[39, "date"].strftime("%Y-%m-%d"))
        self.assertEqual((result["low"], result["high"]), (3300, 3900))
        self.assertEqual((result["lower_threshold"], result["upper_threshold"]), (3360, 3840))

    def test_three_bands_and_exact_boundaries(self):
        for close, band, position in [(3300, "low", 1), (3359.99, "low", 1),
                                       (3360, "middle", 2 / 3), (3839.99, "middle", 2 / 3),
                                       (3840, "high", 1 / 3), (3900, "high", 1 / 3)]:
            with self.subTest(close=close):
                result = assess_market_position(self.fixture(close))
                self.assertEqual(result["band"], band)
                self.assertEqual(result["position"], position)
                self.assertAlmostEqual(result["range_percent"], (close - 3300) / 600 * 100)

    def test_intraday_extremes_not_close_extremes(self):
        frame = self.fixture(3600)
        frame.loc[20, "high"] = 4200
        result = assess_market_position(frame)
        self.assertEqual(result["high"], 4200)
        self.assertEqual(result["high_date"], frame.loc[20, "date"].strftime("%Y-%m-%d"))
        self.assertEqual(result["band"], "middle")

    def test_missing_insufficient_duplicate_and_bad_quotes(self):
        frame = self.fixture()
        duplicate = pd.concat([frame.tail(29), frame.tail(1)])
        for data in (None, pd.DataFrame(), frame.tail(29), duplicate):
            with self.subTest(data=type(data).__name__):
                self.assertFalse(assess_market_position(data)["available"])
        for value in (float("nan"), float("inf"), 0, -1, "--"):
            bad = frame.copy().astype({"close": object})
            bad.loc[39, "close"] = value
            self.assertFalse(assess_market_position(bad)["available"])
        self.assertFalse(assess_market_position(self.fixture(4000))["available"])

    def test_flat_range_does_not_invent_position(self):
        frame = self.fixture()
        frame[["high", "low", "close"]] = 3600
        result = assess_market_position(frame)
        self.assertFalse(result["available"])
        self.assertNotIn("position", result)


class FlightHeightTest(unittest.TestCase):
    def fixture(self):
        closes = [3700.0 + index * 2 for index in range(100)]
        frame = pd.DataFrame({"date": pd.bdate_range("2026-05-01", periods=100),
                              "high": [close + 5 for close in closes],
                              "low": [close - 5 for close in closes], "close": closes})
        frame.loc[0, ["high", "low"]] = [9000, 10]  # Outside the 90-day window.
        frame.loc[20, "high"] = 4100
        frame.loc[80, "high"] = 3950
        return frame

    def test_ten_references_and_nearest_two_distinct_layers(self):
        frame = self.fixture()
        result = assess_flight_height(frame.iloc[::-1])
        self.assertTrue(result["available"])
        self.assertEqual(result["reference_count"], 10)
        refs = {row["label"]: row["price"] for row in result["references"]}
        self.assertEqual(refs, {"10日最高点": 3903, "10日最低点": 3875,
                               "30日最高点": 3950, "30日最低点": 3835,
                               "90日最高点": 4100, "90日最低点": 3715,
                               "MA5": 3894, "MA10": 3889, "MA20": 3879, "MA30": 3869})
        self.assertEqual(result["first"]["support"]["price"], 3894)
        self.assertEqual(result["first"]["resistance"]["price"], 3903)
        self.assertEqual(result["second"]["support"]["price"], 3889)
        self.assertEqual(result["second"]["resistance"]["price"], 3950)
        self.assertTrue(result["second"]["complete"])

    def test_duplicate_levels_merge_all_sources_without_zero_width_range(self):
        frame = self.fixture()
        frame[["high", "low", "close"]] = [3900, 3300, 3600]
        result = assess_flight_height(frame)
        self.assertEqual(result["reference_count"], 10)
        self.assertEqual(result["level_count"], 3)
        self.assertEqual([len(level["sources"]) for level in result["levels"]], [3, 4, 3])
        self.assertEqual(result["touching"]["price"], 3600)
        self.assertEqual(result["first"]["support"]["price"], 3600)
        self.assertEqual(result["first"]["resistance"]["price"], 3900)
        self.assertEqual(result["second"]["support"]["price"], 3300)
        self.assertIsNone(result["second"]["resistance"])
        self.assertFalse(result["second"]["complete"])

    def test_exact_boundary_and_price_outside_reference_range(self):
        levels = [{"price": value} for value in (100, 200, 300, 400)]
        at = flight_intervals(levels, 200)
        self.assertEqual(at["first"], {"support": levels[1], "resistance": levels[2], "complete": True})
        self.assertEqual(at["second"], {"support": levels[0], "resistance": levels[3], "complete": True})
        below = flight_intervals(levels, 90)
        self.assertIsNone(below["first"]["support"])
        self.assertEqual(below["first"]["resistance"], levels[0])
        self.assertEqual(below["second"]["resistance"], levels[1])
        above = flight_intervals(levels, 450)
        self.assertEqual(above["first"]["support"], levels[3])
        self.assertEqual(above["second"]["support"], levels[2])
        self.assertIsNone(above["first"]["resistance"])
        self.assertIsNone(above["second"]["resistance"])

    def test_90_day_requirement_and_invalid_recent_data(self):
        frame = self.fixture()
        self.assertFalse(assess_flight_height(frame.tail(89))["available"])
        self.assertTrue(assess_market_position(frame.tail(89))["available"])
        for value in (float("nan"), float("inf"), -1):
            bad = frame.copy()
            bad.loc[50, "close"] = value
            self.assertFalse(assess_flight_height(bad)["available"])
        self.assertFalse(assess_flight_height(None)["available"])


if __name__ == "__main__":
    unittest.main()
