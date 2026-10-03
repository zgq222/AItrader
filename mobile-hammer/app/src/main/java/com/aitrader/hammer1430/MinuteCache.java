package com.aitrader.hammer1430;

import android.content.Context;
import org.json.*;
import java.util.*;

final class MinuteCache {
    static void save(Context c,String code,List<DailyBar> bars)throws Exception {
        JSONArray rows=new JSONArray();for(DailyBar b:bars)rows.put(new JSONArray().put(b.date).put(b.open).put(b.high).put(b.low).put(b.close).put(b.volume));
        if(!c.getSharedPreferences("minute15_cache",0).edit().putString(code,rows.toString()).commit())throw new Exception("15分钟缓存保存失败");
    }
    static List<DailyBar> load(Context c,String code)throws Exception {
        List<DailyBar> out=new ArrayList<>();JSONArray rows=new JSONArray(c.getSharedPreferences("minute15_cache",0).getString(code,"[]"));
        for(int i=0;i<rows.length();i++){JSONArray r=rows.getJSONArray(i);out.add(new DailyBar(r.getString(0),r.getDouble(1),r.getDouble(2),r.getDouble(3),r.getDouble(4),r.getDouble(5)));}return out;
    }
}
