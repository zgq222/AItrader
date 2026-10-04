package com.aitrader.hammer1430;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Native daily candle, volume and selectable indicator chart; drag to pan, pinch to zoom. */
final class StockChartView extends View {
    interface OnBarSelected { void onSelected(int index); }
    static final String[] INDICATORS={"MACD","KDJ","RSI14","BOLL","VI14"};
    private static final int UP=Ui.RED,DOWN=Ui.GREEN;
    private static final int GRID=Ui.LINE,LABEL=Ui.SECONDARY;
    private static final int GOLD=Color.rgb(224,143,32),BLUE=Color.rgb(35,112,198),PURPLE=Color.rgb(138,78,166);
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint annotationPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    static final class BehaviorLabel {
        final int zoneIndex;
        final String text;
        final RectF bounds;
        BehaviorLabel(int index,String text,RectF bounds){zoneIndex=index;this.text=text;this.bounds=bounds;}
    }
    private final List<BehaviorLabel> behaviorLabels=new ArrayList<>();
    private final float density;
    private final ScaleGestureDetector scaleDetector;
    private List<DailyBar> bars=new ArrayList<>();
    private StockIndicators values;
    private OnBarSelected listener;
    private int visible=50,end=0,selected=-1,indicator=0;
    private float touchX,initialX;
    private int initialEnd;
    private boolean dragged;
    private boolean minute;
    private int period=ChartPeriod.DAY;
    private java.util.Map<String,String> periodSpans=new java.util.HashMap<>();
    private double stopPrice=Double.NaN;
    private List<MinuteBehavior.Zone> zones=new ArrayList<>();
    private java.util.Map<String,Double> volumeBaselines=new java.util.HashMap<>();

