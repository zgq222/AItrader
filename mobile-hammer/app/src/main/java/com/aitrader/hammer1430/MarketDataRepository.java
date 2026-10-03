package com.aitrader.hammer1430;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A separate, small index request: never waits for the whole stock scan. */
final class MarketDataRepository {
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
    static void refresh(Context context){
        if(!RUNNING.compareAndSet(false,true))return;
        Context app=context.getApplicationContext();SharedPreferences prefs=app.getSharedPreferences("scan",Context.MODE_PRIVATE);
        prefs.edit().putBoolean("market_busy",true).putString("market_error","").apply();
        new Thread(()->{
            try{
                TencentClient.Quote quote=TencentClient.indexQuote();String latest=quote.today.date;
                List<DailyBar> history=new ArrayList<>();
                if(latest.equals(prefs.getString("market_history_date","")))try{
                    JSONArray saved=new JSONArray(prefs.getString("market_history","[]"));
                    for(int i=0;i<saved.length();i++){JSONArray row=saved.getJSONArray(i);
                        history.add(new DailyBar(row.getString(0),row.getDouble(1),row.getDouble(2),row.getDouble(3),row.getDouble(4),0));}
                }catch(JSONException ignored){history.clear();}
                if(history.size()<90)history=TencentClient.indexHistory(latest);
                TreeMap<String,DailyBar> unique=new TreeMap<>();
                for(DailyBar b:history)if(b.date.compareTo(latest)<=0)unique.put(b.date,b);
                // Quote fields can be zero before the opening auction; keep the prior daily close in that case.
                if(quote.today.low>0&&quote.today.high>=quote.today.low&&quote.today.close>=quote.today.low&&quote.today.close<=quote.today.high)
                    unique.put(latest,quote.today);
                else if(!unique.containsKey(latest))throw new Exception("当日指数OHLC尚未就绪，请稍后刷新");
                List<DailyBar> bars=new ArrayList<>(unique.values());JSONObject result=MarketRules.assess(bars);
                if(!result.optBoolean("available"))throw new Exception(result.optString("reason","指数日线不可用"));
                SimpleDateFormat time=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA);time.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
                result.put("quote_time",time.format(new Date(quote.updatedAt*1000L))).put("updated_at",time.format(new Date()))
                        .put("final_close",time.format(new Date(quote.updatedAt*1000L)).substring(11,16).compareTo("15:00")>=0);
                JSONArray saved=new JSONArray();for(DailyBar b:bars)saved.put(new JSONArray().put(b.date).put(b.open).put(b.high).put(b.low).put(b.close));
                prefs.edit().putString("market_payload",result.toString()).putString("market_history",saved.toString())
                        .putString("market_history_date",latest).putString("market_error","").apply();
            }catch(Exception error){prefs.edit().putString("market_error","刷新失败："+error.getMessage()+"；保留上次成功数据").apply();}
            finally{RUNNING.set(false);prefs.edit().putBoolean("market_busy",false).apply();}
        },"market-overview-refresh").start();
    }
}
