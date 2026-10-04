package com.aitrader.hammer1430;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Real native View rendering with Skia; test snapshots never ship in the APK. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="w390dp-h844dp-xxhdpi",shadows=TradingFeaturesTest.FakeBoard.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class UiRenderTest {
    private JSONObject fixture;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    @Before public void prepare()throws Exception{
        try(InputStream input=getClass().getResourceAsStream("/ui_preview.json")){
            fixture=new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));
        }
        Context app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("scan",Context.MODE_PRIVATE).edit().clear()
                .putString("market_payload",fixture.getJSONObject("market").toString())
                .putString("rotation_payload",fixture.getJSONObject("rotation").toString())
                .putString("selection_payload",fixture.getJSONObject("selection").toString())
                .putString("status","行情已更新 · 09-30收盘").putLong("updated",1790916000000L).commit();
        app.getSharedPreferences("watchlist",Context.MODE_PRIVATE).edit().clear().commit();
        WatchlistStore.importBundled(app);app.getSharedPreferences("watchlist",0).edit().remove("stocks").commit();
        app.getSharedPreferences("holdings",Context.MODE_PRIVATE).edit().clear().commit();
        controller=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();activity=controller.get();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    @After public void close(){controller.pause().stop().destroy();}
    private View appRoot(){return ((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);}
    private View description(View view,String description){
        if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View match=description(((ViewGroup)view).getChildAt(i),description);if(match!=null)return match;}
        return null;
    }
    private View named(View view,String text){
        if(view.getVisibility()==View.GONE)return null;
        if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View match=named(((ViewGroup)view).getChildAt(i),text);if(match!=null)return match;}
        return null;
    }
    private EditText findInput(View view){if(view instanceof EditText)return (EditText)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){EditText e=findInput(((ViewGroup)view).getChildAt(i));if(e!=null)return e;}return null;}
    private void tab(String name){View tab=description(appRoot(),name);assertNotNull(tab);tab.performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void layout(int width,int height){layout(appRoot(),width,height);}
    private void layout(View root,int width,int height){int w=Ui.dp(activity,width),h=Ui.dp(activity,height);
        for(int frame=0;frame<2;frame++){root.forceLayout();root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);}}
    private void capture(String filename,int width,int height)throws Exception{
        capture(appRoot(),filename,width,height);
    }
    private void capture(View root,String filename,int width,int height)throws Exception{
        layout(root,width,height);Bitmap image=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(image));
        File target=new File(System.getProperty("aitrader.ui.outputs"),filename);target.getParentFile().mkdirs();try(FileOutputStream output=new FileOutputStream(target)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));}image.recycle();
    }
    @Test public void marketAndRotationSnapshots()throws Exception{
        assertNotNull(named(appRoot(),"3842.195"));assertNotNull(named(appRoot(),"2/3仓"));assertNotNull(named(appRoot(),"第一层"));assertNotNull(named(appRoot(),"第二层"));
        capture("apk-v1.16-market.png",390,844);
        View nav=description(appRoot(),"板块轮动");int[] before=new int[2];nav.getLocationOnScreen(before);
        ScrollView scroll=(ScrollView)((ViewGroup)((ViewGroup)appRoot()).getChildAt(1)).getChildAt(0);scroll.scrollTo(0,Ui.dp(activity,180));capture("apk-v1.16-flight.png",390,844);int[] after=new int[2];nav.getLocationOnScreen(after);assertArrayEquals("bottom navigation stays fixed",before,after);
        tab("板块轮动");capture("apk-v1.16-rotation.png",390,844);
        JSONObject first=fixture.getJSONObject("rotation").getJSONArray("industries").getJSONObject(0);
        View header=description(appRoot(),first.getString("name")+"，行业排名"+first.getInt("rank")+"，点击展开成分股");assertNotNull(header);header.performClick();capture("apk-v1.16-rotation-expanded.png",390,844);
        JSONArray stocks=fixture.getJSONObject("rotation").getJSONArray("stocks");JSONObject stock=null;for(int i=0;i<stocks.length();i++)if(stocks.getJSONObject(i).getString("industry").equals(first.getString("name"))){stock=stocks.getJSONObject(i);break;}
        assertNotNull(stock);assertNotNull(named(appRoot(),stock.getString("name")));assertNotNull(description(appRoot(),stock.getString("name")+"日K与15分钟K线"));
        TextView gain=(TextView)named(appRoot(),String.format(java.util.Locale.CHINA,"%+.2f%%",stock.getDouble("return_30d_pct")));assertNotNull(gain);assertTrue("stock return has a visible line",gain.getHeight()>=Ui.dp(activity,20));
        assertNotNull(gain.getLayout());assertTrue("right-aligned return is inside its visible bounds",gain.getLayout().getLineLeft(0)<gain.getWidth());
        View favorite=description(appRoot(),stock.getString("name")+"自选操作");assertNotNull(favorite);favorite.performClick();assertTrue(WatchlistStore.contains(activity,stock.getString("code")));
        tab("自选股");assertNotNull(named(appRoot(),stock.getString("name")));capture("apk-v1.16-watchlist.png",390,844);
        assertNull(description(appRoot(),"5分钟选股"));assertNull(description(appRoot(),"30日涨幅"));
        description(appRoot(),stock.getString("name")+"加入持仓").performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getButton(-1).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(HoldingsStore.find(activity,stock.getString("code")));tab("持仓股");assertNotNull(named(appRoot(),stock.getString("name")));
        description(appRoot(),stock.getString("name")+"设置止损线").performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();android.app.AlertDialog stop=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        EditText input=findInput(stop.getWindow().getDecorView());assertNotNull(input);input.setText("2.345");stop.getButton(-1).performClick();assertTrue(stop.isShowing());assertNotNull(input.getError());
        input.setText("2.50");stop.getButton(-1).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(2.5,HoldingsStore.find(activity,stock.getString("code")).getDouble("stop_price"),0);
        capture("apk-v1.16-holdings.png",390,844);
    }
    @Test public void compactRowsAndPinnedCollapse()throws Exception{
        tab("板块轮动");JSONObject first=fixture.getJSONObject("rotation").getJSONArray("industries").getJSONObject(0);String name=first.getString("name");
        View header=description(appRoot(),name+"，行业排名"+first.getInt("rank")+"，点击展开成分股");header.performClick();layout(390,844);
        LinearLayout card=(LinearLayout)header.getParent();ScrollView scroll=(ScrollView)((ViewGroup)((ViewGroup)appRoot()).getChildAt(1)).getChildAt(0);
        JSONObject stock=null;JSONArray stocks=fixture.getJSONObject("rotation").getJSONArray("stocks");for(int i=0;i<stocks.length();i++)if(stocks.getJSONObject(i).getString("industry").equals(name)){stock=stocks.getJSONObject(i);break;}assertNotNull(stock);
        LinearLayout row=(LinearLayout)appRoot().findViewWithTag("rotation-stock:"+stock.getString("code"));assertNotNull(row);assertTrue("compact stock height",row.getHeight()<=Ui.dp(activity,112));
        LinearLayout actions=(LinearLayout)row.findViewWithTag("stock-actions:"+stock.getString("code"));assertEquals(3,actions.getChildCount());for(int i=0;i<3;i++){assertTrue(actions.getChildAt(i) instanceof Button);assertEquals(actions.getChildAt(0).getTop(),actions.getChildAt(i).getTop());assertTrue(actions.getChildAt(i).getWidth()>Ui.dp(activity,44));}
        assertNotNull(named(row,stock.getString("code")));assertNull(named(row,stock.getString("code")+" · 板块内#1"));
        scroll.scrollTo(0,card.getTop()+header.getTop()+Ui.dp(activity,500));capture("apk-v1.16-rotation-sticky.png",390,844);
        View pin=description(appRoot(),"轮动板块悬浮标题");assertEquals(View.VISIBLE,pin.getVisibility());int[] p1=new int[2];pin.getLocationOnScreen(p1);
        scroll.scrollBy(0,Ui.dp(activity,240));layout(390,844);int[] p2=new int[2];pin.getLocationOnScreen(p2);assertArrayEquals("pinned sector stays visible while stocks scroll",p1,p2);
        View collapse=description(appRoot(),name+"悬浮收起");assertNotNull(collapse);collapse.performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();layout(390,844);Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.GONE,pin.getVisibility());assertNull(named(appRoot(),stock.getString("name")));int[] head=new int[2],viewport=new int[2];header.getLocationOnScreen(head);scroll.getLocationOnScreen(viewport);assertTrue("original expand header remains reachable",head[1]>=viewport[1]&&head[1]<viewport[1]+scroll.getHeight());
        header.performClick();layout(320,720);assertNotNull(named(appRoot(),stock.getString("name")));capture("apk-v1.16-rotation-small-expanded.png",320,720);
        scroll.scrollTo(0,card.getTop()+header.getTop()+Ui.dp(activity,100));layout(320,720);tab("市场总览");assertEquals(View.GONE,pin.getVisibility());
    }
    @Test public void compactPhoneAndLargeText()throws Exception{
        controller.pause().stop().destroy();RuntimeEnvironment.setFontScale(1.3f);
        controller=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();activity=controller.get();Shadows.shadowOf(Looper.getMainLooper()).idle();
        layout(320,720);capture("apk-v1.16-compact.png",320,720);
        TextView price=(TextView)named(appRoot(),"3842.195");assertNotNull(price);assertTrue("price fits compact width",price.getPaint().measureText(price.getText().toString())<=price.getWidth()-price.getPaddingLeft()-price.getPaddingRight()+1);
        assertTrue(description(appRoot(),"刷新大盘").getWidth()>=Ui.dp(activity,44));
        tab("板块轮动");capture("apk-v1.16-rotation-compact.png",320,720);assertNotNull(named(appRoot(),fixture.getJSONObject("rotation").getJSONArray("industries").getJSONObject(0).getString("name")));
        JSONObject first=fixture.getJSONObject("rotation").getJSONArray("industries").getJSONObject(0);description(appRoot(),first.getString("name")+"，行业排名"+first.getInt("rank")+"，点击展开成分股").performClick();capture("apk-v1.16-rotation-large-font.png",320,720);
        LinearLayout body=(LinearLayout)((LinearLayout)description(appRoot(),first.getString("name")+"，行业排名"+first.getInt("rank")+"，点击展开成分股").getParent()).getChildAt(3);LinearLayout stock=(LinearLayout)body.getChildAt(body.getChildCount()-1),actions=(LinearLayout)stock.getChildAt(2);assertEquals(3,actions.getChildCount());
        for(int i=0;i<3;i++){Button button=(Button)actions.getChildAt(i);assertTrue("all actions stay readable with large type",button.getPaint().measureText(button.getText().toString())<=button.getWidth()-button.getPaddingLeft()-button.getPaddingRight()+1);}
    }
    @Test public void conceptTagsAndCompactIdentityNavigateCorrectly()throws Exception {
        WatchlistStore.add(activity,"000513","丽珠集团","化学制药");tab("自选股");layout(390,844);
        LinearLayout row=(LinearLayout)appRoot().findViewWithTag("personal-stock:000513");assertNotNull(row);
        LinearLayout identity=(LinearLayout)row.findViewWithTag("stock-identity:000513");assertEquals(4,identity.getChildCount());
        for(int i=0;i<3;i++){assertEquals(identity.getChildAt(0).getTop(),identity.getChildAt(i).getTop());assertTrue(identity.getChildAt(i).getWidth()>0);}
        assertEquals("000513",((TextView)identity.getChildAt(1)).getText().toString());
        LinearLayout actions=(LinearLayout)row.findViewWithTag("stock-actions:000513");assertEquals(3,actions.getChildCount());assertEquals("15分钟/日K",((Button)actions.getChildAt(0)).getText().toString());
        HorizontalScrollView strip=(HorizontalScrollView)row.findViewWithTag("concept-tags:000513");assertNotNull(strip);LinearLayout tags=(LinearLayout)strip.getChildAt(0);
        ConceptCatalog catalog=new ConceptCatalog(activity);List<JSONObject> expected=catalog.tags("000513");assertEquals(expected.size(),tags.getChildCount());assertTrue(expected.size()>1);
        for(int i=0;i<tags.getChildCount();i++){assertEquals(catalog.tagText(expected.get(i)),((TextView)tags.getChildAt(i)).getText().toString());assertEquals(tags.getChildAt(0).getTop(),tags.getChildAt(i).getTop());}
        capture("apk-v1.22-concept-watchlist.png",390,844);RuntimeEnvironment.setFontScale(1.3f);capture("apk-v1.22-concept-compact.png",320,720);assertEquals(1,((TextView)identity.getChildAt(1)).getLineCount());assertEquals(1,((TextView)identity.getChildAt(2)).getLineCount());RuntimeEnvironment.setFontScale(1f);
        tags.getChildAt(0).performClick();Intent opened=Shadows.shadowOf(activity).getNextStartedActivity();assertEquals(BoardChartActivity.class.getName(),opened.getComponent().getClassName());assertEquals("concept",opened.getStringExtra(BoardChartActivity.EXTRA_TYPE));assertEquals(expected.get(0).getString("name"),opened.getStringExtra(BoardChartActivity.EXTRA_INDUSTRY));
        description(row,"丽珠集团加入持仓").performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getButton(-1).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();tab("持仓股");assertNotNull(appRoot().findViewWithTag("concept-tags:000513"));assertNotNull(description(appRoot(),"丽珠集团设置止损线"));capture("apk-v1.22-concept-holdings.png",390,844);
    }
    @Test public void rulesAndFailureKeepPrices()throws Exception{
        description(appRoot(),"查看规则与刷新说明").performClick();
        android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);assertTrue(dialog.isShowing());dialog.dismiss();
        activity.getSharedPreferences("scan",Context.MODE_PRIVATE).edit().putString("market_error","网络暂不可用；保留上次成功数据").commit();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(named(appRoot(),"3842.195"));assertNotNull(named(appRoot(),"行情更新未完成"));
    }
    private int stockColor(View row){return ((android.graphics.drawable.GradientDrawable)row.getBackground()).getColor().getDefaultColor();}
    @Test public void firstOpenImportsRealDesktopSnapshotIntoWatchlist()throws Exception {
        controller.pause().stop().destroy();RuntimeEnvironment.getApplication().getSharedPreferences("watchlist",0).edit().clear().commit();
        controller=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();activity=controller.get();Shadows.shadowOf(Looper.getMainLooper()).idle();tab("自选股");
        JSONArray incoming=WatchlistStore.bundledSnapshot(activity).getJSONArray("stocks");assertEquals(288,WatchlistStore.stocks(activity).size());
        for(int i=0;i<incoming.length();i++){JSONObject stock=incoming.getJSONObject(i);View row=appRoot().findViewWithTag("personal-stock:"+stock.getString("code"));assertNotNull(row);assertEquals(stock.getBoolean("starred")?Ui.PINK:Ui.STOCK_BG,stockColor(row));}
        capture("apk-v1.19-imported-watchlist.png",390,844);
        assertTrue(HoldingsStore.stocks(activity).isEmpty());
        String held=incoming.getJSONObject(0).getString("code");WatchlistStore.setStarred(activity,held,false);WatchlistStore.remove(activity,incoming.getJSONObject(1).getString("code"));String edited=WatchlistStore.payload(activity);
        controller.pause().stop().destroy();controller=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();activity=controller.get();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(edited,WatchlistStore.payload(activity));
    }
    @Test public void starredWatchlistOrderAndRotationColorsFollowLocalLists()throws Exception {
        JSONObject sector=fixture.getJSONObject("rotation").getJSONArray("industries").getJSONObject(0);
        List<JSONObject> members=new ArrayList<>();JSONArray all=fixture.getJSONObject("rotation").getJSONArray("stocks");
        for(int i=0;i<all.length();i++)if(sector.getString("name").equals(all.getJSONObject(i).getString("industry")))members.add(all.getJSONObject(i));assertTrue(members.size()>=4);
        JSONObject ordinary=members.get(0),priority=members.get(1),holding=members.get(2),other=members.get(3);
        for(JSONObject stock:new JSONObject[]{ordinary,priority})WatchlistStore.add(activity,stock.getString("code"),stock.getString("name"),sector.getString("name"));
        HoldingsStore.add(activity,holding.getString("code"),holding.getString("name"),sector.getString("name"));Shadows.shadowOf(Looper.getMainLooper()).idle();tab("自选股");
        View ordinaryRow=appRoot().findViewWithTag("personal-stock:"+ordinary.getString("code")),priorityRow=appRoot().findViewWithTag("personal-stock:"+priority.getString("code"));assertEquals(Ui.STOCK_BG,stockColor(ordinaryRow));
        description(priorityRow,priority.getString("name")+"添加收藏").performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
        priorityRow=appRoot().findViewWithTag("personal-stock:"+priority.getString("code"));ordinaryRow=appRoot().findViewWithTag("personal-stock:"+ordinary.getString("code"));
        ViewGroup body=(ViewGroup)priorityRow.getParent();assertSame(body,ordinaryRow.getParent());assertTrue(body.indexOfChild(priorityRow)<body.indexOfChild(ordinaryRow));assertEquals(Ui.PINK,stockColor(priorityRow));assertEquals(Ui.STOCK_BG,stockColor(ordinaryRow));
        capture("apk-v1.18-watchlist.png",390,844);RuntimeEnvironment.setFontScale(1.3f);capture("apk-v1.18-watchlist-small.png",320,720);
        Button star=(Button)description(priorityRow,priority.getString("name")+"取消收藏");assertNotNull(star.getLayout());assertTrue(star.getPaint().measureText(star.getText().toString())<=star.getWidth()-star.getPaddingLeft()-star.getPaddingRight()+1);RuntimeEnvironment.setFontScale(1f);
        // Recreate the activity: priority must come from durable data, not transient UI state.
        controller.pause().stop().destroy();controller=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();activity=controller.get();Shadows.shadowOf(Looper.getMainLooper()).idle();tab("自选股");
        priorityRow=appRoot().findViewWithTag("personal-stock:"+priority.getString("code"));assertEquals(Ui.PINK,stockColor(priorityRow));
        tab("板块轮动");description(appRoot(),sector.getString("name")+"，行业排名"+sector.getInt("rank")+"，点击展开成分股").performClick();layout(390,844);
        assertEquals(Ui.PINK_SOFT,stockColor(appRoot().findViewWithTag("rotation-stock:"+ordinary.getString("code"))));assertEquals(Ui.PINK,stockColor(appRoot().findViewWithTag("rotation-stock:"+priority.getString("code"))));assertEquals(Ui.PINK_SOFT,stockColor(appRoot().findViewWithTag("rotation-stock:"+holding.getString("code"))));assertEquals(Ui.STOCK_BG,stockColor(appRoot().findViewWithTag("rotation-stock:"+other.getString("code"))));
        View row=appRoot().findViewWithTag("rotation-stock:"+ordinary.getString("code"));ScrollView scroll=(ScrollView)((ViewGroup)((ViewGroup)appRoot()).getChildAt(1)).getChildAt(0);Rect position=new Rect();row.getDrawingRect(position);((ViewGroup)scroll.getChildAt(0)).offsetDescendantRectToMyCoords(row,position);scroll.scrollTo(0,position.top);capture("apk-v1.18-rotation-marked.png",390,844);
        description(appRoot(),ordinary.getString("name")+"自选操作").performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(Ui.STOCK_BG,stockColor(appRoot().findViewWithTag("rotation-stock:"+ordinary.getString("code"))));
        description(appRoot(),holding.getString("name")+"自选操作").performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();description(appRoot(),holding.getString("name")+"自选操作").performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(Ui.PINK_SOFT,stockColor(appRoot().findViewWithTag("rotation-stock:"+holding.getString("code"))));
        // Add ordinary back, cancel priority, then both rows follow the saved insertion order.
        WatchlistStore.add(activity,ordinary.getString("code"),ordinary.getString("name"),sector.getString("name"));Shadows.shadowOf(Looper.getMainLooper()).idle();tab("自选股");
        description(appRoot(),priority.getString("name")+"取消收藏").performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(Ui.STOCK_BG,stockColor(appRoot().findViewWithTag("personal-stock:"+priority.getString("code"))));
        WatchlistStore.setStarred(activity,ordinary.getString("code"),true);Shadows.shadowOf(Looper.getMainLooper()).idle();
        ordinaryRow=appRoot().findViewWithTag("personal-stock:"+ordinary.getString("code"));priorityRow=appRoot().findViewWithTag("personal-stock:"+priority.getString("code"));body=(ViewGroup)ordinaryRow.getParent();assertTrue(body.indexOfChild(ordinaryRow)<body.indexOfChild(priorityRow));
        description(appRoot(),ordinary.getString("name")+"取消收藏").performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();ordinaryRow=appRoot().findViewWithTag("personal-stock:"+ordinary.getString("code"));priorityRow=appRoot().findViewWithTag("personal-stock:"+priority.getString("code"));body=(ViewGroup)ordinaryRow.getParent();assertTrue(body.indexOfChild(priorityRow)<body.indexOfChild(ordinaryRow));
    }
    @Test public void offlineBoardChartUsesSameTheme()throws Exception{
        ActivityController<BoardChartActivity> board=Robolectric.buildActivity(BoardChartActivity.class,new android.content.Intent().putExtra(BoardChartActivity.EXTRA_INDUSTRY,"房地产")).create().start().resume().visible();
        try{BoardChartActivity screen=board.get();View root=((ViewGroup)screen.findViewById(android.R.id.content)).getChildAt(0);
            assertNotNull(named(root,"房地产"));assertNotNull(description(root,"返回行业列表"));capture(root,"apk-v1.16-board.png",390,844);
        }finally{board.pause().stop().destroy();}
    }
}
