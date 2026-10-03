"""Concept rotation with the same calendar, market baseline and scoring as industry rotation."""
from __future__ import annotations
import hashlib
import threading
from datetime import datetime
import industry_strength_service as strength
import stock_concept_service as concepts
import watchlist_service as watchlists

_lock=threading.RLock()
_cache=None
_signature=None
METHOD='all_concepts_equal_percentile_30d_low_to_close_v1'

def _sources():
    paths,names,stock_signature=strength._sources()
    boards,mapping,member_names,dates,member_signature=concepts.sources()
    names={**member_names,**names}  # Latest catalog names control the ST filter.
    signature=(stock_signature,member_signature,METHOD)
    return boards,mapping,dates,paths,names,signature

def _data(source=None):
    global _cache,_signature
    boards,mapping,dates,paths,names,signature=source or _sources()
    if _cache is None or signature!=_signature:
        _cache=strength._build(paths,names,mapping);_signature=signature
    return _cache,boards,mapping,dates,paths,names,signature

def refresh(force=False):
    with _lock:
        source=_sources()
        boards,mapping,member_dates,paths,names,signature=source
        digest=hashlib.sha256(repr(signature).encode()).hexdigest()
        with watchlists.WATCHLISTS_LOCK:previous=watchlists.load_watchlists().get('concept_rotation_screen',{})
        if not force and previous.get('signature')==digest:return previous
        cache,*_=_data(source)
        trade_date,ranked=strength._ranked_rows(cache,boards)
        if not trade_date:raise ValueError('暂无可用的本地概念行情')
        days=[day for day in watchlists.recent_market_dates(60) if day<=trade_date][-30:]
        buckets={name:[] for name in boards};gains={}
        for code,item in mapping.items():
            name=names.get(code,'')
            if not strength._eligible(code,name):continue
            gains[code]=watchlists.low_to_latest_close_gain(watchlists._latest_rows(paths[code],100),days) if code in paths and len(days)==30 else None
            for concept in strength._memberships(mapping,code):
                buckets[concept].append({'code':code,'name':name,'concept':concept,'concept_code':boards[concept],
                    'concept_member_date':member_dates.get(concept),'return_30d_pct':None,**(gains[code] or {}),'return_start':days[0] if days else None})
        stocks=[]
        for row in ranked:
            members=buckets[row['name']];members.sort(key=lambda s:(s['return_30d_pct'] is None,-(s['return_30d_pct'] or 0),s['code']))
            for rank,stock in enumerate(members,1):stock['sector_rank']=rank
            row.update(member_count=len(members),code=boards[row['name']],member_date=member_dates.get(row['name']))
            stocks.extend(members)
        screen={'updated_at':datetime.now().isoformat(timespec='seconds'),'trade_date':trade_date,'method':METHOD,'signature':digest,
                'concepts':ranked,'concept_count':len(ranked),'stock_count':len(stocks),'unique_stock_count':len(gains),
                'board_rules':'同一交易日，5日/20日相对强度、5日跑赢市场比例、站上MA20比例分别转换为概念排名百分位，再等权平均；强度50%、广度50%。四项齐全才评分，缺项置后。',
                'stock_rules':'全部沪深主板非ST成分股，按30个交易日最低日K低点至最新收盘价涨幅降序；缺行情置后，不设前N名。股票可属于多个概念，市场基准不重复计数。',
                'source':'同花顺概念成分股（开盘红/levistock日期快照）+ 本地不复权日K',
                'missing_member_count':sum(name not in member_dates for name in boards),'stock_sort':'return_30d_low_to_close'}
        with watchlists.WATCHLISTS_LOCK:
            payload=watchlists.load_watchlists();group=next(g for g in payload['groups'] if g['id']==watchlists.CONCEPT_ROTATION_GROUP_ID)
            group['stocks']=stocks;payload['concept_rotation_screen']=screen;watchlists.save_watchlists(payload)
        return screen

def history(name):
    with _lock:
        cache,boards,_,dates,_,_,_=_data()
        if name not in boards:raise ValueError('未找到该概念')
        rows=strength._history(cache,name)
        return {'name':name,'data':rows,'total':len(rows),'first_date':rows[0]['date'] if rows else None,'latest_date':rows[-1]['date'] if rows else None,
                'member_date':dates.get(name),'scope':'沪深主板非ST，按当前概念成员及最新股票名称过滤',
                'benchmark':'全部本地沪深主板非ST股票的同期等权平均涨幅（每只股票只计一次）',
                'source':'本地不复权日K + 同花顺概念日期快照',
                'note':'与行业轮动相同，按共享交易日历计算，缺项不补零；同一股票可属于多个概念，历史按当前成员回看。'}
