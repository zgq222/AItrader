package com.aitrader.hammer1430;

import org.json.*;
import java.util.*;

/** Reuses the published rotation ranking; a personal subset never changes a sector's market rank. */
final class IndustryRanking {
    private final Map<String,Integer> ranks=new HashMap<>();
    private final String date;
    private final int total;
    IndustryRanking(JSONObject rotation){
        date=rotation.optString("trade_date");JSONArray rows=rotation.optJSONArray("industries");
        Set<String> names=new HashSet<>();
        if(rows!=null)for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null)continue;String name=row.optString("name");if(name.isEmpty()||name.equals("待分类"))continue;
            names.add(name);int rank=row.optInt("rank");
            if(rank>0&&Double.isFinite(row.optDouble("score",Double.NaN)))ranks.put(name,rank);
        }
        total=names.size();
    }
    int rank(String industry){return ranks.getOrDefault(industry,Integer.MAX_VALUE);}
    String label(String industry){return ranks.containsKey(industry)?"行业排名 "+rank(industry)+"/"+total:"行业暂无排名";}
    String note(){return ranks.isEmpty()?"行业排名暂无数据，请刷新板块轮动":"行业按轮动排名排序"+(date.isEmpty()?"":" · "+date);}
    List<Map.Entry<String,List<JSONObject>>> ordered(Map<String,List<JSONObject>> groups){
        List<Map.Entry<String,List<JSONObject>>> result=new ArrayList<>(groups.entrySet());
        result.sort((a,b)->{
            if(a.getKey().equals("待分类")||b.getKey().equals("待分类"))return Boolean.compare(a.getKey().equals("待分类"),b.getKey().equals("待分类"));
            int order=Integer.compare(rank(a.getKey()),rank(b.getKey()));return order!=0?order:a.getKey().compareTo(b.getKey());
        });return result;
    }
}
