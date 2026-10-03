package com.aitrader.hammer1430;

import android.app.Application;
import android.content.Context;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class SectorHistoryTest {
    @Test public void webHistoryCrosscheck()throws Exception {
        JSONObject fixture;try(InputStream in=getClass().getResourceAsStream("/sector_history_reference.json")){fixture=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        List<String> dates=new ArrayList<>();JSONArray calendar=fixture.getJSONArray("dates");for(int i=0;i<calendar.length();i++)dates.add(calendar.getString(i));
        List<SectorRotationRule.Stock> stocks=new ArrayList<>();JSONArray inputs=fixture.getJSONArray("stocks");
        for(int i=0;i<inputs.length();i++){JSONObject stock=inputs.getJSONObject(i);JSONArray raw=stock.getJSONArray("bars");List<DailyBar> bars=new ArrayList<>();for(int j=0;j<raw.length();j++){JSONArray r=raw.getJSONArray(j);bars.add(new DailyBar(r.getString(0),r.optDouble(1),r.optDouble(2),r.optDouble(3),r.optDouble(4),Double.NaN));}stocks.add(new SectorRotationRule.Stock(stock.getString("code"),stock.getString("name"),stock.getString("industry"),bars));}
        JSONObject expected=fixture.getJSONObject("histories");Set<String> names=new TreeSet<>();expected.keys().forEachRemaining(names::add);Map<String,List<SectorHistoryRule.Row>> result=SectorHistoryRule.calculate(stocks,dates,names);int checks=0;
        for(String name:names){JSONArray rows=expected.getJSONArray(name);List<SectorHistoryRule.Row> actual=result.get(name);assertEquals(dates.size(),actual.size());
            for(int i=20;i<dates.size();i++){JSONObject e=rows.getJSONObject(i);SectorHistoryRule.Row r=actual.get(i);assertEquals(name+" date",e.getString("date"),r.date);compare(e,"relative_5d",r.relative5);compare(e,"relative_20d",r.relative20);compare(e,"outperform_5d_pct",r.outperform);compare(e,"above_ma20_pct",r.above);assertEquals(e.getInt("covered_5d"),r.covered5);assertEquals(e.getInt("covered_20d"),r.covered20);assertEquals(e.getInt("ma20_covered"),r.maCovered);assertEquals(e.getInt("benchmark_covered_5d"),r.market5);assertEquals(e.getInt("benchmark_covered_20d"),r.market20);checks+=10;}
        }assertTrue(checks>=9000);System.out.println("Web historical values checked: "+checks);
    }
    private void compare(JSONObject e,String key,double actual)throws Exception {if(e.isNull(key))assertFalse(Double.isFinite(actual));else assertEquals(key,e.getDouble(key),actual,1e-8);}
    @Test public void missingWindowsAndUnknownIndustryBenchmark()throws Exception {
        List<String> dates=new ArrayList<>();List<DailyBar> a=new ArrayList<>(),b=new ArrayList<>();for(int i=0;i<25;i++){String day=java.time.LocalDate.of(2026,1,1).plusDays(i).toString();dates.add(day);a.add(new DailyBar(day,10,11,9,10+i,100));b.add(new DailyBar(day,10,11,9,10,100));}
        List<SectorRotationRule.Stock> stocks=Arrays.asList(new SectorRotationRule.Stock("600001","甲","测试",a),new SectorRotationRule.Stock("000001","乙","待分类",b),new SectorRotationRule.Stock("600002","ST丙","测试",a));
        Map<String,List<SectorHistoryRule.Row>> result=SectorHistoryRule.calculate(stocks,dates,Arrays.asList("测试","无成员"));SectorHistoryRule.Row r=result.get("测试").get(24);assertEquals(1,r.eligible);assertEquals(2,r.market5);assertEquals(2,r.marketExpected);assertEquals(((34.0/29-1)*100)/2,r.relative5,1e-8);assertEquals(100,r.outperform,0);assertEquals(100,r.above,0);
        a.remove(19);r=SectorHistoryRule.calculate(stocks,dates,Arrays.asList("测试","无成员")).get("测试").get(24);assertFalse(Double.isFinite(r.relative5));assertFalse(Double.isFinite(r.relative20));assertFalse(Double.isFinite(r.above));assertFalse(Double.isFinite(result.get("无成员").get(24).above));
    }
    @Test public void atomicStoreKeepsFieldsAndSource()throws Exception {
        Context c=RuntimeEnvironment.getApplication();List<SectorHistoryRule.Row> rows=Collections.singletonList(new SectorHistoryRule.Row("2026-09-30",Double.NaN,1.2,60,55,0,5,6,10,0,20,30));SectorHistoryStore.save(c,Collections.singletonMap("房地产",rows));JSONObject stored=SectorHistoryStore.load(c,"房地产");assertTrue(stored.getString("source").contains("手机独立计算"));assertTrue(stored.getJSONArray("data").getJSONObject(0).isNull("relative_5d"));assertEquals(1.2,stored.getJSONArray("data").getJSONObject(0).getDouble("relative_20d"),0);assertTrue(c.getSharedPreferences("scan",0).getLong("sector_history_updated",0)>0);
        JSONObject offline=SectorHistoryStore.load(c,"白酒");assertTrue(offline.getString("source").contains("内置快照"));assertEquals(240,offline.getJSONArray("data").length());
    }
}
