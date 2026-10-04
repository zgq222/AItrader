package com.aitrader.hammer1430;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Keep every synced minute bar; intervals and old 15-minute preferences remain isolated. */
final class MinuteCache {
    private static final Object IO_LOCK=new Object();
    private static File file(Context c,String code,int interval)throws Exception {if(!code.matches("\\d{6}")||(interval!=5&&interval!=15))throw new IOException("股票代码或周期无效");File dir=new File(c.getFilesDir(),"minute_history_v2/"+interval);dir.mkdirs();return new File(dir,code+".json");}
    static void save(Context c,String code,List<DailyBar> bars)throws Exception {save(c,code,15,bars);}
    static void save(Context c,String code,int interval,List<DailyBar> bars)throws Exception {synchronized(IO_LOCK){TreeMap<String,DailyBar> merged=new TreeMap<>();for(DailyBar b:load(c,code,interval))merged.put(b.date,b);for(DailyBar b:bars)merged.put(b.date,b);JSONArray rows=new JSONArray();for(DailyBar b:merged.values())rows.put(new JSONArray().put(b.date).put(b.open).put(b.high).put(b.low).put(b.close).put(b.volume));ChartFiles.write(file(c,code,interval),rows.toString().getBytes(StandardCharsets.UTF_8));}}
    static List<DailyBar> load(Context c,String code)throws Exception {return load(c,code,15);}
    static List<DailyBar> load(Context c,String code,int interval)throws Exception {synchronized(IO_LOCK){String raw="[]";File f=file(c,code,interval);if(f.exists())raw=new String(new AtomicFile(f).readFully(),StandardCharsets.UTF_8);else if(interval==15)raw=c.getSharedPreferences("minute15_cache",0).getString(code,"[]");List<DailyBar> out=new ArrayList<>();JSONArray rows=new JSONArray(raw);for(int i=0;i<rows.length();i++){JSONArray r=rows.getJSONArray(i);out.add(new DailyBar(r.getString(0),r.getDouble(1),r.getDouble(2),r.getDouble(3),r.getDouble(4),r.getDouble(5)));}return out;}}
}
