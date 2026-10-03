import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import pandas as pd
import requests

import board_minute_service as service


def payload(rows, **extra):
    return {"data": ";".join(
        f"{stamp},100,110,90,{close},{volume},{amount},,,,0"
        for stamp, close, volume, amount in rows), **extra}


class BoardMinuteTests(unittest.TestCase):
    def setUp(self):
        service._attempts.clear()
        service._locks.clear()

    def test_parser_rejects_daily_wrong_prices_and_out_of_session_bars_and_preserves_zero(self):
        data = payload([("20260930", 105, 10, 100), ("202609301200", 105, 10, 100),
                        ("202609301000", 200, 10, 100), ("202609300930", 100, 0, 0)])
        frame = service._parse(data)
        self.assertEqual(len(frame), 1)
        self.assertEqual(frame.iloc[0].datetime, "2026-09-30 09:30:00")
        self.assertEqual(frame.iloc[0].volume, 0)

    def test_history_merges_archive_and_rolling_tail_and_keeps_saved_history(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "minute.csv.gz"
            old = service._parse(payload([("202609281500", 100, 10, 100)]))
            old.to_csv(path, index=False, compression="gzip")
            def fetch(symbol, period, file):
                self.assertEqual(symbol, "bk_881131")
                self.assertEqual(period, "60")
                return (payload([("202609291500", 105, 20, 200), ("202609301500", 110, 30, 300)],
                                name="Board", year={"2026": 3}) if file == "last"
                        else payload([("202609301500", 100, 0, 0)]))
            with patch.object(service, "_request", side_effect=fetch):
                frame, warning = service._history("bk_881131", "Board", 1, path, refresh=True)
                self.assertIsNone(warning)
                self.assertEqual(len(frame), 3)
                self.assertEqual(frame.iloc[-1].close, 110)
                self.assertEqual(len(service._read(path)), 3)
                # Immediate reads use the persisted data rather than hitting the network again.
                frame, _ = service._history("bk_881131", "Board", 1, path)
                self.assertEqual(len(frame), 3)

    def test_unavailable_provider_uses_real_cache_and_does_not_overwrite_it(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "minute.csv.gz"
            service._parse(payload([("202609301500", 100, 0, 0)])).to_csv(path, index=False, compression="gzip")
            saved = path.read_bytes()
            with patch.object(service, "_request", side_effect=requests.Timeout()):
                frame, warning = service._history("bk_881131", "Board", 1, path, refresh=True)
                self.assertEqual(len(frame), 1)
                self.assertIn("更新失败", warning)
                self.assertEqual(path.read_bytes(), saved)
                with self.assertRaises(service.MinuteUnavailable):
                    service._history("bk_881132", "Other", 1, path.with_name("empty.csv.gz"), refresh=True)

    def test_name_mismatch_cannot_import_another_index(self):
        with tempfile.TemporaryDirectory() as folder:
            with patch.object(service, "_request", return_value=payload([("202609301500", 100, 0, 0)], name="Wrong")):
                with self.assertRaises(service.MinuteUnavailable):
                    service._history("bk_881131", "Board", 1, Path(folder) / "cache.csv.gz", refresh=True)
            self.assertFalse(list(Path(folder).glob("*.gz")))

    def test_ten_fifteen_minute_aggregation_is_session_aligned_and_drops_gaps(self):
        rows = [(f"20260930{hour:02d}{minute:02d}", 100, 1, 10)
                for hour, minute in [(9,35),(9,40),(9,45),(11,25),(11,30),(13,5),(13,10),(13,15)]]
        frame = service._parse(payload(rows))
        ten = service._aggregate(frame, 10)
        self.assertEqual(ten.datetime.tolist(), ["2026-09-30 09:40:00", "2026-09-30 11:30:00", "2026-09-30 13:10:00"])
        fifteen = service._aggregate(frame, 15)
        self.assertEqual(fifteen.datetime.tolist(), ["2026-09-30 09:45:00", "2026-09-30 13:15:00"])
        self.assertEqual(fifteen.volume.tolist(), [3, 3])
        self.assertEqual(fifteen.amount.tolist(), [30, 30])

    def test_daily_percent_and_macd_use_history_before_selected_day(self):
        frame = service._parse(payload([("202609291500", 100, 1, 10),
                                        ("202609300930", 110, 1, 10), ("202609301000", 100, 1, 10)]))
        with tempfile.TemporaryDirectory() as folder:
            daily = Path(folder) / "daily.csv"
            pd.DataFrame({"日期": ["2026-09-28", "2026-09-29"], "收盘价": [100, 100]}).to_csv(daily,index=False)
            result = service._with_metrics(frame, daily)
            self.assertAlmostEqual(result.iloc[1]["pct_change"], 10)
            self.assertEqual(result.iloc[2]["pct_change"], 0)
            self.assertNotEqual(result.iloc[1].dif, 0)  # No per-day EMA reset.
            self.assertAlmostEqual(result.iloc[1].macd, (result.iloc[1].dif-result.iloc[1].dea)*2)

    def test_invalid_requests_are_rejected_before_network_or_path_access(self):
        for options in [dict(interval=2), dict(benchmark="../../etc"), dict(trade_date="2026-02-30"),
                        dict(trade_date="../2026-09-30"),dict(board_type="unknown")]:
            arguments={"board_type":"industry","name":"Board",**options}
            with self.subTest(options=options), self.assertRaises(ValueError):
                service.get_board_minute_kline(**arguments)

    def test_date_filter_missing_benchmark_and_null_percent_stay_explicit(self):
        frame = service._parse(payload([("202609291500", 100, 1, 10), ("202609301500", 110, 1, 10)]))
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with patch.object(service, "_board_identity", return_value="881131"), patch.object(service, "BOARD_DIR", root), patch.object(service, "INDEX_DIR", root), patch.object(service, "_history", side_effect=[(frame,None),service.MinuteUnavailable("Market unavailable")]):
                result = service.get_board_minute_kline("industry", "Board", trade_date="2026-09-29")
            self.assertEqual(result["total"], 1)
            self.assertEqual(result["history_total"], 2)
            self.assertIsNone(result["data"][0]["pct_change"])
            self.assertEqual(result["benchmark"]["data"], [])
            self.assertEqual(result["benchmark"]["warning"], "Market unavailable")


if __name__ == "__main__":
    unittest.main()
