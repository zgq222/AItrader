package com.aitrader.hammer1430;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** THS year inventory determines the full available board history; never assume a 240-bar cap. */
final class BoardHistoryClient {
    private static File file(Context c,String code,boolean concept){return new File(c.getFilesDir(),"board_history_v2/"+(concept?"concept/":"industry/")+code+".json");}
    static JSONObject cached(Context c,String code,boolean concept)throws Exception {
        File f=file(c,code,concept);if(f.exists())return new JSONObject(new String(new AtomicFile(f).readFully(),StandardCharsets.UTF_8));
        try(InputStream in=c.getAssets().open((concept?"concept_klines/":"board_klines/")+code+".json")){return new JSONObject(new String(read(in),StandardCharsets.UTF_8));}
    }
    private static byte[] read(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>12_000_000)throw new IOException("板块历史文件过大");out.write(buf,0,n);}return out.toByteArray();}
    private static JSONObject get(String code,String part)throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("https://d.10jqka.com.cn/v6/line/bk_"+code+"/01/"+part+".js").openConnection();connection.setConnectTimeout(6000);connection.setReadTimeout(12000);connection.setRequestProperty("User-Agent","Mozilla/5.0");connection.setRequestProperty("Referer","https://q.10jqka.com.cn/");
        try{if(connection.getResponseCode()!=200)throw new IOException("板块"+part+"行情 HTTP "+connection.getResponseCode());String text;try(InputStream in=connection.getInputStream()){text=new String(read(in),Charset.forName("GB18030"));}int first=text.indexOf('{'),last=text.lastIndexOf('}');if(first<0||last<first)throw new IOException("板块"+part+"行情格式异常");return new JSONObject(text.substring(first,last+1));}finally{connection.disconnect();}
    }
    static JSONArray parse(JSONObject payload)throws Exception {JSONArray rows=new JSONArray();for(String item:payload.getString("data").split(";")){if(item.isEmpty())continue;String[] v=item.split(",");if(v.length<7||!v[0].matches("\\d{8}"))throw new IOException("板块日K记录格式异常");String day=LocalDate.parse(v[0],java.time.format.DateTimeFormatter.BASIC_ISO_DATE).toString();JSONArray r=new JSONArray().put(day);for(int i=1;i<=6;i++){double d=Double.parseDouble(v[i]);if(!Double.isFinite(d))throw new IOException("板块日K价格无效");r.put(d);}if(r.getDouble(3)<=0||r.getDouble(2)<Math.max(r.getDouble(1),r.getDouble(4))||r.getDouble(3)>Math.min(r.getDouble(1),r.getDouble(4))||r.getDouble(5)<0)throw new IOException("板块日K范围无效");rows.put(r);}return rows;}
    private static void merge(TreeMap<String,JSONArray> out,JSONArray rows)throws Exception {for(int i=0;i<rows.length();i++){JSONArray r=rows.getJSONArray(i);LocalDate.parse(r.getString(0));out.put(r.getString(0),r);}}
    static JSONObject sync(Context c,String code,boolean concept,TencentClient.Progress progress)throws Exception {
        if(!code.matches("88\\d{4}"))throw new IOException("板块代码无效");JSONObject meta=get(code,"last"),years=meta.getJSONObject("year");TreeMap<String,JSONArray> rows=new TreeMap<>();try{merge(rows,cached(c,code,concept).getJSONArray("data"));}catch(Exception ignored){}JSONArray recent=parse(meta);merge(rows,recent);
        Map<String,Integer> covered=new HashMap<>();for(String date:rows.keySet())covered.merge(date.substring(0,4),1,Integer::sum);List<String> missing=new ArrayList<>();Iterator<String> keys=years.keys();while(keys.hasNext()){String year=keys.next();if(!year.matches("\\d{4}"))throw new IOException("板块年份目录无效");if(covered.getOrDefault(year,0)<years.getInt(year))missing.add(year);}Collections.sort(missing);
        ExecutorService workers=Executors.newFixedThreadPool(4);List<Future<JSONArray>> tasks=new ArrayList<>();try{for(String year:missing)tasks.add(workers.submit(()->parse(get(code,year))));for(int i=0;i<tasks.size();i++){if(Thread.currentThread().isInterrupted())throw new InterruptedException("同步已中断");JSONArray history=tasks.get(i).get();String year=missing.get(i);if(history.length()<years.getInt(year))throw new IOException(year+"年板块数据未完整返回");merge(rows,history);if(progress!=null)progress.update("同步板块全历史 · "+(i+1)+"/"+missing.size()+"年 · "+rows.size()+"根");}}finally{workers.shutdownNow();}
        merge(rows,recent);if(rows.isEmpty())throw new IOException("板块日K为空");JSONArray data=new JSONArray();for(JSONArray row:rows.values())data.put(row);JSONObject result=new JSONObject().put("name",meta.optString("name")).put("source","同花顺"+(concept?"概念":"行业")+" · 全历史同步").put("complete",true).put("latest_date",rows.lastKey()).put("data",data);
        File target=file(c,code,concept);target.getParentFile().mkdirs();ChartFiles.write(target,result.toString().getBytes(StandardCharsets.UTF_8));return result;
    }
}
