package com.aitrader.hammer1430;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="w390dp-h844dp-xxhdpi",shadows=TradingFeaturesTest.FakeTencent.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TradingFeaturesTest {
    static volatile boolean failMinutes;
    static volatile CountDownLatch entered,release;
    @Implements(value=TencentClient.class,isInAndroidSdk=false)
    public static class FakeTencent {
        @Implementation protected static List<DailyBar> minutes(String code)throws Exception {if(entered!=null){entered.countDown();release.await(3,TimeUnit.SECONDS);}if(failMinutes)throw new IOException("测试离线");return behaviorBars();}
        @Implementation protected static TencentClient.Quote quote(String code){List<DailyBar> d=daily();DailyBar b=d.get(d.size()-1);return new TencentClient.Quote(code,"测试股票",b,10,MinuteBehavior.epoch(b.date+" 15:00")/1000);}
        @Implementation protected static List<DailyBar> history(String code,String date){return daily();}
        @Implementation protected static List<DailyBar> history(String code,String date,int count){return daily();}
        @Implementation protected static List<TencentClient.Quote> quotes(List<String> codes){List<TencentClient.Quote> out=new ArrayList<>();for(String c:codes)out.add(quote(c));return out;}
    }
    @Before public void reset(){Context c=RuntimeEnvironment.getApplication();for(String pref:new String[]{"holdings","watchlist","minute_cache","minute15_cache","scan"})c.getSharedPreferences(pref,0).edit().clear().commit();failMinutes=false;entered=null;release=null;}
    static List<DailyBar> daily(){List<DailyBar> out=new ArrayList<>();for(int i=0;i<40;i++)out.add(new DailyBar(LocalDate.of(2026,1,1).plusDays(i).toString(),10,10.2,9.8,10+i*.002,100));return out;}
    static final int ATTACK_INDEX=326,REDUCE_INDEX=334;
    static List<DailyBar> behaviorBars(){List<DailyBar> out=new ArrayList<>();
        LocalDate date=LocalDate.of(2026,1,2);List<LocalDate> days=new ArrayList<>();
        while(days.size()<20){if(date.getDayOfWeek()!=DayOfWeek.SATURDAY&&date.getDayOfWeek()!=DayOfWeek.SUNDAY)days.add(0,date);date=date.minusDays(1);}
        for(LocalDate day:days)for(int i=0;i<16;i++){LocalTime time=i<8?LocalTime.of(9,45).plusMinutes(i*15L):LocalTime.of(13,15).plusMinutes((i-8)*15L);out.add(new DailyBar(day+" "+time,10,10.02,9.98,10,100));}
        for(int i=0;i<6;i++)out.add(new DailyBar(LocalDateTime.of(2026,1,5,9,45).plusMinutes(15L*i).format(MinuteBehavior.FORMAT),10,10.02,9.98,10,100));
        out.add(new DailyBar("2026-01-05 11:15",10,10.06,9.99,10.05,300));
        out.add(new DailyBar("2026-01-05 11:30",10.05,10.11,10.04,10.10,320));
        for(int i=0;i<6;i++)out.add(new DailyBar(LocalDateTime.of(2026,1,5,13,15).plusMinutes(15L*i).format(MinuteBehavior.FORMAT),10.10,10.11,10.09,10.10,100));
        out.add(new DailyBar("2026-01-05 14:45",10.10,10.11,9.89,9.90,400));return out;
    }
    @Test public void behaviorConfirmationAndGaps(){List<DailyBar> b=behaviorBars();long now=MinuteBehavior.epoch("2026-01-05 15:00");
        List<MinuteBehavior.Zone> z=MinuteBehavior.find(b,now);assertEquals(2,z.size());assertTrue(z.get(0).attack);assertEquals(ATTACK_INDEX,z.get(0).start);assertEquals(ATTACK_INDEX+1,z.get(0).end);assertFalse(z.get(1).attack);assertEquals(REDUCE_INDEX,z.get(1).start);
        assertTrue(MinuteBehavior.find(b,MinuteBehavior.epoch("2026-01-05 11:14")).isEmpty());
        List<DailyBar> gap=new ArrayList<>(b.subList(0,ATTACK_INDEX));DailyBar a=b.get(ATTACK_INDEX);gap.add(new DailyBar("2026-01-05 13:15",a.open,a.high,a.low,a.close,a.volume));assertEquals(MinuteBehavior.ATTACK,MinuteBehavior.find(gap,now).get(0).kind);
        gap.set(ATTACK_INDEX,new DailyBar("2026-01-06 11:15",a.open,a.high,a.low,a.close,a.volume));assertEquals(MinuteBehavior.ATTACK,MinuteBehavior.find(gap,MinuteBehavior.epoch("2026-01-06 15:00")).get(0).kind);
        gap.set(ATTACK_INDEX,new DailyBar("2026-01-05 11:30",a.open,a.high,a.low,a.close,a.volume));assertEquals(MinuteBehavior.ATTACK,MinuteBehavior.find(gap,now).get(0).kind);
        List<DailyBar> normal=new ArrayList<>(b.subList(0,ATTACK_INDEX));normal.add(new DailyBar(a.date,a.open,a.high,a.low,a.close,100));assertTrue(MinuteBehavior.find(normal,now).isEmpty());
        assertTrue(MinuteBehavior.find(b.subList(0,10),now).isEmpty());assertEquals(Long.MAX_VALUE,MinuteBehavior.epoch("2026-02-30 10:00"));
    }
    @Test public void realMinuteContractAndCache()throws Exception {
        String raw;try(InputStream in=getClass().getResourceAsStream("/minute15_live.json")){raw=new String(in.readAllBytes(),StandardCharsets.UTF_8);}
        List<DailyBar> bars=TencentClient.parseMinutes(raw,"sh600519");assertEquals(320,bars.size());assertTrue(bars.get(0).date.compareTo(bars.get(319).date)<0);assertEquals("2026-09-30 15:00",bars.get(319).date);assertEquals(1258.62,bars.get(319).close,.0001);
        Context c=RuntimeEnvironment.getApplication();MinuteCache.save(c,"600519",bars);List<DailyBar> cached=MinuteCache.load(c,"600519");assertEquals(bars.size(),cached.size());assertEquals(bars.get(319).volume,cached.get(319).volume,0);
        JSONObject root=new JSONObject(raw);JSONArray a=root.getJSONObject("data").getJSONObject("sh600519").getJSONArray("m15");a.put(a.getJSONArray(319));a.put(new JSONArray("[\"202602301000\",1,1,1,1,1]"));a.put(new JSONArray("[\"202609301235\",1,1,1,1,1]"));a.put(new JSONArray("[\"202609301505\",1,1,1,1,1]"));assertEquals(320,TencentClient.parseMinutes(root.toString(),"sh600519").size());
        try{TencentClient.parseMinutes("{\"code\":1}","sh600519");fail();}catch(IOException expected){}
        a.put(new JSONArray("[\"202609301455\",1,1,1,1,1]"));a.put(new JSONArray("[\"202609300930\",1,1,1,1,1]"));a.put(new JSONArray("[\"202609301300\",1,1,1,1,1]"));assertEquals(320,TencentClient.parseMinutes(root.toString(),"sh600519").size());
        // Upgrade must never present a saved 5-minute series as 15-minute data.
        c.getSharedPreferences("minute15_cache",0).edit().clear().commit();
        c.getSharedPreferences("minute_cache",0).edit().putString("600519","[[\"2026-09-30 14:55\",1,1,1,1,1]]").commit();assertTrue(MinuteCache.load(c,"600519").isEmpty());
        String old;try(InputStream in=getClass().getResourceAsStream("/minute_live.json")){old=new String(in.readAllBytes(),StandardCharsets.UTF_8);}
        try{TencentClient.parseMinutes(old,"sh600519");fail("5-minute response must be rejected");}catch(IOException expected){}
    }
    @Test public void holdingsAreIndependentAndPersistStops()throws Exception {Context c=RuntimeEnvironment.getApplication();
        WatchlistStore.add(c,"600519","贵州茅台","白酒");HoldingsStore.add(c,"600519","贵州茅台","白酒");HoldingsStore.stop(c,"600519",1200.0);HoldingsStore.add(c,"600519","贵州茅台","白酒");assertEquals(1,HoldingsStore.stocks(c).size());assertEquals(1200,HoldingsStore.find(c,"600519").getDouble("stop_price"),0);
        WatchlistStore.remove(c,"600519");assertNotNull(HoldingsStore.find(c,"600519"));for(double invalid:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,1.234}){try{HoldingsStore.stop(c,"600519",invalid);fail();}catch(IOException expected){}}
        assertTrue(HoldingsStore.stopText(HoldingsStore.find(c,"600519"),new JSONObject().put("latest_price",1200)).contains("已触及"));
        PersonalQuotes.load(c);JSONObject quote=new JSONObject(c.getSharedPreferences("scan",0).getString("favorite_quote_payload","{}"));assertTrue("holdings-only code included in quote refresh",quote.has("600519"));
        HoldingsStore.stop(c,"600519",null);assertFalse(HoldingsStore.find(c,"600519").has("stop_price"));HoldingsStore.remove(c,"600519");assertTrue(HoldingsStore.stocks(c).isEmpty());
    }
    @Test public void watchlistPriorityPersistsAndSortsStably()throws Exception {
        Context app=RuntimeEnvironment.getApplication();
        // Previous APK records have no starred field and remain ordinary watchlist entries.
        app.getSharedPreferences("watchlist",0).edit().putString("stocks","[{\"code\":\"600000\",\"name\":\"旧记录\",\"industry\":\"银行\"}]").commit();
        WatchlistStore.add(app,"600036","招商银行","银行");WatchlistStore.add(app,"000001","平安银行","银行");
        WatchlistStore.setStarred(app,"000001",true);WatchlistStore.setStarred(app,"600036",true);
        List<JSONObject> saved=WatchlistStore.stocks(app),sorted=WatchlistStore.priorityFirst(saved);
        assertEquals("600036",sorted.get(0).getString("code"));assertEquals("000001",sorted.get(1).getString("code"));assertEquals("600000",sorted.get(2).getString("code"));assertEquals("600000",saved.get(0).getString("code"));
        WatchlistStore.add(app,"000001","平安银行更新","银行");assertTrue(WatchlistStore.stocks(app).get(2).getBoolean("starred"));
        WatchlistStore.setStarred(app,"600036",false);WatchlistStore.setStarred(app,"000001",false);
        assertEquals("600000",WatchlistStore.priorityFirst(WatchlistStore.stocks(app)).get(0).getString("code"));
        WatchlistStore.setStarred(app,"000001",true);WatchlistStore.remove(app,"000001");WatchlistStore.add(app,"000001","平安银行","银行");assertFalse(WatchlistStore.stocks(app).get(2).optBoolean("starred"));
        String before=WatchlistStore.payload(app);try{WatchlistStore.setStarred(app,"600519",true);fail();}catch(IOException expected){}assertEquals(before,WatchlistStore.payload(app));
        final String[] memory={before};WatchlistStore.Repository readOnly=new WatchlistStore.Repository(new WatchlistStore.Backend(){public String read(){return memory[0];}public boolean write(String value){return false;}});
        try{readOnly.setStarred("600036",true);fail();}catch(IOException expected){}assertEquals(before,memory[0]);
    }
    @Test public void webSnapshotImportsOnceAndPreservesPhoneData()throws Exception {
        Context app=RuntimeEnvironment.getApplication();JSONObject snapshot=WatchlistStore.bundledSnapshot(app);
        JSONArray incoming=snapshot.getJSONArray("stocks");assertEquals(288,incoming.length());int starred=0;Set<String> codes=new HashSet<>();
        for(int i=0;i<incoming.length();i++){JSONObject item=incoming.getJSONObject(i);assertTrue(codes.add(item.getString("code")));if(item.getBoolean("starred"))starred++;}assertEquals(50,starred);
        assertTrue(WatchlistStore.importSnapshot(app,snapshot));assertEquals(288,WatchlistStore.stocks(app).size());
        String first=incoming.getJSONObject(0).getString("code");WatchlistStore.setStarred(app,first,false);WatchlistStore.remove(app,incoming.getJSONObject(1).getString("code"));
        String adjusted=WatchlistStore.payload(app);assertFalse(WatchlistStore.importSnapshot(app,snapshot));assertEquals(adjusted,WatchlistStore.payload(app));
        app.getSharedPreferences("watchlist",0).edit().clear().commit();
        WatchlistStore.add(app,"600519","我的名称","白酒");WatchlistStore.setStarred(app,"600519",true);HoldingsStore.add(app,"600519","贵州茅台","白酒");HoldingsStore.stop(app,"600519",1200.0);String holdingBefore=HoldingsStore.payload(app);
        JSONObject fixture=new JSONObject("{\"id\":\"merge-check\",\"stocks\":[{\"code\":\"600519\",\"name\":\"网页名称\",\"starred\":false},{\"code\":\"000001\",\"name\":\"持仓股\",\"starred\":true},{\"code\":\"600036\",\"name\":\"笔记股\",\"starred\":false}]}");
        WatchlistStore.importSnapshot(app,fixture);List<JSONObject> merged=WatchlistStore.stocks(app);assertEquals(3,merged.size());assertEquals("我的名称",merged.get(0).getString("name"));assertTrue(merged.get(0).getBoolean("starred"));assertTrue(merged.get(1).getBoolean("starred"));assertFalse(merged.get(2).getBoolean("starred"));assertEquals(holdingBefore,HoldingsStore.payload(app));
        String before=WatchlistStore.payload(app);JSONObject invalid=new JSONObject("{\"id\":\"invalid\",\"stocks\":[{\"code\":\"000002\",\"starred\":true},{\"code\":\"bad\"}]}");
        try{WatchlistStore.importSnapshot(app,invalid);fail();}catch(IOException expected){}assertEquals(before,WatchlistStore.payload(app));assertFalse(app.getSharedPreferences("watchlist",0).getBoolean("imported:invalid",false));
        app.getSharedPreferences("watchlist",0).edit().putString("stocks","broken").commit();try{WatchlistStore.importSnapshot(app,snapshot);fail();}catch(JSONException expected){}assertEquals("broken",WatchlistStore.payload(app));assertFalse(app.getSharedPreferences("watchlist",0).getBoolean("imported:"+snapshot.getString("id"),false));
    }
    private View root(Activity a){return ((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);}
    private View named(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View r=named(((ViewGroup)v).getChildAt(i),text);if(r!=null)return r;}return null;}
    private StockChartView chart(View v){if(v instanceof StockChartView)return (StockChartView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){StockChartView c=chart(((ViewGroup)v).getChildAt(i));if(c!=null)return c;}return null;}
    private void awaitChart(StockChartView c,boolean minute)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();if(c.detail(0).contains(minute?"15分钟K线":"2026-01-01"))return;Thread.sleep(10);}fail("chart did not finish loading");}
    private void snapshot(View v,Context c,String file,int width,int height)throws Exception {int w=Ui.dp(c,width),h=Ui.dp(c,height);for(int i=0;i<2;i++){v.forceLayout();v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);}Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));try(FileOutputStream out=new FileOutputStream(new File(System.getProperty("aitrader.ui.outputs"),file))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
    @Test public void switchingPeriodCachingAndNativeBoxes()throws Exception {Context app=RuntimeEnvironment.getApplication();HoldingsStore.add(app,"600519","测试股票","白酒");HoldingsStore.stop(app,"600519",9.95);
        ActivityController<StockChartActivity> controller=Robolectric.buildActivity(StockChartActivity.class,new Intent().putExtra(StockChartActivity.EXTRA_CODE,"600519").putExtra(StockChartActivity.EXTRA_NAME,"测试股票")).create().start().resume().visible();
        try{StockChartActivity a=controller.get();View r=root(a);StockChartView chart=chart(r);awaitChart(chart,false);assertTrue(chart.behaviorZones().isEmpty());
            named(r,"15分钟K线").performClick();awaitChart(chart,true);assertEquals(2,chart.behaviorZones().size());assertNotNull(named(r,"主力行为框选"));snapshot(r,a,"apk-v1.23-stock-minute-page.png",390,844);
            snapshot(chart,a,"apk-v1.23-minute-chart.png",358,480);assertTrue(chart.detail(ATTACK_INDEX).contains("主力进攻"));assertTrue(chart.detail(REDUCE_INDEX).contains("主力减仓"));chart.focus(ATTACK_INDEX);assertNotNull(named(r,"查看框选规则"));
            failMinutes=true;a.refreshData();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();if(contains(r,"本机缓存（更新失败）"))break;Thread.sleep(10);}assertTrue(contains(r,"本机缓存（更新失败）"));assertEquals(2,chart.behaviorZones().size());
            named(r,"日K").performClick();awaitChart(chart,false);assertTrue(chart.behaviorZones().isEmpty());
            failMinutes=false;entered=new CountDownLatch(1);release=new CountDownLatch(1);named(r,"15分钟K线").performClick();assertTrue(entered.await(2,TimeUnit.SECONDS));named(r,"日K").performClick();release.countDown();awaitChart(chart,false);assertTrue("late minute response cannot overwrite daily candles",chart.detail(0).contains("2026-01-01"));
        }finally{if(release!=null)release.countDown();controller.pause().stop().destroy();}
    }
    @Test public void fullChineseBehaviorLabelsStayReadableAndCanBeSelected()throws Exception {
        Context app=RuntimeEnvironment.getApplication();StockChartView chart=new StockChartView(app);
        chart.setMinuteMode(true);chart.setBars(behaviorBars());chart.setStopPrice(9.95);
        for(int width:new int[]{358,288}){
            app.getResources().getConfiguration().fontScale=width==288?1.3f:1f;
            snapshot(chart,app,"apk-v1.23-labels-"+width+".png",width,480);
            List<StockChartView.BehaviorLabel> labels=chart.behaviorLabels();assertEquals(2,labels.size());
            assertEquals("1  主力进攻",labels.get(0).text);assertEquals("2  主力减仓",labels.get(1).text);
            assertFalse(RectF.intersects(labels.get(0).bounds,labels.get(1).bounds));
            for(StockChartView.BehaviorLabel label:labels){assertTrue(label.bounds.left>=0);assertTrue(label.bounds.right<=chart.getWidth());assertTrue(label.bounds.bottom<chart.getHeight()*.53f);assertFalse(label.text.contains("?"));}
            final int[] selected={-1};chart.setOnBarSelected(index->selected[0]=index);
            RectF target=labels.get(0).bounds;long time=android.os.SystemClock.uptimeMillis();
            MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,target.centerX(),target.centerY(),0),up=MotionEvent.obtain(time,time+10,MotionEvent.ACTION_UP,target.centerX(),target.centerY(),0);
            chart.onTouchEvent(down);chart.onTouchEvent(up);down.recycle();up.recycle();assertEquals(ATTACK_INDEX+1,selected[0]);
        }
        app.getResources().getConfiguration().fontScale=1f;
        // Six separated signals exercise label crowding; adjacent opposite candles now form one web frame.
        List<DailyBar> many=new ArrayList<>(behaviorBars().subList(0,320));
        for(int i=0;i<12;i++){
            LocalTime time=i<8?LocalTime.of(9,45).plusMinutes(i*15L):LocalTime.of(13,15).plusMinutes((i-8)*15L);
            if(i%2==0){int signal=i/2;double open=10,close=signal%2==0?11+signal*.1:9-signal*.1;
                many.add(new DailyBar("2026-01-05 "+time,open,Math.max(open,close)+.01,Math.min(open,close)-.01,close,300*Math.pow(3,signal)));
            }else many.add(new DailyBar("2026-01-05 "+time,10,10.02,9.98,10,100));
        }
        chart=new StockChartView(app);chart.setMinuteMode(true);chart.setBars(many);assertEquals(6,chart.behaviorZones().size());chart.focus(320);
        snapshot(chart,app,"apk-v1.23-labels-crowded.png",288,480);
        List<StockChartView.BehaviorLabel> labels=chart.behaviorLabels();assertEquals(4,labels.size());assertEquals(0,labels.get(0).zoneIndex);
        for(int i=0;i<labels.size();i++)for(int j=i+1;j<labels.size();j++)assertFalse(RectF.intersects(labels.get(i).bounds,labels.get(j).bounds));
        chart.setMinuteMode(false);chart.setBars(daily());snapshot(chart,app,"apk-v1.23-daily-chart.png",358,480);assertTrue(chart.behaviorLabels().isEmpty());
    }
    private boolean contains(View v,String text){if(v instanceof TextView&&((TextView)v).getText().toString().contains(text))return true;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(contains(((ViewGroup)v).getChildAt(i),text))return true;return false;}
}
