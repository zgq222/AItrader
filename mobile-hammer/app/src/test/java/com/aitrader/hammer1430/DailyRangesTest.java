package com.aitrader.hammer1430;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="w390dp-h844dp-xxhdpi",shadows=DailyRangesTest.Feed.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DailyRangesTest {
    private static final LocalDate LAST=LocalDate.of(2026,9,30);
    private static final Queue<Integer> requests=new ConcurrentLinkedQueue<>();
    @Implements(value=TencentClient.class,isInAndroidSdk=false)
    public static class Feed {
        @Implementation protected static List<DailyBar> history(String code,String date){return history(code,date,120);}
        @Implementation protected static List<DailyBar> history(String code,String date,int count){requests.add(count);return daily(code.equals("600036")?Math.min(40,count):count);}
        @Implementation protected static List<DailyBar> fullHistory(String code,String date,TencentClient.Progress progress){requests.add(-1);return daily(code.equals("600036")?40:2500);}
        @Implementation protected static List<DailyBar> minutes(String code,int interval){return minutes(code);}
        @Implementation protected static TencentClient.Quote quote(String code){DailyBar b=daily(1).get(0);return new TencentClient.Quote(code,"测试股票",b,10,MinuteBehavior.epoch(b.date+" 15:00")/1000);}
        @Implementation protected static List<DailyBar> minutes(String code){return TradingFeaturesTest.behaviorBars();}
    }
    private static List<DailyBar> daily(int count){List<DailyBar> out=new ArrayList<>();LocalDate day=LAST;while(out.size()<count){if(day.getDayOfWeek()!=DayOfWeek.SATURDAY&&day.getDayOfWeek()!=DayOfWeek.SUNDAY){double price=10+Math.sin(out.size()*.13);out.add(0,new DailyBar(day.toString(),price,price+.3,price-.3,price+.1,100+out.size()));}day=day.minusDays(1);}return out;}
    @Before public void reset(){requests.clear();Context c=RuntimeEnvironment.getApplication();for(String code:new String[]{"600519","600036"})new File(c.getFilesDir(),"raw_daily_v1/"+code+".json").delete();c.getSharedPreferences("scan",0).edit().clear().commit();}
    private View root(Activity a){return ((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);}
    private View named(View v,String name){if(v instanceof TextView&&name.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=named(((ViewGroup)v).getChildAt(i),name);if(found!=null)return found;}return null;}
    private StockChartView chart(View v){if(v instanceof StockChartView)return (StockChartView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){StockChartView found=chart(((ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null;}
    private void await(StockChartView c,boolean minute)throws Exception{java.lang.reflect.Field loading=StockChartActivity.class.getDeclaredField("loading");loading.setAccessible(true);long until=System.nanoTime()+5_000_000_000L;while(System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();if(!loading.getBoolean(c.getContext())&&c.viewport()[0]>0&&c.detail(0).contains(minute?"15分钟K线":daily(2500).get(0).date))return;Thread.sleep(10);}fail("chart not ready");}
    private void capture(Activity a,String filename,int width,int height)throws Exception{View v=root(a);int w=Ui.dp(a,width),h=Ui.dp(a,height);for(int i=0;i<2;i++){v.forceLayout();v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);}ScrollView scroll=v.findViewWithTag("chart-scroll");View card=(View)chart(v).getParent();scroll.scrollTo(0,card.getTop());Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(System.getProperty("aitrader.ui.outputs"),filename))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();for(int count:new int[]{30,60,120,240}){View button=v.findViewWithTag("daily-range:"+count);assertTrue("visible range button "+count,button.getGlobalVisibleRect(new Rect()));assertTrue(button.getWidth()>=Ui.dp(a,44));}}

    @Test public void dailyPresetsRefreshPeriodSwitchAndLandscapeKeepTheWindow()throws Exception{
        Intent intent=new Intent().putExtra(StockChartActivity.EXTRA_CODE,"600519").putExtra(StockChartActivity.EXTRA_NAME,"测试股票");ActivityController<StockChartActivity> controller=Robolectric.buildActivity(StockChartActivity.class,intent).create().start().resume().visible();
        try{StockChartActivity a=controller.get();StockChartView c=chart(root(a));await(c,false);int total=c.viewport()[0];assertTrue("two years exceed 240 sessions",total>480);assertEquals(60,c.viewport()[1]);assertEquals("full-history source is used",Integer.valueOf(-1),requests.peek());assertEquals(2500,total);assertTrue(c.detail(0).contains(daily(2500).get(0).date));assertTrue(c.detail(total-1).contains("2026-09-30"));
            for(int count:new int[]{30,60,120,240}){root(a).findViewWithTag("daily-range:"+count).performClick();assertEquals(count,c.viewport()[1]);assertEquals(total,c.viewport()[0]);assertEquals(total-1,c.viewport()[2]);}
            named(root(a),"RSI14").performClick();c.restoreViewport(new int[]{total-12,240,total-30,2});int[] dailyBefore=c.viewport();a.refreshData();await(c,false);assertArrayEquals(dailyBefore,c.viewport());capture(a,"apk-v1.29-daily-240.png",390,844);a.getResources().getConfiguration().fontScale=1.3f;capture(a,"apk-v1.29-daily-small.png",320,720);a.getResources().getConfiguration().fontScale=1f;
            named(root(a),"15分钟K线").performClick();await(c,true);assertEquals(View.GONE,root(a).findViewWithTag("daily-ranges").getVisibility());assertEquals(50,c.viewport()[1]);c.focus(TradingFeaturesTest.ATTACK_INDEX);int[] minuteBefore=c.viewport();named(root(a),"日K").performClick();await(c,false);assertArrayEquals(dailyBefore,c.viewport());assertEquals(View.VISIBLE,root(a).findViewWithTag("daily-ranges").getVisibility());named(root(a),"15分钟K线").performClick();await(c,true);assertArrayEquals(minuteBefore,c.viewport());named(root(a),"日K").performClick();await(c,false);
            named(root(a),"横屏").performClick();Bundle saved=new Bundle();controller.pause().stop().saveInstanceState(saved).destroy();RuntimeEnvironment.setQualifiers("w844dp-h390dp-land-xxhdpi");controller=Robolectric.buildActivity(StockChartActivity.class,intent).create(saved).start().resume().visible();a=controller.get();c=chart(root(a));await(c,false);assertArrayEquals(dailyBefore,c.viewport());assertEquals(240,saved.getInt("daily_range"));capture(a,"apk-v1.29-daily-landscape.png",844,390);
        }finally{if(!controller.get().isDestroyed())controller.pause().stop().destroy();RuntimeEnvironment.setQualifiers("w390dp-h844dp-xxhdpi");}
    }

    @Test public void oldCacheExpandsAndRetainsLongHistoryOnNextSession()throws Exception{
        Context c=RuntimeEnvironment.getApplication();DailyHistoryCache cache=new DailyHistoryCache(c);TencentClient.Quote quote=Feed.quote("600519");assertEquals(120,cache.load(quote,LAST.toString()).size());
        File file=new File(c.getFilesDir(),"raw_daily_v1/600519.json");JSONObject old=new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));old.remove("history_count");java.nio.file.Files.write(file.toPath(),old.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(750,cache.load(quote,LAST.toString(),750).size());assertEquals(750,cache.load(quote,LAST.toString(),750).size());assertEquals(750,cache.load(quote,LAST.toString()).size());assertEquals(Arrays.asList(120,750),new ArrayList<>(requests));
        cache.load(quote,LAST.plusDays(1).toString());assertEquals(Arrays.asList(120,750,750),new ArrayList<>(requests));
        requests.clear();TencentClient.Quote shortQuote=Feed.quote("600036");assertEquals(40,cache.load(shortQuote,LAST.toString(),750).size());assertEquals(40,cache.load(shortQuote,LAST.toString(),750).size());assertEquals(Collections.singletonList(750),new ArrayList<>(requests));StockChartView chart=new StockChartView(c);chart.setBars(cache.cached("600036"));chart.setVisibleCount(240);assertEquals(40,chart.viewport()[1]);assertEquals(40,chart.viewport()[0]);assertEquals(39,chart.viewport()[2]);
    }
}
