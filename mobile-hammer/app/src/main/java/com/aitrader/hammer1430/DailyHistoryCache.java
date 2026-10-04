package com.aitrader.hammer1430;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** One raw daily-history download per code/session, shared by all three screens. */
final class DailyHistoryCache {
    private final File directory;
    private static final java.util.concurrent.ConcurrentHashMap<String,Object> LOCKS=new java.util.concurrent.ConcurrentHashMap<>();
    private static Object lock(String code){return LOCKS.computeIfAbsent(code,k->new Object());}
    DailyHistoryCache(Context context){directory=new File(context.getFilesDir(),"raw_daily_v1");directory.mkdirs();}
    List<DailyBar> cached(String code)throws Exception{
        if(!code.matches("\\d{6}"))throw new IOException("股票代码无效");
        File file=new File(directory,code+".json");
        if(!file.exists())throw new FileNotFoundException(code+" 尚无日K缓存");
        JSONObject saved=new JSONObject(read(file));
        JSONArray values=saved.getJSONArray("bars");
        TreeMap<String,DailyBar> byDate=new TreeMap<>();
        for(int i=0;i<values.length();i++){
            JSONArray row=values.getJSONArray(i);
            String date=row.getString(0);
            double open=row.getDouble(1),high=row.getDouble(2),low=row.getDouble(3),close=row.getDouble(4);
            if(date.matches("\\d{4}-\\d{2}-\\d{2}")&&open>0&&high>0&&low>0&&close>0)
                byDate.put(date,new DailyBar(date,open,high,low,close,row.optDouble(5,Double.NaN)));
        }
        if(byDate.isEmpty())throw new IOException(code+" 日K缓存为空");
        return new ArrayList<>(byDate.values());
    }
    List<DailyBar> load(TencentClient.Quote quote,String latest)throws Exception{
        return load(quote,latest,120);
    }
    List<DailyBar> load(TencentClient.Quote quote,String latest,int historyCount)throws Exception{synchronized(lock(quote.code)){return loadLocked(quote,latest,historyCount);}}
    private List<DailyBar> loadLocked(TencentClient.Quote quote,String latest,int historyCount)throws Exception{
        File file=new File(directory,quote.code+".json");List<DailyBar> bars=null;int cachedHistoryCount=120;
        if(file.exists())try{if(new JSONObject(read(file)).optBoolean("full_history"))return loadFull(quote.code,latest,quote,null);}catch(org.json.JSONException ignored){}
        if(file.exists())try{
            JSONObject saved=new JSONObject(read(file));
            cachedHistoryCount=saved.optInt("history_count",120);
            if(latest.equals(saved.optString("through"))&&(saved.optInt("history_count",120)>=historyCount||saved.getJSONArray("bars").length()>=historyCount)){
                bars=new ArrayList<>();JSONArray values=saved.getJSONArray("bars");
                for(int i=0;i<values.length();i++){
                    JSONArray row=values.getJSONArray(i);
                    bars.add(new DailyBar(row.getString(0),row.getDouble(1),row.getDouble(2),row.getDouble(3),row.getDouble(4),row.optDouble(5,Double.NaN)));
                }
            }
        }catch(Exception ignored){bars=null;}
        if(bars==null){
            // Keep an expanded stock history when another screen refreshes it next session.
            historyCount=Math.max(historyCount,cachedHistoryCount);
            bars=historyCount==120?TencentClient.history(quote.code,latest):TencentClient.history(quote.code,latest,historyCount);
            if(bars.isEmpty())throw new IOException(quote.code+" 日K为空");
            JSONArray values=new JSONArray();
            for(DailyBar bar:bars){JSONArray row=new JSONArray();
                row.put(bar.date).put(bar.open).put(bar.high).put(bar.low).put(bar.close);
                row.put(Double.isFinite(bar.volume)?bar.volume:JSONObject.NULL);values.put(row);
            }
            JSONObject saved=new JSONObject();saved.put("through",latest).put("history_count",historyCount).put("bars",values);
            File temp=File.createTempFile(quote.code+"-",".tmp",directory);
            try(FileOutputStream out=new FileOutputStream(temp)){out.write(saved.toString().getBytes(StandardCharsets.UTF_8));}
            try{java.nio.file.Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
            catch(java.nio.file.AtomicMoveNotSupportedException e){java.nio.file.Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
            finally{temp.delete();}
        }
        // Replace today's cached row with the latest quote, never append duplicates.
        TreeMap<String,DailyBar> byDate=new TreeMap<>();
        for(DailyBar bar:bars)if(bar.date.compareTo(latest)<=0)byDate.put(bar.date,bar);
        if(latest.equals(quote.today.date))byDate.put(latest,quote.today);
        return new ArrayList<>(byDate.values());
    }
    boolean complete(String code){try{return new JSONObject(read(new File(directory,code+".json"))).optBoolean("full_history");}catch(Exception e){return false;}}
    List<DailyBar> loadFull(String code,String latest,TencentClient.Quote quote,TencentClient.Progress progress)throws Exception{synchronized(lock(code)){return fullLocked(code,latest,quote,progress);}}
    private List<DailyBar> fullLocked(String code,String latest,TencentClient.Quote quote,TencentClient.Progress progress)throws Exception{
        if(!code.matches("\\d{6}"))throw new IOException("股票代码无效");
        File file=new File(directory,code+".json");JSONObject saved=null;List<DailyBar> previous=null;
        if(file.exists())try{saved=new JSONObject(read(file));if(saved.optBoolean("full_history"))previous=cached(code);}catch(Exception ignored){}
        TreeMap<String,DailyBar> all=new TreeMap<>();boolean write=false;
        if(previous!=null){for(DailyBar b:previous)all.put(b.date,b);String through=saved.getString("through");if(!latest.equals(through)){
            long gap=java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(through),java.time.LocalDate.parse(latest));
            if(gap>90)all.clear();else {List<DailyBar> recent=TencentClient.history(code,latest);if(recent.isEmpty())throw new IOException("日K增量更新为空");for(DailyBar b:recent)if(b.date.compareTo(latest)<=0)all.put(b.date,b);write=true;}
        }}
        if(all.isEmpty()){for(DailyBar b:TencentClient.fullHistory(code,latest,progress))all.put(b.date,b);write=true;}
        if(quote!=null&&latest.equals(quote.today.date))all.put(latest,quote.today);
        if(write){JSONArray values=new JSONArray();for(DailyBar b:all.values())values.put(new JSONArray().put(b.date).put(b.open).put(b.high).put(b.low).put(b.close).put(Double.isFinite(b.volume)?b.volume:JSONObject.NULL));JSONObject payload=new JSONObject().put("through",latest).put("full_history",true).put("history_count",all.size()).put("bars",values);
            ChartFiles.write(file,payload.toString().getBytes(StandardCharsets.UTF_8));
        }return new ArrayList<>(all.values());
    }
    private String read(File file)throws IOException{
        try(FileInputStream input=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int read;
            while((read=input.read(buffer))!=-1)output.write(buffer,0,read);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
