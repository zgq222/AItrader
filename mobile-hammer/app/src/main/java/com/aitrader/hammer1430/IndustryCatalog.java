package com.aitrader.hammer1430;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

final class IndustryCatalog {
    private final JSONObject stocks;
    IndustryCatalog(Context context)throws Exception{
        try(InputStream input=context.getAssets().open("stock_industries.json");ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int read;
            while((read=input.read(buffer))!=-1)output.write(buffer,0,read);
            stocks=new JSONObject(output.toString(StandardCharsets.UTF_8.name())).getJSONObject("stocks");
        }
    }
    String industry(String code){return stocks.optString(code,"待分类");}
    Set<String> industries(){
        Set<String> names=new TreeSet<>();Iterator<String> codes=stocks.keys();
        while(codes.hasNext()){String name=industry(codes.next());if(!name.isEmpty()&&!name.equals("待分类"))names.add(name);}
        return names;
    }
}
