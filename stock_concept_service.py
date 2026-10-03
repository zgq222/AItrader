"""Many-to-many THS concepts, using dated constituent snapshots."""
from __future__ import annotations
import csv
import re
import threading
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import stock_industry_service

BASE_DIR=Path(__file__).resolve().parent
BOARD_DIR=BASE_DIR/'板块'/'概念板块'
MEMBER_DIR=BASE_DIR/'板块'/'成分股'/'concept'
CATALOG_FILE=BASE_DIR/'板块'/'板块列表'/'同花顺板块列表.csv'
_sync_lock=threading.Lock()

def safe_name(name):
    return re.sub(r'[\\/:*?"<>|]','_',str(name)).strip()

def catalog():
    with CATALOG_FILE.open(encoding='utf-8-sig',newline='') as handle:
        return {row['板块名称']:row['板块代码'] for row in csv.DictReader(handle)
                if row.get('板块类型')=='概念' and re.fullmatch(r'\d{6}',row.get('板块代码',''))}

def sources():
    boards=catalog();latest={};original={safe_name(name):name for name in boards}
    for path in MEMBER_DIR.glob('*.csv'):
        match=re.fullmatch(r'(.+)_(\d{4}-\d{2}-\d{2})',path.stem)
        name=original.get(match[1]) if match else None
        if name and (name not in latest or path.stem>latest[name].stem):latest[name]=path
    mapping={};names={};dates={}
    for name,path in sorted(latest.items()):
        seen=set();dates[name]=path.stem[-10:]
        with path.open(encoding='utf-8-sig',newline='') as handle:
            for row in csv.DictReader(handle):
                code=re.sub(r'^(sh|sz|bj)','',row.get('code',''),flags=re.I).removesuffix('.0').zfill(6)
                if not re.fullmatch(r'\d{6}',code) or code in seen:continue
                seen.add(code);names[code]=row.get('name') or code
                mapping.setdefault(code,{'industries':[]})['industries'].append(name)
    files=[CATALOG_FILE,*latest.values()]
    signature=tuple(sorted((str(p),p.stat().st_mtime_ns,p.stat().st_size) for p in files))
    return boards,mapping,names,dates,signature

def sync_members(trade_date):
    with _sync_lock:return _sync_members(trade_date)

def _sync_members(trade_date):
    """Only fetch missing/outdated snapshots; retain old data if a source fails."""
    import levistock as lk
    import os
    boards=catalog();_,_,_,dates,_=sources();MEMBER_DIR.mkdir(parents=True,exist_ok=True)
    pending=[(name,code) for name,code in boards.items() if dates.get(name,'')<trade_date]
    def fetch(item):
        name,code=item
        try:
            rows=lk.sector_stocks_his_kph(plate_id=code,date=trade_date)
            if not rows:raise ValueError('未返回成分股')
            target=MEMBER_DIR/f'{safe_name(name)}_{trade_date}.csv';temp=target.with_suffix('.tmp')
            with temp.open('w',encoding='utf-8-sig',newline='') as handle:
                writer=csv.DictWriter(handle,fieldnames=['code','name','数据日期','数据源']);writer.writeheader()
                for row in rows:writer.writerow({'code':row['code'],'name':row['name'],'数据日期':trade_date,'数据源':'同花顺概念/开盘红/levistock'})
            os.replace(temp,target)
            return None
        except Exception as error:return {'name':name,'error':str(error)[:160]}
    with ThreadPoolExecutor(max_workers=4) as pool:errors=[error for error in pool.map(fetch,pending) if error]
    return {'trade_date':trade_date,'requested':len(pending),'saved':len(pending)-len(errors),'errors':errors}
