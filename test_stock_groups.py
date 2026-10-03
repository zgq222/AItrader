import csv
import json
import os
import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path
from unittest.mock import patch

import stock_industry_service as industries
import watchlist_service as watchlists


class StockGroupsTest(unittest.TestCase):
    def test_return_ranking_uses_window_low_filters_st_and_keeps_sector_top50(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            dates = [(date(2026, 8, 1) + timedelta(days=i)).isoformat() for i in range(31)]

            def history(code, name, low=8, last=20, end_date=None, invalid_lows=False):
                path = root / f"{code}_{name}_原始数据.csv"
                with path.open('w', encoding='utf-8', newline='') as handle:
                    writer = csv.writer(handle)
                    writer.writerow(['date', 'close', 'low'])
                    writer.writerows([(dates[0], 10, 0.01),
                                      (dates[1], 1000, 0 if invalid_lows else low+2),
                                      (dates[10], 12, 0 if invalid_lows else low),
                                      (end_date or dates[-1], last, 0 if invalid_lows else last-1)])
                return path

            for i in range(160):
                history(f'600{i:03}', f'Equity{i}', last=20+i)
            history('300001', 'Growth', last=10000)
            history('688001', 'STAR', last=10000)
            history('600900', 'STExcluded', last=10000)
            history('600901', 'ZeroLow', invalid_lows=True)
            history('600902', 'Outdated', end_date=dates[-2])
            history('600903', 'Invalid', last=float('nan'))
            old = history('600904', 'OldNonSTName', last=10000)
            current = history('600904', 'STNewName', last=10000)
            os.utime(old, (100, 100))
            os.utime(current, (200, 200))
            file = root / 'watchlists.json'
            file.write_text(json.dumps({'groups': [{'id': 'holdings', 'name': '持仓股',
                                                    'stocks': [{'code': '600001', 'name': 'Stock1'}]}]}), encoding='utf-8')
            with patch.object(watchlists, 'RAW_DIR', root), patch.object(watchlists, 'WATCHLISTS_FILE', file), \
                 patch.object(watchlists, 'recent_market_dates', side_effect=lambda count: dates[-count:]), \
                 patch.object(industries, 'load_industry_map', return_value={}):
                summary = watchlists.screen_main_board_30d_top150()
                payload = watchlists.load_watchlists()
            group = next(g for g in payload['groups'] if g['id'] == watchlists.MAIN_BOARD_30D_GROUP_ID)
            self.assertEqual((summary['eligible_count'], len(group['stocks'])), (160, 50))
            self.assertEqual(group['stocks'][0]['code'], '600159')
            self.assertAlmostEqual(group['stocks'][0]['return_30d_pct'], (179/8-1)*100)
            self.assertEqual(group['stocks'][-1]['code'], '600110')
            self.assertEqual([s['return_rank'] for s in group['stocks']], list(range(1, 51)))
            self.assertEqual([s['sector_rank'] for s in group['stocks']], list(range(1, 51)))
            self.assertEqual(group['stocks'][0]['return_low_date'], dates[10])
            self.assertEqual(group['stocks'][0]['return_low_price'], 8)
            self.assertEqual(group['stocks'][0]['return_start'], dates[1])
            self.assertTrue(group['system'] and group['auto_refresh'])
            self.assertEqual(next(g for g in payload['groups'] if g['id'] == 'holdings')['stocks'][0]['code'], '600001')

    def test_top150_seeds_industries_but_each_can_extend_beyond_global_rank150(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            dates = [(date(2026, 8, 1) + timedelta(days=i)).isoformat() for i in range(30)]
            industry_map = {}
            for index in range(240):
                code = f'600{index:03}'
                industry_map[code] = {'industry': f'行业{index % 4}'}
                with (root / f'{code}_Equity{index}_原始数据.csv').open('w', encoding='utf-8', newline='') as handle:
                    writer = csv.writer(handle)
                    writer.writerow(['date', 'close', 'low'])
                    writer.writerow([dates[0], 10, 8])
                    writer.writerow([dates[-1], 300-index, 299-index])
            file = root / 'watchlists.json'
            with patch.object(watchlists, 'RAW_DIR', root), patch.object(watchlists, 'WATCHLISTS_FILE', file), \
                 patch.object(watchlists, 'recent_market_dates', side_effect=lambda count: dates[-count:]), \
                 patch.object(industries, 'load_industry_map', return_value=industry_map):
                summary = watchlists.screen_main_board_30d_top150()
                payload = watchlists.load_watchlists()
            group = next(g for g in payload['groups'] if g['id'] == watchlists.MAIN_BOARD_30D_GROUP_ID)
            self.assertEqual(summary['seed_stock_count'], 150)
            self.assertEqual(summary['seed_industry_count'], 4)
            self.assertEqual(summary['stock_count'], 200)
            self.assertEqual(max(stock['return_rank'] for stock in group['stocks']), 200)
            self.assertTrue(all(stock['return_30d_pct'] > 0 for stock in group['stocks']))
            for industry in summary['seed_industries']:
                sector_stocks = [stock for stock in group['stocks'] if stock['industry'] == industry]
                self.assertEqual(len(sector_stocks), 50)
                self.assertEqual([stock['sector_rank'] for stock in sector_stocks], list(range(1, 51)))

    def test_insufficient_calendar_does_not_replace_existing_group(self):
        with patch.object(watchlists, 'recent_market_dates', return_value=['2026-09-29']), \
             patch.object(watchlists, 'save_watchlists') as save:
            with self.assertRaises(ValueError):
                watchlists.screen_main_board_30d_top150()
            save.assert_not_called()

    def test_latest_industry_snapshot_wins_and_supplements_only_fill_missing_codes(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for filename, codes in [('SectorA_2026-08-01.csv', ['600001', '600002']),
                                    ('SectorA_2026-09-01.csv', ['600002']),
                                    ('SectorB_2026-09-01.csv', ['600001'])]:
                (root/filename).write_text('code,name\n' + '\n'.join(f'{c},Stock' for c in codes), encoding='utf-8')
            supplement = root/'supplements.json'
            supplement.write_text(json.dumps({'stocks': {
                '600001': {'industry': 'OldSupplement', 'industry_date': '2026-08-01', 'industry_source': 'F10'},
                '600003': {'industry': 'SectorC', 'industry_date': '2026-09-29', 'industry_source': 'F10'},
            }}), encoding='utf-8')
            with patch.object(industries, 'MEMBER_DIR', root), patch.object(industries, 'SUPPLEMENTS_FILE', supplement), \
                 patch.object(industries, '_signature', None), patch.object(industries, '_mapping', {}):
                mapping = industries.load_industry_map()
                self.assertEqual(mapping['600001']['industry'], 'SectorB')
                self.assertEqual(mapping['600002']['industry'], 'SectorA')
                self.assertEqual(mapping['600003']['industry'], 'SectorC')
                stock = industries.enrich_stocks([{'code': '600003', 'name': 'Stock'}])[0]
                self.assertEqual(stock['industry_source'], 'F10')
                (root/'SectorC_2026-09-30.csv').write_text('code,name\n600003,Stock', encoding='utf-8')
                self.assertEqual(industries.load_industry_map()['600003']['industry_source'], '同花顺行业成分股（开盘红/levistock）')


if __name__ == '__main__':
    unittest.main()
