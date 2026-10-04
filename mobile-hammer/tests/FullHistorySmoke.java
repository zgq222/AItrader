package com.aitrader.hammer1430;

import java.util.*;
import java.lang.reflect.Method;
import org.json.*;

/** Opt-in primary-feed check; only downloads a few objects and writes no user data. */
public final class FullHistorySmoke {
    public static void main(String[] args)throws Exception {
        List<DailyBar> daily=TencentClient.fullHistory("600519","2026-09-30",System.out::println);
        if(!daily.get(0).date.equals("2001-08-27")||daily.size()<6000)throw new AssertionError("Listing history was truncated");
        System.out.println("Full stock daily verified: "+daily.size()+" / "+daily.get(0).date+"—"+daily.get(daily.size()-1).date);
        List<DailyBar> five=TencentClient.minutes("002383",5);
        if(five.size()<960||MinuteBehavior.find(five,MinuteBehavior.epoch("2026-09-30 15:00"),5).isEmpty())throw new AssertionError("5-minute baseline unavailable");
        System.out.println("Live 5-minute verified: "+five.size()+" bars / "+MinuteBehavior.find(five,MinuteBehavior.epoch("2026-09-30 15:00"),5).size()+" behavior frames");
        Method get=BoardHistoryClient.class.getDeclaredMethod("get",String.class,String.class);get.setAccessible(true);
        for(String code:new String[]{"881156","885343"}){JSONObject meta=(JSONObject)get.invoke(null,code,"last");JSONObject years=meta.getJSONObject("year");String first=Collections.min(years.keySet());JSONArray earliest=BoardHistoryClient.parse((JSONObject)get.invoke(null,code,first));if(earliest.length()!=years.getInt(first))throw new AssertionError("Board first-year coverage mismatch");BoardHistoryClient.parse(meta);System.out.println("Board feed verified: "+code+" / "+first+" / "+earliest.length()+" bars / "+meta.get("total")+" total");}
    }
}
