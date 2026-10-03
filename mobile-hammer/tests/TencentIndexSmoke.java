package com.aitrader.hammer1430;

import java.util.*;
import org.json.JSONObject;

/** Opt-in live API check; does not scan or write any user data. */
public final class TencentIndexSmoke {
    public static void main(String[] args)throws Exception{
        TencentClient.Quote quote=TencentClient.indexQuote();
        if(!quote.name.contains("上证")||quote.today.close<1000)throw new AssertionError("sh000001 must be the index, not the sz000001 stock");
        List<DailyBar> history=TencentClient.indexHistory(quote.today.date);
        TreeMap<String,DailyBar> rows=new TreeMap<>();for(DailyBar b:history)rows.put(b.date,b);rows.put(quote.today.date,quote.today);
        JSONObject market=MarketRules.assess(new ArrayList<>(rows.values()));
        if(!market.getBoolean("available")||!market.getJSONObject("flight_height").getBoolean("available"))throw new AssertionError("live index cannot calculate modules");
        System.out.println("Live Tencent index verified: "+quote.today.date+" / "+quote.today.close+" / "+history.size()+" daily bars / "+market.getString("band")+" / ten reference levels");
    }
}
