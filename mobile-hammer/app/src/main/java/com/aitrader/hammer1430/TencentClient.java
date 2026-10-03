package com.aitrader.hammer1430;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

final class TencentClient {
    static final class Quote {
        final String code,name;
        final DailyBar today;
        final double previousClose;
        final long updatedAt;
        Quote(String code,String name,DailyBar today,double previousClose,long updatedAt){
            this.code=code;this.name=name;this.today=today;this.previousClose=previousClose;this.updatedAt=updatedAt;
        }
    }
    static final class Snapshot {
        final List<Quote> quotes;
        final int total;
        Snapshot(List<Quote> quotes,int total){this.quotes=quotes;this.total=total;}
    }
    private static byte[] get(String uri)throws Exception {
        Exception last=null;
        for(int attempt=0;attempt<3;attempt++){
            HttpURLConnection connection=null;
            try {
                connection=(HttpURLConnection)new URL(uri).openConnection();
                connection.setConnectTimeout(8000);connection.setReadTimeout(12000);
                connection.setRequestProperty("User-Agent","Mozilla/5.0");
                connection.setRequestProperty("Accept","*/*");
                int status=connection.getResponseCode();if(status!=200)throw new IOException("HTTP "+status);
                try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[8192];int read;
                    while((read=input.read(buffer))!=-1)output.write(buffer,0,read);
                    return output.toByteArray();
                }
            }catch(Exception error){last=error;Thread.sleep(500L*(attempt+1));}
            finally{if(connection!=null)connection.disconnect();}
        }
        throw new Exception("腾讯行情连接失败："+last.getMessage(),last);
    }
    private static double value(String[] fields,int index){try{return Double.parseDouble(fields[index]);}catch(Exception e){return Double.NaN;}}
    private static long timestamp(String raw){
        try {SimpleDateFormat f=new SimpleDateFormat("yyyyMMddHHmmss",Locale.CHINA);
            f.setLenient(false);f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));return f.parse(raw).getTime()/1000L;}
        catch(Exception ignored){return 0;}
    }
    private static String date(long epoch){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.CHINA);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));return f.format(new Date(epoch*1000));}
    private static List<String> codes(Context context)throws Exception {
        List<String> result=new ArrayList<>();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(context.getAssets().open("mainboard_codes.txt"),StandardCharsets.UTF_8))){
            String line;while((line=reader.readLine())!=null)if(line.matches("(000|001|002|003|600|601|603|605)\\d{3}"))result.add(line);
        }
        if(result.size()<2000)throw new Exception("内置主板代码清单不完整");return result;
    }
    private static String symbol(String code){return (code.startsWith("6")?"sh":"sz")+code;}
    private static List<Quote> quoteBatch(List<String> codes)throws Exception {
        return quoteBatch(codes,false);
    }
    private static List<Quote> quoteBatch(List<String> codes,boolean individual)throws Exception {
        return quoteBatch(codes,individual,false);
    }
    private static List<Quote> quoteBatch(List<String> codes,boolean individual,boolean index)throws Exception {
        StringBuilder uri=new StringBuilder("https://qt.gtimg.cn/q=");
        for(String code:codes){if(uri.charAt(uri.length()-1)!='=')uri.append(',');uri.append(index?"sh"+code:symbol(code));}
        String body=new String(get(uri.toString()),Charset.forName("GBK"));
        List<Quote> result=new ArrayList<>();
        for(String record:body.split(";")){
            int start=record.indexOf('"'),end=record.lastIndexOf('"');if(start<0||end<=start)continue;
            String[] fields=record.substring(start+1,end).split("~",-1);if(fields.length<35)continue;
            String code=fields[2],name=fields[1];
            if(individual){
                if(!codes.contains(code))continue;
            }else if(!code.matches("(000|001|002|003|600|601|603|605)\\d{3}"))continue;
            double close=value(fields,3),previous=value(fields,4),open=value(fields,5),high=value(fields,33),low=value(fields,34),volume=value(fields,6);
            long updated=timestamp(fields[30]);
            if(!(Double.isFinite(close)&&Double.isFinite(previous)&&Double.isFinite(open)&&Double.isFinite(high)&&Double.isFinite(low))
                    ||close<=0||previous<=0||(!index&&(open<=0||high<=0||low<=0))||updated<=0)continue;
            result.add(new Quote(code,name,new DailyBar(date(updated),open,high,low,close,volume),previous,updated));
        }
        return result;
    }
    static Quote quote(String code)throws Exception {
        if(!code.matches("\\d{6}"))throw new IOException("股票代码无效");
        List<Quote> quotes=quoteBatch(Collections.singletonList(code),true);
        if(quotes.isEmpty())throw new IOException(code+" 当前报价不可用");
        return quotes.get(0);
    }
    static Quote indexQuote()throws Exception {
        List<Quote> quotes=quoteBatch(Collections.singletonList("000001"),true,true);
        if(quotes.isEmpty())throw new IOException("上证指数当前报价不可用");
        return quotes.get(0);
    }
    static List<DailyBar> indexHistory(String throughDate)throws Exception {
        return historySymbol("sh000001",throughDate);
    }
    static List<Quote> quotes(List<String> codes)throws Exception {
        List<Quote> result=new ArrayList<>();
        for(int i=0;i<codes.size();i+=100)result.addAll(quoteBatch(codes.subList(i,Math.min(i+100,codes.size())),true));
        return result;
    }
    static Snapshot snapshot(Context context)throws Exception {
        List<String> universe=codes(context);ExecutorService workers=Executors.newFixedThreadPool(8);
        List<Future<List<Quote>>> pending=new ArrayList<>();List<Quote> result=new ArrayList<>();
        try {
            for(int i=0;i<universe.size();i+=100){List<String> batch=new ArrayList<>(universe.subList(i,Math.min(i+100,universe.size())));
                pending.add(workers.submit(()->quoteBatch(batch)));}
            for(Future<List<Quote>> item:pending)result.addAll(item.get());
        }finally{workers.shutdownNow();}
        if(result.size()<universe.size()*0.85)throw new Exception("报价数量异常：仅取得 "+result.size()+"/"+universe.size()+" 只，已取消筛选");
        return new Snapshot(result,universe.size());
    }
    static List<DailyBar> history(String code,String throughDate)throws Exception {
        return historySymbol(symbol(code),throughDate);
    }
    static List<DailyBar> minutes(String code)throws Exception {
        if(!code.matches("\\d{6}"))throw new IOException("股票代码无效");
        return parseMinutes(new String(get("https://ifzq.gtimg.cn/appstock/app/kline/mkline?param="+symbol(code)+",m15,,640"),StandardCharsets.UTF_8),symbol(code));
    }
    static List<DailyBar> parseMinutes(String raw,String key)throws Exception {
        JSONObject root=new JSONObject(raw),data=root.optJSONObject("data"),stock=data==null?null:data.optJSONObject(key);
        JSONArray rows=stock==null?null:stock.optJSONArray("m15");if(root.optInt("code",-1)!=0||rows==null)throw new IOException("15分钟K线数据不可用");
        TreeMap<String,DailyBar> sorted=new TreeMap<>();
        for(int i=0;i<rows.length();i++){JSONArray r=rows.optJSONArray(i);if(r==null||r.length()<6)continue;try{
            String stamp=r.getString(0);if(!stamp.matches("\\d{12}"))continue;
            String time=stamp.substring(0,4)+"-"+stamp.substring(4,6)+"-"+stamp.substring(6,8)+" "+stamp.substring(8,10)+":"+stamp.substring(10);
            long epoch=MinuteBehavior.epoch(time);if(epoch==Long.MAX_VALUE)continue;
            java.time.LocalTime t=java.time.LocalDateTime.parse(time,MinuteBehavior.FORMAT).toLocalTime();
            if(!MinuteBehavior.isBarTime(t))continue;
            double o=r.getDouble(1),c=r.getDouble(2),h=r.getDouble(3),l=r.getDouble(4),v=r.getDouble(5);
            if(!Double.isFinite(o)||!Double.isFinite(c)||!Double.isFinite(h)||!Double.isFinite(l)||!Double.isFinite(v)||l<=0||v<0||h<Math.max(o,c)||l>Math.min(o,c))continue;
            sorted.put(time,new DailyBar(time,o,h,l,c,v));
        }catch(Exception ignored){}}
        if(sorted.isEmpty())throw new IOException("15分钟K线为空或格式无效");return new ArrayList<>(sorted.values());
    }
    private static List<DailyBar> historySymbol(String key,String throughDate)throws Exception {
        String uri="https://web.ifzq.gtimg.cn/appstock/app/kline/kline?param="+key+",day,,,120";
        JSONObject root=new JSONObject(new String(get(uri),StandardCharsets.UTF_8));
        JSONObject data=root.optJSONObject("data");JSONObject stock=data==null?null:data.optJSONObject(key);
        JSONArray rows=stock==null?null:stock.optJSONArray("day");
        if(rows==null)throw new Exception("日K数据为空："+key);
        List<DailyBar> result=new ArrayList<>();
        for(int i=0;i<rows.length();i++){
            JSONArray row=rows.optJSONArray(i);if(row==null||row.length()<5)continue;
            String day=row.optString(0,"");if(day.compareTo(throughDate)>0)continue;
            try{result.add(new DailyBar(day,Double.parseDouble(row.getString(1)),Double.parseDouble(row.getString(3)),
                    Double.parseDouble(row.getString(4)),Double.parseDouble(row.getString(2)),
                    row.length()>5?Double.parseDouble(row.getString(5)):Double.NaN));}
            catch(Exception ignored){}
        }
        return result;
    }
}
