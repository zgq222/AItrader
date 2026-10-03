package com.aitrader.hammer1430;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/** Stock detail using the APK's existing daily price source and shared cache. */
public final class StockChartActivity extends Activity implements TraderApplication.Refreshable {
    static final String EXTRA_CODE="stock_code",EXTRA_NAME="stock_name",EXTRA_MINUTE="open_minute",EXTRA_FOCUS="focus_bar_time";
    private final ArrayList<Button> indicatorButtons=new ArrayList<>();
    private final ArrayList<Button> rangeButtons=new ArrayList<>();
    private static final int[] DAILY_RANGES={30,60,120,240};
    private LinearLayout dailyRanges;
    private int dailyRange=60;
    private int[] dailyViewport,minuteViewport;
    // Fetch extra sessions to cover two calendar years, including data-source gaps.
    private static final int DAILY_HISTORY_BARS=750;
    private TextView status,detail,priceLabel,changeLabel,stopLabel,selectedHeading;
    private LinearLayout behaviorBox;
    private Button dailyButton,minuteButton,holdingButton;
    private boolean minuteMode;
    private int generation;
    private final List<DailyBar> dailyRows=new ArrayList<>(),minuteRows=new ArrayList<>();
    private StockChartView chart;
    private String stockCode,stockName;
    private boolean loading;
    private int[] restoredViewport;
    private String focusTime;
    private long lastRefresh;
    private ChartOrientation orientation;

    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String value,int size){
        return Ui.text(this,value,size,size<=14?Ui.SECONDARY:Ui.INK,size>=17);
    }
    @Override public void onCreate(Bundle state){super.onCreate(state);
        orientation=new ChartOrientation(this,state);
        String code=getIntent().getStringExtra(EXTRA_CODE),name=getIntent().getStringExtra(EXTRA_NAME);
        if(code==null||!code.matches("\\d{6}")){finish();return;}
        if(name==null||name.isEmpty())name=code;
        stockCode=code;stockName=name;minuteMode=state!=null?state.getBoolean("minute",false):getIntent().getBooleanExtra(EXTRA_MINUTE,false);restoredViewport=state==null?null:state.getIntArray("chart_viewport");focusTime=state==null?getIntent().getStringExtra(EXTRA_FOCUS):null;
        if(state!=null){dailyRange=state.getInt("daily_range",60);dailyViewport=state.getIntArray("daily_viewport");minuteViewport=state.getIntArray("minute_viewport");}
        if(getIntent().getBooleanExtra("holding_risk",false))try{PersonalSignalStore.acknowledge(this,code);}catch(Exception ignored){}
        LinearLayout root=Ui.column(this);setContentView(root);Ui.install(this,root);
        ScrollView scroll=new ScrollView(this);scroll.setTag("chart-scroll");scroll.setFillViewport(true);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16),dp(12),dp(16),dp(24));scroll.addView(page);
        LinearLayout nav=Ui.row(this);Ui.Icon back=Ui.iconButton(this,"back","返回股票列表");back.setOnClickListener(view->finish());nav.addView(back,new LinearLayout.LayoutParams(dp(44),dp(44)));
        TextView navigation=Ui.text(this,"股票详情",14,Ui.SECONDARY,false);navigation.setPadding(dp(10),0,0,0);nav.addView(navigation,new LinearLayout.LayoutParams(0,-2,1));
        nav.addView(NotesDialogs.button(this,NotesStore.stock(code),name,code,()->{}),new LinearLayout.LayoutParams(dp(48),dp(44)));
        nav.addView(orientation.button(),new LinearLayout.LayoutParams(dp(64),dp(44)));
        Ui.Icon refresh=Ui.iconButton(this,"refresh","刷新当前K线");refresh.setOnClickListener(v->refreshData());nav.addView(refresh,new LinearLayout.LayoutParams(dp(44),dp(44)));nav.setPadding(dp(16),dp(12),dp(16),0);root.addView(nav,0,new LinearLayout.LayoutParams(-1,-2));Ui.gap(page,4);
        LinearLayout heading=Ui.row(this),identity=Ui.column(this);Ui.add(identity,Ui.number(this,name,28,Ui.INK));Ui.gap(identity,6);Ui.add(identity,text(code,13));heading.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
        Button favorite=Ui.button(this,"＋ 自选",false);heading.addView(favorite,new LinearLayout.LayoutParams(dp(100),dp(44)));Ui.add(page,heading);Ui.gap(page,18);
        try{favorite.setText(WatchlistStore.contains(this,code)?"移出自选":"＋ 加自选");}
        catch(Exception error){favorite.setText("自选读取失败");favorite.setEnabled(false);}
        String favoriteName=name;
        favorite.setOnClickListener(view->{
            try{
                boolean exists=WatchlistStore.contains(this,code);
                if(exists)WatchlistStore.remove(this,code);
                else {
                    String industry="待分类";
                    try{industry=new IndustryCatalog(this).industry(code);}catch(Exception ignored){}
                    WatchlistStore.add(this,code,favoriteName,industry);
                }
                favorite.setText(exists?"＋ 加自选":"移出自选");
                android.widget.Toast.makeText(this,exists?"已移出自选":"已加入自选",android.widget.Toast.LENGTH_SHORT).show();
            }catch(Exception error){android.widget.Toast.makeText(this,error.getMessage(),android.widget.Toast.LENGTH_LONG).show();}
        });
        LinearLayout quote=Ui.card(page);Ui.add(quote,text("最新价",12));Ui.gap(quote,9);priceLabel=Ui.number(this,"—",36,Ui.INK);Ui.add(quote,priceLabel);Ui.gap(quote,8);
        changeLabel=text("行情待更新",14);Ui.add(quote,changeLabel);Ui.gap(quote,12);status=text("正在读取最新日K…",12);Ui.add(quote,status);
        LinearLayout holdings=Ui.card(page);LinearLayout holdingRow=Ui.row(this);stopLabel=text("加入持仓后可设置止损线",13);holdingRow.addView(stopLabel,new LinearLayout.LayoutParams(0,-2,1));holdingButton=Ui.button(this,"＋ 持仓",false);holdingRow.addView(holdingButton,new LinearLayout.LayoutParams(dp(106),dp(44)));Ui.add(holdings,holdingRow);
        holdingButton.setOnClickListener(v->{try{if(HoldingsStore.find(this,stockCode)==null)HoldingDialogs.add(this,stockCode,stockName,new IndustryCatalog(this).industry(stockCode),this::updateStop);else HoldingDialogs.editStop(this,stockCode,this::updateStop);}catch(Exception e){android.widget.Toast.makeText(this,e.getMessage(),android.widget.Toast.LENGTH_LONG).show();}});
        holdingButton.setOnLongClickListener(v->{try{if(HoldingsStore.find(this,stockCode)==null)return false;HoldingDialogs.manage(this,stockCode,stockName,this::updateStop);return true;}catch(Exception e){NotesDialogs.error(this,e);return true;}});
        LinearLayout periods=Ui.row(this);periods.setPadding(dp(4),dp(4),dp(4),dp(4));periods.setBackground(Ui.shape(this,Ui.LINE,12));dailyButton=Ui.button(this,"日K",false);minuteButton=Ui.button(this,"15分钟K线",false);dailyButton.setContentDescription("切换日K");minuteButton.setContentDescription("切换15分钟K线");periods.addView(dailyButton,new LinearLayout.LayoutParams(0,dp(44),1));periods.addView(minuteButton,new LinearLayout.LayoutParams(0,dp(44),1));Ui.add(page,periods);Ui.gap(page,16);
        dailyButton.setOnClickListener(v->choosePeriod(false));minuteButton.setOnClickListener(v->choosePeriod(true));
        LinearLayout chartHeading=Ui.row(this);chartHeading.addView(text("K线与指标",20),new LinearLayout.LayoutParams(0,-2,1));chartHeading.addView(orientation.button(true),new LinearLayout.LayoutParams(dp(92),dp(40)));LinearLayout.LayoutParams chartTitleParams=new LinearLayout.LayoutParams(-1,-2);chartTitleParams.setMargins(dp(3),dp(14),dp(3),dp(12));page.addView(chartHeading,chartTitleParams);LinearLayout chartCard=Ui.card(page);chartCard.setPadding(dp(8),dp(10),dp(8),dp(10));
        HorizontalScrollView chooser=new HorizontalScrollView(this);chooser.setHorizontalScrollBarEnabled(false);
        LinearLayout choices=Ui.row(this);choices.setPadding(dp(4),dp(4),dp(4),dp(4));choices.setBackground(Ui.shape(this,Ui.BG,12));chooser.addView(choices);
        chartCard.addView(chooser);
        for(int index=0;index<StockChartView.INDICATORS.length;index++){
            final int chosen=index;
            Button button=Ui.button(this,StockChartView.INDICATORS[index],false);button.setTextSize(12);button.setPadding(dp(4),dp(8),dp(4),dp(8));
            choices.addView(button,new LinearLayout.LayoutParams(dp(64),dp(44)));
            indicatorButtons.add(button);
            button.setOnClickListener(view->{chart.setIndicator(chosen);selectIndicator(chosen);});
        }
        selectIndicator(0);
        dailyRanges=Ui.row(this);dailyRanges.setTag("daily-ranges");dailyRanges.setPadding(dp(4),dp(4),dp(4),dp(4));dailyRanges.setBackground(Ui.shape(this,Ui.BG,12));
        for(int count:DAILY_RANGES){
            Button button=Ui.button(this,count+"日",false);button.setTag("daily-range:"+count);button.setSingleLine(true);button.setPadding(dp(4),dp(8),dp(4),dp(8));button.setAutoSizeTextTypeUniformWithConfiguration(10,13,1,android.util.TypedValue.COMPLEX_UNIT_SP);button.setContentDescription("查看最近"+count+"个交易日日K");
            rangeButtons.add(button);dailyRanges.addView(button,new LinearLayout.LayoutParams(0,dp(44),1));
            button.setOnClickListener(v->{dailyRange=count;restoredViewport=null;chart.setVisibleCount(count);updateRanges();});
        }
        Ui.gap(chartCard,8);Ui.add(chartCard,dailyRanges);updateRanges();
        chart=new StockChartView(this);chart.setMinimumHeight(dp(480));
        chartCard.addView(chart,new LinearLayout.LayoutParams(-1,dp(480)));
        LinearLayout selected=Ui.card(page);selectedHeading=text(minuteMode?"所选15分钟":"所选交易日",12);Ui.add(selected,selectedHeading);Ui.gap(selected,9);detail=text("点选K线查看当天数据",13);detail.setMinHeight(dp(72));Ui.add(selected,detail);
        chart.setOnBarSelected(index->detail.setText(chart.detail(index)));
        TextView hint=text("左右拖动查看历史 · 双指缩放 · 点选K线查看指标",12);page.addView(hint);
        behaviorBox=Ui.column(this);Ui.add(page,behaviorBox);
        if(orientation.landscape()&&getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE){
            page.removeAllViews();page.setPadding(dp(12),dp(4),dp(12),dp(8));nav.setPadding(dp(12),dp(4),dp(12),0);navigation.setText(name+"  "+code);Ui.compact(navigation);
            ((android.view.ViewGroup)priceLabel.getParent()).removeView(priceLabel);priceLabel.setTextSize(16);nav.addView(priceLabel,2,new LinearLayout.LayoutParams(dp(80),dp(32)));
            periods.removeView(dailyButton);periods.removeView(minuteButton);heading.removeView(favorite);
            nav.addView(dailyButton,3,new LinearLayout.LayoutParams(dp(60),dp(36)));nav.addView(minuteButton,4,new LinearLayout.LayoutParams(dp(105),dp(36)));nav.addView(favorite,5,new LinearLayout.LayoutParams(dp(88),dp(36)));
            int height=Math.max(240,getResources().getConfiguration().screenHeightDp-140);chart.setMinimumHeight(0);chart.getLayoutParams().height=dp(height);Ui.add(page,chartCard);
            Ui.add(page,selected);Ui.add(page,holdings);Ui.add(page,quote);Ui.add(page,behaviorBox);
        }
        chart.setMinuteMode(minuteMode);Ui.segment(dailyButton,!minuteMode);Ui.segment(minuteButton,minuteMode);updateStop();renderBehaviors();refreshData();
    }
    @Override protected void onResume(){super.onResume();
        updateStop();
        if(chart!=null&&android.os.SystemClock.elapsedRealtime()-lastRefresh>=RefreshSchedule.INTERVAL_MS)refreshData();}
    @Override public void refreshData(){
        if(chart==null||loading||stockCode==null)return;
        loading=true;status.setText(minuteMode?"正在读取15分钟K线…":"正在读取日K…");int request=++generation;boolean minute=minuteMode;lastRefresh=android.os.SystemClock.elapsedRealtime();
        new Thread(()->{try{load(stockCode,minute,request);}finally{runOnUiThread(()->{if(request==generation)loading=false;});}},"stock-chart-"+stockCode).start();
    }
    private void selectIndicator(int index){
        for(int i=0;i<indicatorButtons.size();i++){
            Button button=indicatorButtons.get(i);
            boolean active=i==index;
            Ui.segment(button,active);
        }
    }
    private void choosePeriod(boolean minute){
        if(minuteMode==minute)return;rememberViewport();generation++;loading=false;minuteMode=minute;restoredViewport=minute?minuteViewport:dailyViewport;chart.setMinuteMode(minute);selectedHeading.setText(minute?"所选15分钟":"所选交易日");detail.setText("点选K线查看数据");
        Ui.segment(dailyButton,!minute);Ui.segment(minuteButton,minute);updateRanges();List<DailyBar> cached=minute?minuteRows:dailyRows;if(!cached.isEmpty())showRows(cached);renderBehaviors();refreshData();
    }
    private void updateRanges(){dailyRanges.setVisibility(minuteMode?View.GONE:View.VISIBLE);for(int i=0;i<rangeButtons.size();i++)Ui.segment(rangeButtons.get(i),dailyRange==DAILY_RANGES[i]);}
    private void rememberViewport(){if(chart!=null&&chart.viewport()[0]>0){if(minuteMode)minuteViewport=chart.viewport();else dailyViewport=chart.viewport();}}
    private void showRows(List<DailyBar> rows){
        boolean first=chart.viewport()[0]==0;chart.setBars(rows);
        if(restoredViewport!=null){chart.restoreViewport(restoredViewport);selectIndicator(restoredViewport[3]);restoredViewport=null;}
        else if(first)chart.setVisibleCount(minuteMode?50:dailyRange);
    }
    @Override protected void onSaveInstanceState(Bundle state){if(orientation!=null)orientation.save(state);state.putBoolean("minute",minuteMode);rememberViewport();if(chart!=null)state.putIntArray("chart_viewport",restoredViewport!=null?restoredViewport:chart.viewport());state.putInt("daily_range",dailyRange);state.putIntArray("daily_viewport",dailyViewport);state.putIntArray("minute_viewport",minuteViewport);super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){generation++;super.onDestroy();}
    private void updateStop(){if(chart==null||stockCode==null)return;try{
        org.json.JSONObject h=HoldingsStore.find(this,stockCode);double stop=h==null?Double.NaN:h.optDouble("stop_price",Double.NaN);chart.setStopPrice(stop);
        holdingButton.setText(h==null?"＋ 持仓":"设置止损");
        List<DailyBar> rows=minuteMode?minuteRows:dailyRows;org.json.JSONObject quote=rows.isEmpty()?null:new org.json.JSONObject().put("latest_price",rows.get(rows.size()-1).close);
        stopLabel.setText(h==null?"加入持仓后可设置止损线":HoldingsStore.stopText(h,quote));stopLabel.setTextColor(quote!=null&&quote.optDouble("latest_price")<=stop?Ui.RED:Ui.SECONDARY);
    }catch(Exception e){stopLabel.setText("止损线读取失败");}}
    private void renderBehaviors(){
        if(behaviorBox==null)return;behaviorBox.removeAllViews();behaviorBox.setVisibility(minuteMode?View.VISIBLE:View.GONE);if(!minuteMode)return;
        Ui.section(behaviorBox,"主力行为框选","基于量价推测");LinearLayout card=Ui.card(behaviorBox);
        List<MinuteBehavior.Zone> zones=chart.behaviorZones();Ui.add(card,text("放量≥20交易日中位数×2.5 · 全部框选",13));Ui.gap(card,5);Ui.add(card,text("红框进攻 · 绿框减仓 · 黄框持平 · 包含框虚线",12));Ui.gap(card,10);
        Button rules=Ui.button(this,"查看框选规则",false);rules.setOnClickListener(v->new android.app.AlertDialog.Builder(this).setTitle("主力行为 · 量价推测").setMessage(MinuteBehavior.RULES).setPositiveButton("知道了",null).show());Ui.add(card,rules);
        if(minuteRows.isEmpty()){Ui.add(card,text("15分钟数据尚未就绪",13));return;}
        if(!chart.behaviorBaselineReady()){Ui.gap(card,10);Ui.add(card,text("当前日前不足20个完整交易日或基准为0，暂无放量判断，请刷新补齐历史。",13));}
        if(zones.isEmpty()){Ui.gap(card,10);Ui.add(card,text("有基准的历史范围未发现达到2.5倍中位量的区间",13));return;}
        Ui.gap(card,8);Ui.add(card,text("共 "+zones.size()+"个区间 · 点选定位（显示最近12个）",12));
        for(int i=zones.size()-1;i>=Math.max(0,zones.size()-12);i--){MinuteBehavior.Zone z=zones.get(i);Ui.line(card);TextView item=Ui.text(this,(i+1)+"  "+z.label()+"\n"+z.description(minuteRows),13,z.kind==MinuteBehavior.VOLUME?Ui.AMBER:z.attack?Ui.RED:Ui.GREEN,true);item.setMinHeight(dp(48));item.setOnClickListener(v->chart.focus(z.end));Ui.add(card,item);}
    }
    private void load(String code,boolean minute,int request){
        List<DailyBar> rows=null;String source="腾讯最新行情";Exception failure=null;
        if(minute){
            try{rows=TencentClient.minutes(code);MinuteCache.save(this,code,rows);}catch(Exception e){failure=e;}
            if(rows==null||rows.isEmpty())try{rows=MinuteCache.load(this,code);source="本机缓存（更新失败）";}catch(Exception ignored){}
        }else{
            DailyHistoryCache cache=new DailyHistoryCache(this);
            try{TencentClient.Quote q=TencentClient.quote(code);rows=cache.load(q,q.today.date,DAILY_HISTORY_BARS);}catch(Exception e){failure=e;}
            if(rows==null||rows.isEmpty())try{rows=cache.cached(code);source="本机缓存（更新失败）";}catch(Exception ignored){}
            if(rows==null||rows.isEmpty())try{rows=TencentClient.history(code,"9999-12-31",DAILY_HISTORY_BARS);source="腾讯历史日K";}catch(Exception e){failure=e;}
        }
        TreeMap<String,DailyBar> sorted=new TreeMap<>();if(rows!=null)for(DailyBar b:rows)if(b.open>0&&b.high>0&&b.low>0&&b.close>0)sorted.put(b.date,b);
        if(!minute&&!sorted.isEmpty()){
            String start=java.time.LocalDate.parse(sorted.lastKey()).minusYears(2).toString();
            sorted.headMap(start,false).clear();
        }
        List<DailyBar> ready=new ArrayList<>(sorted.values());String label=source;Exception error=failure;
        runOnUiThread(()->{
            if(isFinishing()||isDestroyed()||request!=generation||minute!=minuteMode)return;
            if(ready.isEmpty()){status.setText((minute?"15分钟K线":"日K")+"读取失败："+(error==null?"数据为空":error.getMessage())+"；已有图表保留");return;}
            List<DailyBar> target=minute?minuteRows:dailyRows;target.clear();target.addAll(ready);
            DailyBar last=ready.get(ready.size()-1);priceLabel.setText(String.format(Locale.CHINA,"%.2f",last.close));
            double base=minute?last.open:ready.size()>1?ready.get(ready.size()-2).close:Double.NaN;double change=(last.close/base-1)*100;
            changeLabel.setText(String.format(Locale.CHINA,"%s %+.2f%% · %s",minute?"本根涨跌":"日涨幅",change,last.date));changeLabel.setTextColor(change>=0?Ui.RED:Ui.GREEN);
            status.setText(label+" · "+last.date+" · "+ready.size()+"根"+(minute?"15分钟K线":"日K")+(minute&&MinuteBehavior.epoch(last.date)>System.currentTimeMillis()?" · 末根未结束":""));
            showRows(ready);if(minute&&focusTime!=null){for(int i=0;i<ready.size();i++)if(focusTime.equals(ready.get(i).date)){chart.focus(i);break;}focusTime=null;}updateStop();renderBehaviors();
        });
    }
}
