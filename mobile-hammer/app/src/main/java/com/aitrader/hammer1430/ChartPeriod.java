package com.aitrader.hammer1430;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Calendar periods share the same unadjusted daily price history. */
final class ChartPeriod {
    static final int DAY=0,FIVE=5,FIFTEEN=15,WEEK=7,MONTH=30;
    static boolean minute(int period){return period==FIVE||period==FIFTEEN;}
    static String label(int period){return period==FIVE?"5分钟K线":period==FIFTEEN?"15分钟K线":period==WEEK?"周线":period==MONTH?"月线":"日K";}
    static String unit(int period){return period==WEEK?"周":period==MONTH?"月":minute(period)?"根":"日";}
    static final class Series {
        final List<DailyBar> bars=new ArrayList<>();
        final Map<String,String> spans=new HashMap<>();
    }
    static Series aggregate(List<DailyBar> daily,int period){
        Series result=new Series();TreeMap<String,DailyBar> ordered=new TreeMap<>();for(DailyBar b:daily)ordered.put(b.date,b);
        if(period!=WEEK&&period!=MONTH){result.bars.addAll(ordered.values());return result;}
        Map<String,List<DailyBar>> groups=new LinkedHashMap<>();
        for(DailyBar b:ordered.values()){LocalDate d=LocalDate.parse(b.date);String key=period==WEEK?d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString():d.withDayOfMonth(1).toString();groups.computeIfAbsent(key,k->new ArrayList<>()).add(b);}
        for(List<DailyBar> group:groups.values()){DailyBar first=group.get(0),last=group.get(group.size()-1);double high=Double.NEGATIVE_INFINITY,low=Double.POSITIVE_INFINITY,volume=0;boolean volumeReady=true;
            for(DailyBar b:group){high=Math.max(high,b.high);low=Math.min(low,b.low);if(Double.isFinite(b.volume))volume+=b.volume;else volumeReady=false;}
            result.bars.add(new DailyBar(last.date,first.open,high,low,last.close,volumeReady?volume:Double.NaN));result.spans.put(last.date,first.date+"—"+last.date);
        }return result;
    }
}
