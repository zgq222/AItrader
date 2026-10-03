import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import pandas as pd
import requests

import minute_kline_service as minutes
import stock_quote_service as quotes


def quote_text(code="600519", pe="19.32", cap="15733.78"):
    fields = [""] * 54
    for index, value in {1:"贵州茅台",2:code,3:"1258.62",30:"20260930161458",39:pe,45:cap}.items():
        fields[index] = value
    return 'v_sh600519="' + "~".join(fields) + '";'


class StockQuoteTests(unittest.TestCase):
    def setUp(self):
        quotes._attempts.clear()
        quotes._quotes.clear()

    def test_quote_units_date_and_missing_or_negative_pe(self):
        payload = quotes.parse_quote(quote_text(), "600519", "sh600519")
        self.assertEqual(payload["pe"], 19.32)
        self.assertAlmostEqual(payload["market_cap"], 1573378000000)
        self.assertEqual(payload["quote_time"], "2026-09-30 16:14:58")
        for pe in ("", "-", "0", "nan"):
            self.assertIsNone(quotes.parse_quote(quote_text(pe=pe), "600519", "sh600519")["pe"])
        self.assertEqual(quotes.parse_quote(quote_text(pe="-8.5"), "600519", "sh600519")["pe"], -8.5)

    def test_reject_mismatched_quote_and_unsafe_code(self):
        with self.assertRaises(quotes.QuoteUnavailable):
            quotes.parse_quote(quote_text(code="000001"), "600519", "sh600519")
        with patch.object(quotes.requests, "Session") as session:
            with self.assertRaises(ValueError):
                quotes.get_stock_quote("../600519")
            session.assert_not_called()

    def test_cache_and_offline_fallback_keep_actual_timestamp(self):
        session = Mock()
        session.__enter__ = Mock(return_value=session)
        session.__exit__ = Mock(return_value=False)
        session.get.return_value.content = quote_text().encode("gbk")
        with tempfile.TemporaryDirectory() as directory, patch.object(quotes, "CACHE_DIR", Path(directory)), patch.object(quotes.requests, "Session", return_value=session):
            first = quotes.get_stock_quote("600519")
            self.assertEqual(quotes.get_stock_quote("600519"), first)
            self.assertEqual(session.get.call_count, 1)
            quotes._attempts.clear()
            session.get.side_effect = requests.ConnectionError("offline")
            failed = quotes.get_stock_quote("600519")
            self.assertEqual(failed["pe"], first["pe"])
            self.assertEqual(failed["quote_time"], first["quote_time"])
            self.assertIn("缓存", failed["warning"])
            self.assertEqual(quotes.get_stock_quote("600519")["warning"], failed["warning"])


class MinuteDailyReturnTests(unittest.TestCase):
    def test_same_daily_baseline_across_bars_and_trading_day_gap(self):
        daily = pd.DataFrame({"date":["2026-09-25","2026-09-28","2026-09-29"],"close":[100,110,99]})
        frame = pd.DataFrame({"datetime":["2026-09-28 09:31:00","2026-09-28 15:00:00","2026-09-29 09:31:00"],"close":[105,110,99]})
        with tempfile.TemporaryDirectory() as directory, patch.object(minutes,"RAW_DIR",Path(directory)), patch.object(minutes,"read_dataframe",return_value=daily):
            (Path(directory)/"600001_test_原始数据.csv").touch()
            enriched = minutes._add_daily_returns(frame,"600001")
        self.assertEqual(enriched.pre_close.tolist(),[100,100,110])
        for actual, expected in zip(enriched["pct_change"].tolist(),[5,10,-10]):
            self.assertAlmostEqual(actual,expected)

    def test_missing_daily_baseline_is_unknown(self):
        frame = pd.DataFrame({"datetime":["2026-09-30 09:31:00"],"close":[10]})
        with tempfile.TemporaryDirectory() as directory, patch.object(minutes,"RAW_DIR",Path(directory)):
            enriched = minutes._add_daily_returns(frame,"600001")
        self.assertTrue(pd.isna(enriched.iloc[0]["pct_change"]))
        self.assertTrue(pd.isna(enriched.iloc[0]["pre_close"]))


if __name__ == "__main__":
    unittest.main()
