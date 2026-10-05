package com.aitrader.hammer1430;

import android.content.*;
import org.json.*;
import java.time.*;
import java.util.*;
import java.math.*;

/** Fast holdings quotes and speech state are isolated from the slower all-watchlist refresh. */
final class HoldingLive {
    static final long QUOTE_MS=3000,SPEECH_MS=5000,STALE_MS=45_000;
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("holding_live",0);}
    static boolean window(long wall){ZonedDateTime now=Instant.ofEpochMilli(wall).atZone(MinuteBehavior.ZONE);LocalTime t=now.toLocalTime();return now.getDayOfWeek()!=DayOfWeek.SATURDAY&&now.getDayOfWeek()!=DayOfWeek.SUNDAY&&!t.isBefore(LocalTime.of(9,0))&&t.isBefore(LocalTime.of(15,0));}
    static boolean closing(long wall){LocalTime t=Instant.ofEpochMilli(wall).atZone(MinuteBehavior.ZONE).toLocalTime();return !t.isBefore(LocalTime.of(15,0))&&t.isBefore(LocalTime.of(15,1));}
    static String today(long wall){return Instant.ofEpochMilli(wall).atZone(MinuteBehavior.ZONE).toLocalDate().toString();}
    static String slot(long wall){LocalDateTime d=Instant.ofEpochMilli(wall).atZone(MinuteBehavior.ZONE).toLocalDateTime().withSecond(0).withNano(0);d=d.withMinute(d.getMinute()/5*5);return MinuteBehavior.isBarTime(d.toLocalTime(),5)?d.format(MinuteBehavior.FORMAT):null;}
    static JSONObject quotes(Context c){try{return new JSONObject(prefs(c).getString("quotes","{}"));}catch(Exception e){return new JSONObject();}}
    static JSONObject quote(Context c,String code,JSONObject fallback){JSONObject live=quotes(c).optJSONObject(code);if(live==null)return fallback;if(fallback!=null&&fallback.optString("quote_time").compareTo(live.optString("quote_time"))>0)return fallback;return live;}
    static JSONObject display(Context c,String code,JSONObject fallback){JSONObject live=quote(c,code,fallback);if(live==null||live==fallback)return live;try{JSONObject merged=new JSONObject(fallback==null?"{}":fallback.toString());Iterator<String> keys=live.keys();while(keys.hasNext()){String key=keys.next();merged.put(key,live.get(key));}
        if(fallback!=null&&fallback.optString("return_end").equals(live.optString("quote_time").substring(0,10))&&Double.isFinite(fallback.optDouble("return_30d_pct"))&&fallback.optDouble("latest_price")>0){double low=fallback.getDouble("latest_price")/(1+fallback.getDouble("return_30d_pct")/100);double dayLow=live.optDouble("day_low",Double.NaN);if(dayLow>0)low=Math.min(low,dayLow);merged.put("return_30d_pct",(live.getDouble("latest_price")/low-1)*100);}else merged.remove("return_30d_pct");return merged;
    }catch(Exception e){return live;}}
    static boolean fresh(JSONObject q,long now){return q!=null&&Double.isFinite(q.optDouble("change"))&&q.optLong("quote_at")<=now+5000&&now-q.optLong("quote_at")<=STALE_MS&&q.optString("quote_time").startsWith(today(now));}
    static String spoken(double change){if(!Double.isFinite(change))return null;BigDecimal rounded=BigDecimal.valueOf(change).setScale(2,RoundingMode.HALF_UP).stripTrailingZeros();return (rounded.signum()<0?"负":"")+rounded.abs().toPlainString();}
    static boolean duration(int minutes){return minutes==30||minutes==60||minutes==120;}
    static synchronized void publish(Context c,List<TencentClient.Quote> incoming,long now)throws Exception{
        Set<String> held=new HashSet<>();for(JSONObject s:HoldingsStore.stocks(c))held.add(s.getString("code"));JSONObject result=quotes(c);
        for(TencentClient.Quote q:incoming){if(!held.contains(q.code)||!Double.isFinite(q.previousClose)||q.previousClose<=0)continue;JSONObject old=result.optJSONObject(q.code);if(old!=null&&old.optLong("quote_at")>q.updatedAt*1000)continue;
            String time=Instant.ofEpochSecond(q.updatedAt).atZone(MinuteBehavior.ZONE).format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"));result.put(q.code,new JSONObject().put("latest_price",q.today.close).put("change",(q.today.close/q.previousClose-1)*100).put("day_low",q.today.low).put("quote_time",time).put("quote_at",q.updatedAt*1000));
        }
        List<String> remove=new ArrayList<>();Iterator<String> keys=result.keys();while(keys.hasNext()){String code=keys.next();if(!held.contains(code))remove.add(code);}for(String code:remove)result.remove(code);
        prefs(c).edit().putString("quotes",result.toString()).putLong("updated",now).putString("quote_error",incoming.size()<held.size()?"部分持仓暂无报价，请核对行情时间":"").apply();
    }
}
