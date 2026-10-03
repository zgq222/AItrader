package com.aitrader.hammer1430;

import android.content.Context;
import org.json.*;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Append-only, dated notes, independent of replaceable quotes and stock groups. */
final class NotesStore {
    static final String PREFS="notes_v1";
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneId.of("Asia/Shanghai"));
    static String stock(String code){return "stock:"+code;}
    static String board(String name,boolean concept){return (concept?"concept:":"industry:")+name;}
    static String payload(Context c){return c.getSharedPreferences(PREFS,0).getString("entries","{}");}
    static JSONArray notes(Context c,String key)throws Exception {JSONArray a=new JSONObject(payload(c)).optJSONArray(key);return a==null?new JSONArray():a;}
    static JSONObject entry(String text,long now)throws Exception {
        String body=text==null?"":text.trim();if(body.isEmpty())throw new IOException("请输入笔记正文");
        return new JSONObject().put("id",UUID.randomUUID().toString()).put("date",DATE.format(Instant.ofEpochMilli(now))).put("created_at",now).put("body",body);
    }
    static String line(JSONObject note){return note.optString("date")+"："+note.optString("body");}
    static String today(){return DATE.format(Instant.now());}
    static synchronized void append(Context c,String key,String body)throws Exception {append(c,key,body,System.currentTimeMillis());}
    static synchronized void append(Context c,String key,String body,long now)throws Exception {
        JSONObject all=new JSONObject(payload(c));JSONArray rows=all.optJSONArray(key);if(rows==null)rows=new JSONArray();rows.put(entry(body,now));all.put(key,rows);
        if(!c.getSharedPreferences(PREFS,0).edit().putString("entries",all.toString()).commit())throw new IOException("笔记保存失败，请重试");
    }
}
