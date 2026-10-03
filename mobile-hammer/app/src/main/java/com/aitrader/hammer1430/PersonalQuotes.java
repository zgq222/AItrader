package com.aitrader.hammer1430;

import android.content.*;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Personal quote updates stay fast even while industry histories are syncing. */
final class PersonalQuotes {
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    static void refresh(Context context){Context c=context.getApplicationContext();new Thread(()->load(c),"personal-quotes").start();}
    static void load(Context c){if(!BUSY.compareAndSet(false,true))return;
        SharedPreferences p=c.getSharedPreferences("scan",0);p.edit().putBoolean("personal_busy",true).apply();
        try {
            Set<String> set=new LinkedHashSet<>();for(JSONObject s:WatchlistStore.stocks(c))set.add(s.getString("code"));for(JSONObject s:HoldingsStore.stocks(c))set.add(s.getString("code"));
            List<String> codes=new ArrayList<>(set);List<TencentClient.Quote> fresh=TencentClient.quotes(codes);
            JSONObject quotes=new JSONObject(p.getString("favorite_quote_payload","{}"));SimpleDateFormat time=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA);time.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            for(TencentClient.Quote q:fresh)quotes.put(q.code,new JSONObject().put("latest_price",q.today.close).put("change",(q.today.close/q.previousClose-1)*100).put("quote_time",time.format(new Date(q.updatedAt*1000L))).put("risk_ratio",RiskRatio.unavailable("正在补齐日K参考点")));
            p.edit().putString("favorite_quote_payload",quotes.toString()).putString("favorite_quote_error",fresh.size()==codes.size()?"":"部分股票暂无最新报价").putLong("personal_updated",System.currentTimeMillis()).apply();
            if(!fresh.isEmpty())try{
                String latest=TencentClient.indexQuote().today.date;
                List<String> dates=new ArrayList<>();for(DailyBar b:TencentClient.indexHistory(latest))if(b.date.compareTo(latest)<=0)dates.add(b.date);dates=new ArrayList<>(new TreeSet<>(dates));
                if(!dates.contains(latest))dates.add(latest);
                final List<String> calendar=new ArrayList<>(dates.subList(Math.max(0,dates.size()-30),dates.size()));final String tradeDate=latest;
                ExecutorService workers=Executors.newFixedThreadPool(8);List<Future<JSONObject>> gains=new ArrayList<>();DailyHistoryCache cache=new DailyHistoryCache(c);
                try{for(TencentClient.Quote q:fresh)gains.add(workers.submit(()->{
                    if(!q.today.date.equals(tradeDate))return new JSONObject().put("risk_ratio",RiskRatio.unavailable("当日行情缺失或停牌"));
                    List<DailyBar> rows=cache.load(q,tradeDate);SelectionRule.Gain gain=calendar.size()>=30?SelectionRule.thirtyDayGain(rows,calendar):null;
                    return new JSONObject().put("return_30d_pct",gain==null?JSONObject.NULL:gain.percent).put("return_end",gain==null?JSONObject.NULL:gain.endDate).put("risk_ratio",RiskRatio.calculate(rows,calendar,q));
                }));
                    for(int i=0;i<fresh.size();i++){TencentClient.Quote q=fresh.get(i);JSONObject result;try{result=gains.get(i).get();}catch(Exception e){result=new JSONObject().put("risk_ratio",RiskRatio.unavailable("日K未更新，无法计算"));}
                        JSONObject saved=quotes.getJSONObject(q.code);saved.put("return_30d_pct",result.opt("return_30d_pct")).put("return_end",result.opt("return_end")).put("risk_ratio",result.getJSONObject("risk_ratio"));
                    }
                }finally{workers.shutdownNow();}
                p.edit().putString("favorite_quote_payload",quotes.toString()).apply();
            }catch(Exception e){for(TencentClient.Quote q:fresh)quotes.getJSONObject(q.code).put("risk_ratio",RiskRatio.unavailable("日K参考点未更新："+e.getMessage()));p.edit().putString("favorite_quote_payload",quotes.toString()).putString("favorite_quote_error","低点至今涨幅及盈亏比未更新："+e.getMessage()).apply();}
        }catch(Exception e){p.edit().putString("favorite_quote_error",String.valueOf(e.getMessage())).apply();}
        finally{BUSY.set(false);p.edit().putBoolean("personal_busy",false).apply();}
    }
}
