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
public class ConceptTest {
    @Before public void reset(){RuntimeEnvironment.getApplication().getSharedPreferences("scan",0).edit().clear().commit();}
    @Test public void allConceptRankingsAndHistoryMatchWeb()throws Exception {
        JSONObject fixture;try(InputStream in=getClass().getResourceAsStream("/concept_reference.json")){fixture=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        List<String> dates=new ArrayList<>();JSONArray ds=fixture.getJSONArray("dates");for(int i=0;i<ds.length();i++)dates.add(ds.getString(i));
        List<SectorRotationRule.Stock> stocks=new ArrayList<>();JSONArray inputs=fixture.getJSONArray("stocks");for(int i=0;i<inputs.length();i++){JSONObject s=inputs.getJSONObject(i);List<DailyBar> bars=new ArrayList<>();JSONArray raw=s.getJSONArray("bars");for(int j=0;j<raw.length();j++){JSONArray r=raw.getJSONArray(j);bars.add(new DailyBar(r.getString(0),r.optDouble(1),r.optDouble(2),r.optDouble(3),r.optDouble(4),Double.NaN));}stocks.add(new SectorRotationRule.Stock(s.getString("code"),s.getString("name"),"",bars));}
        ConceptCatalog catalog=new ConceptCatalog(RuntimeEnvironment.getApplication());assertEquals(293,catalog.names().size());
        List<SectorRotationRule.Sector> ranked=SectorRotationRule.calculate(stocks,dates,catalog.names(),catalog.memberships);
        JSONArray expected=fixture.getJSONArray("ranked");assertEquals(expected.length(),ranked.size());
        Map<String,List<SectorHistoryRule.Row>> histories=SectorHistoryRule.calculate(stocks,dates,catalog.names(),catalog.memberships);
        for(int i=0;i<expected.length();i++){JSONObject e=expected.getJSONObject(i);SectorRotationRule.Sector a=ranked.get(i);assertEquals(e.getString("name"),a.name);assertEquals(e.optInt("rank",0),a.rank);compare(e,"score",a.score);compare(e,"relative_5d",a.relative5);compare(e,"relative_20d",a.relative20);compare(e,"outperform_5d_pct",a.outperform);compare(e,"above_ma20_pct",a.above);
            JSONArray rows=fixture.getJSONObject("histories").getJSONArray(a.name);for(int j=20;j<rows.length();j++){JSONObject r=rows.getJSONObject(j);SectorHistoryRule.Row h=histories.get(a.name).get(j);compare(r,"relative_5d",h.relative5);compare(r,"relative_20d",h.relative20);compare(r,"above_ma20_pct",h.above);compare(r,"outperform_5d_pct",h.outperform);assertEquals(r.getInt("benchmark_expected"),h.marketExpected);assertEquals(r.getInt("eligible_count"),h.eligible);}
        }
    }
    private void compare(JSONObject row,String field,double actual)throws Exception {if(row.isNull(field))assertFalse(field,Double.isFinite(actual));else assertEquals(field,row.getDouble(field),actual,1e-8);}
    @Test public void overlappingMembershipsNeverDuplicateMarketAndMissingValuesStayLast()throws Exception {
        List<String> dates=new ArrayList<>();List<DailyBar> rising=new ArrayList<>(),flat=new ArrayList<>();for(int i=0;i<30;i++){String day=java.time.LocalDate.of(2026,1,1).plusDays(i).toString();dates.add(day);rising.add(new DailyBar(day,10,50,9,10+i,100));flat.add(new DailyBar(day,10,11,9,10,100));}
        List<SectorRotationRule.Stock> inputs=Arrays.asList(new SectorRotationRule.Stock("600001","甲","",rising),new SectorRotationRule.Stock("000001","乙","",flat),new SectorRotationRule.Stock("600002","ST丙","",rising));Map<String,List<String>> memberships=new HashMap<>();memberships.put("600001",Arrays.asList("A","B","A"));memberships.put("600002",Arrays.asList("A","B"));
        List<SectorRotationRule.Sector> ranked=SectorRotationRule.calculate(inputs,dates,Arrays.asList("A","B","Empty"),memberships);assertEquals(1,ranked.get(0).stocks.size());assertEquals(1,ranked.get(1).stocks.size());assertEquals(0,ranked.get(2).rank);assertEquals(ranked.get(0).score,ranked.get(1).score,0);
        SectorHistoryRule.Row row=SectorHistoryRule.calculate(inputs,dates,Arrays.asList("A","B"),memberships).get("A").get(29);assertEquals(2,row.marketExpected);assertEquals(2,row.market5);assertEquals(1,row.eligible);assertEquals(((39.0/34-1)*100)/2,row.relative5,1e-9);
    }
    @Test public void datedRankFallbackSortedTagsAndSeparateConceptHistory()throws Exception {
        Context c=RuntimeEnvironment.getApplication();ConceptCatalog catalog=new ConceptCatalog(c);List<JSONObject> tags=catalog.tags("000513");assertTrue(tags.size()>1);int previous=0;for(JSONObject tag:tags){int rank=tag.optInt("rank",0);if(rank>0){assertTrue(rank>=previous);previous=rank;}assertTrue(catalog.tagText(tag).endsWith("/293"));}
        assertEquals("2026-09-30",catalog.tradeDate());JSONObject offline=SectorHistoryStore.load(c,"重组蛋白",true);assertEquals(240,offline.getJSONArray("data").length());assertTrue(offline.getString("source").contains("概念"));
        List<SectorHistoryRule.Row> rows=Collections.singletonList(new SectorHistoryRule.Row("2026-09-30",2,3,60,70,5,5,5,6,10,10,12));SectorHistoryStore.save(c,Collections.singletonMap("足球概念",rows),true);assertEquals(2,SectorHistoryStore.load(c,"足球概念",true).getJSONArray("data").getJSONObject(0).getDouble("relative_5d"),0);assertTrue(c.getSharedPreferences("scan",0).getLong("concept_history_updated",0)>0);
        JSONArray original=ConceptCatalog.read(c,"concept_rankings.json").getJSONArray("concepts");JSONObject changed=new JSONObject().put("trade_date","2026-10-09").put("concepts",original);for(int i=0;i<original.length();i++){JSONObject r=original.getJSONObject(i);if("重组蛋白".equals(r.getString("name")))r.put("rank",293);if("足球概念".equals(r.getString("name")))r.put("rank",1);}
        c.getSharedPreferences("scan",0).edit().putString("concept_payload",changed.toString()).commit();catalog.update(c);assertEquals("2026-10-09",catalog.tradeDate());assertEquals("足球概念 1/293",catalog.tagText(catalog.rank("足球概念")));
        c.getSharedPreferences("scan",0).edit().putString("concept_payload","broken").commit();catalog.update(c);assertEquals("2026-10-09",catalog.tradeDate());
    }
}
