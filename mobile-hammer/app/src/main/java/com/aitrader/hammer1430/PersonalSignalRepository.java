package com.aitrader.hammer1430;

import android.content.*;
import org.json.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Downloads previous-bearish or falling watchlist names and all holdings, independently on the phone. */
final class PersonalSignalRepository {
    private static JSONObject evaluate(Context c,JSONObject item,boolean watched,boolean held,TencentClient.Quote quote,List<String> calendar,long now)throws Exception{
        String latest=calendar.get(calendar.size()-1),code=item.getString("code");
        if(quote==null||!latest.equals(quote.today.date))throw new Exception("最新交易日报价缺失或停牌");
        JSONObject row=new JSONObject().put("code",code).put("name",item.optString("name",code)).put("trade_date",latest).put("stale",false).put("rule_version",PersonalSignalRule.VERSION);
        List<DailyBar> daily=Collections.emptyList();String dailyError="";
        if(watched)try{daily=new DailyHistoryCache(c).load(quote,latest);}catch(Exception e){dailyError="日K未更新："+e.getMessage();}
        PersonalSignalRule.Decision prior=PersonalSignalRule.evaluate(daily,calendar,Collections.emptyList(),now);
        if(watched&&prior.previous==null)dailyError="前一交易日日K缺失，不能判断阴线或下跌";
        else if(watched&&!prior.bearish()&&prior.beforePrevious==null)dailyError="前一交易日的比较收盘缺失，不能判断下跌";
        row.put("previous_date",prior.previousDate).put("previous_known",prior.previous!=null).put("previous_bearish",prior.bearish()).put("previous_falling",prior.falling()).put("previous_eligible",prior.eligible()).put("qualifies",false).put("qualifies_single",false).put("reductions",new JSONArray()).put("minute_fresh",false);
        if(prior.previous!=null)row.put("previous_open",prior.previous.open).put("previous_close",prior.previous.close);
        if(prior.beforePrevious!=null)row.put("comparison_close",prior.beforePrevious.close).put("comparison_date",prior.beforePrevious.date);
        if(watched)row.put("risk_ratio",RiskRatio.calculate(daily,calendar,quote));
        if(!held&&!prior.eligible()){row.put("error",dailyError);return row;}
        List<DailyBar> minutes=TencentClient.minutes(code);MinuteCache.save(c,code,minutes);
        PersonalSignalRule.Decision signal=PersonalSignalRule.evaluate(daily,calendar,minutes,now);
        if(!signal.minuteCurrent)throw new Exception("最新交易日暂无已结束的15分钟行情");
        if(!signal.baselineReady)throw new Exception("最新交易日前不足20个完整交易日或中位量为0");
        String dataTime="";for(DailyBar bar:minutes)if(MinuteBehavior.epoch(bar.date)<=now)dataTime=bar.date;
        row.put("minute_fresh",true).put("data_time",dataTime).put("qualifies",signal.qualifies()).put("qualifies_single",signal.qualifiesSingle()).put("error",dailyError);
        if(signal.qualifiesSingle())row.put("single_attack",PersonalSignalStore.frame(signal.singleAttack,minutes));
        if(signal.qualifies())row.put("first_attack",PersonalSignalStore.frame(signal.firstAttack,minutes)).put("second_attack",PersonalSignalStore.frame(signal.secondAttack,minutes));
        JSONArray reductions=new JSONArray();for(MinuteBehavior.Zone z:signal.reductions)reductions.put(PersonalSignalStore.frame(z,minutes));row.put("reductions",reductions);return row;
    }
    static void load(Context c)throws Exception{
        List<JSONObject> watched=WatchlistStore.stocks(c),held=HoldingsStore.stocks(c);Set<String> watchCodes=new HashSet<>(),heldCodes=new HashSet<>();Map<String,JSONObject> items=new LinkedHashMap<>();
        for(JSONObject s:held){heldCodes.add(s.getString("code"));items.put(s.getString("code"),s);}for(JSONObject s:watched){watchCodes.add(s.getString("code"));items.put(s.getString("code"),s);}
        if(items.isEmpty()){PersonalSignalStore.publish(c,new JSONObject().put("trade_date","").put("stocks",new JSONObject()).put("errors",new JSONArray()).put("checked",0));return;}
        TencentClient.Quote reference=TencentClient.indexQuote();List<DailyBar> ref=TencentClient.indexHistory(reference.today.date);TreeSet<String> dates=new TreeSet<>();for(DailyBar b:ref)if(b.date.compareTo(reference.today.date)<=0)dates.add(b.date);dates.add(reference.today.date);List<String> calendar=new ArrayList<>(dates);if(calendar.size()<2)throw new Exception("无法确定前一交易日");
        String latest=calendar.get(calendar.size()-1);String priorMarketDate=PersonalSignalStore.payload(c).optString("trade_date");if(latest.compareTo(priorMarketDate)<0)throw new Exception("市场交易日早于上次结果，取消本次过期检查");
        Map<String,TencentClient.Quote> quotes=new HashMap<>();for(TencentClient.Quote q:TencentClient.quotes(new ArrayList<>(items.keySet())))quotes.put(q.code,q);
        JSONObject old=PersonalSignalStore.records(c),records=new JSONObject();JSONArray errors=new JSONArray();long now=System.currentTimeMillis();
        ExecutorService workers=Executors.newFixedThreadPool(8);List<Map.Entry<String,JSONObject>> entries=new ArrayList<>(items.entrySet());List<Future<JSONObject>> jobs=new ArrayList<>();
        try{
            for(Map.Entry<String,JSONObject> e:entries)jobs.add(workers.submit(()->evaluate(c,e.getValue(),watchCodes.contains(e.getKey()),heldCodes.contains(e.getKey()),quotes.get(e.getKey()),calendar,now)));
            for(int i=0;i<jobs.size();i++){String code=entries.get(i).getKey();JSONObject row;
                try{row=jobs.get(i).get();if(!row.optString("error").isEmpty())errors.put(code+"："+row.optString("error"));}
                catch(Exception e){Throwable cause=e instanceof ExecutionException?e.getCause():e;String error=String.valueOf(cause.getMessage());errors.put(code+"："+error);
                    JSONObject prior=old.optJSONObject(code);row=prior!=null&&latest.equals(prior.optString("trade_date"))?new JSONObject(prior.toString()):new JSONObject().put("code",code).put("name",entries.get(i).getValue().optString("name",code)).put("trade_date",latest).put("rule_version",PersonalSignalRule.VERSION).put("qualifies",false).put("qualifies_single",false).put("reductions",new JSONArray());
                    row.put("stale",true).put("minute_fresh",false).put("error",error);
                }
                records.put(code,row);
                // Holdings were queued first. Deliver their alerts without waiting for the larger watchlist batch.
                if(!heldCodes.isEmpty()&&i+1==heldCodes.size()){
                    JSONObject partial=new JSONObject(records.toString());for(String pending:items.keySet())if(!partial.has(pending)){JSONObject cached=old.optJSONObject(pending);if(cached!=null&&latest.equals(cached.optString("trade_date")))partial.put(pending,new JSONObject(cached.toString()).put("stale",true).put("minute_fresh",false));}
                    HoldingRiskNotifier.send(c,PersonalSignalStore.publish(c,new JSONObject().put("trade_date",latest).put("previous_date",calendar.get(calendar.size()-2)).put("stocks",partial).put("errors",errors).put("checked",i+1)));
                }
                if(i%16==0||i==jobs.size()-1)PersonalSignalStore.prefs(c).edit().putString("status","正在检查自选与持仓 "+(i+1)+"/"+jobs.size()).apply();
            }
        }finally{workers.shutdownNow();}
        JSONObject payload=new JSONObject().put("trade_date",latest).put("previous_date",calendar.get(calendar.size()-2)).put("stocks",records).put("errors",errors).put("checked",items.size());
        HoldingRiskNotifier.send(c,PersonalSignalStore.publish(c,payload));
    }
}
