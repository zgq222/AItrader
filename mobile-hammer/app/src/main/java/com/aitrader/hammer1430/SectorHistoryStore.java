package com.aitrader.hammer1430;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class SectorHistoryStore {
    private static final Object IO_LOCK=new Object();
    private static File file(Context c,String industry,boolean concept)throws Exception {return new File(new File(c.getFilesDir(),concept?"concept_history_v1":"sector_history_v1"),URLEncoder.encode(industry,"UTF-8")+".json");}
    private static Object finite(double x){return Double.isFinite(x)?x:JSONObject.NULL;}
    static void save(Context c,Map<String,List<SectorHistoryRule.Row>> histories)throws Exception {
        save(c,histories,false);
    }
    static void save(Context c,Map<String,List<SectorHistoryRule.Row>> histories,boolean concept)throws Exception {
        for(Map.Entry<String,List<SectorHistoryRule.Row>> e:histories.entrySet()){
            JSONArray rows=new JSONArray();for(SectorHistoryRule.Row r:e.getValue())rows.put(new JSONObject().put("date",r.date).put("relative_5d",finite(r.relative5)).put("relative_20d",finite(r.relative20)).put("outperform_5d_pct",finite(r.outperform)).put("above_ma20_pct",finite(r.above)).put("covered_5d",r.covered5).put("covered_20d",r.covered20).put("ma20_covered",r.maCovered).put("eligible_count",r.eligible).put("benchmark_covered_5d",r.market5).put("benchmark_covered_20d",r.market20).put("benchmark_expected",r.marketExpected));
            JSONObject payload=new JSONObject().put("name",e.getKey()).put("source","腾讯不复权日K · 手机独立计算").put("data",rows).put("latest_date",rows.length()==0?"":rows.getJSONObject(rows.length()-1).getString("date"));
            File target=file(c,e.getKey(),concept);target.getParentFile().mkdirs();AtomicFile atomic=new AtomicFile(target);FileOutputStream out=null;
            synchronized(IO_LOCK){try{out=atomic.startWrite();out.write(payload.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(out);}catch(Exception error){if(out!=null)atomic.failWrite(out);throw error;}}
        }
        c.getSharedPreferences("scan",0).edit().putLong(concept?"concept_history_updated":"sector_history_updated",System.currentTimeMillis()).apply();
    }
    static JSONObject load(Context c,String industry)throws Exception {
        return load(c,industry,false);
    }
    static JSONObject load(Context c,String industry,boolean concept)throws Exception {
        File f=file(c,industry,concept);synchronized(IO_LOCK){if(f.exists())try(InputStream in=new AtomicFile(f).openRead()){return read(in);}catch(Exception ignored){}}
        JSONObject index;try(InputStream in=c.getAssets().open(concept?"concept_kline_index.json":"board_kline_index.json")){index=read(in);}
        String code=index.getJSONObject("boards").optString(industry,"");if(!code.matches("\\d+"))throw new IOException("该行业暂无强度与广度历史");
        try(InputStream in=c.getAssets().open((concept?"concept_histories/":"sector_histories/")+code+".json")){return read(in);}
    }
    private static JSONObject read(InputStream in)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>2_000_000)throw new IOException("行业历史数据过大");}return new JSONObject(out.toString("UTF-8"));}
}
