import unittest

import pandas as pd

from five_minute_selection_service import (daily_ma20_rising, daily_uptrend,
                                           find_recent_rising_structure, main_force_frames,
                                           match_latest_sequence, merge_contained_frames,
                                           recent_three_day_pullback)


class FiveMinuteSelectionTest(unittest.TestCase):
    def test_recent_pullback_uses_latest_run_and_does_not_bridge_missing_days(self):
        dates = [day.strftime("%Y-%m-%d") for day in pd.bdate_range("2026-09-01", periods=13)]
        rows = [{"date": day, "open": 10, "close": 10} for day in dates]
        for index in (3, 4, 5, 9, 10, 11):
            rows[index]["open"] = 11
        # A rising candle still qualifies when its close is below yesterday's close.
        rows[9].update(open=9, close=9.5)
        match = recent_three_day_pullback(rows, dates[1:])
        self.assertEqual(match, {"pullback_start_date": dates[9], "pullback_end_date": dates[11]})
        match = recent_three_day_pullback([row for index, row in enumerate(rows) if index != 10], dates[1:])
        self.assertEqual(match["pullback_end_date"], dates[5])
        outside_run = [{"date": day, "open": 11 if index < 3 else 10, "close": 10}
                       for index, day in enumerate(dates)]
        self.assertIsNone(recent_three_day_pullback(outside_run, dates[1:]))

    def test_daily_trend_uses_latest_daily_close(self):
        rising = [{"close": 10 + index * 0.05} for index in range(70)]
        self.assertTrue(daily_uptrend(rising))
        rising[-1]["close"] = 8
        self.assertFalse(daily_uptrend(rising))

    def test_rising_structure_requires_three_solid_legs_and_breakout(self):
        def frame(index, direction, low, high, contained=False):
            stamp = pd.Timestamp("2026-09-24 09:35") + pd.Timedelta(minutes=5 * index)
            return {"start_index": index, "end_index": index, "start_time": stamp,
                    "end_time": stamp, "low": low, "high": high,
                    "direction": direction, "contained": contained}
        frames = [frame(0, 1, 10, 11), frame(1, 1, 10.5, 12),
                  frame(2, 1, 11, 13), frame(3, 0, 11.5, 12.5),
                  frame(4, -1, 9, 12), frame(5, -1, 8.8, 11.5),
                  frame(6, -1, 8.6, 11), frame(7, 1, 10, 13),
                  frame(8, 1, 10.5, 13.5, contained=True)]
        match = find_recent_rising_structure(pd.DataFrame(), frames, ["2026-09-24"])
        self.assertIsNotNone(match)
        self.assertEqual(match["up_attack_count"], 3)
        self.assertEqual(match["down_reduction_count"], 3)
        self.assertEqual(match["signal"], "5分钟上涨结构")
        frames[4]["low"] = 11.1
        self.assertIsNone(find_recent_rising_structure(pd.DataFrame(), frames, ["2026-09-24"]))

    def test_single_bar_at_22_times_prior_median_is_a_frame(self):
        rows = []
        for day in pd.bdate_range("2026-08-03", periods=21):
            for start, count in ((pd.Timestamp(day).replace(hour=9, minute=35), 24),
                                 (pd.Timestamp(day).replace(hour=13, minute=5), 24)):
                for index in range(count):
                    rows.append({"datetime": start + pd.Timedelta(minutes=5 * index),
                                 "open": 10, "high": 10.1, "low": 9.9, "close": 10.05,
                                 "volume": 100})
        rows[-1]["volume"] = 250
        frames = main_force_frames(pd.DataFrame(rows))
        self.assertEqual(len(frames), 1)
        self.assertEqual(frames[0]["count"], 1)

    def test_first_resumed_attack_and_pullback_floor(self):
        prices = [(10, 10.2), (10.2, 10.2), (10.3, 10.6), (10.6, 10.6),
                  (10.6, 10.4), (10.4, 10.4), (10.4, 10.2), (10.2, 10.2),
                  (10.2, 10.5)]
        rows = [{"datetime": pd.Timestamp("2026-09-24 09:35") + pd.Timedelta(minutes=5 * index),
                 "open": start, "close": end, "low": min(start, end),
                 "high": max(start, end), "volume": 100}
                for index, (start, end) in enumerate(prices)]
        bars = pd.DataFrame(rows)
        frames = [{"start_index": index, "end_index": index,
                   "start_time": rows[index]["datetime"], "end_time": rows[index]["datetime"],
                   "start_price": rows[index]["open"], "end_price": rows[index]["close"],
                   "direction": 1 if rows[index]["close"] > rows[index]["open"] else -1}
                  for index in (0, 2, 4, 6, 8)]
        match = match_latest_sequence(bars, frames, "2026-09-24")
        self.assertEqual((match["attack_count"], match["reduction_count"]), (2, 2))
        bars.loc[7, "low"] = 9.99
        self.assertIsNone(match_latest_sequence(bars, frames, "2026-09-24"))
        bars.loc[7, "low"] = 10.2
        frames.insert(-1, {**frames[-1], "direction": 0})
        self.assertIsNotNone(match_latest_sequence(bars, frames, "2026-09-24"))

    def test_containment_merges_transitively(self):
        def frame(index, low, high):
            return {"start_index": index, "end_index": index, "start_time": pd.Timestamp("2026-09-24") + pd.Timedelta(minutes=index),
                    "end_time": pd.Timestamp("2026-09-24") + pd.Timedelta(minutes=index), "start_price": 10,
                    "end_price": 10.1, "low": low, "high": high, "direction": 1}
        merged = merge_contained_frames([frame(0, 10, 20), frame(1, 12, 18), frame(2, 8, 22), frame(3, 9, 21)])
        self.assertEqual(len(merged), 4)
        self.assertTrue(merged[0]["contained"])
        self.assertFalse(merged[2]["contained"])
        self.assertTrue(merged[3]["contained"])
        self.assertEqual(merged[2]["start_index"], 2)

    def test_containment_allows_attack_and_reduction(self):
        def frame(index, low, high, direction):
            return {"start_index": index, "end_index": index, "start_time": pd.Timestamp("2026-09-24") + pd.Timedelta(minutes=index),
                    "end_time": pd.Timestamp("2026-09-24") + pd.Timedelta(minutes=index), "start_price": 10,
                    "end_price": 10.1 if direction > 0 else 9.9, "low": low, "high": high, "direction": direction}
        merged = merge_contained_frames([frame(0, 10, 20, 1), frame(1, 12, 18, -1), frame(2, 8, 22, 1)])
        self.assertEqual(len(merged), 3)
        self.assertTrue(merged[0]["contained"])
        self.assertTrue(merged[1]["contained"])
        self.assertFalse(merged[2]["contained"])


if __name__ == "__main__":
    unittest.main()
