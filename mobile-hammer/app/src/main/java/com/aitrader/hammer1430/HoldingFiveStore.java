package com.aitrader.hammer1430;

import android.content.*;
import org.json.*;
import java.util.*;

/** Five-minute alerts have their own seen/ack state; never consume a 15-minute alert. */
final class HoldingFiveStore {
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("holding_five",0);}
    static JSONObject records(Context c){try{return new JSONObject(prefs(c).getString("records","{}"));}catch(Exception e){return new JSONObject();}}
    static JSONArray reductions(Context c,String code){JSONObject r=records(c).optJSONObject(code);JSONArray a=r==null?null:r.optJSONArray("reductions");return a==null?new JSONArray():a;}
    private static String id(String code,JSONObject row,JSONObject frame){return "5|"+code+"|"+row.optString("trade_date")+"|"+frame.optString("start_time");}
    static boolean unread(Context c,String code){JSONObject row=records(c).optJSONObject(code);if(row==null)return false;JSONObject ack;try{ack=new JSONObject(prefs(c).getString("ack","{}"));}catch(Exception e){ack=new JSONObject();}JSONArray frames=reductions(c,code);for(int i=0;i<frames.length();i++)if(!ack.optBoolean(id(code,row,frames.optJSONObject(i))))return true;return false;}
    static synchronized void acknowledge(Context c,String code)throws Exception{JSONObject row=records(c).optJSONObject(code);if(row==null)return;JSONObject ack=new JSONObject(prefs(c).getString("ack","{}"));JSONArray frames=reductions(c,code);for(int i=0;i<frames.length();i++)ack.put(id(code,row,frames.getJSONObject(i)),true);if(!prefs(c).edit().putString("ack",ack.toString()).commit())throw new Exception("提醒已读状态保存失败");}
    static JSONObject evaluate(JSONObject held,List<DailyBar> bars,String date,long now)throws Exception{
        Map<String,Double> baseline=MinuteBehavior.baselines(bars,now,5);if(baseline.getOrDefault(date,0.0)<=0)throw new Exception("今日前不足20个完整交易日，无法判断5分钟减仓");JSONArray frames=new JSONArray();String latest="";
        for(DailyBar b:bars)if(b.date.startsWith(date+" ")&&MinuteBehavior.epoch(b.date)<=now)latest=b.date;
        if(latest.isEmpty())throw new Exception("今日暂无已结束的5分钟K线");
        for(MinuteBehavior.Zone z:MinuteBehavior.find(bars,now,baseline,5))if(!z.contained&&z.kind==MinuteBehavior.REDUCE&&bars.get(z.start).date.startsWith(date+" "))frames.put(PersonalSignalStore.frame(z,bars));
        return new JSONObject().put("code",held.getString("code")).put("name",held.optString("name")).put("trade_date",date).put("data_time",latest).put("stale",false).put("reductions",frames);
    }
    static synchronized List<JSONObject> publish(Context c,JSONObject incoming)throws Exception{
        JSONObject seen=new JSONObject(prefs(c).getString("seen","{}")),all=records(c);List<JSONObject> events=new ArrayList<>();Set<String> held=new HashSet<>();for(JSONObject item:HoldingsStore.stocks(c))held.add(item.getString("code"));Iterator<String> keys=incoming.keys();
        while(keys.hasNext()){String code=keys.next();if(!held.contains(code))continue;JSONObject row=incoming.getJSONObject(code);all.put(code,row);if(row.optBoolean("stale"))continue;JSONArray frames=row.getJSONArray("reductions");for(int i=0;i<frames.length();i++){JSONObject f=frames.getJSONObject(i);String key=id(code,row,f);if(!seen.optBoolean(key)){seen.put(key,true);events.add(new JSONObject().put("code",code).put("name",row.optString("name",code)).put("trade_date",row.getString("trade_date")).put("frame",f));}}}
        if(!prefs(c).edit().putString("records",all.toString()).putString("seen",seen.toString()).putLong("updated",System.currentTimeMillis()).commit())throw new Exception("5分钟提醒保存失败");return events;
    }
}
