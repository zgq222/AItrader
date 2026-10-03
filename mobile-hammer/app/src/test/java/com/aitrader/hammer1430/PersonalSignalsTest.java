package com.aitrader.hammer1430;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowNotificationManager;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="w390dp-h844dp-xxhdpi",shadows=PersonalSignalsTest.Feed.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PersonalSignalsTest {
    static final long NOW=MinuteBehavior.epoch("2026-01-05 15:00");
    static final List<String> CALENDAR=Arrays.asList("2026-01-02","2026-01-05");
    static Map<String,Integer> minuteCalls=new java.util.concurrent.ConcurrentHashMap<>();static boolean failHolding,advanceDay,extendReduction,newReduction;
    static Map<String,List<DailyBar>> historyOverrides=new HashMap<>(),minuteOverrides=new HashMap<>();
    static List<DailyBar> daily(boolean bearish){return Arrays.asList(new DailyBar("2026-01-02",10,10.3,9.7,bearish?9.9:10.1,100),new DailyBar("2026-01-05",10,11.3,9.7,10.2,100),new DailyBar("2026-01-06",10,11,9,10,100));}
    static List<DailyBar> baseline(){return new ArrayList<>(TradingFeaturesTest.behaviorBars().subList(0,320));}
    static List<DailyBar> attacks(){List<DailyBar> b=baseline();b.add(new DailyBar("2026-01-05 09:45",10,10.21,9.99,10.2,300));b.add(new DailyBar("2026-01-05 10:00",10.2,10.3,10.1,10.2,100));b.add(new DailyBar("2026-01-05 10:15",10.3,10.61,10.29,10.6,300));return b;}
    static List<DailyBar> reduction(){List<DailyBar> b=baseline();b.add(new DailyBar("2026-01-05 09:45",10.8,10.81,10.4,10.5,300));
        if(extendReduction)b.add(new DailyBar("2026-01-05 10:00",10.5,10.6,10.2,10.3,300));
        if(newReduction){b.add(new DailyBar("2026-01-05 10:15",10.9,11.1,10.8,11,100));b.add(new DailyBar("2026-01-05 10:30",11.2,11.3,10.9,11,300));}return b;}
    @Implements(value=TencentClient.class,isInAndroidSdk=false)
    public static class Feed {
        @Implementation protected static TencentClient.Quote indexQuote(){return quote("600519");}
        @Implementation protected static List<DailyBar> indexHistory(String date){List<DailyBar> result=new ArrayList<>(history("600519",date));result.add(0,new DailyBar("2025-12-31",10,10.3,9.7,10,100));return result;}
        @Implementation protected static TencentClient.Quote quote(String code){String day=advanceDay?"2026-01-06":"2026-01-05";return new TencentClient.Quote(code,code.equals("000001")?"测试持仓股":"测试进攻股",new DailyBar(day,10,11.5,9.5,10.2,100),10,MinuteBehavior.epoch(day+" 15:00")/1000);}
        @Implementation protected static List<TencentClient.Quote> quotes(List<String> codes){List<TencentClient.Quote> out=new ArrayList<>();for(String code:codes)out.add(quote(code));return out;}
        @Implementation protected static List<DailyBar> history(String code,String date){List<DailyBar> out=new ArrayList<>();for(DailyBar bar:historyOverrides.getOrDefault(code,daily(!code.equals("000002"))))if(bar.date.compareTo(date)<=0)out.add(bar);return out;}
        @Implementation protected static List<DailyBar> minutes(String code)throws Exception{minuteCalls.put(code,minuteCalls.getOrDefault(code,0)+1);if(code.equals("000001")&&failHolding)throw new IOException("测试离线");return minuteOverrides.getOrDefault(code,code.equals("000001")?reduction():attacks());}
    }
    @Before public void reset()throws Exception{
        Context c=RuntimeEnvironment.getApplication();for(String p:new String[]{"scan","watchlist","holdings","personal_signals","minute15_cache"})c.getSharedPreferences(p,0).edit().clear().commit();
        JSONObject snapshot=WatchlistStore.bundledSnapshot(c);c.getSharedPreferences("watchlist",0).edit().putBoolean("imported:"+snapshot.getString("id"),true).commit();
        minuteCalls.clear();historyOverrides.clear();minuteOverrides.clear();failHolding=false;advanceDay=false;extendReduction=false;newReduction=false;
        WatchlistStore.add(c,"600519","测试进攻股","白酒");WatchlistStore.setStarred(c,"600519",true);WatchlistStore.add(c,"000002","前日阳线股","房地产");HoldingsStore.add(c,"000001","测试持仓股","银行");HoldingsStore.stop(c,"000001",9.5);
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
    }
    @Test public void onlyExactPreviousSessionAndTwoIndependentSolidAttacksQualify(){
        PersonalSignalRule.Decision d=PersonalSignalRule.evaluate(daily(true),CALENDAR,attacks(),NOW);assertTrue(d.qualifies());assertEquals("2026-01-02",d.previousDate);assertEquals(320,d.firstAttack.start);assertEquals(322,d.secondAttack.start);
        assertFalse(PersonalSignalRule.evaluate(daily(false),CALENDAR,attacks(),NOW).qualifies());
        assertFalse(PersonalSignalRule.evaluate(daily(true).subList(1,2),CALENDAR,attacks(),NOW).qualifies());
        List<DailyBar> one=attacks();one.remove(321);one.set(321,new DailyBar("2026-01-05 10:00",10.3,10.61,10.29,10.6,300));assertFalse("two adjacent candles merge into one shape",PersonalSignalRule.evaluate(daily(true),CALENDAR,one,NOW).qualifies());
        assertFalse(PersonalSignalRule.evaluate(daily(true),CALENDAR,attacks(),MinuteBehavior.epoch("2026-01-05 10:14")).qualifies());
        assertFalse(PersonalSignalRule.evaluate(daily(true),Arrays.asList("2026-01-05","2026-01-06"),attacks(),NOW).qualifies());
    }
    @Test public void solidReductionInterruptsButYellowAndContainedDoNot(){
        List<DailyBar> b=attacks();DailyBar second=b.remove(322);b.add(new DailyBar("2026-01-05 10:15",10.3,10.61,10.29,10.6,100));b.add(new DailyBar("2026-01-05 10:30",10.2,10.25,10.19,10.2,300));b.add(new DailyBar("2026-01-05 10:45",10.3,10.61,10.29,10.6,100));b.add(new DailyBar("2026-01-05 11:00",10.3,10.61,10.29,10.6,300));
        assertTrue("yellow holding frame ignored",PersonalSignalRule.evaluate(daily(true),CALENDAR,b,NOW).qualifies());
        b.set(323,new DailyBar("2026-01-05 10:30",10.18,10.2,10.1,10.12,300));assertTrue("contained green ignored",PersonalSignalRule.evaluate(daily(true),CALENDAR,b,NOW).qualifies());assertTrue(PersonalSignalRule.evaluate(daily(true),CALENDAR,b,NOW).reductions.isEmpty());
        b.set(323,new DailyBar("2026-01-05 10:30",10.5,10.8,10.22,10.3,300));PersonalSignalRule.Decision d=PersonalSignalRule.evaluate(daily(true),CALENDAR,b,NOW);assertFalse("solid green separates attacks",d.qualifies());assertEquals(1,d.reductions.size());
        List<DailyBar> later=attacks();later.add(new DailyBar("2026-01-05 10:30",10.8,10.9,10.4,10.8,100));later.add(new DailyBar("2026-01-05 10:45",10.8,10.9,10.4,10.5,300));assertTrue("an already observed pair remains a same-day candidate",PersonalSignalRule.evaluate(daily(true),CALENDAR,later,NOW).qualifies());
    }
    @Test public void previousFallingIncludesPositiveAndFlatCandlesButRequiresExactReference(){
        List<String> calendar=Arrays.asList("2025-12-31","2026-01-02","2026-01-05");
        DailyBar reference=new DailyBar("2025-12-31",10,10.3,9.7,10,100),positive=new DailyBar("2026-01-02",9.8,10.1,9.7,9.9,100);
        PersonalSignalRule.Decision pair=PersonalSignalRule.evaluate(Arrays.asList(reference,positive),calendar,attacks(),NOW);assertFalse(pair.bearish());assertTrue(pair.falling());assertTrue(pair.qualifies());assertTrue(pair.qualifiesSingle());
        List<DailyBar> one=new ArrayList<>(attacks().subList(0,321));PersonalSignalRule.Decision single=PersonalSignalRule.evaluate(Arrays.asList(reference,positive),calendar,one,NOW);assertTrue(single.qualifiesSingle());assertFalse(single.qualifies());assertEquals(320,single.singleAttack.start);
        assertFalse("unfinished 15-minute frames are excluded",PersonalSignalRule.evaluate(Arrays.asList(reference,positive),calendar,one,MinuteBehavior.epoch("2026-01-05 09:44")).qualifiesSingle());
        assertTrue(PersonalSignalRule.evaluate(Arrays.asList(reference,new DailyBar("2026-01-02",9.9,10.1,9.7,9.9,100)),calendar,one,NOW).qualifiesSingle());
        assertFalse("unchanged doji is neither bearish nor falling",PersonalSignalRule.evaluate(Arrays.asList(reference,new DailyBar("2026-01-02",10,10.1,9.7,10,100)),calendar,one,NOW).qualifiesSingle());
        assertFalse("a missing reference cannot be replaced by an older session",PersonalSignalRule.evaluate(Arrays.asList(new DailyBar("2025-12-30",10,11,9,10.5,100),positive),calendar,one,NOW).qualifiesSingle());
        PersonalSignalRule.Decision bearishUp=PersonalSignalRule.evaluate(Arrays.asList(reference,new DailyBar("2026-01-02",10.3,10.4,10.1,10.2,100)),calendar,one,NOW);assertTrue(bearishUp.bearish());assertFalse(bearishUp.falling());assertTrue("either condition suffices",bearishUp.qualifiesSingle());
        one.set(320,new DailyBar("2026-01-05 09:45",10,10.21,9.99,10,300));assertFalse("yellow frames are not attacks",PersonalSignalRule.evaluate(Arrays.asList(reference,positive),calendar,one,NOW).qualifiesSingle());
        List<DailyBar> contained=attacks();contained.set(322,new DailyBar("2026-01-05 10:15",10.3,10.5,9.8,10,300));assertFalse("a contained attack is ignored",PersonalSignalRule.evaluate(Arrays.asList(reference,positive),calendar,contained,NOW).qualifiesSingle());
    }
    @Test public void repositoryDownloadsFallingPositiveStockAndUpgradesSingleToPair()throws Exception{
        Context c=RuntimeEnvironment.getApplication();WatchlistStore.add(c,"600036","招商银行","银行");
        historyOverrides.put("600036",Arrays.asList(new DailyBar("2025-12-31",10,10.3,9.7,10,100),new DailyBar("2026-01-02",9.8,10.1,9.7,9.9,100),daily(true).get(1)));
        minuteOverrides.put("600036",new ArrayList<>(attacks().subList(0,321)));PersonalSignalRepository.load(c);JSONObject row=PersonalSignalStore.records(c).getJSONObject("600036");
        assertTrue(minuteCalls.containsKey("600036"));assertFalse(row.getBoolean("previous_bearish"));assertTrue(row.getBoolean("previous_falling"));assertTrue(row.getBoolean("qualifies_single"));assertFalse(row.getBoolean("qualifies"));assertEquals("2025-12-31",row.getString("comparison_date"));assertEquals("2026-01-05 09:45",row.getJSONObject("single_attack").getString("start_time"));assertEquals(PersonalSignalRule.VERSION,row.getInt("rule_version"));
        minuteOverrides.put("600036",attacks());PersonalSignalRepository.load(c);row=PersonalSignalStore.records(c).getJSONObject("600036");assertTrue(row.getBoolean("qualifies"));assertTrue(row.getBoolean("qualifies_single"));assertEquals("2026-01-05 10:15",row.getJSONObject("second_attack").getString("start_time"));
        failHolding=true;PersonalSignalRepository.load(c);assertTrue(PersonalSignalStore.records(c).getJSONObject("000001").getBoolean("stale"));assertTrue(row.getBoolean("qualifies"));
    }
    @Test public void repositoryFiltersWatchlistChecksHoldingsAndDeduplicatesAlerts()throws Exception{
        Context c=RuntimeEnvironment.getApplication();PersonalSignalRepository.load(c);JSONObject records=PersonalSignalStore.records(c);
        assertTrue(records.getJSONObject("600519").getBoolean("qualifies"));assertFalse(records.getJSONObject("000002").getBoolean("qualifies"));assertFalse(minuteCalls.containsKey("000002"));assertEquals(1,PersonalSignalStore.reductions(c,"000001").length());assertTrue(PersonalSignalStore.unread(c,"000001"));
        ShadowNotificationManager manager=Shadows.shadowOf((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE));Notification n=manager.getNotification(50_001);assertNotNull(n);assertEquals(NotificationManager.IMPORTANCE_HIGH,((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).getNotificationChannel(HoldingRiskNotifier.CHANNEL).getImportance());
        Intent open=Shadows.shadowOf(n.contentIntent).getSavedIntent();assertTrue(open.getBooleanExtra(StockChartActivity.EXTRA_MINUTE,false));assertEquals("2026-01-05 09:45",open.getStringExtra(StockChartActivity.EXTRA_FOCUS));
        ((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(50_001);PersonalSignalRepository.load(c);assertNull("no repeated push",manager.getNotification(50_001));
        extendReduction=true;PersonalSignalRepository.load(c);assertNull("growing frame keeps the same identity",manager.getNotification(50_001));assertEquals("2026-01-05 10:00",PersonalSignalStore.reductions(c,"000001").getJSONObject(0).getString("end_time"));
        PersonalSignalStore.acknowledge(c,"000001");assertFalse(PersonalSignalStore.unread(c,"000001"));newReduction=true;PersonalSignalRepository.load(c);assertNotNull(manager.getNotification(50_001));assertTrue(PersonalSignalStore.unread(c,"000001"));
        failHolding=true;((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(50_001);PersonalSignalRepository.load(c);assertTrue(PersonalSignalStore.records(c).getJSONObject("000001").getBoolean("stale"));assertEquals(2,PersonalSignalStore.reductions(c,"000001").length());assertNull(manager.getNotification(50_001));
        advanceDay=true;PersonalSignalRepository.load(c);assertFalse("do not carry yesterday's selection to a new market date",PersonalSignalStore.records(c).getJSONObject("600519").getBoolean("qualifies"));assertEquals(0,PersonalSignalStore.reductions(c,"000001").length());assertEquals(9.5,HoldingsStore.find(c,"000001").getDouble("stop_price"),0);
    }
    private View root(Activity a){return ((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);}
    private View tagged(View v,String tag){if(tag.equals(v.getTag()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View result=tagged(((ViewGroup)v).getChildAt(i),tag);if(result!=null)return result;}return null;}
    private View description(View v,String text){if(text.equals(v.getContentDescription()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View result=description(((ViewGroup)v).getChildAt(i),text);if(result!=null)return result;}return null;}
    private void capture(Activity a,String name,int width,int height)throws Exception{View r=root(a);int w=Ui.dp(a,width),h=Ui.dp(a,height);for(int i=0;i<2;i++){r.forceLayout();r.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));r.layout(0,0,w,h);}Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);r.draw(new Canvas(b));try(FileOutputStream out=new FileOutputStream(new File(System.getProperty("aitrader.ui.outputs"),name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
    @Test public void nativeModuleRiskBadgeAndMinuteRoutingWorkAtSmallWidth()throws Exception{
        Context c=RuntimeEnvironment.getApplication();PersonalSignalRepository.load(c);ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class,new Intent().putExtra("module",6)).create().start().resume().visible();
        try{MainActivity a=controller.get();assertNotNull(description(root(a),"自选延伸"));assertNotNull(tagged(root(a),"signal-candidate:600519"));assertNotNull(tagged(root(a),"signal-risk:000001"));capture(a,"apk-v1.24-personal-signals.png",390,844);
            a.getResources().getConfiguration().fontScale=1.3f;capture(a,"apk-v1.24-personal-signals-small.png",320,720);
            description(root(a),"持仓股").performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();View badge=tagged(root(a),"holding-risk:000001");
            // Expand holdings before inspecting its warning.
            if(badge==null){ViewGroup content=(ViewGroup)((ScrollView)((ViewGroup)((ViewGroup)root(a)).getChildAt(1)).getChildAt(0)).getChildAt(0);clickContaining(content,"银行");badge=tagged(root(a),"holding-risk:000001");}assertNotNull(badge);badge.performClick();Intent intent=Shadows.shadowOf(a).getNextStartedActivity();assertTrue(intent.getBooleanExtra(StockChartActivity.EXTRA_MINUTE,false));assertEquals("2026-01-05 09:45",intent.getStringExtra(StockChartActivity.EXTRA_FOCUS));assertFalse(PersonalSignalStore.unread(c,"000001"));
        }finally{controller.pause().stop().destroy();}
    }
    @Test public void extensionGroupsByIndustryKeepsPriorityAndSurvivesQuoteRefresh()throws Exception{
        Context c=RuntimeEnvironment.getApplication();PersonalSignalRepository.load(c);
        WatchlistStore.add(c,"600036","招商银行","银行");WatchlistStore.add(c,"000001","平安银行","银行");WatchlistStore.setStarred(c,"000001",true);
        JSONObject payload=PersonalSignalStore.payload(c),rows=payload.getJSONObject("stocks");
        // Separate qualifying bank stocks let this UI test cover grouping and stable favorite priority.
        for(String code:new String[]{"600036","000001"}){JSONObject row=new JSONObject(rows.getJSONObject("600519").toString());row.put("code",code);row.put("name",WatchlistStore.stocks(c).stream().filter(s->code.equals(s.optString("code"))).findFirst().get().optString("name"));if(code.equals("000001"))row.put("reductions",rows.getJSONObject(code).getJSONArray("reductions"));rows.put(code,row);}
        PersonalSignalStore.prefs(c).edit().putString("payload",payload.toString()).commit();
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class,new Intent().putExtra("module",6)).create().start().resume().visible();
        try{MainActivity a=controller.get();View r=root(a);String bank=new IndustryCatalog(c).industry("000001"),liquor=new IndustryCatalog(c).industry("600519");
            assertNotEquals(bank,liquor);assertNotNull(description(r,bank+"，2只股票，点击展开或收起"));assertNotNull(description(r,liquor+"，1只股票，点击展开或收起"));assertNull("unqualified stocks are excluded",tagged(r,"signal-candidate:000002"));
            ViewGroup body=(ViewGroup)tagged(r,"industry-body:6:"+bank);assertSame(body,tagged(r,"signal-candidate:000001").getParent());assertTrue(body.indexOfChild(tagged(r,"signal-candidate:000001"))<body.indexOfChild(tagged(r,"signal-candidate:600036")));
            description(r,bank+"，2只股票，点击展开或收起").performClick();assertEquals(View.GONE,body.getVisibility());
            c.getSharedPreferences("scan",0).edit().putString("favorite_quote_payload","{\"600036\":{\"return_30d_pct\":3.25}}").commit();Shadows.shadowOf(Looper.getMainLooper()).idle();
            body=(ViewGroup)tagged(root(a),"industry-body:6:"+bank);assertEquals("quote updates keep a collapsed industry collapsed",View.GONE,body.getVisibility());
            description(root(a),bank+"，2只股票，点击展开或收起").performClick();assertEquals(View.VISIBLE,body.getVisibility());assertNotNull(tagged(root(a),"signal-candidate:600036"));
            description(root(a),bank+"板块日K，内置快照，可选联网更新").performClick();Intent board=Shadows.shadowOf(a).getNextStartedActivity();assertEquals(BoardChartActivity.class.getName(),board.getComponent().getClassName());assertEquals(bank,board.getStringExtra(BoardChartActivity.EXTRA_INDUSTRY));
            description(root(a),"平安银行连续进攻组合，定位15分钟").performClick();Intent minute=Shadows.shadowOf(a).getNextStartedActivity();assertEquals("000001",minute.getStringExtra(StockChartActivity.EXTRA_CODE));assertTrue(minute.getBooleanExtra(StockChartActivity.EXTRA_MINUTE,false));assertEquals("2026-01-05 10:15",minute.getStringExtra(StockChartActivity.EXTRA_FOCUS));assertTrue("viewing an attack does not acknowledge a reduction",PersonalSignalStore.unread(c,"000001"));
            capture(a,"apk-v1.25-extension-top.png",390,844);
            View group=tagged(root(a),"industry-group:6:"+bank);ScrollView scroll=(ScrollView)group.getParent().getParent();scroll.scrollTo(0,group.getTop());capture(a,"apk-v1.25-extension-industries.png",390,844);
            a.getResources().getConfiguration().fontScale=1.3f;capture(a,"apk-v1.25-extension-industries-small.png",320,720);
            for(String code:new String[]{"600036","000001"}){ViewGroup actions=(ViewGroup)tagged(root(a),"stock-actions:"+code);assertEquals(3,actions.getChildCount());for(int i=0;i<3;i++)assertTrue(actions.getChildAt(i).getWidth()>0);assertNotNull(tagged(root(a),"concept-tags:"+code));}
            description(root(a),"招商银行自选操作").performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(WatchlistStore.contains(c,"600036"));assertNotNull(description(root(a),bank+"，1只股票，点击展开或收起"));assertNull(tagged(root(a),"signal-candidate:600036"));assertEquals(9.5,HoldingsStore.find(c,"000001").getDouble("stop_price"),0);
        }finally{controller.pause().stop().destroy();}
    }
    @Test public void publishedRanksArePreservedAndUnrankedIndustriesStayLast()throws Exception{
        JSONObject rotation=new JSONObject().put("trade_date","2026-09-30").put("industries",new JSONArray()
                .put(new JSONObject().put("name","银行").put("rank",1).put("score",90))
                .put(new JSONObject().put("name","白酒").put("rank",2).put("score",70))
                .put(new JSONObject().put("name","缺项行业").put("rank",3).put("score",JSONObject.NULL)));
        Map<String,List<JSONObject>> groups=new TreeMap<>();for(String name:new String[]{"白酒","缺项行业","待分类","无行情行业","银行"})groups.put(name,Collections.emptyList());
        IndustryRanking ranking=new IndustryRanking(rotation);List<Map.Entry<String,List<JSONObject>>> ordered=ranking.ordered(groups);assertEquals("银行",ordered.get(0).getKey());assertEquals("白酒",ordered.get(1).getKey());assertEquals("待分类",ordered.get(4).getKey());assertEquals("行业排名 2/3",ranking.label("白酒"));assertEquals("行业暂无排名",ranking.label("缺项行业"));
        groups.remove("银行");assertEquals("白酒",ranking.ordered(groups).get(0).getKey());assertEquals("a personal subset keeps the full-market rank","行业排名 2/3",ranking.label("白酒"));assertTrue(ranking.note().contains("2026-09-30"));
        IndustryRanking missing=new IndustryRanking(new JSONObject());assertTrue(missing.note().contains("请刷新"));assertEquals("待分类",missing.ordered(groups).get(groups.size()-1).getKey());
    }
    private JSONObject ranks(String bank,String liquor,boolean bankFirst)throws Exception{return new JSONObject().put("trade_date","2026-01-05").put("industries",new JSONArray().put(new JSONObject().put("name",bank).put("rank",bankFirst?1:2).put("score",bankFirst?90:70)).put(new JSONObject().put("name",liquor).put("rank",bankFirst?2:1).put("score",bankFirst?70:90)).put(new JSONObject().put("name","缺项行业").put("rank",JSONObject.NULL).put("score",JSONObject.NULL)));}
    private void assertIndustryBefore(View root,String prefix,String first,String second){View a=tagged(root,"industry-group:"+prefix+first),b=tagged(root,"industry-group:"+prefix+second);assertNotNull(a);assertNotNull(b);assertSame(a.getParent(),b.getParent());ViewGroup parent=(ViewGroup)a.getParent();assertTrue(parent.indexOfChild(a)<parent.indexOfChild(b));}
    @Test public void twoSignalPartsAndWatchlistUseRotationOrderAndMoveWithoutDuplicates()throws Exception{
        Context c=RuntimeEnvironment.getApplication();PersonalSignalRepository.load(c);WatchlistStore.add(c,"600036","招商银行","银行");WatchlistStore.add(c,"000001","平安银行","银行");WatchlistStore.setStarred(c,"000001",true);WatchlistStore.add(c,"600809","山西汾酒","白酒");
        String bank=new IndustryCatalog(c).industry("000001"),liquor=new IndustryCatalog(c).industry("600519");assertEquals(liquor,new IndustryCatalog(c).industry("600809"));
        JSONObject payload=PersonalSignalStore.payload(c),rows=payload.getJSONObject("stocks"),example=rows.getJSONObject("600519");
        for(String code:new String[]{"000001","600036","600809"}){JSONObject row=new JSONObject(example.toString());row.put("code",code).put("name",code.equals("000001")?"平安银行":code.equals("600036")?"招商银行":"山西汾酒");if(code.equals("000001"))row.put("reductions",rows.getJSONObject(code).getJSONArray("reductions"));else{row.put("qualifies",false).put("qualifies_single",true).put("single_attack",example.getJSONObject("first_attack")).put("previous_bearish",false).put("previous_falling",true).put("previous_open",9.8).put("previous_close",9.9).put("comparison_close",10);row.remove("first_attack");row.remove("second_attack");}rows.put(code,row);}
        c.getSharedPreferences("scan",0).edit().putString("rotation_payload",ranks(bank,liquor,true).toString()).commit();PersonalSignalStore.prefs(c).edit().putString("payload",payload.toString()).commit();
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class,new Intent().putExtra("module",6)).create().start().resume().visible();
        try{MainActivity a=controller.get();View r=root(a);assertNotNull(tagged(r,"signal-part:pair"));assertNotNull(tagged(r,"signal-part:single"));assertNotNull(tagged(r,"signal-candidate:600519"));assertNull(tagged(r,"signal-single:600519"));assertNotNull(tagged(r,"signal-single:600036"));assertNull(tagged(r,"signal-candidate:600036"));assertIndustryBefore(r,"6:",bank,liquor);assertIndustryBefore(r,"6:single:",bank,liquor);
            description(r,"招商银行单个进攻框，定位15分钟").performClick();Intent minute=Shadows.shadowOf(a).getNextStartedActivity();assertEquals("600036",minute.getStringExtra(StockChartActivity.EXTRA_CODE));assertTrue(minute.getBooleanExtra(StockChartActivity.EXTRA_MINUTE,false));assertEquals("2026-01-05 09:45",minute.getStringExtra(StockChartActivity.EXTRA_FOCUS));assertTrue(PersonalSignalStore.unread(c,"000001"));
            capture(a,"apk-v1.26-extension-top.png",390,844);View heading=tagged(r,"signal-part:pair");ScrollView scroll=(ScrollView)tagged(r,"industry-group:6:"+bank).getParent().getParent();scroll.scrollTo(0,((View)heading.getParent()).getTop());capture(a,"apk-v1.26-extension-pair.png",390,844);
            scroll.scrollTo(0,((View)tagged(r,"signal-part:single").getParent()).getTop());capture(a,"apk-v1.26-extension-single.png",390,844);a.getResources().getConfiguration().fontScale=1.3f;capture(a,"apk-v1.26-extension-single-small.png",320,720);
            description(tagged(r,"industry-group:6:"+bank),bank+"，1只股票，点击展开或收起").performClick();assertEquals(View.GONE,tagged(r,"industry-body:6:"+bank).getVisibility());assertEquals("parts have independent collapse state",View.VISIBLE,tagged(r,"industry-body:6:single:"+bank).getVisibility());
            description(r,"自选股").performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertIndustryBefore(root(a),"2:",bank,liquor);ViewGroup watchBank=(ViewGroup)tagged(root(a),"industry-body:2:"+bank);assertTrue(watchBank.indexOfChild(tagged(root(a),"personal-stock:000001"))<watchBank.indexOfChild(tagged(root(a),"personal-stock:600036")));
            c.getSharedPreferences("scan",0).edit().putString("rotation_payload",ranks(bank,liquor,false).toString()).commit();Shadows.shadowOf(Looper.getMainLooper()).idle();assertIndustryBefore(root(a),"2:",liquor,bank);capture(a,"apk-v1.26-watchlist-order-small.png",320,720);
            description(root(a),"自选延伸").performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertIndustryBefore(root(a),"6:",liquor,bank);assertIndustryBefore(root(a),"6:single:",liquor,bank);assertEquals(View.GONE,tagged(root(a),"industry-body:6:"+bank).getVisibility());assertEquals(View.VISIBLE,tagged(root(a),"industry-body:6:single:"+bank).getVisibility());
            JSONObject promoted=rows.getJSONObject("600036");promoted.put("qualifies",true).put("first_attack",example.getJSONObject("first_attack")).put("second_attack",example.getJSONObject("second_attack"));PersonalSignalStore.prefs(c).edit().putString("payload",payload.toString()).commit();Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(tagged(root(a),"signal-single:600036"));assertNull(tagged(root(a),"industry-group:6:single:"+bank));description(tagged(root(a),"industry-group:6:"+bank),bank+"，2只股票，点击展开或收起").performClick();assertNotNull(tagged(root(a),"signal-candidate:600036"));assertEquals(9.5,HoldingsStore.find(c,"000001").getDouble("stop_price"),0);
        }finally{controller.pause().stop().destroy();}
    }
    private boolean clickContaining(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText())){View parent=(View)v.getParent();while(parent!=null&&!parent.isClickable())parent=parent.getParent() instanceof View?(View)parent.getParent():null;if(parent!=null)parent.performClick();return parent!=null;}if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(clickContaining(((ViewGroup)v).getChildAt(i),text))return true;return false;}
}
