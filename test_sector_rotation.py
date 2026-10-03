import csv
import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path
from unittest.mock import patch

import industry_strength_service as strength
import sector_rotation_service as rotation
import watchlist_service as watchlists


class SectorRotationTest(unittest.TestCase):
    def test_percentile_scoring_ties_negative_returns_and_missing_values(self):
        rows = [{"name": name, "relative_5d": a, "relative_20d": b,
                 "outperform_5d_pct": c, "above_ma20_pct": d}
                for name, a, b, c, d in [('A', -2, 0, 50, 50), ('B', -2, 0, 50, 50),
                                        ('C', -5, -3, 0, 0), ('Missing', 10, None, 100, 100)]]
        strength.rank_industries(rows)
        self.assertEqual([row['name'] for row in rows], ['A', 'B', 'C', 'Missing'])
        self.assertEqual(rows[0]['score'], rows[1]['score'])
        self.assertAlmostEqual(rows[0]['score'], 250/3)
        self.assertAlmostEqual(rows[2]['score'], 100/3)
        self.assertIsNone(rows[-1]['score'])
        self.assertIsNone(rows[-1]['rank'])

    def test_all_constituents_latest_names_missing_prices_and_existing_groups(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            days = [(date(2026, 8, 1) + timedelta(days=i)).isoformat() for i in range(30)]
            names = {'000001': 'One', '600001': 'Two', '600002': '*STLatest', '300001': 'Growth',
                     '688001': 'STAR', '600003': 'Missing'}
            mapping = {code: {'industry': 'A'} for code in names}
            paths = {}
            for code in names:
                if code == '600003':
                    continue
                path = root / f'{code}_OldName_原始数据.csv'
                paths[code] = path
                with path.open('w', encoding='utf-8', newline='') as handle:
                    writer = csv.writer(handle)
                    writer.writerow(['date','close','low'])
                    writer.writerows([(day, 20 if code=='600001' else 12, 10) for day in days])
            ranking = {'date': days[-1], 'total': 2, 'rules': 'test',
                       'data': [{'name':'B','score':90}, {'name':'A','score':80}]}
            with patch.object(watchlists, 'WATCHLISTS_FILE', root/'watchlists.json'), \
                 patch.object(rotation.strength, '_sources', return_value=(paths,names,('source-v1',))), \
                 patch.object(rotation.strength, 'get_ranked_industries', return_value=ranking) as rank, \
                 patch.object(rotation.stock_industry_service, 'load_industry_map', return_value=mapping), \
                 patch.object(watchlists, 'recent_market_dates', return_value=days):
                payload=watchlists.load_watchlists()
                payload['groups'].append({'id':'holdings','name':'持仓股','stocks':[{'code':'600999','name':'Keep'}]})
                payload['notes']['600999']='retain note'
                watchlists.save_watchlists(payload)
                screen=rotation.refresh()
                result=watchlists.load_watchlists()
                group=next(g for g in result['groups'] if g['id']==watchlists.SECTOR_ROTATION_GROUP_ID)
                self.assertEqual([s['code'] for s in group['stocks']], ['600001','000001','600003'])
                self.assertEqual(group['stocks'][0]['return_30d_pct'],100)
                self.assertNotIn('return_30d_pct',group['stocks'][-1])
                self.assertEqual(screen['industries'][0]['member_count'],0)
                self.assertEqual(screen['stock_count'],3)
                self.assertEqual(result['notes']['600999'],'retain note')
                self.assertEqual(next(g for g in result['groups'] if g['id']=='holdings')['stocks'][0]['code'],'600999')
                rotation.refresh()
                rank.assert_called_once()
                rotation.refresh(force=True)
                self.assertEqual(rank.call_count,2)

    def test_latest_rankings_match_history_and_do_not_fill_missing_with_zero(self):
        # Use the existing small history fixture, rather than full production files.
        from test_industry_strength import IndustryStrengthTest
        fixture=IndustryStrengthTest()
        fixture.setUp()
        try:
            fixture.fixture()
            ranked=strength.get_ranked_industries()
            row=ranked['data'][0]
            latest=strength.get_industry_strength_history(row['name'])['data'][-1]
            for field in ('relative_5d','relative_20d','outperform_5d_pct','above_ma20_pct'):
                self.assertAlmostEqual(row[field],latest[field])
        finally:
            fixture.doCleanups()


if __name__ == '__main__':
    unittest.main()
