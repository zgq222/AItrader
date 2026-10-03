package com.aitrader.hammer1430;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import java.io.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="w390dp-h844dp-xxhdpi",shadows=TradingFeaturesTest.FakeTencent.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LandscapeTest {
    private View root(Activity a){return ((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);}
    private View tagged(View v,String tag){if(tag.equals(v.getTag()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=tagged(((ViewGroup)v).getChildAt(i),tag);if(found!=null)return found;}return null;}
    private View named(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=named(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}return null;}
    private StockChartView chart(View v){if(v instanceof StockChartView)return (StockChartView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){StockChartView found=chart(((ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null;}
    private void await(StockChartView c,boolean minute)throws Exception {long until=System.nanoTime()+5_000_000_000L;while(System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();if(c.detail(0).contains(minute?"15分钟K线":"2026-01-01"))return;Thread.sleep(10);}fail("chart not ready");}
    private void capture(Activity a,String name,int width,int height)throws Exception {View v=root(a);int w=Ui.dp(a,width),h=Ui.dp(a,height);for(int i=0;i<2;i++){v.forceLayout();v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);}Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));try(FileOutputStream out=new FileOutputStream(new File(System.getProperty("aitrader.ui.outputs"),name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
    private void verifyPinnedControls(Activity a,String name)throws Exception{
        View toolbar=tagged(root(a),"chart-orientation:toolbar"),plot=tagged(root(a),"chart-orientation:plot");assertNotNull(toolbar);assertNotNull(plot);assertEquals("横屏查看",((TextView)plot).getText().toString());
        int[] before=new int[2],after=new int[2];toolbar.getLocationOnScreen(before);ScrollView scroll=(ScrollView)tagged(root(a),"chart-scroll");scroll.scrollTo(0,((View)plot.getParent()).getTop());capture(a,"apk-v1.28-"+name+"-plot.png",390,844);toolbar.getLocationOnScreen(after);assertArrayEquals("orientation button stays visible while scrolling",before,after);
        android.graphics.Rect visible=new android.graphics.Rect();assertTrue("plot-level button is also visible beside the chart",plot.getGlobalVisibleRect(visible));assertTrue(toolbar.getGlobalVisibleRect(visible));
        a.getResources().getConfiguration().fontScale=1.3f;capture(a,"apk-v1.28-"+name+"-plot-small.png",320,720);assertTrue(toolbar.getGlobalVisibleRect(visible));assertTrue(plot.getGlobalVisibleRect(visible));a.getResources().getConfiguration().fontScale=1f;
    }
    @Before public void reset(){TradingFeaturesTest.failMinutes=false;TradingFeaturesTest.entered=null;TradingFeaturesTest.release=null;RuntimeEnvironment.getApplication().getSharedPreferences("scan",0).edit().clear().commit();}
    @Test public void stockRotationPreservesMinuteWindowSelectionAndIndicator()throws Exception {
        Intent intent=new Intent().putExtra(StockChartActivity.EXTRA_CODE,"600519").putExtra(StockChartActivity.EXTRA_NAME,"测试股票");ActivityController<StockChartActivity> controller=Robolectric.buildActivity(StockChartActivity.class,intent).create().start().resume().visible();
        try{StockChartActivity a=controller.get();StockChartView c=chart(root(a));await(c,false);assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,a.getRequestedOrientation());assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,a.getPackageManager().getActivityInfo(a.getComponentName(),0).screenOrientation);assertNotNull(named(root(a),"横屏"));capture(a,"apk-v1.27-stock-portrait.png",390,844);
            named(root(a),"15分钟K线").performClick();await(c,true);c.focus(TradingFeaturesTest.ATTACK_INDEX);c.setIndicator(2);int[] before=c.viewport();verifyPinnedControls(a,"stock");named(root(a),"横屏查看").performClick();assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,a.getRequestedOrientation());assertNotNull(named(root(a),"竖屏"));Bundle saved=new Bundle();controller.pause().stop().saveInstanceState(saved).destroy();
            RuntimeEnvironment.setQualifiers("w844dp-h390dp-land-xxhdpi");controller=Robolectric.buildActivity(StockChartActivity.class,intent).create(saved).start().resume().visible();a=controller.get();c=chart(root(a));await(c,true);assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,a.getRequestedOrientation());assertNotNull(named(root(a),"竖屏"));assertArrayEquals(before,c.viewport());assertEquals(2,c.behaviorZones().size());capture(a,"apk-v1.27-stock-landscape.png",844,390);assertTrue(c.getWidth()>Ui.dp(a,700));assertTrue(c.getHeight()>=Ui.dp(a,210));assertNotNull(named(root(a),"15分钟K线"));named(root(a),"日K").performClick();await(c,false);capture(a,"apk-v1.27-daily-landscape.png",844,390);
            int[] dailyBefore=c.viewport();named(root(a),"竖屏").performClick();assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,a.getRequestedOrientation());saved=new Bundle();controller.pause().stop().saveInstanceState(saved).destroy();RuntimeEnvironment.setQualifiers("w390dp-h844dp-xxhdpi");controller=Robolectric.buildActivity(StockChartActivity.class,intent).create(saved).start().resume().visible();a=controller.get();c=chart(root(a));await(c,false);assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,a.getRequestedOrientation());assertArrayEquals(dailyBefore,c.viewport());assertNotNull(named(root(a),"横屏"));capture(a,"apk-v1.27-stock-returned-portrait.png",390,844);
            controller.pause().stop().destroy();RuntimeEnvironment.setQualifiers("w844dp-h390dp-land-xxhdpi");controller=Robolectric.buildActivity(StockChartActivity.class,intent).create().start().resume().visible();a=controller.get();assertEquals("opening while the phone is sideways must still request portrait",ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,a.getRequestedOrientation());assertNotNull(named(root(a),"横屏"));assertNotNull(named(root(a),"股票详情"));assertNull(named(root(a),"竖屏"));
        }finally{if(!controller.get().isDestroyed())controller.pause().stop().destroy();RuntimeEnvironment.setQualifiers("w390dp-h844dp-xxhdpi");}
    }
    @Test public void bothBoardTypesKeepTheirWindowAcrossLandscape()throws Exception {
        for(String type:new String[]{"industry","concept"}){RuntimeEnvironment.setQualifiers("w390dp-h844dp-xxhdpi");Intent intent=new Intent().putExtra(BoardChartActivity.EXTRA_INDUSTRY,type.equals("concept")?"重组蛋白":"房地产").putExtra(BoardChartActivity.EXTRA_TYPE,type);ActivityController<BoardChartActivity> controller=Robolectric.buildActivity(BoardChartActivity.class,intent).create().start().resume().visible();
            try{BoardChartActivity a=controller.get();assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,a.getRequestedOrientation());assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,a.getPackageManager().getActivityInfo(a.getComponentName(),0).screenOrientation);assertNotNull(named(root(a),"横屏"));capture(a,"apk-v1.27-"+type+"-portrait.png",390,844);
                named(root(a),"30日").performClick();verifyPinnedControls(a,type);named(root(a),"横屏查看").performClick();assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,a.getRequestedOrientation());Bundle saved=new Bundle();controller.pause().stop().saveInstanceState(saved).destroy();RuntimeEnvironment.setQualifiers("w844dp-h390dp-land-xxhdpi");controller=Robolectric.buildActivity(BoardChartActivity.class,intent).create(saved).start().resume().visible();a=controller.get();capture(a,"apk-v1.27-"+type+"-landscape.png",844,390);assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,a.getRequestedOrientation());assertEquals(30,saved.getInt("chart_count"));assertNotNull(named(root(a),"竖屏"));assertNotNull(named(root(a),"强度图 / 广度图"));Bundle after=new Bundle();controller.saveInstanceState(after);assertEquals(saved.getInt("chart_end"),after.getInt("chart_end"));assertEquals(30,after.getInt("chart_count"));assertEquals(saved.getInt("chart_selected"),after.getInt("chart_selected"));
                named(root(a),"竖屏").performClick();assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,a.getRequestedOrientation());after=new Bundle();controller.pause().stop().saveInstanceState(after).destroy();RuntimeEnvironment.setQualifiers("w390dp-h844dp-xxhdpi");controller=Robolectric.buildActivity(BoardChartActivity.class,intent).create(after).start().resume().visible();a=controller.get();Bundle returned=new Bundle();controller.saveInstanceState(returned);assertEquals(saved.getInt("chart_end"),returned.getInt("chart_end"));assertEquals(30,returned.getInt("chart_count"));assertEquals(saved.getInt("chart_selected"),returned.getInt("chart_selected"));assertNotNull(named(root(a),"横屏"));assertNull(named(root(a),"强度图 / 广度图"));
                controller.pause().stop().destroy();RuntimeEnvironment.setQualifiers("w844dp-h390dp-land-xxhdpi");controller=Robolectric.buildActivity(BoardChartActivity.class,intent).create().start().resume().visible();a=controller.get();assertEquals("fresh board screen stays portrait even when the phone is sideways",ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,a.getRequestedOrientation());assertNotNull(named(root(a),"横屏"));assertNull(named(root(a),"强度图 / 广度图"));
            }finally{if(!controller.get().isDestroyed())controller.pause().stop().destroy();RuntimeEnvironment.setQualifiers("w390dp-h844dp-xxhdpi");}
        }
    }
}
