package com.aitrader.hammer1430;

import android.app.Application;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MinuteBehaviorTest {
    private static final long NOW=MinuteBehavior.epoch("2026-09-30 15:00");
    private static void session(List<DailyBar> bars,String date,int count){
        for(int i=0;i<count;i++){LocalTime time=i<8?LocalTime.of(9,45).plusMinutes(i*15L):LocalTime.of(13,15).plusMinutes((i-8)*15L);bars.add(new DailyBar(date+" "+time,10,10.02,9.98,10,100));}
    }
    private static List<DailyBar> baseline(){return new ArrayList<>(TradingFeaturesTest.behaviorBars().subList(0,320));}
    private static MinuteBehavior.Zone zoneAt(List<MinuteBehavior.Zone> zones,int index){for(MinuteBehavior.Zone z:zones)if(z.start<=index&&z.end>=index)return z;return null;}
    private String resource(String name)throws Exception{try(InputStream in=getClass().getResourceAsStream("/"+name)){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    private void snapshot(StockChartView chart,Context context,String file,int width,int height)throws Exception{
        int w=Ui.dp(context,width),h=Ui.dp(context,height);chart.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));chart.layout(0,0,w,h);
        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);chart.draw(new Canvas(bitmap));
        try(FileOutputStream out=new FileOutputStream(new File(System.getProperty("aitrader.ui.outputs"),file))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
    }
    @Test public void real640BarsMatchExactWebReferenceAndCoverHezhongSeptember10()throws Exception{
        List<DailyBar> bars=TencentClient.parseMinutes(resource("minute15_002383_live.json"),"sz002383");assertEquals(640,bars.size());
        List<MinuteBehavior.Zone> zones=MinuteBehavior.find(bars,NOW);JSONArray expected=new JSONArray(resource("minute15_002383_web_frames.json"));assertEquals(43,zones.size());assertEquals(expected.length(),zones.size());
        for(int i=0;i<zones.size();i++){MinuteBehavior.Zone z=zones.get(i);JSONObject e=expected.getJSONObject(i);assertEquals(e.getInt("start"),z.start);assertEquals(e.getInt("end"),z.end);assertEquals(e.getInt("direction")>0?MinuteBehavior.ATTACK:e.getInt("direction")<0?MinuteBehavior.REDUCE:MinuteBehavior.VOLUME,z.kind);assertEquals(e.getBoolean("contained"),z.contained);assertEquals(e.getDouble("low"),z.low,1e-9);assertEquals(e.getDouble("high"),z.high,1e-9);}
        Map<String,Double> medians=MinuteBehavior.baselines(bars,NOW);assertEquals(4648.5,medians.get("2026-09-10"),0);
        int first=416;assertEquals("2026-09-10 09:45",bars.get(first).date);assertEquals("2026-09-10 10:00",bars.get(first+1).date);
        MinuteBehavior.Zone z=zoneAt(zones,first);assertNotNull(z);assertSame(z,zoneAt(zones,first+1));assertEquals(421,z.end);assertEquals(MinuteBehavior.ATTACK,z.kind);assertEquals(29.647,z.ratio,.001);
        assertNull(zoneAt(MinuteBehavior.find(bars,MinuteBehavior.epoch("2026-09-10 09:44")),first));
        assertEquals(medians.get("2026-09-10"),MinuteBehavior.baselines(bars.subList(0,424),NOW).get("2026-09-10"));
        Context context=RuntimeEnvironment.getApplication();StockChartView chart=new StockChartView(context);chart.setMinuteMode(true);chart.setBars(bars);chart.restoreViewport(new int[]{first+8,24,first,0});
        assertTrue(chart.detail(first).contains("本根量比 29.65"));assertTrue(chart.detail(first+1).contains("本根量比 7.50"));assertTrue(chart.detail(first).contains("主力进攻"));
        snapshot(chart,context,"apk-v1.23-002383-september10.png",358,480);assertTrue(chart.behaviorLabels().stream().anyMatch(label->chart.behaviorZones().get(label.zoneIndex).start==416));
        snapshot(chart,context,"apk-v1.23-002383-landscape.png",820,250);for(StockChartView.BehaviorLabel label:chart.behaviorLabels()){assertTrue(label.bounds.right<=chart.getWidth());assertTrue(label.bounds.bottom<Ui.dp(context,100));}
    }
    @Test public void baselineRequiresTwentyCompletePriorDaysAndUsesMedian(){
        List<DailyBar> b=baseline();b.set(0,new DailyBar(b.get(0).date,10,10.02,9.98,10,1_000_000));b.add(new DailyBar("2026-01-05 09:45",10,10.02,9.98,10,250));
        assertEquals(100,MinuteBehavior.baselines(b,NOW).get("2026-01-05"),0);assertEquals(1,MinuteBehavior.find(b,NOW).size());
        b.set(320,new DailyBar("2026-01-05 09:45",10,10.02,9.98,10,249.99));assertTrue(MinuteBehavior.find(b,NOW).isEmpty());
        b.set(320,new DailyBar("2026-01-05 09:45",10,10.02,9.98,10,100000));b.remove(15);assertFalse(MinuteBehavior.baselines(b,NOW).containsKey("2026-01-05"));assertTrue(MinuteBehavior.find(b,NOW).isEmpty());
        b=baseline();b.add(new DailyBar("2026-01-05 09:45",10,10.02,9.98,10,300));assertTrue(MinuteBehavior.find(b,MinuteBehavior.epoch("2026-01-05 09:44")).isEmpty());
        b=baseline();for(int i=0;i<b.size();i++){DailyBar bar=b.get(i);b.set(i,new DailyBar(bar.date,bar.open,bar.high,bar.low,bar.close,0));}b.add(new DailyBar("2026-01-05 09:45",10,10.02,9.98,10,300));assertTrue(MinuteBehavior.find(b,NOW).isEmpty());
    }
    @Test public void mergeAllAdjacentVolumeBarsBeforeChoosingDirectionAndDoNotCrossSessions(){
        List<DailyBar> b=baseline();b.add(new DailyBar("2026-01-05 09:45",10,11.1,9.9,11,300));b.add(new DailyBar("2026-01-05 10:00",11,11.1,9.9,10,300));
        List<MinuteBehavior.Zone> zones=MinuteBehavior.find(b,NOW);assertEquals(1,zones.size());assertEquals(MinuteBehavior.VOLUME,zones.get(0).kind);
        b.add(new DailyBar("2026-01-05 10:15",10,10.1,8.9,9,300));zones=MinuteBehavior.find(b,NOW);assertEquals(1,zones.size());assertEquals(MinuteBehavior.REDUCE,zones.get(0).kind);assertEquals(320,zones.get(0).start);assertEquals(322,zones.get(0).end);assertEquals(8.9,zones.get(0).low,0);assertEquals(11.1,zones.get(0).high,0);
        b=baseline();session(b,"2026-01-05",7);b.add(new DailyBar("2026-01-05 11:30",10,10,10,10,300));b.add(new DailyBar("2026-01-05 13:15",10,10,10,10,300));assertEquals(2,MinuteBehavior.find(b,NOW).size());
        b=baseline();b.add(new DailyBar("2026-01-05 09:45",10,10.02,9.98,10,300));b.add(new DailyBar("2026-01-05 10:15",10,10.02,9.98,10,300));assertEquals(2,MinuteBehavior.find(b,NOW).size());
        b=baseline();b.add(new DailyBar("2026-01-05 09:45",10,10.02,9.98,10,Double.NaN));assertTrue(MinuteBehavior.find(b,NOW).isEmpty());
    }
}
