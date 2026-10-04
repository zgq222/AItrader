package com.aitrader.hammer1430;

import android.app.Application;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,shadows=BoardSyncTest.Feed.class)
public class BoardSyncTest {
    static volatile boolean failYear;
    static final Queue<String> reads=new ConcurrentLinkedQueue<>();
    @Implements(value=BoardHistoryClient.class,isInAndroidSdk=false)
    public static class Feed {
        @Implementation protected static JSONObject get(String code,String part)throws Exception{
            reads.add(part);if(part.equals("last"))return new JSONObject().put("name","测试板块").put("year",new JSONObject("{\"2024\":2,\"2025\":1,\"2026\":2}")).put("data","20260930,10,12,9,11,200,2000");
            if(part.equals("2025")&&failYear)throw new IOException("年份请求失败");
            String rows=part.equals("2024")?"20240102,10,12,9,11,100,1000;20240103,11,13,10,12,100,1000":part.equals("2025")?"20250102,10,12,9,11,100,1000":"20260929,10,12,9,11,100,1000;20260930,10,12,9,11,100,1000";
            return new JSONObject().put("data",rows);
        }
    }
    @Before public void reset(){reads.clear();failYear=false;Context app=RuntimeEnvironment.getApplication();for(String type:new String[]{"industry","concept"})new File(app.getFilesDir(),"board_history_v2/"+type+"/889999.json").delete();}
    @Test public void incompleteCacheDownloadsMissingYearsAndThenOnlyLatestData()throws Exception{
        Context app=RuntimeEnvironment.getApplication();JSONObject result=BoardHistoryClient.sync(app,"889999",false,null);assertEquals(5,result.getJSONArray("data").length());assertTrue(result.getBoolean("complete"));assertEquals("2024-01-02",result.getJSONArray("data").getJSONArray(0).getString(0));assertEquals(200,result.getJSONArray("data").getJSONArray(4).getDouble(5),0);assertTrue(reads.containsAll(Arrays.asList("last","2024","2025","2026")));
        reads.clear();JSONObject again=BoardHistoryClient.sync(app,"889999",false,null);assertEquals(5,again.getJSONArray("data").length());assertEquals(Collections.singletonList("last"),new ArrayList<>(reads));assertEquals(5,BoardHistoryClient.cached(app,"889999",false).getJSONArray("data").length());
    }
    @Test public void failedYearCannotReplaceThePreviousArchive()throws Exception{
        Context app=RuntimeEnvironment.getApplication();File f=new File(app.getFilesDir(),"board_history_v2/concept/889999.json");String before=new JSONObject().put("data",BoardHistoryClient.parse(Feed.get("889999","2024"))).toString();ChartFiles.write(f,before.getBytes(StandardCharsets.UTF_8));failYear=true;
        try{BoardHistoryClient.sync(app,"889999",true,null);fail();}catch(Exception expected){}assertEquals(before,new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));assertEquals(2,BoardHistoryClient.cached(app,"889999",true).getJSONArray("data").length());
    }
}
