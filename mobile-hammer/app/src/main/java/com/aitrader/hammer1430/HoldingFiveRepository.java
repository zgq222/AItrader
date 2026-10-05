package com.aitrader.hammer1430;

import android.content.Context;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Check each completed five-minute slot; fill the 20-day baseline once, then fetch only recent bars. */
final class HoldingFiveRepository {
    private final Map<String,String> completed=new ConcurrentHashMap<>();
    void scan(Context c,long now)throws Exception{
        String slot=HoldingLive.slot(now);if(slot==null)return;String today=HoldingLive.today(now);JSONObject quotes=HoldingLive.quotes(c),previous=HoldingFiveStore.records(c),results=new JSONObject();ExecutorService workers=Executors.newFixedThreadPool(4);List<JSONObject> pending=new ArrayList<>();List<Future<JSONObject>> jobs=new ArrayList<>();
        try{for(JSONObject item:HoldingsStore.stocks(c)){String code=item.getString("code");JSONObject q=quotes.optJSONObject(code);if(q==null||!q.optString("quote_time").startsWith(today)||slot.equals(completed.get(code)))continue;pending.add(item);jobs.add(workers.submit(()->{
                List<DailyBar> cached;try{cached=MinuteCache.load(c,code,5);}catch(Exception e){cached=Collections.emptyList();}
                boolean full=cached.isEmpty()||!cached.get(cached.size()-1).date.startsWith(today)||MinuteBehavior.baselines(cached,now,5).getOrDefault(today,0.0)<=0;
                List<DailyBar> fresh=full?TencentClient.minutes(code,5):TencentClient.recentFiveMinutes(code);if(Thread.currentThread().isInterrupted())throw new InterruptedException();MinuteCache.save(c,code,5,fresh);List<DailyBar> bars=MinuteCache.load(c,code,5);JSONObject row=HoldingFiveStore.evaluate(item,bars,today,now);
                if(row.getString("data_time").compareTo(slot)<0)throw new Exception("5分钟源尚未更新至 "+slot.substring(11));completed.put(code,slot);return row;
            }));}
            for(int i=0;i<jobs.size();i++){String code=pending.get(i).getString("code");try{results.put(code,jobs.get(i).get());}catch(Exception e){Throwable cause=e instanceof ExecutionException?e.getCause():e;JSONObject old=previous.optJSONObject(code);JSONObject row=old!=null&&today.equals(old.optString("trade_date"))?new JSONObject(old.toString()):new JSONObject().put("code",code).put("trade_date",today).put("reductions",new JSONArray());row.put("stale",true).put("error",String.valueOf(cause.getMessage()));results.put(code,row);}}
        }finally{workers.shutdownNow();}
        if(results.length()>0)HoldingRiskNotifier.send(c,HoldingFiveStore.publish(c,results),5);
    }
}
