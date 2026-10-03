import csv
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import concept_rotation_service as concepts
import stock_concept_service as members
import industry_strength_service as strength
import watchlist_service as watchlists
from test_industry_strength import IndustryStrengthTest

class ConceptRotationTest(unittest.TestCase):
    def fixture(self):
        fixture=IndustryStrengthTest();fixture.setUp();self.addCleanup(fixture.doCleanups);fixture.fixture()
        snapshot={'000001':{'industries':['概念A','概念B','概念A']},'600001':{'industries':['概念A']},
                  '600002':{'industries':['概念B']},'600003':{'industries':['概念A']},'300001':{'industries':['概念A']}}
        boards={'概念A':'885001','概念B':'885002','空缺':'885003'}
        dates={'概念A':fixture.dates[-1],'概念B':fixture.dates[-1]}
        for target,value in [(concepts,'_cache'),(concepts,'_signature')]:
            ctx=patch.object(target,value,None);ctx.start();self.addCleanup(ctx.stop)
        ctx=patch.object(members,'sources',return_value=(boards,snapshot,{},dates,('v1',)));ctx.start();self.addCleanup(ctx.stop)
        ctx=patch.object(watchlists,'WATCHLISTS_FILE',fixture.root/'watchlists.json');ctx.start();self.addCleanup(ctx.stop)
        ctx=patch.object(watchlists,'recent_market_dates',return_value=fixture.dates);ctx.start();self.addCleanup(ctx.stop)
        return fixture

    def test_shared_baseline_many_memberships_and_latest_names(self):
        self.fixture();a=concepts.history('概念A')['data'][-1];b=concepts.history('概念B')['data'][-1]
        industry=strength.get_industry_strength_history('行业A')['data'][-1]
        for key in ['relative_5d','relative_20d','outperform_5d_pct','above_ma20_pct','benchmark_5d','benchmark_20d']:
            self.assertAlmostEqual(a[key],industry[key])
        self.assertEqual(a['eligible_count'],2);self.assertEqual(b['eligible_count'],2)
        self.assertEqual(a['benchmark_covered_5d'],3);self.assertEqual(b['benchmark_covered_5d'],3)
        self.assertEqual(a['benchmark_expected'],3);self.assertEqual(a['benchmark_5d'],b['benchmark_5d'])
        with self.assertRaises(ValueError):concepts.history('不存在')

    def test_rank_sort_duplicate_across_concepts_and_preserved_manual_groups(self):
        fixture=self.fixture();payload=watchlists.load_watchlists();payload['groups'].append({'id':'holdings','name':'持仓','stocks':[{'code':'600999','name':'原股票'}]});payload['notes']['600999']='原笔记';watchlists.save_watchlists(payload)
        screen=concepts.refresh();self.assertEqual(screen['concept_count'],3);self.assertEqual(screen['stock_count'],4);self.assertEqual(screen['unique_stock_count'],3)
        self.assertIsNone(screen['concepts'][-1]['score']);self.assertEqual(screen['concepts'][-1]['name'],'空缺')
        saved=watchlists.load_watchlists();stocks=next(g for g in saved['groups'] if g['id']=='concept_rotation')['stocks']
        self.assertEqual(sum(s['code']=='000001' for s in stocks),2)
        self.assertEqual({s['concept'] for s in stocks if s['code']=='000001'},{'概念A','概念B'})
        for concept in ['概念A','概念B']:
            section=[s for s in stocks if s['concept']==concept];self.assertEqual(section[0]['code'],'000001');self.assertEqual([s['sector_rank'] for s in section],[1,2]);self.assertEqual(section[0]['concept_member_date'],fixture.dates[-1])
        self.assertEqual(saved['notes']['600999'],'原笔记');self.assertEqual(next(g for g in saved['groups'] if g['id']=='holdings')['stocks'][0]['name'],'原股票')
        # A restarted server can serve a saved ranking without rebuilding all history.
        with patch.object(concepts,'_cache',None),patch.object(strength,'_build',side_effect=AssertionError('unnecessary rebuild')):
            self.assertEqual(concepts.refresh()['signature'],screen['signature'])
        for row in screen['concepts']:
            if row['score'] is not None:
                last=concepts.history(row['name'])['data'][-1]
                for key in ['relative_5d','relative_20d','outperform_5d_pct','above_ma20_pct']:self.assertAlmostEqual(row[key],last[key])

    def test_snapshot_dedup_latest_date_and_safe_names(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);catalog=root/'catalog.csv';store=root/'members';store.mkdir()
            with catalog.open('w',encoding='utf-8-sig',newline='') as f:
                w=csv.writer(f);w.writerow(['板块名称','板块代码','板块类型']);w.writerows([['DRG/DIP','885001','概念'],['B','885002','概念'],['行业','881001','行业']])
            for name,day,rows in [('DRG_DIP','2026-09-29',[('000001','Old')]),('DRG_DIP','2026-09-30',[('600001','Latest'),('600001','Duplicate')]),('B','2026-09-30',[('600001','Latest')])]:
                with (store/f'{name}_{day}.csv').open('w',encoding='utf-8-sig',newline='') as f:
                    w=csv.writer(f);w.writerow(['code','name']);w.writerows(rows)
            with patch.object(members,'CATALOG_FILE',catalog),patch.object(members,'MEMBER_DIR',store):
                boards,mapping,names,dates,signature=members.sources()
            self.assertNotIn('行业',boards);self.assertNotIn('000001',mapping);self.assertEqual(mapping['600001']['industries'],['B','DRG/DIP']);self.assertEqual(dates['DRG/DIP'],'2026-09-30');self.assertEqual(members.safe_name('DRG/DIP'),'DRG_DIP')

if __name__=='__main__':unittest.main()
