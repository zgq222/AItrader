package com.aitrader.hammer1430;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="w390dp-h844dp-xxhdpi",shadows={FullChartsTest.Feed.class,TradingFeaturesTest.FakeBoard.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FullChartsTest {
    static boolean failFull;
    static int fullCalls;
    static String fixture(String name)throws Exception {try(InputStream in=FullChartsTest.class.getResourceAsStream("/"+name)){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    static List<DailyBar> daily(){List<DailyBar> out=new ArrayList<>();LocalDate date=LocalDate.of(2026,9,30);while(out.size()<2600){if(date.getDayOfWeek()!=DayOfWeek.SATURDAY&&date.getDayOfWeek()!=DayOfWeek.SUNDAY)out.add(0,new DailyBar(date.toString(),10,12,9,11,100));date=date.minusDays(1);}return out;}
    @Implements(value=TencentClient.class,isInAndroidSdk=false)
    public static class Feed {
        @Implementation protected static TencentClient.Quote quote(String code){DailyBar b=daily().get(2599);return new TencentClient.Quote(code,"测试股票",b,10,MinuteBehavior.epoch(b.date+" 15:00")/1000);}
        @Implementation protected static List<DailyBar> fullHistory(String code,String through,TencentClient.Progress p)throws Exception{fullCalls++;if(failFull)throw new IOException("测试分页失败");return daily();}
        @Implementation protected static List<DailyBar> history(String code,String through){List<DailyBar> all=daily();return new ArrayList<>(all.subList(all.size()-120,all.size()));}
        @Implementation protected static List<DailyBar> minutes(String code,int period)throws Exception{return period==5?TencentClient.parseSinaFive(fixture("minute5_002383_sina.json")):TradingFeaturesTest.behaviorBars();}
    }
    @Before public void reset(){failFull=false;fullCalls=0;Context c=RuntimeEnvironment.getApplication();new File(c.getFilesDir(),"raw_daily_v1/002383.json").delete();new File(c.getFilesDir(),"minute_history_v2/5/002383.json").delete();new File(c.getFilesDir(),"minute_history_v2/15/002383.json").delete();}
    @Test public void calendarAggregationUsesFirstOpenLastCloseAndIncludesPartialPeriods(){
        List<DailyBar> input=Arrays.asList(new DailyBar("2026-01-05",20,23,19,22,5),new DailyBar("2025-12-31",12,15,11,14,3),new DailyBar("2025-12-29",10,13,9,12,2),new DailyBar("2026-01-02",14,18,13,17,4));
        ChartPeriod.Series weeks=ChartPeriod.aggregate(input,7);assertEquals(2,weeks.bars.size());DailyBar first=weeks.bars.get(0);assertEquals("2026-01-02",first.date);assertEquals(10,first.open,0);assertEquals(17,first.close,0);assertEquals(18,first.high,0);assertEquals(9,first.low,0);assertEquals(9,first.volume,0);assertEquals("2025-12-29—2026-01-02",weeks.spans.get(first.date));
        ChartPeriod.Series months=ChartPeriod.aggregate(input,30);assertEquals(2,months.bars.size());assertEquals(14,months.bars.get(0).close,0);assertEquals(14,months.bars.get(1).open,0);assertEquals(22,months.bars.get(1).close,0);assertEquals(9,months.bars.get(1).volume,0);
        assertTrue(Double.isNaN(ChartPeriod.aggregate(Arrays.asList(input.get(0),new DailyBar("2026-01-06",22,24,21,23,Double.NaN)),7).bars.get(0).volume));
    }
    @Test public void downloadPagesUntilEmptyAndRejectsStalledOrFailedHistory()throws Exception{
        List<String> cursors=new ArrayList<>();List<DailyBar> result=TencentClient.downloadFull("2026-09-30",end->{cursors.add(end);if(end.equals("2026-09-30"))return daily().subList(2000,2600);if(end.equals(daily().get(2000).date.substring(0,10)))throw new AssertionError("must request preceding day");if(cursors.size()==2)return daily().subList(0,2000);return Collections.emptyList();},null);
        assertEquals(2600,result.size());assertEquals(3,cursors.size());assertEquals(LocalDate.parse(daily().get(2000).date).minusDays(1).toString(),cursors.get(1));assertEquals(LocalDate.parse(result.get(0).date).minusDays(1).toString(),cursors.get(2));
        try{TencentClient.downloadFull("2026-09-30",end->daily(),null);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("未向前分页"));}
        try{TencentClient.downloadFull("2026-09-30",end->{if(end.equals("2026-09-30"))return daily().subList(2000,2600);throw new IOException("断网");},null);fail();}catch(IOException expected){assertEquals("断网",expected.getMessage());}
    }
    @Test public void fullCacheUpgradeIsAtomicAndOtherModulesCannotTruncateIt()throws Exception{
        Context c=RuntimeEnvironment.getApplication();DailyHistoryCache cache=new DailyHistoryCache(c);TencentClient.Quote q=Feed.quote("002383");assertEquals(120,cache.load(q,q.today.date).size());assertFalse(cache.complete("002383"));failFull=true;
        try{cache.loadFull(q.code,q.today.date,q,null);fail();}catch(IOException expected){}assertEquals(120,cache.cached(q.code).size());assertFalse(cache.complete(q.code));failFull=false;
        assertEquals(2600,cache.loadFull(q.code,q.today.date,q,null).size());assertTrue(cache.complete(q.code));assertEquals(2600,cache.load(q,q.today.date).size());assertEquals(2600,cache.loadFull(q.code,q.today.date,q,null).size());assertEquals(2,fullCalls);
        TencentClient.Quote next=new TencentClient.Quote(q.code,q.name,new DailyBar("2026-10-01",11,12,10,11,200),11,0);assertEquals(2601,cache.load(next,"2026-10-01").size());assertTrue(cache.complete(q.code));assertEquals(2,fullCalls);
    }
    @Test public void fiveMinuteLiveFramesExactlyMatchOriginalWebAlgorithmAndKeepAllHistory()throws Exception{
        List<DailyBar> rows=TencentClient.parseSinaFive(fixture("minute5_002383_sina.json"));assertEquals(1970,rows.size());assertEquals("2026-09-30 15:00",rows.get(1969).date);
        List<MinuteBehavior.Zone> actual=MinuteBehavior.find(rows,MinuteBehavior.epoch("2026-09-30 15:00"),5);JSONArray expected=new JSONArray(fixture("minute5_002383_web_frames.json"));assertEquals(98,actual.size());assertEquals(expected.length(),actual.size());
        for(int i=0;i<actual.size();i++){MinuteBehavior.Zone z=actual.get(i);JSONObject f=expected.getJSONObject(i);assertEquals(f.getInt("startIndex"),z.start);assertEquals(f.getInt("endIndex"),z.end);assertEquals(f.getDouble("low"),z.low,1e-8);assertEquals(f.getDouble("high"),z.high,1e-8);assertEquals(f.getBoolean("contained"),z.contained);int dir=f.getInt("direction");assertEquals(dir==0?MinuteBehavior.VOLUME:dir>0?MinuteBehavior.ATTACK:MinuteBehavior.REDUCE,z.kind);}
        Context c=RuntimeEnvironment.getApplication();MinuteCache.save(c,"002383",5,rows);MinuteCache.save(c,"002383",5,rows.subList(1900,1970));assertEquals(1970,MinuteCache.load(c,"002383",5).size());assertTrue(MinuteCache.load(c,"002383",15).isEmpty());MinuteCache.save(c,"002383",15,TradingFeaturesTest.behaviorBars());assertEquals(335,MinuteCache.load(c,"002383",15).size());assertEquals(1970,MinuteCache.load(c,"002383",5).size());
        assertTrue(MinuteBehavior.rules(5).contains("共960根"));assertFalse(MinuteBehavior.rules(5).contains("共320根"));
        String raw="{\"rc\":0,\"data\":{\"code\":\"002383\",\"klines\":[\"2026-09-30 09:35,10,11,12,9,100\",\"2026-09-30 09:36,10,11,12,9,100\"]}}";assertEquals(10000,TencentClient.parseFiveMinutes(raw,"002383").get(0).volume,0);
    }
    private View root(Activity a){return ((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);}
    private StockChartView chart(View v){if(v instanceof StockChartView)return (StockChartView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){StockChartView c=chart(((ViewGroup)v).getChildAt(i));if(c!=null)return c;}return null;}
    private void await(StockChartActivity a,StockChartView c)throws Exception{java.lang.reflect.Field loading=StockChartActivity.class.getDeclaredField("loading");loading.setAccessible(true);long end=System.nanoTime()+5_000_000_000L;while(System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();if(!loading.getBoolean(a)&&c.viewport()[0]>0)return;Thread.sleep(10);}fail("chart not ready");}
    private void capture(Activity a,String file,int width,int height)throws Exception{View v=root(a);int w=Ui.dp(a,width),h=Ui.dp(a,height);for(int i=0;i<2;i++){v.forceLayout();v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);}View plot=chart(v);if(plot==null)plot=v.findViewWithTag("board-candles");View detail=v.findViewWithTag("bar-detail");if(detail==null)detail=v.findViewWithTag("board-bar-detail");Rect p=new Rect(0,0,plot.getWidth(),plot.getHeight()),d=new Rect(0,0,detail.getWidth(),detail.getHeight());((ViewGroup)v).offsetDescendantRectToMyCoords(plot,p);((ViewGroup)v).offsetDescendantRectToMyCoords(detail,d);assertTrue("selected data sits above candles",d.bottom<=p.top);((ScrollView)v.findViewWithTag("chart-scroll")).scrollTo(0,Math.max(0,d.top-Ui.dp(a,70)));Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(System.getProperty("aitrader.ui.outputs"),file))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();}
    @Test public void stockPeriodsKeepIndependentWindowsAndDataAbovePlotInBothOrientations()throws Exception{
        Intent intent=new Intent().putExtra(StockChartActivity.EXTRA_CODE,"002383").putExtra(StockChartActivity.EXTRA_NAME,"合众思壮");ActivityController<StockChartActivity> controller=Robolectric.buildActivity(StockChartActivity.class,intent).create().start().resume().visible();
        try{StockChartActivity a=controller.get();StockChartView c=chart(root(a));await(a,c);assertEquals(2600,c.viewport()[0]);root(a).findViewWithTag("chart-period:5").performClick();await(a,c);assertEquals(1970,c.viewport()[0]);assertEquals(98,c.behaviorZones().size());c.focus(c.behaviorZones().get(97).end);int[] five=c.viewport();capture(a,"apk-v1.32-stock-five.png",390,844);
            root(a).findViewWithTag("chart-period:7").performClick();await(a,c);assertEquals(ChartPeriod.aggregate(daily(),7).bars.size(),c.viewport()[0]);assertTrue(c.detail(c.viewport()[0]-1).contains("周线"));root(a).findViewWithTag("daily-range:240").performClick();int[] week=c.viewport();capture(a,"apk-v1.32-stock-week.png",390,844);
            root(a).findViewWithTag("chart-period:30").performClick();await(a,c);assertEquals(ChartPeriod.aggregate(daily(),30).bars.size(),c.viewport()[0]);capture(a,"apk-v1.32-stock-month.png",320,720);
            root(a).findViewWithTag("chart-period:7").performClick();await(a,c);assertArrayEquals(week,c.viewport());root(a).findViewWithTag("chart-period:5").performClick();await(a,c);assertArrayEquals(five,c.viewport());
            Bundle state=new Bundle();controller.pause().stop().saveInstanceState(state).destroy();RuntimeEnvironment.setQualifiers("w844dp-h390dp-land-xxhdpi");state.putBoolean("chart_landscape",true);controller=Robolectric.buildActivity(StockChartActivity.class,intent).create(state).start().resume().visible();a=controller.get();c=chart(root(a));await(a,c);assertArrayEquals(five,c.viewport());capture(a,"apk-v1.32-stock-five-landscape.png",844,390);
        }finally{if(!controller.get().isDestroyed())controller.pause().stop().destroy();RuntimeEnvironment.setQualifiers("w390dp-h844dp-xxhdpi");}
    }
    @Test public void industryAndConceptArchivesExceed240BarsAndExposeWeeklyMonthlyCharts()throws Exception{
        for(boolean concept:new boolean[]{false,true}){String name=concept?"重组蛋白":"房地产";String indexName=concept?"concept_kline_index.json":"board_kline_index.json";Context app=RuntimeEnvironment.getApplication();String index;try(InputStream in=app.getAssets().open(indexName)){index=new String(in.readAllBytes(),StandardCharsets.UTF_8);}String code=new JSONObject(index).getJSONObject("boards").getString(name);assertTrue(BoardHistoryClient.cached(app,code,concept).getJSONArray("data").length()>1000);
            Intent intent=new Intent().putExtra(BoardChartActivity.EXTRA_INDUSTRY,name).putExtra(BoardChartActivity.EXTRA_TYPE,concept?"concept":"industry");ActivityController<BoardChartActivity> controller=Robolectric.buildActivity(BoardChartActivity.class,intent).create().start().resume().visible();try{BoardChartActivity a=controller.get();Shadows.shadowOf(Looper.getMainLooper()).idle();root(a).findViewWithTag("board-period:7").performClick();capture(a,"apk-v1.32-"+(concept?"concept":"industry")+"-week.png",390,844);root(a).findViewWithTag("board-period:30").performClick();capture(a,"apk-v1.32-"+(concept?"concept":"industry")+"-month.png",390,844);
                Bundle saved=new Bundle();controller.pause().stop().saveInstanceState(saved).destroy();RuntimeEnvironment.setQualifiers("w844dp-h390dp-land-xxhdpi");saved.putBoolean("chart_landscape",true);controller=Robolectric.buildActivity(BoardChartActivity.class,intent).create(saved).start().resume().visible();capture(controller.get(),"apk-v1.32-"+(concept?"concept":"industry")+"-month-landscape.png",844,390);
            }finally{if(!controller.get().isDestroyed())controller.pause().stop().destroy();RuntimeEnvironment.setQualifiers("w390dp-h844dp-xxhdpi");}}
    }
}
