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
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="w390dp-h844dp-xxhdpi",shadows=TradingFeaturesTest.FakeBoard.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BoardMetricsTest {
    @Test public void conceptCandlesAndMetricsUseConceptSnapshots()throws Exception {
        ActivityController<BoardChartActivity> controller=Robolectric.buildActivity(BoardChartActivity.class,new Intent().putExtra(BoardChartActivity.EXTRA_INDUSTRY,"重组蛋白").putExtra(BoardChartActivity.EXTRA_TYPE,"concept")).create().start().resume().visible();
        try{BoardChartActivity a=controller.get();View root=((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);draw(root,a,"apk-v1.22-concept-board.png",390,844);
            assertNotNull(named(root,"概念板块"));assertNotNull(named(root,"重组蛋白"));assertNotNull(named(root,"强度图"));assertNotNull(named(root,"广度图"));
            IndustryMetricChart strength=(IndustryMetricChart)description(root,"行业相对强度历史图"),breadth=(IndustryMetricChart)description(root,"行业上涨广度历史图");assertNotNull(strength);assertNotNull(breadth);
            JSONObject expected=SectorHistoryStore.load(a,"重组蛋白",true);JSONArray rows=expected.getJSONArray("data");JSONObject last=rows.getJSONObject(rows.length()-1);assertEquals(last.getString("date"),strength.dateAt(strength.endVisible()-1));assertEquals(last.getDouble("relative_5d"),strength.value(strength.endVisible()-1,0),1e-8);assertEquals(last.getDouble("above_ma20_pct"),breadth.value(breadth.endVisible()-1,1),1e-8);
            named(root,"30日").performClick();assertEquals(30,strength.endVisible()-strength.firstVisible());assertEquals(strength.firstVisible(),breadth.firstVisible());
            ScrollView scroll=(ScrollView)root.findViewWithTag("chart-scroll");View card=(View)strength.getParent();scroll.scrollTo(0,Math.max(0,card.getTop()-Ui.dp(a,60)));draw(root,a,"apk-v1.22-concept-metrics.png",390,844);
        }finally{controller.pause().stop().destroy();}
    }
    private View description(View v,String t){if(t.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View r=description(((ViewGroup)v).getChildAt(i),t);if(r!=null)return r;}return null;}
    private View named(View v,String t){if(v instanceof TextView&&t.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View r=named(((ViewGroup)v).getChildAt(i),t);if(r!=null)return r;}return null;}
    private void draw(View v,Context c,String name,int width,int height)throws Exception {int w=Ui.dp(c,width),h=Ui.dp(c,height);for(int i=0;i<2;i++){v.forceLayout();v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);}Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));try(FileOutputStream out=new FileOutputStream(new File(System.getProperty("aitrader.ui.outputs"),name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
    private void touch(View v,int action,float x){MotionEvent e=MotionEvent.obtain(0,10,action,x,Ui.dp(v.getContext(),100),0);v.onTouchEvent(e);e.recycle();}
    @Test public void chartsMatchWebAndShareViewportAndSelection()throws Exception {
        ActivityController<BoardChartActivity> controller=Robolectric.buildActivity(BoardChartActivity.class,new Intent().putExtra(BoardChartActivity.EXTRA_INDUSTRY,"房地产")).create().start().resume().visible();
        try{BoardChartActivity a=controller.get();View root=((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);draw(root,a,"apk-v1.16-board.png",390,844);
            IndustryMetricChart strength=(IndustryMetricChart)description(root,"行业相对强度历史图"),breadth=(IndustryMetricChart)description(root,"行业上涨广度历史图");assertNotNull(strength);assertNotNull(breadth);assertNotNull(named(root,"强度图"));assertNotNull(named(root,"广度图"));
            JSONArray expected=SectorHistoryStore.load(a,"房地产").getJSONArray("data");JSONObject last=null;String candleDate=strength.dateAt(strength.endVisible()-1);for(int i=0;i<expected.length();i++)if(candleDate.equals(expected.getJSONObject(i).getString("date")))last=expected.getJSONObject(i);assertNotNull(last);assertEquals(last.getDouble("relative_5d"),strength.value(strength.endVisible()-1,0),1e-9);assertEquals(last.getDouble("above_ma20_pct"),breadth.value(breadth.endVisible()-1,1),1e-9);
            named(root,"30日").performClick();assertEquals(30,strength.endVisible()-strength.firstVisible());assertEquals(strength.firstVisible(),breadth.firstVisible());assertEquals(strength.selectedIndex(),breadth.selectedIndex());
            int end=strength.endVisible();touch(strength,MotionEvent.ACTION_DOWN,Ui.dp(a,70));touch(strength,MotionEvent.ACTION_MOVE,Ui.dp(a,170));touch(strength,MotionEvent.ACTION_UP,Ui.dp(a,170));assertTrue(strength.endVisible()<end);assertEquals(strength.endVisible(),breadth.endVisible());
            touch(breadth,MotionEvent.ACTION_DOWN,Ui.dp(a,80));touch(breadth,MotionEvent.ACTION_UP,Ui.dp(a,80));assertEquals(strength.selectedIndex(),breadth.selectedIndex());assertTrue(strength.selectedIndex()<strength.endVisible());
            named(root,"60日").performClick();draw(strength,a,"apk-v1.16-strength.png",358,218);draw(breadth,a,"apk-v1.16-breadth.png",358,218);
            ScrollView scroll=(ScrollView)root.findViewWithTag("chart-scroll");View card=(View)strength.getParent();scroll.scrollTo(0,Math.max(0,card.getTop()-Ui.dp(a,100)));draw(root,a,"apk-v1.16-board-metrics.png",390,844);
            SectorHistoryStore.save(a,Collections.singletonMap("房地产",Collections.singletonList(new SectorHistoryRule.Row(last.getString("date"),3.5,5.5,70,80,10,10,10,20,100,100,200))));Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(3.5,strength.value(strength.endVisible()-1,0),0);assertEquals(80,breadth.value(breadth.endVisible()-1,1),0);assertFalse("old snapshot is not carried into missing phone dates",Double.isFinite(strength.value(strength.endVisible()-2,0)));
        }finally{controller.pause().stop().destroy();}
    }
}
