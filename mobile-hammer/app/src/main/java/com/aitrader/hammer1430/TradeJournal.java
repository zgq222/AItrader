package com.aitrader.hammer1430;

import android.content.Context;
import org.json.*;
import java.io.IOException;
import java.util.*;

/** One journal per holding episode. Stocks and episodes commit together in holdings preferences. */
final class TradeJournal {
    static String payload(Context c){return c.getSharedPreferences("holdings",0).getString("trade_cycles","[]");}
    static JSONArray cycles(Context c)throws Exception {return new JSONArray(payload(c));}
    static JSONObject find(JSONArray cycles,String id)throws Exception {for(int i=0;i<cycles.length();i++){JSONObject row=cycles.getJSONObject(i);if(id.equals(row.optString("id")))return row;}return null;}
    static JSONObject ensure(JSONArray cycles,JSONObject stock)throws Exception {
        JSONObject row=find(cycles,stock.optString("trade_cycle_id"));if(row!=null&&!row.optBoolean("closed"))return row;
        row=new JSONObject().put("id",UUID.randomUUID().toString()).put("code",stock.getString("code")).put("name",stock.optString("name")).put("industry",stock.optString("industry","待分类")).put("opened_date",NotesStore.today()).put("opened_at",System.currentTimeMillis()).put("closed",false).put("buy_notes",new JSONArray()).put("sell_notes",new JSONArray());
        stock.put("trade_cycle_id",row.getString("id"));cycles.put(row);return row;
    }
    static void append(JSONObject cycle,String kind,String body)throws Exception {
        if(!kind.equals("buy_notes")&&!kind.equals("sell_notes"))throw new IOException("未知逻辑类型");
        JSONArray rows=cycle.optJSONArray(kind);if(rows==null)rows=new JSONArray();rows.put(NotesStore.entry(body,System.currentTimeMillis()));cycle.put(kind,rows);
    }
    static void optional(JSONObject cycle,String kind,String body)throws Exception {if(body!=null&&!body.trim().isEmpty())append(cycle,kind,body);}
    static List<JSONObject> forStock(Context c,String code)throws Exception {List<JSONObject> out=new ArrayList<>();JSONArray rows=cycles(c);for(int i=rows.length()-1;i>=0;i--){JSONObject row=rows.getJSONObject(i);if(code.equals(row.optString("code")))out.add(row);}return out;}
    static List<JSONObject> reviews(Context c)throws Exception {List<JSONObject> out=new ArrayList<>();JSONArray rows=cycles(c);for(int i=rows.length()-1;i>=0;i--){JSONObject row=rows.getJSONObject(i);if(row.optBoolean("closed")&&row.getJSONArray("buy_notes").length()>0&&row.getJSONArray("sell_notes").length()>0)out.add(row);}return out;}
    static synchronized void addLogic(Context c,String id,String kind,String body)throws Exception {
        synchronized(HoldingsStore.class){JSONArray rows=cycles(c);JSONObject row=find(rows,id);if(row==null)throw new IOException("持仓记录不存在");append(row,kind,body);if(!c.getSharedPreferences("holdings",0).edit().putString("trade_cycles",rows.toString()).commit())throw new IOException("买卖逻辑保存失败，请重试");}
    }
    static synchronized String current(Context c,String code)throws Exception {
        synchronized(HoldingsStore.class){JSONObject stock=HoldingsStore.find(c,code);if(stock==null)throw new IOException("请先加入持仓");JSONArray rows=cycles(c);JSONObject row=ensure(rows,stock);HoldingsStore.saveWithJournal(c,stock,rows);return row.getString("id");}
    }
}
