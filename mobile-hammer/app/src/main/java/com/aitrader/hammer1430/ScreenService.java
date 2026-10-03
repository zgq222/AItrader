package com.aitrader.hammer1430;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScreenService extends Service {
    static final String CHANNEL="selection_results";
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
    private NotificationManager manager;
    @Override public void onCreate(){super.onCreate();manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"AItrader行情更新",NotificationManager.IMPORTANCE_DEFAULT));}
    @Override public IBinder onBind(Intent intent){return null;}
    private Notification notification(String title,String detail){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle(title)
                .setContentText(detail).setStyle(new Notification.BigTextStyle().bigText(detail)).setContentIntent(open).setAutoCancel(true).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        Notification progress=notification("AItrader刷新中","正在读取行情，在手机计算板块轮动");
        if(Build.VERSION.SDK_INT>=29)startForeground(1430,progress,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1430,progress);
        if(!RUNNING.compareAndSet(false,true))return START_NOT_STICKY;
        saveStatus("正在同步最新行情…");
        new Thread(()->{try{refreshFavorites();runScan();}catch(Exception error){String detail=error.getMessage()==null?error.toString():error.getMessage();
                saveStatus("轮动刷新失败："+detail);manager.notify(1431,notification("AItrader刷新失败",detail));}
            finally{RUNNING.set(false);stopSelf();}},"selection-scan").start();
        return START_NOT_STICKY;
    }
    private SharedPreferences prefs(){return getSharedPreferences("scan",MODE_PRIVATE);}
    private void saveStatus(String status){prefs().edit().putString("status",status).apply();}
    private String date(long millis){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.CHINA);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));return f.format(new Date(millis));}
    private String tradeDate(List<TencentClient.Quote> quotes)throws Exception{
        Map<String,Integer> votes=new HashMap<>();
        for(TencentClient.Quote q:quotes){String day=date(q.updatedAt*1000L);votes.put(day,votes.getOrDefault(day,0)+1);}
        String result=null;int count=0;
        for(Map.Entry<String,Integer> vote:votes.entrySet())if(vote.getValue()>count){result=vote.getKey();count=vote.getValue();}
        if(result==null||count<quotes.size()*0.8)throw new Exception("最新交易日行情不完整");return result;
    }
    private List<String> historyDates(String latest)throws Exception{
        List<DailyBar> reference=TencentClient.history("600519",latest);
        TreeSet<String> days=new TreeSet<>();days.add(latest);
        for(DailyBar bar:reference)if(bar.date.compareTo(latest)<=0)days.add(bar.date);
        List<String> result=new ArrayList<>(days);
        if(result.size()<30)throw new Exception("无法确定最近30个交易日");return result;
    }
    private Map<String,List<DailyBar>> loadHistories(List<TencentClient.Quote> quotes,String latest)throws Exception{
        DailyHistoryCache dailyCache=new DailyHistoryCache(this);
        ExecutorService workers=Executors.newFixedThreadPool(16);
        List<Future<List<DailyBar>>> pending=new ArrayList<>();Map<String,List<DailyBar>> histories=new HashMap<>();
        int errors=0;
        try{
            for(TencentClient.Quote q:quotes)pending.add(workers.submit(()->dailyCache.load(q,latest)));
            for(int i=0;i<pending.size();i++){
                try{histories.put(quotes.get(i).code,pending.get(i).get());}catch(ExecutionException error){errors++;}
                if(i%100==0||i==pending.size()-1)saveStatus("正在同步主板非ST日K："+(i+1)+"/"+quotes.size()+"，同步行业日K");
            }
        }finally{workers.shutdownNow();}
        if(errors>0)throw new Exception(errors+" 只股票日K未获取，行业数据不完整；请重试，已成功的日K会复用缓存");
        return histories;
    }
    private static Object finite(double value){return Double.isFinite(value)?value:JSONObject.NULL;}
    private JSONObject sectorRotation(List<TencentClient.Quote> quotes,List<TencentClient.Quote> allQuotes,Map<String,List<DailyBar>> histories,List<String> dates,List<String> historyDates,boolean finalClose)throws Exception{
        IndustryCatalog catalog=new IndustryCatalog(this);List<SectorRotationRule.Stock> inputs=new ArrayList<>();
        Map<String,TencentClient.Quote> latestQuotes=new HashMap<>();
        for(TencentClient.Quote q:allQuotes)latestQuotes.put(q.code,q);
        for(TencentClient.Quote q:quotes){latestQuotes.put(q.code,q);
            inputs.add(new SectorRotationRule.Stock(q.code,q.name,catalog.industry(q.code),histories.get(q.code)));}
        // Keep members without a current quote visible at the end of their industry.
        try(java.io.InputStream input=getAssets().open("mainboard_catalog.json")){
            java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];int read;
            while((read=input.read(buffer))!=-1)output.write(buffer,0,read);
            JSONObject names=new JSONObject(output.toString("UTF-8"));
            Iterator<String> codes=names.keys();while(codes.hasNext()){String code=codes.next();if(latestQuotes.containsKey(code))continue;
                inputs.add(new SectorRotationRule.Stock(code,names.getString(code),catalog.industry(code),Collections.emptyList()));}
        }
        for(TencentClient.Quote q:allQuotes)if(!quotes.contains(q))
            inputs.add(new SectorRotationRule.Stock(q.code,q.name,catalog.industry(q.code),Collections.emptyList()));
        List<SectorRotationRule.Sector> sectors=SectorRotationRule.calculate(inputs,dates,catalog.industries());
        SectorHistoryStore.save(this,SectorHistoryRule.calculate(inputs,historyDates,catalog.industries()));
        JSONArray industries=new JSONArray(),stocks=new JSONArray();
        for(SectorRotationRule.Sector sector:sectors){
            industries.put(new JSONObject().put("name",sector.name).put("rank",sector.rank>0?sector.rank:JSONObject.NULL)
                    .put("score",finite(sector.score)).put("strength_score",finite(sector.strength)).put("breadth_score",finite(sector.breadth))
                    .put("relative_5d",finite(sector.relative5)).put("relative_20d",finite(sector.relative20))
                    .put("outperform_5d_pct",finite(sector.outperform)).put("above_ma20_pct",finite(sector.above)).put("up_pct",finite(sector.up))
                    .put("covered_5d",sector.gains5.size()).put("covered_20d",sector.gains20.size()).put("ma20_covered",sector.maCovered)
                    .put("member_count",sector.stocks.size()));
            for(SectorRotationRule.Stock stock:sector.stocks){JSONObject item=new JSONObject().put("code",stock.code).put("name",stock.name)
                    .put("industry",stock.industry).put("sector_rank",stock.sectorRank);
                SelectionRule.Gain gain=stock.gain;
                item.put("return_30d_pct",gain==null?JSONObject.NULL:gain.percent);
                if(gain!=null)item.put("return_low_price",gain.low).put("return_low_date",gain.lowDate).put("return_end",gain.endDate).put("end_close",gain.close);
                stocks.put(item);
            }
        }
        return new JSONObject().put("trade_date",dates.get(dates.size()-1)).put("final_close",finalClose)
                .put("industries",industries).put("stocks",stocks).put("stock_count",stocks.length()).put("industry_count",industries.length())
                .put("quote_count",quotes.size()).put("method","four_metric_equal_percentile")
                .put("source","腾讯不复权日K · 手机独立计算 · 内置同花顺行业分类").put("concept_ranking",ConceptCatalog.calculate(this,inputs,dates,historyDates));
    }
    private boolean afterClose(List<TencentClient.Quote> quotes){
        long newest=0;for(TencentClient.Quote q:quotes)newest=Math.max(newest,q.updatedAt);
        Calendar c=Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));c.setTimeInMillis(newest*1000L);
        return c.get(Calendar.HOUR_OF_DAY)>=15;
    }
    static boolean isRunning(){return RUNNING.get();}
    private void refreshFavorites(){PersonalQuotes.load(this);}
    private void runScan()throws Exception{
        TencentClient.Snapshot snap=TencentClient.snapshot(this);String latest=tradeDate(snap.quotes);
        List<TencentClient.Quote> dayQuotes=new ArrayList<>();
        for(TencentClient.Quote q:snap.quotes)if(SelectionRule.eligible(q.code,q.name)&&latest.equals(date(q.updatedAt*1000L)))dayQuotes.add(q);
        if(dayQuotes.isEmpty())throw new Exception("当前交易日暂无有效主板非ST行情");
        String today=date(System.currentTimeMillis());Calendar now=Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        int hour=now.get(Calendar.HOUR_OF_DAY),minute=now.get(Calendar.MINUTE);
        if(latest.equals(today)&&hour>=9&&hour<15&&(hour>9||minute>=30)){
            long newest=0;for(TencentClient.Quote q:dayQuotes)newest=Math.max(newest,q.updatedAt);
            if(System.currentTimeMillis()-newest*1000L>20*60*1000L)throw new Exception("盘中行情超过20分钟未更新");
        }
        List<String> allDates=historyDates(latest);List<String> recentDates=new ArrayList<>(allDates.subList(allDates.size()-30,allDates.size()));
        Map<String,List<DailyBar>> histories=loadHistories(dayQuotes,latest);
        JSONObject rotation=sectorRotation(dayQuotes,snap.quotes,histories,recentDates,allDates,afterClose(dayQuotes));
        SimpleDateFormat time=new SimpleDateFormat("MM-dd HH:mm",Locale.CHINA);time.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        String status="板块轮动 "+rotation.getInt("industry_count")+"个行业 · 快照 "+time.format(new Date());
        prefs().edit().remove("cache_rule_version").remove("groups_cache").remove("groups_payload")
                .remove("selection_payload").putString("rotation_payload",rotation.toString()).putString("concept_payload",rotation.getJSONObject("concept_ranking").toString())
                .putString("status",status).putLong("updated",System.currentTimeMillis()).apply();
        manager.notify(1431,notification("AItrader数据已刷新",status));
    }
}
