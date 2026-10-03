package com.aitrader.hammer1430;

import android.content.*;
import org.json.*;
import java.util.*;

final class PersonalSignalStore {
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("personal_signals",0);}
    static JSONObject payload(Context c){try{return new JSONObject(prefs(c).getString("payload","{}"));}catch(Exception e){return new JSONObject();}}
    static JSONObject records(Context c){JSONObject records=payload(c).optJSONObject("stocks");return records==null?new JSONObject():records;}
    static JSONArray reductions(Context c,String code){JSONObject row=records(c).optJSONObject(code);JSONArray a=row==null?null:row.optJSONArray("reductions");return a==null?new JSONArray():a;}
    static String id(String code,String day,JSONObject frame){return code+"|"+day+"|"+frame.optString("start_time");}
    static boolean unread(Context c,String code){
        JSONObject row=records(c).optJSONObject(code);if(row==null)return false;String day=row.optString("trade_date");
        JSONObject ack;try{ack=new JSONObject(prefs(c).getString("ack","{}"));}catch(Exception e){ack=new JSONObject();}
        JSONArray frames=reductions(c,code);for(int i=0;i<frames.length();i++)if(!ack.optBoolean(id(code,day,frames.optJSONObject(i))))return true;return false;
    }
    static int unreadHoldings(Context c){int count=0;try{for(JSONObject s:HoldingsStore.stocks(c))if(unread(c,s.optString("code")))count++;}catch(Exception ignored){}return count;}
    static synchronized void acknowledge(Context c,String code)throws Exception{
        JSONObject ack=new JSONObject(prefs(c).getString("ack","{}")),row=records(c).optJSONObject(code);if(row==null)return;
        JSONArray frames=reductions(c,code);for(int i=0;i<frames.length();i++)ack.put(id(code,row.optString("trade_date"),frames.getJSONObject(i)),true);
        if(!prefs(c).edit().putString("ack",ack.toString()).commit())throw new Exception("提醒已读状态保存失败");
    }
    static JSONObject frame(MinuteBehavior.Zone z,List<DailyBar> bars)throws Exception{
        return new JSONObject().put("start_time",bars.get(z.start).date).put("end_time",bars.get(z.end).date)
                .put("ratio",z.ratio).put("move",z.move).put("high",z.high).put("low",z.low);
    }
    // Commit both snapshot and deduplication before notifying. Repeated scans or growing frames never re-alert.
    static synchronized List<JSONObject> publish(Context c,JSONObject payload)throws Exception{
        JSONObject seen=new JSONObject(prefs(c).getString("seen","{}"));List<JSONObject> fresh=new ArrayList<>();
        Set<String> held=new HashSet<>();for(JSONObject s:HoldingsStore.stocks(c))held.add(s.optString("code"));
        JSONObject rows=payload.getJSONObject("stocks");Iterator<String> codes=rows.keys();
        while(codes.hasNext()){String code=codes.next();JSONObject row=rows.getJSONObject(code);if(!held.contains(code)||!row.optBoolean("minute_fresh"))continue;
            JSONArray frames=row.optJSONArray("reductions");if(frames==null)continue;
            for(int i=0;i<frames.length();i++){JSONObject frame=frames.getJSONObject(i);String id=id(code,row.getString("trade_date"),frame);
                if(!seen.optBoolean(id)){seen.put(id,true);fresh.add(new JSONObject().put("code",code).put("name",row.optString("name",code)).put("trade_date",row.getString("trade_date")).put("frame",frame));}}
        }
        if(!prefs(c).edit().putString("payload",payload.toString()).putString("seen",seen.toString()).putLong("updated",System.currentTimeMillis()).commit())throw new Exception("信号结果保存失败");
        return fresh;
    }
}
