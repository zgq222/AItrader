package com.aitrader.hammer1430;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.json.*;

/** User's risk / potential-profit ratio; uses the exact market sessions, including today. */
final class RiskRatio {
    static final String FORMULA="盈亏比 =（上车点 − 止损点）/（止盈点 − 上车点）\n上车点：当日最新价，收盘后为收盘价\n止损点：当日与前一交易日日K最低点的较低值\n止盈点：含当日在内最近10个交易日的最高价";
    static String quoteTime(TencentClient.Quote q){return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochSecond(q.updatedAt));}
    static JSONObject unavailable(String reason)throws Exception{return new JSONObject().put("available",false).put("reason",reason);}
    static JSONObject calculate(List<DailyBar> daily,List<String> calendar,TencentClient.Quote quote)throws Exception {
        JSONObject out=unavailable("最近10个交易日日K不足");if(quote==null||calendar.size()<10)return out;
        String latest=calendar.get(calendar.size()-1);out.put("trade_date",latest).put("quote_time",quoteTime(quote));
        if(!latest.equals(quote.today.date))return out.put("reason","当日行情缺失或停牌");
        Map<String,DailyBar> rows=new HashMap<>();for(DailyBar b:daily)rows.put(b.date,b);rows.put(latest,quote.today);
        double high=Double.NEGATIVE_INFINITY;
        for(String date:calendar.subList(calendar.size()-10,calendar.size())){DailyBar b=rows.get(date);if(b==null||!Double.isFinite(b.high)||b.high<=0)return out.put("reason",date+" 日K缺失");high=Math.max(high,b.high);}
        DailyBar previous=rows.get(calendar.get(calendar.size()-2));double price=quote.today.close,stop=Math.min(quote.today.low,previous.low);
        out.put("entry",Double.isFinite(price)?price:JSONObject.NULL).put("stop",Double.isFinite(stop)?stop:JSONObject.NULL).put("target",high).put("previous_date",previous.date);
        LocalTime time=Instant.ofEpochSecond(quote.updatedAt).atZone(ZoneId.of("Asia/Shanghai")).toLocalTime();out.put("phase",time.isBefore(LocalTime.of(15,0))?"盘中最新价":"收盘价");
        if(!Double.isFinite(price)||!Double.isFinite(stop)||price<=0||stop<=0||stop>price)return out.put("reason","上车点或止损参考无效");
        if(high<=price)return out.put("reason","现价已达到或超过10日最高点，无上方止盈空间");
        double ratio=(price-stop)/(high-price);if(!Double.isFinite(ratio))return out.put("reason","盈亏比无法计算");
        return out.put("available",true).put("reason","").put("ratio",ratio);
    }
}
