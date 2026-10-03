package com.aitrader.hammer1430;

import android.content.Context;
import org.json.*;
import java.io.IOException;
import java.util.*;

/** Local holdings are independent of favorites and replaceable market snapshots. */
final class HoldingsStore {
    static String payload(Context c){return c.getSharedPreferences("holdings",0).getString("stocks","[]");}
    static List<JSONObject> stocks(Context c)throws Exception {
        JSONArray a=new JSONArray(payload(c));List<JSONObject> out=new ArrayList<>();
        for(int i=0;i<a.length();i++)out.add(a.getJSONObject(i));return out;
    }
    static JSONObject find(Context c,String code)throws Exception {for(JSONObject s:stocks(c))if(code.equals(s.optString("code")))return s;return null;}
    static synchronized void add(Context c,String code,String name,String industry)throws Exception {
        add(c,code,name,industry,"");
    }
    static synchronized void add(Context c,String code,String name,String industry,String buyLogic)throws Exception {
        if(!code.matches("(000|001|002|003|300|301|600|601|603|605|688|689)\\d{3}"))throw new IOException("请输入有效的六位沪深代码");
        JSONObject item=find(c,code);if(item==null)item=new JSONObject().put("code",code);
        item.put("name",name==null||name.isEmpty()?code:name).put("industry",industry==null?"待分类":industry);
        JSONArray cycles=TradeJournal.cycles(c);JSONObject cycle=TradeJournal.ensure(cycles,item);TradeJournal.optional(cycle,"buy_notes",buyLogic);saveWithJournal(c,item,cycles);
    }
    static synchronized void stop(Context c,String code,Double price)throws Exception {
        JSONObject s=find(c,code);if(s==null)throw new IOException("请先加入持仓");
        if(price!=null&&(!Double.isFinite(price)||price<=0||price>1000000||Math.abs(price*100-Math.rint(price*100))>1e-6))throw new IOException("请输入正数止损价，最多两位小数");
        if(price==null)s.remove("stop_price");else s.put("stop_price",price);saveItem(c,s);
    }
    private static void saveItem(Context c,JSONObject item)throws Exception {
        saveWithJournal(c,item,null);
    }
    static void saveWithJournal(Context c,JSONObject item,JSONArray cycles)throws Exception {
        JSONArray out=new JSONArray();boolean found=false;
        for(JSONObject s:stocks(c)){if(s.optString("code").equals(item.optString("code"))){if(!found)out.put(item);found=true;}else out.put(s);}
        if(!found)out.put(item);write(c,out,cycles);
    }
    static synchronized void remove(Context c,String code)throws Exception {remove(c,code,"");}
    static synchronized void remove(Context c,String code,String sellLogic)throws Exception {
        JSONArray out=new JSONArray(),cycles=TradeJournal.cycles(c);JSONObject held=find(c,code);
        if(held!=null){JSONObject cycle=TradeJournal.ensure(cycles,held);TradeJournal.optional(cycle,"sell_notes",sellLogic);cycle.put("closed",true).put("closed_date",NotesStore.today()).put("closed_at",System.currentTimeMillis());}
        for(JSONObject s:stocks(c))if(!code.equals(s.optString("code")))out.put(s);write(c,out,cycles);
    }
    private static void write(Context c,JSONArray a,JSONArray cycles)throws IOException {android.content.SharedPreferences.Editor editor=c.getSharedPreferences("holdings",0).edit().putString("stocks",a.toString());if(cycles!=null)editor.putString("trade_cycles",cycles.toString());if(!editor.commit())throw new IOException("持仓保存失败，请重试");}
    static String stopText(JSONObject s,JSONObject quote){
        double stop=s.optDouble("stop_price",Double.NaN),last=quote==null?Double.NaN:quote.optDouble("latest_price",Double.NaN);
        if(!Double.isFinite(stop))return "止损线未设置";
        return String.format(Locale.CHINA,"止损线 ¥%.2f",stop)+(Double.isFinite(last)?last<=stop?" · 最新报价已触及":" · 距止损 "+String.format(Locale.CHINA,"%.2f%%",(last/stop-1)*100):" · 行情待刷新");
    }
}
