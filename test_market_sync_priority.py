import os
import tempfile
import unittest
from contextlib import ExitStack
from datetime import date
from pathlib import Path
from unittest.mock import patch

import market_sync
import watchlist_service


class DailyUpdatePriorityTest(unittest.TestCase):
    def run_sync(self, max_stocks=0):
        # Deliberately put ST and growth-board stocks before the priority stocks.
        names = {'000016': 'ST康佳A', '300001': '特锐德', '600001': '主板甲',
                 '920001': '北交股', '688001': '科创股', '000001': '平安银行',
                 '002001': '主板乙', '600002': '已更新股票'}
        files = {code: (name, Path(code+'.csv'), Path(code+'_indicators.csv'))
                 for code, name in names.items()}
        with ExitStack() as stack:
            stack.enter_context(patch.object(market_sync, '_stock_files', return_value=files))
            stack.enter_context(patch.object(watchlist_service, '_latest_row',
                side_effect=lambda path: {'date': '2026-09-29' if path.stem == '600002' else '2026-09-28'}))
            stack.enter_context(patch.object(market_sync, '_write_status'))
            stack.enter_context(patch.object(market_sync.time, 'sleep'))
            stack.enter_context(patch('builtins.print'))
            fetch = stack.enter_context(patch.object(market_sync, '_fetch_free_daily',
                                                    side_effect=RuntimeError('mock data source')))
            result = market_sync.sync_stock_daily(max_stocks=max_stocks, target_date=date(2026, 9, 29))
            return [call.args[0] for call in fetch.call_args_list], result

    def test_priority_stocks_precede_all_others_without_dropping_any(self):
        calls, result = self.run_sync()
        self.assertEqual(calls, ['000001', '002001', '600001', '000016', '300001', '688001', '920001'])
        self.assertEqual(result['checked'], 8)
        self.assertEqual(result['already_current'], 1)
        self.assertEqual(result['failed'], 7)

    def test_update_limit_is_applied_after_prioritizing(self):
        calls, _ = self.run_sync(max_stocks=2)
        self.assertEqual(calls, ['000001', '002001'])

    def test_renamed_stock_uses_latest_file_to_determine_st_status(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            old = root/'000016_康佳A_原始数据.csv'
            current = root/'000016_ST康佳A_原始数据.csv'
            old.write_text('date,close\n2026-09-28,10', encoding='utf-8')
            current.write_text('date,close\n2026-09-29,10', encoding='utf-8')
            os.utime(old, (100, 100))
            os.utime(current, (200, 200))
            with patch.object(market_sync, 'RAW_DIR', root), patch.object(market_sync, 'INDICATOR_DIR', root):
                catalog = market_sync._stock_files()
            self.assertEqual(catalog['000016'][0], 'ST康佳A')
            self.assertEqual(catalog['000016'][1], current)
            self.assertEqual(watchlist_service.stock_update_sort_key('000016', catalog['000016'][0])[0], 1)


if __name__ == '__main__':
    unittest.main()
