package com.aitrader.hammer1430;

import org.json.*;
import java.time.LocalDate;
import java.util.*;

/** The desktop market-position and flight-height rules, calculated on the phone. */
final class MarketRules {
    private static double rounded(double value){return Math.rint(value*1000)/1000;}
    private static List<DailyBar> ordered(List<DailyBar> bars){
        TreeMap<String,DailyBar> unique=new TreeMap<>();
        for(DailyBar bar:bars)try{LocalDate.parse(bar.date);unique.put(bar.date,bar);}catch(RuntimeException ignored){}
        return new ArrayList<>(unique.values());
    }
    private static String invalid(List<DailyBar> rows,int count){
        if(rows.size()<count)return "需要"+count+"个交易日日线（现有"+rows.size()+"日）";
        for(int i=rows.size()-count;i<rows.size();i++){
            DailyBar b=rows.get(i);
            if(!Double.isFinite(b.high)||!Double.isFinite(b.low)||!Double.isFinite(b.close)||b.low<=0||b.close<=0||b.high<=0)
                return "最近"+count+"个交易日存在无效点位";
            if(b.high<b.low||b.close<b.low||b.close>b.high)return "最近"+count+"个交易日日线价格范围异常";
        }
        return null;
    }
    static JSONObject assess(List<DailyBar> bars)throws JSONException{
        List<DailyBar> rows=ordered(bars);
        JSONObject result=new JSONObject().put("available",false).put("name","上证指数").put("code","000001")
                .put("source","腾讯上证指数行情 · 手机本地计算").put("lookback",30);
        String reason=invalid(rows,30);
        if(reason!=null)result.put("reason",reason);
        else{
            List<DailyBar> recent=rows.subList(rows.size()-30,rows.size());
            DailyBar last=recent.get(29),low=recent.get(0),high=low;
            for(DailyBar b:recent){if(b.low<low.low)low=b;if(b.high>high.high)high=b;}
            double lower=low.low+(high.high-low.low)*.1,upper=high.high-(high.high-low.low)*.1;
            result.put("date",last.date).put("start_date",recent.get(0).date).put("close",last.close)
                    .put("low",low.low).put("high",high.high).put("low_date",low.date).put("high_date",high.date)
                    .put("lower_threshold",lower).put("upper_threshold",upper);
            if(high.high==low.low)result.put("reason","最高点与最低点相同，无法划分区间");
            else{
                String band=last.close<lower?"low":last.close<upper?"middle":"high";
                result.put("available",true).put("band",band).put("band_label",band.equals("low")?"低位":band.equals("middle")?"中位":"高位")
                        .put("position_label",band.equals("low")?"满仓":band.equals("middle")?"2/3仓":"1/3仓")
                        .put("range_percent",(last.close-low.low)/(high.high-low.low)*100);
            }
        }
        return result.put("flight_height",flight(rows));
    }
    private static JSONObject flight(List<DailyBar> rows)throws JSONException{
        JSONObject result=new JSONObject().put("available",false);
        String reason=invalid(rows,90);if(reason!=null)return result.put("reason",reason);
        DailyBar last=rows.get(rows.size()-1);JSONArray references=new JSONArray();
        for(int window:new int[]{10,30,90}){
            List<DailyBar> recent=rows.subList(rows.size()-window,rows.size());DailyBar high=recent.get(0),low=high;
            for(DailyBar b:recent){if(b.high>high.high)high=b;if(b.low<low.low)low=b;}
            references.put(new JSONObject().put("label",window+"日最高点").put("price",rounded(high.high)).put("date",high.date));
            references.put(new JSONObject().put("label",window+"日最低点").put("price",rounded(low.low)).put("date",low.date));
        }
        for(int window:new int[]{5,10,20,30}){
            double sum=0;for(int i=rows.size()-window;i<rows.size();i++)sum+=rows.get(i).close;
            references.put(new JSONObject().put("label","MA"+window).put("price",rounded(sum/window)).put("date",last.date));
        }
        TreeMap<Double,JSONObject> unique=new TreeMap<>();
        for(int i=0;i<references.length();i++){
            JSONObject ref=references.getJSONObject(i);double price=ref.getDouble("price");
            if(!unique.containsKey(price))unique.put(price,new JSONObject().put("price",price).put("sources",new JSONArray()));
            unique.get(price).getJSONArray("sources").put(ref);
        }
        List<JSONObject> levels=new ArrayList<>(unique.values());double close=rounded(last.close);int split=0;
        while(split<levels.size()&&levels.get(split).getDouble("price")<=close)split++;
        result.put("available",true).put("close",close).put("date",last.date).put("references",references)
                .put("levels",new JSONArray(levels)).put("level_count",levels.size()).put("reference_count",10)
                .put("first",interval(levels,split-1,split)).put("second",interval(levels,split-2,split+1));
        if(split>0&&levels.get(split-1).getDouble("price")==close)result.put("touching",levels.get(split-1));
        return result;
    }
    private static JSONObject interval(List<JSONObject> levels,int support,int resistance)throws JSONException{
        return new JSONObject().put("support",support>=0&&support<levels.size()?levels.get(support):JSONObject.NULL)
                .put("resistance",resistance>=0&&resistance<levels.size()?levels.get(resistance):JSONObject.NULL)
                .put("complete",support>=0&&resistance<levels.size());
    }
}
