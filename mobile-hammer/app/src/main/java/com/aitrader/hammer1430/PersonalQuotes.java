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
            for(TencentClient.Quote q:fresh)quotes.put(q.code,new JSONObject().put("latest_price",q.today.close).put("change",(q.today.close/q.previousClose-1)*100).put("quote_time",time.format(new Date(q.updatedAt*1000L))));
            p.edit().putString("favorite_quote_payload",quotes.toString()).putString("favorite_quote_error",fresh.size()==codes.size()?"":"部分股票暂无最新报价").putLong("personal_updated",System.currentTimeMillis()).apply();
            if(!fresh.isEmpty())try{
                String latest="";for(TencentClient.Quote q:fresh)if(q.today.date.compareTo(latest)>0)latest=q.today.date;
                List<String> dates=new ArrayList<>();for(DailyBar b:TencentClient.history("600519",latest))if(b.date.compareTo(latest)<=0)dates.add(b.date);dates=new ArrayList<>(new TreeSet<>(dates));
                if(!dates.contains(latest))dates.add(latest);if(dates.size()<30)throw new Exception("最近30个交易日不足");
                final List<String> calendar=new ArrayList<>(dates.subList(dates.size()-30,dates.size()));final String tradeDate=latest;
                ExecutorService workers=Executors.newFixedThreadPool(8);List<Future<SelectionRule.Gain>> gains=new ArrayList<>();DailyHistoryCache cache=new DailyHistoryCache(c);
                try{for(TencentClient.Quote q:fresh)gains.add(workers.submit(()->q.today.date.equals(tradeDate)?SelectionRule.thirtyDayGain(cache.load(q,tradeDate),calendar):null));
                    for(int i=0;i<fresh.size();i++){TencentClient.Quote q=fresh.get(i);SelectionRule.Gain gain=null;try{gain=gains.get(i).get();}catch(Exception ignored){}
                        quotes.getJSONObject(q.code).put("return_30d_pct",gain==null?JSONObject.NULL:gain.percent).put("return_end",gain==null?JSONObject.NULL:gain.endDate);
                    }
                }finally{workers.shutdownNow();}
                p.edit().putString("favorite_quote_payload",quotes.toString()).apply();
            }catch(Exception e){p.edit().putString("favorite_quote_error","低点至今涨幅未更新："+e.getMessage()).apply();}
        }catch(Exception e){p.edit().putString("favorite_quote_error",String.valueOf(e.getMessage())).apply();}
        finally{BUSY.set(false);p.edit().putBoolean("personal_busy",false).apply();}
    }
}
