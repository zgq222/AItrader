package com.aitrader.hammer1430;

import android.content.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.json.*;

/** Portable user data only. Restoring a snapshot also restores removals and unstar operations. */
final class UserDataBackup {
    static final String FORMAT="aitrader-personal-data";
    static JSONObject capture(Context c)throws Exception {
        synchronized(WatchlistStore.class){synchronized(HoldingsStore.class){synchronized(NotesStore.class){
            return new JSONObject().put("format",FORMAT).put("version",1).put("package",c.getPackageName())
                .put("created_at",DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.now()))
                .put("watchlist",new JSONArray(WatchlistStore.payload(c))).put("holdings",new JSONArray(HoldingsStore.payload(c)))
                .put("trade_cycles",TradeJournal.cycles(c)).put("notes",new JSONObject(NotesStore.payload(c)));
        }}}
    }
    static JSONObject read(InputStream in,Context c)throws Exception {
        if(in==null)throw new IOException("无法打开备份文件");ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int size;
        while((size=in.read(buffer))!=-1){if(bytes.size()+size>32*1024*1024)throw new IOException("备份超过32MB，暂无法读取");bytes.write(buffer,0,size);}
        JSONObject value=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));validate(value,c);return value;
    }
    private static void notes(JSONArray entries)throws Exception {Set<String> ids=new HashSet<>();for(int i=0;i<entries.length();i++){JSONObject n=entries.getJSONObject(i);if(n.getString("id").isEmpty()||!ids.add(n.getString("id"))||n.getString("body").trim().isEmpty()||n.getLong("created_at")<0)throw new IOException("笔记格式无效");LocalDate.parse(n.getString("date"),DateTimeFormatter.ofPattern("uuuu/MM/dd").withResolverStyle(java.time.format.ResolverStyle.STRICT));}}
    private static void stocks(JSONArray stocks,boolean held)throws Exception {Set<String> codes=new HashSet<>();for(int i=0;i<stocks.length();i++){JSONObject s=stocks.getJSONObject(i);String code=s.getString("code");if(!code.matches("\\d{6}")||!codes.add(code))throw new IOException("股票代码重复或格式无效");s.getString("name");if(s.has("starred"))s.getBoolean("starred");if(held&&s.has("stop_price")){double stop=s.getDouble("stop_price");if(!Double.isFinite(stop)||stop<=0||stop>1000000||Math.abs(stop*100-Math.rint(stop*100))>1e-6)throw new IOException("止损线格式无效");}}}
    static void validate(JSONObject data,Context c)throws Exception {
        if(!FORMAT.equals(data.getString("format"))||data.getInt("version")!=1||!c.getPackageName().equals(data.getString("package")))throw new IOException("这不是当前应用支持的个人数据备份");
        stocks(data.getJSONArray("watchlist"),false);stocks(data.getJSONArray("holdings"),true);JSONArray cycles=data.getJSONArray("trade_cycles");Map<String,JSONObject> ids=new HashMap<>();
        for(int i=0;i<cycles.length();i++){JSONObject row=cycles.getJSONObject(i);String id=row.getString("id");if(id.isEmpty()||ids.put(id,row)!=null||!row.getString("code").matches("\\d{6}"))throw new IOException("持仓逻辑记录格式无效");row.getBoolean("closed");notes(row.getJSONArray("buy_notes"));notes(row.getJSONArray("sell_notes"));}
        JSONArray holdings=data.getJSONArray("holdings");for(int i=0;i<holdings.length();i++){JSONObject s=holdings.getJSONObject(i);if(s.has("trade_cycle_id")){JSONObject cycle=ids.get(s.getString("trade_cycle_id"));if(cycle==null||cycle.getBoolean("closed")||!s.getString("code").equals(cycle.getString("code")))throw new IOException("持仓与买卖逻辑不匹配");}}
        JSONObject all=data.getJSONObject("notes");Iterator<String> keys=all.keys();while(keys.hasNext()){String key=keys.next();if(!key.matches("stock:\\d{6}|industry:.+|concept:.+"))throw new IOException("笔记对象格式无效");notes(all.getJSONArray(key));}
    }
    static String summary(JSONObject data)throws Exception {int count=0;JSONObject all=data.getJSONObject("notes");Iterator<String> keys=all.keys();while(keys.hasNext())count+=all.getJSONArray(keys.next()).length();JSONArray cycles=data.getJSONArray("trade_cycles");for(int i=0;i<cycles.length();i++){JSONObject r=cycles.getJSONObject(i);count+=r.getJSONArray("buy_notes").length()+r.getJSONArray("sell_notes").length();}return data.getJSONArray("watchlist").length()+"只自选 · "+data.getJSONArray("holdings").length()+"只持仓\n"+count+"条笔记/逻辑 · "+cycles.length()+"段持仓记录";}
    static void restore(Context c,JSONObject incoming)throws Exception {
        // Freeze and validate the whole document before any write.
        JSONObject data=new JSONObject(incoming.toString());validate(data,c);
        synchronized(WatchlistStore.class){synchronized(HoldingsStore.class){synchronized(NotesStore.class){
            SharedPreferences watch=c.getSharedPreferences("watchlist",0),held=c.getSharedPreferences("holdings",0),note=c.getSharedPreferences(NotesStore.PREFS,0);
            Map<String,?> oldWatch=new HashMap<>(watch.getAll()),oldHeld=new HashMap<>(held.getAll()),oldNotes=new HashMap<>(note.getAll());
            try{
                if(!watch.edit().putString("stocks",data.getJSONArray("watchlist").toString()).putBoolean("initial_seed_done",true).commit())throw new IOException("自选恢复保存失败");
                if(!held.edit().putString("stocks",data.getJSONArray("holdings").toString()).putString("trade_cycles",data.getJSONArray("trade_cycles").toString()).commit())throw new IOException("持仓恢复保存失败");
                if(!note.edit().putString("entries",data.getJSONObject("notes").toString()).commit())throw new IOException("笔记恢复保存失败");
            }catch(Exception failure){boolean rolled=rollback(watch,oldWatch)&rollback(held,oldHeld)&rollback(note,oldNotes);throw new IOException(rolled?"恢复失败，原数据已保留，请重试":"恢复失败且原数据回滚未完成，请保留备份文件重试",failure);}
        }}}
    }
    private static boolean rollback(SharedPreferences prefs,Map<String,?> saved){SharedPreferences.Editor e=prefs.edit().clear();for(Map.Entry<String,?> r:saved.entrySet()){Object v=r.getValue();if(v instanceof String)e.putString(r.getKey(),(String)v);else if(v instanceof Boolean)e.putBoolean(r.getKey(),(Boolean)v);else if(v instanceof Integer)e.putInt(r.getKey(),(Integer)v);else if(v instanceof Long)e.putLong(r.getKey(),(Long)v);else if(v instanceof Float)e.putFloat(r.getKey(),(Float)v);}return e.commit();}
}
