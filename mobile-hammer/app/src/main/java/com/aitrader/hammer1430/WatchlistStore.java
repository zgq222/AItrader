package com.aitrader.hammer1430;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.LinkedHashMap;

/** User-owned favorites, separate from replaceable scan results. */
final class WatchlistStore {
    private static final String PREFS="watchlist",KEY="stocks";
    interface Backend { String read(); boolean write(String value); }
    static final class Repository {
        private final Backend backend;
        Repository(Backend backend){this.backend=backend;}
        List<JSONObject> stocks()throws Exception{
            JSONArray saved=new JSONArray(backend.read());List<JSONObject> result=new ArrayList<>();
            for(int i=0;i<saved.length();i++)result.add(saved.getJSONObject(i));
            return result;
        }
        boolean contains(String code)throws Exception{
            for(JSONObject item:stocks())if(code.equals(item.optString("code")))return true;
            return false;
        }
        void setStarred(String code,boolean starred)throws Exception{
            JSONArray result=new JSONArray();boolean found=false;
            for(JSONObject item:stocks()){
                if(code.equals(item.optString("code"))){item.put("starred",starred);found=true;}
                result.put(item);
            }
            if(!found)throw new IOException("请先加入自选股");
            save(result);
        }
        void add(String code,String name,String industry)throws Exception{
            if(!code.matches("\\d{6}"))throw new IOException("请输入六位股票代码");
            JSONArray result=new JSONArray();boolean found=false;
            JSONObject added=new JSONObject().put("code",code).put("name",name==null||name.isEmpty()?code:name)
                    .put("industry",industry==null||industry.isEmpty()?"待分类":industry);
            for(JSONObject item:stocks()){
                if(code.equals(item.optString("code"))){if(!found){added.put("starred",item.optBoolean("starred",false));result.put(added);}found=true;}
                else result.put(item);
            }
            if(!found)result.put(added);
            save(result);
        }
        void remove(String code)throws Exception{
            JSONArray result=new JSONArray();
            for(JSONObject item:stocks())if(!code.equals(item.optString("code")))result.put(item);
            save(result);
        }
        private void save(JSONArray data)throws IOException{
            if(!backend.write(data.toString()))throw new IOException("自选保存失败，请重试");
        }
    }
    private static Repository repository(Context context){
        android.content.SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        return new Repository(new Backend(){
            public String read(){return prefs.getString(KEY,"[]");}
            public boolean write(String value){return prefs.edit().putString(KEY,value).commit();}
        });
    }
    static String payload(Context context){return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY,"[]");}
    static List<JSONObject> stocks(Context context)throws Exception{return repository(context).stocks();}
    static boolean contains(Context context,String code)throws Exception{return repository(context).contains(code);}
    static synchronized void add(Context context,String code,String name,String industry)throws Exception{repository(context).add(code,name,industry);}
    static synchronized void remove(Context context,String code)throws Exception{repository(context).remove(code);}
    static synchronized void setStarred(Context context,String code,boolean starred)throws Exception{repository(context).setStarred(code,starred);}
    static List<JSONObject> priorityFirst(List<JSONObject> stocks){
        List<JSONObject> result=new ArrayList<>(stocks);
        // Stable sorting keeps the original order within each priority level.
        result.sort((a,b)->Boolean.compare(b.optBoolean("starred",false),a.optBoolean("starred",false)));
        return result;
    }
    static JSONObject bundledSnapshot(Context context)throws Exception{
        try(InputStream input=context.getAssets().open("web_watchlist_seed.json");ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int read;while((read=input.read(buffer))!=-1)output.write(buffer,0,read);
            return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        }
    }
    static void importBundled(Context context)throws Exception{importSnapshot(context,bundledSnapshot(context));}
    static synchronized boolean importSnapshot(Context context,JSONObject snapshot)throws Exception{
        String id=snapshot.getString("id");if(id.isEmpty())throw new IOException("导入名单缺少标识");
        android.content.SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String marker="imported:"+id;if(prefs.getBoolean(marker,false))return false;
        // Build the complete merge before saving; the list and completion marker commit together.
        JSONArray saved=new JSONArray(prefs.getString(KEY,"[]")),incoming=snapshot.getJSONArray("stocks");
        Map<String,JSONObject> merged=new LinkedHashMap<>();
        for(int i=0;i<saved.length();i++){JSONObject item=saved.getJSONObject(i);String code=item.getString("code");if(!code.matches("\\d{6}"))throw new IOException("自选记录代码无效");merged.put(code,item);}
        for(int i=0;i<incoming.length();i++){
            JSONObject source=incoming.getJSONObject(i);String code=source.getString("code");
            if(!code.matches("\\d{6}"))throw new IOException("导入名单代码无效");
            JSONObject item=merged.get(code);
            if(item==null){item=new JSONObject().put("code",code).put("name",source.optString("name",code)).put("industry",source.optString("industry","待分类")).put("starred",false);merged.put(code,item);}
            // Note-only entries don't demote a stock already starred by the phone owner.
            if(source.optBoolean("starred",false))item.put("starred",true);
        }
        JSONArray result=new JSONArray();for(JSONObject item:merged.values())result.put(item);
        if(!prefs.edit().putString(KEY,result.toString()).putBoolean(marker,true).commit())throw new IOException("网页名单导入保存失败，下次打开会重试");
        return true;
    }
    static String industry(JSONObject item,Function<String,String> lookup){
        String current=lookup.apply(item.optString("code"));
        if(current==null||current.isEmpty()||"待分类".equals(current))current=item.optString("industry","待分类");
        return current.isEmpty()?"待分类":current;
    }
    static Map<String,List<JSONObject>> grouped(List<JSONObject> stocks,Function<String,String> lookup){
        Map<String,List<JSONObject>> result=new TreeMap<>();
        for(JSONObject item:stocks)result.computeIfAbsent(industry(item,lookup),key->new ArrayList<>()).add(item);
        return result;
    }
}
