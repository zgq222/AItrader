package com.aitrader.hammer1430;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

/** Industry board daily candlesticks and volume, with an offline THS snapshot and optional LAN refresh. */
public final class BoardChartActivity extends Activity implements TraderApplication.Refreshable {
    public static final String EXTRA_INDUSTRY="industry",EXTRA_TYPE="board_type";
    private boolean concept;
    private static final String PREFS="board_chart";
    private static final String SERVER_URL="desktop_api_url";
    private final int red=Ui.RED,green=Ui.GREEN;
    private final List<Button> rangeButtons=new ArrayList<>();
    private String industry;
    private TextView source,detail,status,metricSource,metricDetail;
    private IndustryMetricChart strengthChart,breadthChart;
    private final Map<String,JSONObject> indicators=new HashMap<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener historyChanges=(p,key)->{if((concept?"concept_history_updated":"sector_history_updated").equals(key))runOnUiThread(this::loadIndicators);};
    private EditText serverAddress;
    private Button refreshButton;
    private boolean loading;
    private long lastRefresh;
    private BoardCandles chart;
    private List<Bar> bars=new ArrayList<>();
    private ChartOrientation orientation;

    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView label(String value,int size){return Ui.text(this,value,size,size<=14?Ui.SECONDARY:Ui.INK,size>=17);}
    private void add(LinearLayout parent,View child){parent.addView(child,new LinearLayout.LayoutParams(-1,-2));}

    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        orientation=new ChartOrientation(this,saved);
        industry=getIntent().getStringExtra(EXTRA_INDUSTRY);
        concept="concept".equals(getIntent().getStringExtra(EXTRA_TYPE));
        if(industry==null)industry="";
        LinearLayout root=Ui.column(this);setContentView(root);Ui.install(this,root);ScrollView scroll=new ScrollView(this);scroll.setTag("chart-scroll");root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(16),dp(12),dp(16),dp(18));
        scroll.addView(page);LinearLayout nav=Ui.row(this);Ui.Icon back=Ui.iconButton(this,"back",concept?"返回股票列表":"返回行业列表");back.setOnClickListener(v->finish());nav.addView(back,new LinearLayout.LayoutParams(dp(44),dp(44)));
        TextView caption=label(concept?"概念板块":"行业板块",14);caption.setPadding(dp(10),0,0,0);nav.addView(caption,new LinearLayout.LayoutParams(0,-2,1));nav.addView(orientation.button(),new LinearLayout.LayoutParams(dp(64),dp(44)));nav.setPadding(dp(16),dp(12),dp(16),0);root.addView(nav,0,new LinearLayout.LayoutParams(-1,-2));Ui.gap(page,6);
        add(page,label(industry,28));Ui.gap(page,8);
        if(concept)try{ConceptCatalog catalog=new ConceptCatalog(this);catalog.update(this);JSONObject rank=catalog.rank(industry);add(page,label((rank==null?industry:catalog.tagText(rank))+" · 排名行情日 "+catalog.tradeDate()+" · 成员快照 "+catalog.memberDates.optString(industry,"—"),12));Ui.gap(page,8);}catch(Exception ignored){}
        source=label("正在读取内置板块行情…",13);add(page,source);
        Ui.gap(page,16);
        status=label("",13);add(page,status);
        LinearLayout selected=Ui.card(page);add(selected,label("所选交易日",12));Ui.gap(selected,8);detail=label("",14);add(selected,detail);Ui.gap(selected,10);metricDetail=label("指标待加载",12);add(selected,metricDetail);
        LinearLayout chartHeading=Ui.row(this);chartHeading.addView(label("板块日K",20),new LinearLayout.LayoutParams(0,-2,1));chartHeading.addView(orientation.button(true),new LinearLayout.LayoutParams(dp(92),dp(40)));LinearLayout.LayoutParams chartTitleParams=new LinearLayout.LayoutParams(-1,-2);chartTitleParams.setMargins(dp(3),dp(14),dp(3),dp(12));page.addView(chartHeading,chartTitleParams);LinearLayout chartCard=Ui.card(page);chartCard.setPadding(dp(8),dp(8),dp(8),dp(8));
        chart=new BoardCandles();chart.setOnBarSelected(this::showBar);chartCard.addView(chart,new LinearLayout.LayoutParams(-1,dp(390)));
        LinearLayout ranges=Ui.row(this);ranges.setPadding(dp(4),dp(4),dp(4),dp(4));ranges.setBackground(Ui.shape(this,Ui.BG,12));add(chartCard,ranges);
        for(int count:new int[]{30,60,120,240}){Button button=Ui.button(this,count+"日",false);button.setTextSize(12);button.setTag(count);Ui.segment(button,count==60);rangeButtons.add(button);
            ranges.addView(button,new LinearLayout.LayoutParams(0,dp(44),1));button.setOnClickListener(v->{chart.setVisibleCount(count);for(Button b:rangeButtons)Ui.segment(b,b.getTag().equals(count));});}
        Ui.section(page,"强度图","0线 · 跑赢市场");LinearLayout strengthCard=Ui.card(page);strengthCard.setPadding(dp(8),dp(8),dp(8),dp(8));strengthChart=new IndustryMetricChart(this,true);strengthChart.setWindowListener((end,count,selectedIndex)->chart.setViewport(end,count,selectedIndex));strengthCard.addView(strengthChart,new LinearLayout.LayoutParams(-1,dp(218)));
        Ui.section(page,"广度图","50%参考线");LinearLayout breadthCard=Ui.card(page);breadthCard.setPadding(dp(8),dp(8),dp(8),dp(8));breadthChart=new IndustryMetricChart(this,false);breadthChart.setWindowListener((end,count,selectedIndex)->chart.setViewport(end,count,selectedIndex));breadthCard.addView(breadthChart,new LinearLayout.LayoutParams(-1,dp(218)));
        metricSource=label("正在读取强度与广度历史…",12);add(page,metricSource);Ui.gap(page,10);add(page,label("强度：成分股等权涨幅−主板非ST市场等权涨幅。广度：有效成员占比。三张图按同一日期联动拖动、缩放、点选；缺项留空。",12));
        Ui.gap(page,6);
        add(page,label("填写电脑地址后每10分钟自动刷新；左右滑动查看历史，点选K线查看价格与成交量。",12));
        Ui.section(page,"更新来源","可选");LinearLayout connectionCard=Ui.card(page);add(connectionCard,label("电脑局域网地址",15));Ui.gap(connectionCard,8);
        serverAddress=new EditText(this);serverAddress.setSingleLine(true);serverAddress.setTextSize(14);
        serverAddress.setHint("http://192.168.1.8:8000");serverAddress.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        serverAddress.setText(getSharedPreferences(PREFS,MODE_PRIVATE).getString(SERVER_URL,""));add(connectionCard,serverAddress);Ui.gap(connectionCard,12);
        Button refresh=Ui.button(this,"更新板块日K",true);refreshButton=refresh;add(connectionCard,refresh);
        refresh.setOnClickListener(v->refreshFromDesktop(refresh));
        if(orientation.landscape()&&getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE){
            page.removeAllViews();page.setPadding(dp(12),dp(4),dp(12),dp(8));nav.setPadding(dp(12),dp(4),dp(12),0);caption.setText(industry+" · "+(concept?"概念日K":"行业日K"));Ui.compact(caption);
            chart.getLayoutParams().height=dp(Math.max(210,getResources().getConfiguration().screenHeightDp-150));add(page,chartCard);
            add(page,selected);Ui.section(page,"强度图 / 广度图","同日期联动");LinearLayout metrics=Ui.row(this);metrics.setGravity(android.view.Gravity.TOP);metrics.addView(strengthCard,new LinearLayout.LayoutParams(0,-2,1));metrics.addView(breadthCard,new LinearLayout.LayoutParams(0,-2,1));add(page,metrics);
            add(page,metricSource);add(page,source);add(page,status);add(page,connectionCard);
        }
        page.setFocusableInTouchMode(true);page.requestFocus();
        loadSnapshot();loadIndicators();
        if(saved!=null&&saved.containsKey("chart_end")){chart.setViewport(saved.getInt("chart_end"),saved.getInt("chart_count",60),saved.getInt("chart_selected",-1));for(Button b:rangeButtons)Ui.segment(b,b.getTag().equals(chart.visibleCount));}
        refreshData();
    }

    @Override protected void onSaveInstanceState(Bundle out){if(orientation!=null)orientation.save(out);if(chart!=null){out.putInt("chart_end",chart.endIndex);out.putInt("chart_count",chart.visibleCount);out.putInt("chart_selected",chart.selected);}super.onSaveInstanceState(out);}
    @Override protected void onStart(){super.onStart();getSharedPreferences("scan",MODE_PRIVATE).registerOnSharedPreferenceChangeListener(historyChanges);loadIndicators();}
    @Override protected void onStop(){getSharedPreferences("scan",MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(historyChanges);super.onStop();}
    @Override protected void onResume(){super.onResume();
        if(android.os.SystemClock.elapsedRealtime()-lastRefresh>=RefreshSchedule.INTERVAL_MS)refreshData();}
    @Override public void refreshData(){
        loadIndicators();
        if(serverAddress!=null&&!serverAddress.getText().toString().trim().isEmpty())refreshFromDesktop(refreshButton);
    }
    private void loadIndicators(){if(metricSource==null)return;
        try{JSONObject payload=SectorHistoryStore.load(this,industry,concept);JSONArray rows=payload.getJSONArray("data");Map<String,JSONObject> loaded=new HashMap<>();for(int i=0;i<rows.length();i++){JSONObject r=rows.optJSONObject(i);if(r!=null&&r.optString("date").matches("\\d{4}-\\d{2}-\\d{2}"))loaded.put(r.getString("date"),r);}
            indicators.clear();indicators.putAll(loaded);metricSource.setText(payload.optString("source")+"\n指标截至 "+payload.optString("latest_date","—")+" · "+rows.length()+"个交易日 · 当前主板非ST成员");
            strengthChart.setData(bars,indicators);breadthChart.setData(bars,indicators);syncMetricWindow();showBar(chart.selected);
        }catch(Exception e){metricSource.setText("强度 / 广度读取失败："+e.getMessage()+"；已有数据保留");}
    }
    private void syncMetricWindow(){if(strengthChart==null||chart==null)return;strengthChart.setWindow(chart.endIndex,chart.visibleCount,chart.selected);breadthChart.setWindow(chart.endIndex,chart.visibleCount,chart.selected);}
    private String metric(JSONObject r,String key,boolean relative){double v=r==null?Double.NaN:r.optDouble(key,Double.NaN);return Double.isFinite(v)?String.format(Locale.CHINA,relative?"%+.2f":"%.1f%%",v):"—";}
    private static byte[] read(InputStream stream)throws Exception{
        try(InputStream input=stream;ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] chunk=new byte[8192];int size;
            while((size=input.read(chunk))>=0){output.write(chunk,0,size);if(output.size()>6_000_000)throw new Exception("板块数据超过可读取大小");}
            return output.toByteArray();
        }
    }
    private static String utf8(byte[] data){return new String(data,StandardCharsets.UTF_8);}
    private void loadSnapshot(){
        try{
            JSONObject index=new JSONObject(utf8(read(getAssets().open(concept?"concept_kline_index.json":"board_kline_index.json"))));
            String code=index.getJSONObject("boards").optString(industry,"");
            if(code.isEmpty())throw new Exception("内置快照暂无该板块");
            JSONObject payload=new JSONObject(utf8(read(getAssets().open((concept?"concept_klines/":"board_klines/")+code+".json"))));
            List<Bar> loaded=parseRows(payload.getJSONArray("data"));
            if(loaded.isEmpty())throw new Exception("该板块暂无有效日K");
            show(loaded,payload.optString("source","同花顺行业板块日K（内置快照）"),payload.optString("latest_date",""));
        }catch(Exception error){source.setText("内置板块日K不可用");status.setText(error.getMessage());}
    }
    private void refreshFromDesktop(Button button){
        if(loading)return;
        String base=serverAddress.getText().toString().trim();
        if(base.isEmpty()){status.setText("请填入运行网页服务的电脑局域网地址；离线快照仍可查看。");return;}
        if(!base.startsWith("http://")&&!base.startsWith("https://")){status.setText("地址应以 http:// 或 https:// 开头");return;}
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(SERVER_URL,base).apply();
        loading=true;lastRefresh=android.os.SystemClock.elapsedRealtime();
        button.setEnabled(false);status.setText("正在从电脑读取最新板块日K…");
        new Thread(()->{
            HttpURLConnection connection=null;
            try{
                String endpoint=base.replaceAll("/+$","")+"/api/board/kline?type="+(concept?"concept":"industry")+"&name="
                        +URLEncoder.encode(industry,"UTF-8")+"&period=day";
                connection=(HttpURLConnection)new URL(endpoint).openConnection();
                connection.setConnectTimeout(4000);connection.setReadTimeout(8000);
                int response=connection.getResponseCode();
                if(response!=200)throw new Exception("接口返回 HTTP "+response);
                JSONObject payload=new JSONObject(utf8(read(connection.getInputStream())));
                List<Bar> loaded=parseRows(payload.getJSONArray("data"));
                if(loaded.isEmpty())throw new Exception("电脑接口没有该板块日K");
                String feed=payload.optString("source","电脑网页服务"),date=payload.optString("latest_date",loaded.get(loaded.size()-1).date);
                runOnUiThread(()->{if(isFinishing()||isDestroyed())return;show(loaded,feed+" · 电脑接口",date);
                    status.setText("已更新，当前显示电脑上的板块数据。");loading=false;button.setEnabled(true);});
            }catch(Exception error){String reason=error.getMessage()==null?error.toString():error.getMessage();
                runOnUiThread(()->{if(isFinishing()||isDestroyed())return;
                    status.setText("电脑接口读取失败："+reason+"；继续显示当前数据。");loading=false;button.setEnabled(true);});
            }finally{if(connection!=null)connection.disconnect();}
        },"board-kline-refresh").start();
    }
    private static List<Bar> parseRows(JSONArray data){
        List<Bar> parsed=new ArrayList<>();
        for(int i=Math.max(0,data.length()-240);i<data.length();i++){
            JSONArray compact=data.optJSONArray(i);JSONObject row=data.optJSONObject(i);
            String date=compact!=null?compact.optString(0):row==null?"":row.optString("date");
            double open=compact!=null?compact.optDouble(1,Double.NaN):row==null?Double.NaN:row.optDouble("open",Double.NaN);
            double high=compact!=null?compact.optDouble(2,Double.NaN):row==null?Double.NaN:row.optDouble("high",Double.NaN);
            double low=compact!=null?compact.optDouble(3,Double.NaN):row==null?Double.NaN:row.optDouble("low",Double.NaN);
            double close=compact!=null?compact.optDouble(4,Double.NaN):row==null?Double.NaN:row.optDouble("close",Double.NaN);
            double volume=compact!=null?compact.optDouble(5,Double.NaN):row==null?Double.NaN:row.optDouble("volume",Double.NaN);
            double amount=compact!=null?compact.optDouble(6,Double.NaN):row==null?Double.NaN:row.optDouble("amount",Double.NaN);
            if(date.isEmpty()||!Double.isFinite(open)||!Double.isFinite(high)||!Double.isFinite(low)
                    ||!Double.isFinite(close)||!Double.isFinite(volume)||low<=0||high<low||volume<0)continue;
            parsed.add(new Bar(date,open,high,low,close,volume,amount));
        }
        return parsed;
    }
    private static String quantity(double value){
        if(!Double.isFinite(value))return "—";
        if(Math.abs(value)>=1e8)return String.format(Locale.CHINA,"%.2f亿",value/1e8);
        if(Math.abs(value)>=1e4)return String.format(Locale.CHINA,"%.2f万",value/1e4);
        return String.format(Locale.CHINA,"%.0f",value);
    }
    private void show(List<Bar> loaded,String feed,String latest){
        bars=loaded;chart.setBars(bars);if(strengthChart!=null){strengthChart.setData(bars,indicators);breadthChart.setData(bars,indicators);syncMetricWindow();}
        source.setText(feed.replace("概念板块日K","概念").replace("行业板块日K","行业").replace("（内置快照）"," · 内置快照")+"\n截至 "+latest+" · "+bars.size()+"个交易日");
        showBar(chart.selected);
    }
    private void showBar(int index){
        if(index<0||index>=bars.size())return;
        Bar bar=bars.get(index),prior=index>0?bars.get(index-1):null;
        double change=prior!=null&&prior.close>0?(bar.close/prior.close-1)*100:Double.NaN;
        String changeText=Double.isFinite(change)?String.format(Locale.CHINA,"%+.2f%%",change):"—";
        String value=bar.date+String.format(Locale.CHINA,"\n开  %.2f    高  %.2f\n低  %.2f    收  %.2f",bar.open,bar.high,bar.low,bar.close)
                +"\n涨跌  "+changeText+"  ·  成交量 "+quantity(bar.volume)+"\n成交额  "+quantity(bar.amount);
        android.text.SpannableString styled=new android.text.SpannableString(value);int start=value.indexOf(changeText,value.indexOf("涨跌"));
        if(Double.isFinite(change))styled.setSpan(new android.text.style.ForegroundColorSpan(change>=0?red:green),start,start+changeText.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        detail.setTextColor(Ui.INK);detail.setText(styled);
        JSONObject r=indicators.get(bar.date);if(metricDetail!=null)metricDetail.setText("同日强度（百分点）  5日 "+metric(r,"relative_5d",true)+" / 20日 "+metric(r,"relative_20d",true)+"\n同日广度  跑赢市场 "+metric(r,"outperform_5d_pct",false)+" / 站上MA20 "+metric(r,"above_ma20_pct",false)+(r==null?"\n该日期没有指标历史，缺项留空":"\n有效样本：5日 "+r.optInt("covered_5d")+" / 20日 "+r.optInt("covered_20d")+" / MA20 "+r.optInt("ma20_covered")));
        syncMetricWindow();
    }

    static final class Bar {
        final String date;final double open,high,low,close,volume,amount;
        Bar(String date,double open,double high,double low,double close,double volume,double amount){
            this.date=date;this.open=open;this.high=high;this.low=low;this.close=close;this.volume=volume;this.amount=amount;
        }
    }
    private final class BoardCandles extends View {
        interface OnBarSelected {void selected(int index);}
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private List<Bar> rows=new ArrayList<>();
        private OnBarSelected listener;
        private int visibleCount=60,endIndex=0,selected=-1,downEnd=0;
        private float downX;private boolean dragged;
        private final ScaleGestureDetector zoom;
        BoardCandles(){super(BoardChartActivity.this);setBackgroundColor(Ui.WHITE);setContentDescription(concept?"概念板块日K与成交量图":"行业板块日K与成交量图");zoom=new ScaleGestureDetector(BoardChartActivity.this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){if(rows.isEmpty())return false;int count=Math.max(Math.min(20,rows.size()),Math.min(Math.min(240,rows.size()),Math.round(visibleCount/d.getScaleFactor())));setViewport(Math.max(Math.min(count,rows.size()),endIndex),count,selected);dragged=true;return true;}});}
        void setOnBarSelected(OnBarSelected callback){listener=callback;}
        void setBars(List<Bar> data){
            boolean first=rows.isEmpty(),atLatest=endIndex>=rows.size(),selectedLatest=selected==rows.size()-1;
            String selectedDate=selected>=0&&selected<rows.size()?rows.get(selected).date:null;
            rows=data;endIndex=first||atLatest?rows.size():Math.min(endIndex,rows.size());
            selected=first||selectedLatest?rows.size()-1:Math.min(selected,rows.size()-1);
            if(!first&&!selectedLatest&&selectedDate!=null)for(int i=0;i<rows.size();i++)if(selectedDate.equals(rows.get(i).date)){selected=i;break;}
            invalidate();
        }
        void setViewport(int end,int count,int index){visibleCount=Math.max(1,count);endIndex=Math.max(Math.min(visibleCount,rows.size()),Math.min(rows.size(),end));selected=Math.max(Math.max(0,endIndex-visibleCount),Math.min(endIndex-1,index));invalidate();if(listener!=null)listener.selected(selected);syncMetricWindow();}
        void setVisibleCount(int count){visibleCount=count;endIndex=rows.size();selected=rows.size()-1;invalidate();if(listener!=null)listener.selected(selected);}
        private void line(Canvas canvas,float x1,float y1,float x2,float y2,int color,float width){
            paint.setColor(color);paint.setStrokeWidth(width);paint.setStyle(Paint.Style.STROKE);canvas.drawLine(x1,y1,x2,y2,paint);paint.setStyle(Paint.Style.FILL);
        }
        private void caption(Canvas canvas,String value,float x,float y,int color,int size){
            paint.setColor(color);paint.setTextSize(dp(size));paint.setStyle(Paint.Style.FILL);canvas.drawText(value,x,y,paint);
        }
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);
            if(rows.isEmpty()){caption(canvas,"暂无板块日K",dp(16),dp(28),Color.GRAY,15);return;}
            int start=Math.max(0,endIndex-visibleCount),count=endIndex-start;
            if(count<=0)return;
            float left=dp(6),right=getWidth()-dp(48),top=dp(30),priceBottom=getHeight()*0.68f;
            float volumeTop=getHeight()*0.76f,volumeBottom=getHeight()-dp(35),width=(right-left)/count;
            double minimum=Double.POSITIVE_INFINITY,maximum=Double.NEGATIVE_INFINITY,maxVolume=0;
            for(int i=start;i<endIndex;i++){Bar row=rows.get(i);minimum=Math.min(minimum,row.low);maximum=Math.max(maximum,row.high);maxVolume=Math.max(maxVolume,row.volume);}
            double pad=Math.max((maximum-minimum)*0.07,0.01);minimum-=pad;maximum+=pad;
            for(int i=0;i<4;i++){float y=top+(priceBottom-top)*i/3f;
                line(canvas,left,y,right,y,Color.rgb(227,232,237),dp(1));
                caption(canvas,String.format(Locale.CHINA,"%.1f",maximum-(maximum-minimum)*i/3),right+dp(3),y+dp(4),Color.GRAY,10);
            }
            for(int i=start;i<endIndex;i++){
                Bar row=rows.get(i);float x=left+(i-start+0.5f)*width;
                float highY=top+(float)((maximum-row.high)/(maximum-minimum))*(priceBottom-top);
                float lowY=top+(float)((maximum-row.low)/(maximum-minimum))*(priceBottom-top);
                float openY=top+(float)((maximum-row.open)/(maximum-minimum))*(priceBottom-top);
                float closeY=top+(float)((maximum-row.close)/(maximum-minimum))*(priceBottom-top);
                int color=row.close>=row.open?red:green;
                line(canvas,x,highY,x,lowY,color,dp(1));paint.setColor(color);
                float half=Math.max(dp(1),Math.min(width*0.36f,dp(7)));
                canvas.drawRect(x-half,Math.min(openY,closeY),x+half,Math.max(Math.max(openY,closeY),Math.min(openY,closeY)+dp(1)),paint);
                float height=maxVolume>0?(float)(row.volume/maxVolume)*(volumeBottom-volumeTop):0;
                canvas.drawRect(x-half,volumeBottom-height,x+half,volumeBottom,paint);
            }
            caption(canvas,"成交量",left,volumeTop-dp(5),Color.DKGRAY,11);
            caption(canvas,quantity(maxVolume),right-dp(10),volumeTop-dp(5),Color.GRAY,10);
            for(int step=0;step<4;step++){
                int index=start+(count-1)*step/3;float x=left+(index-start+0.5f)*width;
                caption(canvas,rows.get(index).date.substring(Math.max(0,rows.get(index).date.length()-5)),x-dp(15),getHeight()-dp(11),Color.GRAY,10);
            }
            if(selected>=start&&selected<endIndex){float x=left+(selected-start+0.5f)*width;
                line(canvas,x,top,x,volumeBottom,Color.rgb(75,104,134),dp(1));
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            zoom.onTouchEvent(event);if(rows.isEmpty())return false;float width=(getWidth()-dp(54))/Math.max(1,Math.min(visibleCount,endIndex));
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:downX=event.getX();downEnd=endIndex;dragged=false;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);return true;
                case MotionEvent.ACTION_MOVE:if(event.getPointerCount()>1)return true;float shift=event.getX()-downX;if(Math.abs(shift)>dp(7))dragged=true;if(dragged)setViewport(downEnd-Math.round(shift/Math.max(width,1)),visibleCount,selected);return true;
                case MotionEvent.ACTION_UP:if(!dragged){int start=Math.max(0,endIndex-visibleCount),n=endIndex-start;int index=start+(int)((event.getX()-dp(6))/Math.max(1,(getWidth()-dp(54))/Math.max(1,n)));setViewport(endIndex,visibleCount,index);}if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);performClick();return true;
                case MotionEvent.ACTION_CANCEL:if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);return true;
                default:return true;
            }
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