    StockChartView(Context context) {
        super(context);density=getResources().getDisplayMetrics().density;
        annotationPaint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        scaleDetector=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector detector){
                if(bars.isEmpty())return false;
                visible=Math.max(Math.min(18,bars.size()),Math.min(Math.min(minute?120:240,bars.size()),Math.round(visible/detector.getScaleFactor())));
                end=Math.max(visible,Math.min(end,bars.size()));invalidate();return true;
            }
        });
        setLayerType(View.LAYER_TYPE_SOFTWARE,null);
    }
    void setBars(List<DailyBar> data){
        boolean first=bars.isEmpty(),atLatest=end>=bars.size();
        String selectedDate=selected>=0&&selected<bars.size()?bars.get(selected).date:null;
        String endDate=end>0&&end<=bars.size()?bars.get(end-1).date:null;
        boolean selectedLatest=selected==bars.size()-1;
        bars=new ArrayList<>(data);values=new StockIndicators(bars);
        long now=System.currentTimeMillis();volumeBaselines=minute?MinuteBehavior.baselines(bars,now,period):new java.util.HashMap<>();
        zones=minute?MinuteBehavior.find(bars,now,volumeBaselines,period):new ArrayList<>();
        behaviorLabels.clear();
        if(first){visible=Math.min(50,bars.size());end=bars.size();}
        else {visible=Math.min(visible,bars.size());end=atLatest?bars.size():Math.min(end,bars.size());if(!atLatest&&endDate!=null)for(int i=0;i<bars.size();i++)if(endDate.equals(bars.get(i).date)){end=Math.max(visible,i+1);break;}}
        selected=bars.isEmpty()?-1:Math.min(selected,bars.size()-1);
        if(first||selectedLatest)selected=bars.size()-1;
        else if(selectedDate!=null)for(int i=0;i<bars.size();i++)if(selectedDate.equals(bars.get(i).date)){selected=i;break;}
        invalidate();if(listener!=null&&selected>=0)listener.onSelected(selected);
    }
    int[] viewport(){return new int[]{end,visible,selected,indicator};}
    void setVisibleCount(int count){if(bars.isEmpty())return;visible=Math.max(1,Math.min(count,bars.size()));end=bars.size();selected=end-1;invalidate();if(listener!=null)listener.onSelected(selected);}
    void restoreViewport(int[] values){if(values==null||values.length!=4||bars.isEmpty())return;visible=Math.max(1,Math.min(values[1],bars.size()));end=Math.max(visible,Math.min(values[0],bars.size()));selected=Math.max(-1,Math.min(values[2],bars.size()-1));indicator=Math.max(0,Math.min(values[3],INDICATORS.length-1));invalidate();if(listener!=null&&selected>=0)listener.onSelected(selected);}
    void setMinuteMode(boolean value){setPeriod(value?ChartPeriod.FIFTEEN:ChartPeriod.DAY);}
    void setPeriod(int value){period=value;minute=ChartPeriod.minute(value);bars=new ArrayList<>();end=0;selected=-1;zones.clear();volumeBaselines.clear();behaviorLabels.clear();periodSpans.clear();invalidate();}
    void setPeriodSpans(java.util.Map<String,String> values){periodSpans=new java.util.HashMap<>(values);}
    void setStopPrice(double value){stopPrice=value>0&&Double.isFinite(value)?value:Double.NaN;invalidate();}
    void focus(int index){if(bars.isEmpty())return;selected=Math.max(0,Math.min(index,bars.size()-1));end=Math.min(bars.size(),Math.max(visible,selected+Math.max(1,visible/3)));invalidate();if(listener!=null)listener.onSelected(selected);}
    List<MinuteBehavior.Zone> behaviorZones(){return new ArrayList<>(zones);}
    boolean behaviorBaselineReady(){return !bars.isEmpty()&&volumeBaselines.getOrDefault(bars.get(bars.size()-1).date.substring(0,10),0.0)>0;}
    List<BehaviorLabel> behaviorLabels(){return new ArrayList<>(behaviorLabels);}
    void setIndicator(int value){indicator=Math.max(0,Math.min(value,INDICATORS.length-1));invalidate();
        if(listener!=null&&selected>=0)listener.onSelected(selected);}
    void setOnBarSelected(OnBarSelected callback){listener=callback;}
    private float dp(float value){return value*density;}
    private void stroke(int color,float width){paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(width));}
    private void fill(int color){paint.setColor(color);paint.setStyle(Paint.Style.FILL);}
    private void label(Canvas canvas,String text,float x,float y,int color,float size){
        fill(color);paint.setTextSize(dp(size));canvas.drawText(text,x,y,paint);
    }
    private static String number(double value,int places){return Double.isFinite(value)?String.format(Locale.CHINA,"%+."+places+"f",value):"--";}
    private static String compact(double value){
        if(!Double.isFinite(value))return "--";
        if(Math.abs(value)>=100000000)return String.format(Locale.CHINA,"%.1f亿",value/100000000);
        if(Math.abs(value)>=10000)return String.format(Locale.CHINA,"%.1f万",value/10000);
        return String.format(Locale.CHINA,"%.0f",value);
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        canvas.drawColor(Color.WHITE);
        behaviorLabels.clear();
        if(bars.isEmpty()){label(canvas,"暂无"+ChartPeriod.label(period)+"数据",dp(24),dp(48),LABEL,15);return;}
        int start=Math.max(0,end-visible),count=end-start;
        float left=dp(13),right=getWidth()-dp(47);
        float priceTop=minute?layoutBehaviorLabels(start,end,left):dp(36),priceBottom=getHeight()*.53f;
        float volumeTop=priceBottom+dp(25),volumeBottom=getHeight()*.72f;
        float extraTop=volumeBottom+dp(30),extraBottom=getHeight()-dp(31);
        if(getHeight()<dp(340)){
            extraTop=extraBottom-dp(36);volumeBottom=extraTop-dp(24);
            volumeTop=volumeBottom-dp(20);priceBottom=volumeTop-dp(20);
        }
        float slot=(right-left)/Math.max(1,count);
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY,volumeMax=0;
        for(int i=start;i<end;i++){
            DailyBar bar=bars.get(i);min=Math.min(min,bar.low);max=Math.max(max,bar.high);
            if(Double.isFinite(bar.volume))volumeMax=Math.max(volumeMax,bar.volume);
            if(indicator==3&&Double.isFinite(values.bollLower[i])){
                min=Math.min(min,values.bollLower[i]);max=Math.max(max,values.bollUpper[i]);
            }
        }
        if(Double.isFinite(stopPrice)){min=Math.min(min,stopPrice);max=Math.max(max,stopPrice);}
        double padding=Math.max((max-min)*.07,Math.max(.02,max*.005));min-=padding;max+=padding;
        for(int line=0;line<=4;line++){
            float y=priceTop+(priceBottom-priceTop)*line/4;
            stroke(GRID,.7f);canvas.drawLine(left,y,right,y,paint);
            String value=String.format(Locale.CHINA,"%.2f",max-(max-min)*line/4);
            label(canvas,value,right+dp(3),y+dp(4),LABEL,10);
        }
        for(int i=start;i<end;i++){
            DailyBar bar=bars.get(i);float x=left+(i-start+.5f)*slot;
            int color=bar.close>=bar.open?UP:DOWN;
            float yOpen=scale(bar.open,min,max,priceTop,priceBottom);
            float yClose=scale(bar.close,min,max,priceTop,priceBottom);
            float yHigh=scale(bar.high,min,max,priceTop,priceBottom);
            float yLow=scale(bar.low,min,max,priceTop,priceBottom);
            stroke(color,Math.min(1,slot*.6f/density));canvas.drawLine(x,yHigh,x,yLow,paint);
            fill(color);float body=Math.max(dp(1.5f),Math.abs(yOpen-yClose));
            float halfWidth=Math.min(slot*.4f,Math.max(dp(.5f),slot*.3f));
            canvas.drawRect(x-halfWidth,Math.min(yOpen,yClose),
                    x+halfWidth,Math.min(yOpen,yClose)+body,paint);
            if(volumeMax>0&&Double.isFinite(bar.volume)){
                float volumeHeight=(float)(bar.volume/volumeMax*(volumeBottom-volumeTop));
                canvas.drawRect(x-halfWidth,volumeBottom-volumeHeight,
                        x+halfWidth,volumeBottom,paint);
            }
        }
        canvas.save();canvas.clipRect(left,priceTop,right,priceBottom);
        line(canvas,values.ma5,start,end,left,slot,min,max,priceTop,priceBottom,GOLD);
        line(canvas,values.ma10,start,end,left,slot,min,max,priceTop,priceBottom,BLUE);
        line(canvas,values.ma20,start,end,left,slot,min,max,priceTop,priceBottom,PURPLE);
        if(indicator==3){
            line(canvas,values.bollUpper,start,end,left,slot,min,max,priceTop,priceBottom,UP);
            line(canvas,values.bollLower,start,end,left,slot,min,max,priceTop,priceBottom,DOWN);
        }
        canvas.restore();
        label(canvas,ChartPeriod.label(period)+"  MA5",left,dp(18),GOLD,11);
        label(canvas,"MA10",left+dp(83),dp(18),BLUE,11);
        label(canvas,"MA20",left+dp(143),dp(18),PURPLE,11);
        label(canvas,"成交量",left,volumeTop-dp(7),LABEL,11);
        label(canvas,compact(volumeMax),right+dp(3),volumeTop+dp(6),LABEL,10);
        stroke(GRID,.8f);canvas.drawLine(left,volumeBottom,right,volumeBottom,paint);
        label(canvas,INDICATORS[indicator],left,extraTop-dp(9),LABEL,11);
        drawIndicator(canvas,start,end,left,right,slot,extraTop,extraBottom);
        int middle=(start+end-1)/2;
        label(canvas,axisDate(start,true),left,getHeight()-dp(6),LABEL,10);
        label(canvas,axisDate(middle,false),left+(middle-start)*slot,getHeight()-dp(6),LABEL,10);
        label(canvas,axisDate(end-1,false),Math.max(left,right-dp(42)),getHeight()-dp(6),LABEL,10);
        if(Double.isFinite(stopPrice)){
            float y=scale(stopPrice,min,max,priceTop,priceBottom);stroke(Ui.AMBER,1.25f);paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(5),dp(4)},0));canvas.drawLine(left,y,right,y,paint);paint.setPathEffect(null);
            label(canvas,String.format(Locale.CHINA,"止损 %.2f",stopPrice),left+dp(4),Math.max(priceTop+dp(12),y-dp(5)),Ui.AMBER,11);
        }
        if(selected>=start&&selected<end){
            float x=left+(selected-start+.5f)*slot;
            stroke(Color.rgb(80,95,110),.8f);canvas.drawLine(x,priceTop,x,extraBottom,paint);
        }
        if(minute)drawBehaviors(canvas,start,end,left,right,slot,min,max,priceTop,priceBottom);
    }
    private float layoutBehaviorLabels(int start,int end,float left){
        List<Integer> shown=new ArrayList<>();int selectedZone=-1;
        for(int i=0;i<zones.size();i++){
            MinuteBehavior.Zone z=zones.get(i);if(z.end<start||z.start>=end)continue;
            shown.add(i);if(selected>=z.start&&selected<=z.end)selectedZone=i;
        }
        // Keep the chart readable even when zooming out over many signals.
        while(shown.size()>4)shown.remove(0);
        if(selectedZone>=0&&!shown.contains(selectedZone)){shown.remove(0);shown.add(0,selectedZone);}
        annotationPaint.setTextSize(dp(12)*Math.max(1,Math.min(1.4f,getResources().getConfiguration().fontScale)));
        float x=left,y=dp(43),right=getWidth()-left,height=dp(26);
        float totalWidth=0;for(int i:shown)totalWidth+=annotationPaint.measureText((i+1)+"  "+zones.get(i).chartLabel())+dp(27);
        if(getHeight()<dp(340)&&getWidth()>getHeight()&&totalWidth<=right-left-dp(220)){
            x=left+dp(220);y=0;
        }
        for(int i:shown){
            String text=(i+1)+"  "+zones.get(i).chartLabel();
            float width=annotationPaint.measureText(text)+dp(20);
            if(x>left&&x+width>right){x=left;y+=height+dp(5);}
            behaviorLabels.add(new BehaviorLabel(i,text,new RectF(x,y,x+width,y+height)));
            x+=width+dp(7);
        }
        return shown.isEmpty()?dp(51):Math.max(dp(51),y+height+dp(14));
    }
    private void drawBehaviors(Canvas canvas,int start,int end,float left,float right,float slot,double min,double max,float top,float bottom){
        int inView=0;
        List<RectF> badges=new ArrayList<>();
        for(int i=0;i<zones.size();i++){
            MinuteBehavior.Zone z=zones.get(i);if(z.end<start||z.start>=end)continue;inView++;
            int a=Math.max(start,z.start),b=Math.min(end-1,z.end);double high=Double.NEGATIVE_INFINITY,low=Double.POSITIVE_INFINITY;
            for(int j=a;j<=b;j++){high=Math.max(high,bars.get(j).high);low=Math.min(low,bars.get(j).low);}
            float x1=left+(a-start)*slot,x2=left+(b-start+1)*slot;
            float y1=Math.max(top,scale(high,min,max,top,bottom)-dp(6)),y2=Math.min(bottom,scale(low,min,max,top,bottom)+dp(6));
            int color=z.kind==MinuteBehavior.VOLUME?Ui.AMBER:z.attack?UP:DOWN;
            fill((color&0x00FFFFFF)|0x10000000);canvas.drawRoundRect(x1,y1,x2,y2,dp(3),dp(3),paint);
            stroke(color,selected>=z.start&&selected<=z.end?2:1.25f);if(z.contained)paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(6),dp(4)},0));canvas.drawRoundRect(x1,y1,x2,y2,dp(3),dp(3),paint);paint.setPathEffect(null);
            // Number badges link narrow candle intervals to the full Chinese labels above.
            boolean hasLabel=false;for(BehaviorLabel item:behaviorLabels)if(item.zoneIndex==i){hasLabel=true;break;}
            if(!hasLabel)continue;
            String id=String.valueOf(i+1);paint.setTextSize(dp(10));
            float radius=Math.max(dp(8),paint.measureText(id)*.5f+dp(3));
            float cx=Math.max(left+radius,Math.min(right-radius,(x1+x2)*.5f));
            float cy=Math.max(top+radius,Math.min(bottom-radius,y1));
            RectF badge=new RectF(cx-radius,cy-radius,cx+radius,cy+radius);
            for(int attempt=0;attempt<badges.size()+1;attempt++){
                boolean collision=false;for(RectF prior:badges)if(RectF.intersects(prior,badge)){collision=true;break;}
                if(!collision)break;
                cy=cy+radius*2+dp(3)<=bottom-radius?cy+radius*2+dp(3):top+radius;
                badge.set(cx-radius,cy-radius,cx+radius,cy+radius);
            }
            badges.add(badge);
            fill(color);canvas.drawCircle(cx,cy,radius,paint);
            paint.setColor(Color.WHITE);Paint.FontMetrics fm=paint.getFontMetrics();canvas.drawText(id,cx-paint.measureText(id)*.5f,cy-(fm.ascent+fm.descent)*.5f,paint);
        }
        String hint=inView==0?"基于量价推测 · 当前区间无信号":inView>4?"基于量价推测 · 标签显示最近4项或所选项":"基于量价推测 · 编号对应框选区间";
        label(canvas,hint,left,dp(35),LABEL,10);
        for(BehaviorLabel item:behaviorLabels){
            MinuteBehavior.Zone zone=zones.get(item.zoneIndex);
            int color=zone.kind==MinuteBehavior.VOLUME?Ui.AMBER:zone.attack?UP:DOWN;
            annotationPaint.setStyle(Paint.Style.FILL);annotationPaint.setColor((color&0x00FFFFFF)|0x14000000);
            canvas.drawRoundRect(item.bounds,dp(7),dp(7),annotationPaint);
            annotationPaint.setColor(color);Paint.FontMetrics fm=annotationPaint.getFontMetrics();
            canvas.drawText(item.text,item.bounds.left+dp(10),item.bounds.centerY()-(fm.ascent+fm.descent)*.5f,annotationPaint);
        }
    }
    private String axisDate(int i,boolean first){String d=bars.get(i).date;return minute&&d.length()>=16?first?d.substring(5,10):d.substring(11):d.substring(5);}
    private static float scale(double value,double min,double max,float top,float bottom){
        return bottom-(float)((value-min)/(max-min)*(bottom-top));
    }
    private void line(Canvas canvas,double[] data,int start,int end,float left,float slot,double min,double max,float top,float bottom,int color){
        stroke(color,1.25f);boolean previous=false;float oldX=0,oldY=0;
        for(int i=start;i<end;i++){
            double value=data[i];if(!Double.isFinite(value)){previous=false;continue;}
            float x=left+(i-start+.5f)*slot,y=scale(value,min,max,top,bottom);
            if(previous)canvas.drawLine(oldX,oldY,x,y,paint);
            previous=true;oldX=x;oldY=y;
        }
    }
    private void drawIndicator(Canvas canvas,int start,int end,float left,float right,float slot,float top,float bottom){
        double[] first,second=null,third=null;
        switch(indicator){
            case 1:first=values.k;second=values.d;third=values.j;break;
            case 2:first=values.rsi14;break;
            case 3:first=values.bollUpper;second=values.bollMid;third=values.bollLower;break;
            case 4:first=values.viPlus;second=values.viMinus;break;
            default:first=values.dif;second=values.dea;third=values.macd;
        }
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        double[][] series={first,second,third};
        for(double[] data:series)if(data!=null)for(int i=start;i<end;i++)if(Double.isFinite(data[i])){
            min=Math.min(min,data[i]);max=Math.max(max,data[i]);
        }
        if(!Double.isFinite(min)){label(canvas,"指标数据不足",left,top+dp(22),LABEL,12);return;}
        if(indicator==1||indicator==2){min=Math.min(min,0);max=Math.max(max,100);}
        if(indicator==0){double extent=Math.max(Math.abs(min),Math.abs(max));min=-extent;max=extent;}
        if(max<=min){max=min+1;}
        double padding=(max-min)*.08;min-=padding;max+=padding;
        stroke(GRID,.7f);
        int steps=bottom-top<dp(40)?1:2;
        for(int row=0;row<=steps;row++){
            float y=top+(bottom-top)*row/steps;canvas.drawLine(left,y,right,y,paint);
            String text=indicator==0?String.format(Locale.CHINA,"%.3f",max-(max-min)*row/steps)
                    :indicator==4?String.format(Locale.CHINA,"%.2f",max-(max-min)*row/steps)
                    :indicator==3?String.format(Locale.CHINA,"%.1f",max-(max-min)*row/steps)
                    :String.format(Locale.CHINA,"%.0f",max-(max-min)*row/steps);
            label(canvas,text,right+dp(3),y+dp(3),LABEL,10);
        }
        if(indicator==0){
            float zero=scale(0,min,max,top,bottom);stroke(Color.rgb(150,160,170),.8f);canvas.drawLine(left,zero,right,zero,paint);
            for(int i=start;i<end;i++)if(Double.isFinite(values.macd[i])){
                float x=left+(i-start+.5f)*slot,y=scale(values.macd[i],min,max,top,bottom);
                stroke(values.macd[i]>=0?UP:DOWN,Math.max(1,slot*.48f/density));canvas.drawLine(x,zero,x,y,paint);
            }
            line(canvas,values.dif,start,end,left,slot,min,max,top,bottom,GOLD);
            line(canvas,values.dea,start,end,left,slot,min,max,top,bottom,BLUE);
        }else{
            line(canvas,first,start,end,left,slot,min,max,top,bottom,indicator==4?UP:GOLD);
            if(second!=null)line(canvas,second,start,end,left,slot,min,max,top,bottom,indicator==4?DOWN:BLUE);
            if(third!=null)line(canvas,third,start,end,left,slot,min,max,top,bottom,PURPLE);
            if(indicator==2)for(int level:new int[]{30,70}){
                float y=scale(level,min,max,top,bottom);stroke(Color.rgb(210,210,210),.7f);canvas.drawLine(left,y,right,y,paint);
            }
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        scaleDetector.onTouchEvent(event);
        if(bars.isEmpty())return true;
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                touchX=event.getX();initialX=touchX;initialEnd=end;dragged=false;return true;
            case MotionEvent.ACTION_MOVE:
                if(event.getPointerCount()>1)return true;
                float shift=event.getX()-initialX;
                if(Math.abs(shift)>dp(7))dragged=true;
                if(dragged){
                    float width=Math.max(dp(1),(getWidth()-dp(60))/(float)Math.max(1,visible));
                    int barsMoved=Math.round(shift/width);
                    end=Math.max(visible,Math.min(bars.size(),initialEnd-barsMoved));invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if(dragged){
                    selected=end-1;
                    if(listener!=null)listener.onSelected(selected);invalidate();
                }else if(!scaleDetector.isInProgress()){
                    for(BehaviorLabel item:behaviorLabels)if(item.bounds.contains(event.getX(),event.getY())){
                        focus(zones.get(item.zoneIndex).end);
                        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                        performClick();return true;
                    }
                    float width=(getWidth()-dp(60))/(float)Math.max(1,visible);
                    int offset=(int)((event.getX()-dp(13))/width);
                    selected=Math.max(end-visible,Math.min(end-1,end-visible+offset));
                    if(listener!=null)listener.onSelected(selected);invalidate();
                }
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                performClick();return true;
            case MotionEvent.ACTION_CANCEL:
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:return true;
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
    String detail(int index){
        if(index<0||index>=bars.size())return "";
        DailyBar bar=bars.get(index);double change=index>0&&bars.get(index-1).close>0
                ?(bar.close/bars.get(index-1).close-1)*100:Double.NaN;
        String summary=String.format(Locale.CHINA,"%s  开 %.2f  高 %.2f  低 %.2f  收 %.2f  涨跌 %s%%\n成交量 %s  MA5 %s  MA10 %s  MA20 %s",
                bar.date,bar.open,bar.high,bar.low,bar.close,number(change,2),compact(bar.volume),
                number(values.ma5[index],2),number(values.ma10[index],2),number(values.ma20[index],2));
        if(period==ChartPeriod.WEEK||period==ChartPeriod.MONTH)summary=ChartPeriod.label(period)+" · "+periodSpans.getOrDefault(bar.date,bar.date)+"\n"+summary;
        if(minute){
            summary+="\n"+(MinuteBehavior.epoch(bar.date)>System.currentTimeMillis()?"本根尚未结束，不确认行为":"已结束的"+ChartPeriod.label(period));
            double baseline=volumeBaselines.getOrDefault(bar.date.substring(0,10),0.0);
            summary+=baseline>0?String.format(Locale.CHINA," · 20交易日中位量 %s · 本根量比 %.2f",compact(baseline),bar.volume/baseline):" · 当前日前不足20个完整交易日，暂无放量基准";
            for(int i=0;i<zones.size();i++){MinuteBehavior.Zone z=zones.get(i);if(index>=z.start&&index<=z.end)summary+=" · "+(i+1)+" "+z.label();}
        }
        switch(indicator){
            case 1:return summary+"\nKDJ  K "+number(values.k[index],2)+"  D "+number(values.d[index],2)+"  J "+number(values.j[index],2);
            case 2:return summary+"\nRSI14 "+number(values.rsi14[index],2);
            case 3:return summary+"\nBOLL  上 "+number(values.bollUpper[index],2)+"  中 "+number(values.bollMid[index],2)+"  下 "+number(values.bollLower[index],2);
            case 4:return summary+"\nVI14  + "+number(values.viPlus[index],3)+"  - "+number(values.viMinus[index],3);
            default:return summary+"\nMACD  DIF "+number(values.dif[index],3)+"  DEA "+number(values.dea[index],3)+"  柱 "+number(values.macd[index],3);
        }
    }
}
