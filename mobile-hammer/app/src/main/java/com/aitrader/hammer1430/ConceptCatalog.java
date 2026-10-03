package com.aitrader.hammer1430;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Dated THS memberships; rankings are independently recalculated by the phone. */
final class ConceptCatalog {
    final JSONObject boards,memberDates;
    final Map<String,List<String>> memberships=new HashMap<>();
    private JSONObject ranking;
    private String lastPayload;
    private final Map<String,JSONObject> ranks=new HashMap<>();
    ConceptCatalog(Context c)throws Exception {
        JSONObject catalog=read(c,"concept_catalog.json");boards=catalog.getJSONObject("boards");memberDates=catalog.getJSONObject("member_dates");
        JSONObject stocks=catalog.getJSONObject("stocks");Iterator<String> codes=stocks.keys();
        while(codes.hasNext()){String code=codes.next();JSONArray a=stocks.getJSONArray(code);List<String> names=new ArrayList<>();for(int i=0;i<a.length();i++)names.add(a.getString(i));memberships.put(code,names);}
        ranking=read(c,"concept_rankings.json");indexRanks();
    }
    static JSONObject read(Context c,String asset)throws Exception {try(InputStream in=c.getAssets().open(asset);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));}}
    Collection<String> names(){List<String> result=new ArrayList<>();boards.keys().forEachRemaining(result::add);return result;}
    void update(Context c){String payload=c.getSharedPreferences("scan",0).getString("concept_payload","");if(payload.equals(lastPayload))return;lastPayload=payload;if(!payload.isEmpty())try{JSONObject fresh=new JSONObject(payload);if(fresh.getJSONArray("concepts").length()==boards.length()){ranking=fresh;indexRanks();}}catch(JSONException ignored){}}
    private void indexRanks(){ranks.clear();JSONArray a=ranking.optJSONArray("concepts");if(a!=null)for(int i=0;i<a.length();i++){JSONObject r=a.optJSONObject(i);if(r!=null)ranks.put(r.optString("name"),r);}}
    List<JSONObject> tags(String code){List<JSONObject> result=new ArrayList<>();for(String name:new LinkedHashSet<>(memberships.getOrDefault(code,Collections.emptyList()))){JSONObject row=ranks.get(name);if(row!=null)result.add(row);}
        result.sort((a,b)->{int ra=a.optInt("rank",0),rb=b.optInt("rank",0);int cmp=Integer.compare(ra>0?ra:Integer.MAX_VALUE,rb>0?rb:Integer.MAX_VALUE);return cmp!=0?cmp:a.optString("name").compareTo(b.optString("name"));});return result;}
    String tagText(JSONObject row){int rank=row.optInt("rank",0);return row.optString("name")+" "+(rank>0?rank:"—")+"/"+boards.length();}
    JSONObject rank(String name){return ranks.get(name);}
    String tradeDate(){return ranking.optString("trade_date","—");}
    static JSONObject calculate(Context c,List<SectorRotationRule.Stock> stocks,List<String> recent,List<String> historyDates)throws Exception {
        ConceptCatalog catalog=new ConceptCatalog(c);
        List<SectorRotationRule.Stock> copy=new ArrayList<>();for(SectorRotationRule.Stock s:stocks)copy.add(new SectorRotationRule.Stock(s.code,s.name,s.industry,s.bars));
        List<SectorRotationRule.Sector> sectors=SectorRotationRule.calculate(copy,recent,catalog.names(),catalog.memberships);
        SectorHistoryStore.save(c,SectorHistoryRule.calculate(copy,historyDates,catalog.names(),catalog.memberships),true);
        JSONArray rows=new JSONArray();for(SectorRotationRule.Sector s:sectors)rows.put(new JSONObject().put("name",s.name).put("rank",s.rank>0?s.rank:JSONObject.NULL).put("score",finite(s.score)).put("strength_score",finite(s.strength)).put("breadth_score",finite(s.breadth)).put("relative_5d",finite(s.relative5)).put("relative_20d",finite(s.relative20)).put("outperform_5d_pct",finite(s.outperform)).put("above_ma20_pct",finite(s.above)).put("member_count",s.stocks.size()));
        return new JSONObject().put("concepts",rows).put("trade_date",recent.get(recent.size()-1)).put("source","腾讯不复权日K · 手机独立计算");
    }
    private static Object finite(double v){return Double.isFinite(v)?v:JSONObject.NULL;}
}
