package com.aitrader.hammer1430;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="w390dp-h844dp-xxhdpi",shadows=TradingFeaturesTest.FakeTencent.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PersonalDataTest {
    private Context app;
    @Before public void reset(){app=RuntimeEnvironment.getApplication();for(String p:new String[]{"watchlist","holdings","notes_v1","scan","personal_signals"})app.getSharedPreferences(p,0).edit().clear().commit();TradingFeaturesTest.failMinutes=false;TradingFeaturesTest.entered=null;TradingFeaturesTest.release=null;}
    private View root(Activity a){return ((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);}
    private void await(BooleanSupplier ready)throws Exception{long until=System.currentTimeMillis()+5000;while(!ready.getAsBoolean()&&System.currentTimeMillis()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue("background operation completed",ready.getAsBoolean());}
    private void seed()throws Exception{
        WatchlistStore.add(app,"600519","贵州茅台","白酒");WatchlistStore.add(app,"000001","平安银行","银行");WatchlistStore.setStarred(app,"000001",true);WatchlistStore.setStarred(app,"000001",false);WatchlistStore.remove(app,"600519");WatchlistStore.add(app,"600000","浦发银行","银行");WatchlistStore.setStarred(app,"600000",true);
        HoldingsStore.add(app,"600519","贵州茅台","白酒","第一段买入");HoldingsStore.remove(app,"600519","第一段卖出");HoldingsStore.add(app,"000001","平安银行","银行","第二段买入");HoldingsStore.stop(app,"000001",12.50);
        NotesStore.append(app,NotesStore.stock("600519"),"保留第一条");NotesStore.append(app,NotesStore.stock("600519"),"保留第二条");NotesStore.append(app,NotesStore.board("银行",false),"板块笔记");NotesStore.append(app,NotesStore.board("足球概念",true),"概念笔记");
    }
    @Test public void newSeedOnUpgradeCannotResurrectDeletedOrUnstarredStocks()throws Exception{
        JSONObject old=new JSONObject().put("id","old-web-snapshot").put("stocks",new JSONArray().put(new JSONObject().put("code","600519").put("name","贵州茅台").put("starred",true)).put(new JSONObject().put("code","000001").put("name","平安银行").put("starred",true)));
        WatchlistStore.importSnapshot(app,old);WatchlistStore.remove(app,"600519");WatchlistStore.setStarred(app,"000001",false);WatchlistStore.add(app,"600000","手机新增","银行");
        // A legacy installation has no new guard, and its seed ID differs from the APK asset.
        app.getSharedPreferences("watchlist",0).edit().remove("initial_seed_done").commit();String saved=WatchlistStore.payload(app);WatchlistStore.importBundled(app);assertEquals(saved,WatchlistStore.payload(app));assertFalse(WatchlistStore.contains(app,"600519"));assertFalse(WatchlistStore.stocks(app).get(0).getBoolean("starred"));
        WatchlistStore.remove(app,"000001");WatchlistStore.remove(app,"600000");app.getSharedPreferences("watchlist",0).edit().remove("initial_seed_done").commit();WatchlistStore.importBundled(app);assertEquals("[]",WatchlistStore.payload(app));
    }
    @Test public void freshInstallImportsOnceButLegacyEmptyPhoneRemainsEmpty()throws Exception{
        WatchlistStore.importBundled(app);assertEquals(288,WatchlistStore.stocks(app).size());WatchlistStore.importBundled(app);assertEquals(288,WatchlistStore.stocks(app).size());
        app.getSharedPreferences("watchlist",0).edit().clear().putBoolean("imported:legacy-empty",true).commit();WatchlistStore.importBundled(app);assertEquals("[]",WatchlistStore.payload(app));
    }
    @Test public void backupRoundTripRestoresRemovalsStarsNotesCyclesAndStops()throws Exception{
        seed();app.getSharedPreferences("scan",0).edit().putString("quote_secret_fixture","not-personal-data").commit();JSONObject saved=UserDataBackup.capture(app);assertFalse(saved.toString().contains("quote_secret_fixture"));assertTrue(UserDataBackup.summary(saved).contains("7条笔记/逻辑"));
        JSONObject portable=UserDataBackup.read(new ByteArrayInputStream(saved.toString(2).getBytes(StandardCharsets.UTF_8)),app);
        for(String p:new String[]{"watchlist","holdings","notes_v1"})app.getSharedPreferences(p,0).edit().clear().commit();WatchlistStore.add(app,"601318","后来新增","保险");UserDataBackup.restore(app,portable);WatchlistStore.importBundled(app);
        assertEquals(saved.getJSONArray("watchlist").toString(),WatchlistStore.payload(app));assertFalse(WatchlistStore.contains(app,"600519"));assertFalse(WatchlistStore.contains(app,"601318"));assertFalse(WatchlistStore.stocks(app).get(0).getBoolean("starred"));assertTrue(WatchlistStore.stocks(app).get(1).getBoolean("starred"));assertEquals(12.5,HoldingsStore.find(app,"000001").getDouble("stop_price"),0);assertEquals(saved.getJSONArray("trade_cycles").toString(),TradeJournal.payload(app));assertEquals(saved.getJSONObject("notes").toString(),NotesStore.payload(app));assertEquals(1,TradeJournal.reviews(app).size());
    }
    @Test public void invalidBackupIsRejectedBeforeAnyPersonalWrite()throws Exception{
        seed();JSONObject before=UserDataBackup.capture(app);
        for(int fault=0;fault<6;fault++){JSONObject invalid=new JSONObject(before.toString());switch(fault){case 0:invalid.put("version",2);break;case 1:invalid.put("package","another.package");break;case 2:invalid.getJSONArray("watchlist").put(invalid.getJSONArray("watchlist").getJSONObject(0));break;case 3:invalid.getJSONArray("holdings").getJSONObject(0).put("trade_cycle_id","missing");break;case 4:invalid.getJSONObject("notes").getJSONArray("stock:600519").getJSONObject(0).put("date","2026/02/30");break;default:invalid.getJSONArray("holdings").getJSONObject(0).put("stop_price",-1);}
            try{UserDataBackup.restore(app,invalid);fail("invalid backup accepted");}catch(Exception expected){}assertEquals(before.getJSONArray("watchlist").toString(),WatchlistStore.payload(app));assertEquals(before.getJSONArray("holdings").toString(),HoldingsStore.payload(app));assertEquals(before.getJSONObject("notes").toString(),NotesStore.payload(app));assertEquals(before.getJSONArray("trade_cycles").toString(),TradeJournal.payload(app));
        }
        JSONObject empty=new JSONObject(before.toString()).put("watchlist",new JSONArray());UserDataBackup.restore(app,empty);WatchlistStore.importBundled(app);assertEquals("[]",WatchlistStore.payload(app));
    }
    @Test public void noteBadgeUpdatesAcrossScreensAndIncludesAllLogic()throws Exception{
        seed();ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class,new Intent().putExtra("module",2)).setup();try{MainActivity a=controller.get();View r=root(a);NotesDialogs.NoteButton bank=(NotesDialogs.NoteButton)r.findViewWithTag("notes:stock:000001"),industry=(NotesDialogs.NoteButton)r.findViewWithTag("notes:industry:银行");assertNotNull(bank);assertNotNull(industry);assertEquals(1,bank.noteCount());assertEquals(1,industry.noteCount());NotesStore.append(app,NotesStore.stock("000001"),"手机新增");Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(2,bank.noteCount());assertEquals(2,((NotesDialogs.NoteButton)r.findViewWithTag("notes:stock:000001")).noteCount());assertEquals(1,industry.noteCount());
            NotesDialogs.NoteButton stock=(NotesDialogs.NoteButton)NotesDialogs.button(a,NotesStore.stock("600519"),"贵州茅台","600519",()->{});assertEquals(4,stock.noteCount());NotesDialogs.NoteButton zero=(NotesDialogs.NoteButton)r.findViewWithTag("notes:stock:600000");assertEquals(0,zero.noteCount());assertTrue(zero.getContentDescription().toString().contains("共0条"));
            capture(a,r,"apk-v1.31-watchlist-badges.png",390,844);RuntimeEnvironment.setFontScale(1.3f);capture(a,r,"apk-v1.31-watchlist-badges-small.png",320,720);RuntimeEnvironment.setFontScale(1f);
            r.findViewWithTag("personal-backup").performClick();assertEquals(DataBackupActivity.class.getName(),Shadows.shadowOf(a).getNextStartedActivity().getComponent().getClassName());
        }finally{controller.pause().stop().destroy();RuntimeEnvironment.setFontScale(1f);}
    }
    @Test public void largeBadgeFitsCompactButtonsWithoutChangingStockHeight()throws Exception{
        ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup();try{Activity a=controller.get();a.setTheme(R.style.AppTheme);LinearLayout page=Ui.column(a);page.setBackgroundColor(Ui.BG);a.setContentView(page);page.setPadding(Ui.dp(a,16),Ui.dp(a,16),Ui.dp(a,16),Ui.dp(a,16));Ui.add(page,Ui.text(a,"笔记条数角标",22,Ui.INK,true));for(int n:new int[]{0,1,12,100}){String key=NotesStore.stock(String.format("%06d",600000+n));JSONArray entries=new JSONArray();for(int j=0;j<n;j++)entries.put(NotesStore.entry("测试笔记",System.currentTimeMillis()));JSONObject all=new JSONObject(NotesStore.payload(a)).put(key,entries);a.getSharedPreferences(NotesStore.PREFS,0).edit().putString("entries",all.toString()).commit();LinearLayout row=Ui.row(a);Ui.gap(page,12);row.addView(Ui.text(a,n+"条",14,Ui.INK,false),new LinearLayout.LayoutParams(Ui.dp(a,100),-2));NotesDialogs.NoteButton button=(NotesDialogs.NoteButton)NotesDialogs.button(a,key,"测试","600000",()->{});button.compact();row.addView(button,new LinearLayout.LayoutParams(Ui.dp(a,44),Ui.dp(a,30)));Ui.add(page,row);assertEquals(n,button.noteCount());assertEquals(n>99?"99+":String.valueOf(n),button.badgeText());}Shadows.shadowOf(Looper.getMainLooper()).idle();capture(a,page,"apk-v1.31-badge-sizes.png",320,300);RuntimeEnvironment.setFontScale(1.3f);capture(a,page,"apk-v1.31-badge-sizes-large-font.png",320,300);
        }finally{controller.pause().stop().destroy();RuntimeEnvironment.setFontScale(1f);}
    }
    @Test public void systemFileExportProducesPortableBackupAndCancelKeepsData()throws Exception{
        seed();ActivityController<DataBackupActivity> controller=Robolectric.buildActivity(DataBackupActivity.class).setup();try{DataBackupActivity a=controller.get();View r=root(a);capture(a,r,"apk-v1.31-backup.png",390,844);RuntimeEnvironment.setFontScale(1.3f);capture(a,r,"apk-v1.31-backup-small.png",320,720);RuntimeEnvironment.setFontScale(1f);r.findViewWithTag("backup-export").performClick();Intent picker=Shadows.shadowOf(a).getNextStartedActivityForResult().intent;assertEquals(Intent.ACTION_CREATE_DOCUMENT,picker.getAction());assertTrue(picker.getStringExtra(Intent.EXTRA_TITLE).endsWith(".json"));Uri uri=Uri.parse("content://test/backup.json");ByteArrayOutputStream output=new ByteArrayOutputStream();Shadows.shadowOf(a.getContentResolver()).registerOutputStream(uri,output);a.onActivityResult(41,Activity.RESULT_OK,new Intent().setData(uri));await(()->output.size()>0&&r.findViewWithTag("backup-export").isEnabled());JSONObject exported=UserDataBackup.read(new ByteArrayInputStream(output.toByteArray()),a);assertEquals(NotesStore.payload(a),exported.getJSONObject("notes").toString());String unchanged=WatchlistStore.payload(a);r.findViewWithTag("backup-import").performClick();assertEquals(Intent.ACTION_OPEN_DOCUMENT,Shadows.shadowOf(a).getNextStartedActivityForResult().intent.getAction());a.onActivityResult(42,Activity.RESULT_CANCELED,null);assertEquals(unchanged,WatchlistStore.payload(a));
        }finally{controller.pause().stop().destroy();RuntimeEnvironment.setFontScale(1f);}
    }
    @Test public void fileRestoreRequiresConfirmationAndRestoresSnapshotExactly()throws Exception{
        seed();JSONObject saved=UserDataBackup.capture(app);WatchlistStore.add(app,"601318","备份后的自选","保险");String current=WatchlistStore.payload(app);ActivityController<DataBackupActivity> controller=Robolectric.buildActivity(DataBackupActivity.class).setup();try{DataBackupActivity a=controller.get();Uri uri=Uri.parse("content://test/restore.json");Shadows.shadowOf(a.getContentResolver()).registerInputStream(uri,new ByteArrayInputStream(saved.toString().getBytes(StandardCharsets.UTF_8)));a.onActivityResult(42,Activity.RESULT_OK,new Intent().setData(uri));await(()->ShadowAlertDialog.getLatestAlertDialog()!=null&&ShadowAlertDialog.getLatestAlertDialog().isShowing());assertEquals(current,WatchlistStore.payload(app));AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertEquals(current,WatchlistStore.payload(app));Shadows.shadowOf(a.getContentResolver()).registerInputStream(uri,new ByteArrayInputStream(saved.toString().getBytes(StandardCharsets.UTF_8)));a.onActivityResult(42,Activity.RESULT_OK,new Intent().setData(uri));await(()->ShadowAlertDialog.getLatestAlertDialog()!=dialog&&ShadowAlertDialog.getLatestAlertDialog().isShowing());ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();await(()->!WatchlistStore.payload(app).equals(current)&&root(a).findViewWithTag("backup-import").isEnabled());assertEquals(saved.getJSONArray("watchlist").toString(),WatchlistStore.payload(app));assertEquals(saved.getJSONObject("notes").toString(),NotesStore.payload(app));
        }finally{controller.pause().stop().destroy();}
    }
    private void capture(Activity a,View v,String filename,int width,int height)throws Exception{int w=Ui.dp(a,width),h=Ui.dp(a,height);for(int i=0;i<2;i++){v.forceLayout();v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);}Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));if(filename.equals("apk-v1.31-watchlist-badges.png")){View badge=((ViewGroup)v.findViewWithTag("notes:industry:银行")).getChildAt(1);Rect bounds=new Rect(0,0,badge.getWidth(),badge.getHeight());((ViewGroup)v).offsetDescendantRectToMyCoords(badge,bounds);assertEquals("visible red note badge",Ui.RED,b.getPixel(bounds.left+Ui.dp(a,2),bounds.top+Ui.dp(a,7)));}try(FileOutputStream out=new FileOutputStream(new File(System.getProperty("aitrader.ui.outputs"),filename))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
}
