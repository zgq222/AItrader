package com.aitrader.hammer1430;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import org.json.JSONObject;
import java.util.*;

/** Two date-aligned series. Gaps stay gaps; every panel shares the candle viewport. */
final class IndustryMetricChart extends View {
    interface WindowListener {void change(int end,int count,int selected);}
    private final boolean strength;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector zoom;
    private List<BoardChartActivity.Bar> bars=Collections.emptyList();
    private Map<String,JSONObject> metrics=Collections.emptyMap();
    private int end,count=60,selected=-1,downEnd;
    private float downX;private boolean moved;
    private WindowListener listener;
    IndustryMetricChart(Context c,boolean strength){super(c);this.strength=strength;setContentDescription(strength?"行业相对强度历史图":"行业上涨广度历史图");
        zoom=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){if(bars.isEmpty())return false;int size=Math.max(Math.min(20,bars.size()),Math.min(Math.min(240,bars.size()),Math.round(count/d.getScaleFactor())));if(listener!=null)listener.change(Math.max(Math.min(size,bars.size()),end),size,selected);moved=true;return true;}});
    }
    void setWindowListener(WindowListener l){listener=l;}
    void setData(List<BoardChartActivity.Bar> b,Map<String,JSONObject> m){bars=b;metrics=m;invalidate();}
    void setWindow(int end,int count,int selected){this.end=Math.min(end,bars.size());this.count=count;this.selected=selected;invalidate();}
    int firstVisible(){return Math.max(0,end-count);}int endVisible(){return end;}int selectedIndex(){return selected;}
    String dateAt(int i){return bars.get(i).date;}
    double value(int i,int series){if(i<0||i>=bars.size())return Double.NaN;JSONObject r=metrics.get(dateAt(i));return r==null?Double.NaN:r.optDouble(strength?series==0?"relative_5d":"relative_20d":series==0?"outperform_5d_pct":"above_ma20_pct",Double.NaN);}
    private float dp(float n){return Ui.dp(getContext(),n);}
    private void caption(Canvas c,String t,float x,float y,int color,int size){p.setColor(color);p.setStyle(Paint.Style.FILL);p.setTextSize(dp(size));c.drawText(t,x,y,p);}
    private void stroke(Canvas c,float x,float y,float xx,float yy,int color,float width){p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(width));c.drawLine(x,y,xx,yy,p);}
    @Override protected void onDraw(Canvas c){super.onDraw(c);c.drawColor(Ui.WHITE);float left=dp(6),right=getWidth()-dp(48),top=dp(55),bottom=getHeight()-dp(27);
        caption(c,strength?"5日相对强度 · 百分点":"5日跑赢市场比例 · %",left,dp(17),Ui.BLUE,11);caption(c,strength?"20日相对强度 · 百分点":"站上MA20比例 · %",left,dp(36),Ui.AMBER,11);
        int start=firstVisible(),n=end-start;if(n<=0){caption(c,"暂无历史数据",left,top+dp(20),Ui.SECONDARY,13);return;}
        double min=0,max=strength?0:100;boolean valid=false;
        for(int i=start;i<end;i++)for(int s=0;s<2;s++){double v=value(i,s);if(Double.isFinite(v)){valid=true;if(strength){min=Math.min(min,v);max=Math.max(max,v);}}}
        if(strength){double pad=Math.max(.3,(max-min)*.1);min-=pad;max+=pad;}
        for(int i=0;i<3;i++){float y=top+(bottom-top)*i/2;stroke(c,left,y,right,y,Ui.LINE,.7f);double tick=max-(max-min)*i/2;if(Math.abs(tick)<.000001)tick=0;caption(c,String.format(Locale.CHINA,strength?"%.1f":"%.0f",tick),right+dp(3),y+dp(4),Ui.SECONDARY,10);}
        float ref=y(strength?0:50,min,max,top,bottom);p.setPathEffect(new DashPathEffect(new float[]{dp(4),dp(4)},0));stroke(c,left,ref,right,ref,Ui.SECONDARY,.8f);p.setPathEffect(null);
        if(!valid)caption(c,"此日期范围暂无指标，缺项留空",left,top+dp(22),Ui.SECONDARY,12);
        float slot=(right-left)/n;for(int s=0;s<2;s++){boolean connected=false;float x0=0,y0=0;int color=s==0?Ui.BLUE:Ui.AMBER;
            for(int i=start;i<end;i++){double v=value(i,s);if(!Double.isFinite(v)){connected=false;continue;}float x=left+(i-start+.5f)*slot,yy=y(v,min,max,top,bottom);if(connected)stroke(c,x0,y0,x,yy,color,1.5f);else{p.setColor(color);p.setStyle(Paint.Style.FILL);c.drawCircle(x,yy,dp(1.5f),p);}x0=x;y0=yy;connected=true;}
        }
        if(selected>=start&&selected<end){float x=left+(selected-start+.5f)*slot;stroke(c,x,top,x,bottom,Ui.SECONDARY,.8f);}
        for(int step=0;step<3;step++){int i=start+(n-1)*step/2;caption(c,bars.get(i).date.substring(5),Math.max(left,Math.min(right-dp(30),left+(i-start+.5f)*slot-dp(12))),getHeight()-dp(8),Ui.SECONDARY,10);}
    }
    private float y(double v,double min,double max,float top,float bottom){return bottom-(float)((v-min)/(max-min))*(bottom-top);}
    @Override public boolean onTouchEvent(MotionEvent e){zoom.onTouchEvent(e);if(bars.isEmpty()||listener==null)return true;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:downX=e.getX();downEnd=end;moved=false;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);return true;
            case MotionEvent.ACTION_MOVE:if(e.getPointerCount()>1)return true;float shift=e.getX()-downX;if(Math.abs(shift)>dp(7))moved=true;if(moved){float slot=(getWidth()-dp(54))/Math.max(1,Math.min(count,end));int to=Math.max(Math.min(count,bars.size()),Math.min(bars.size(),downEnd-Math.round(shift/Math.max(1,slot))));listener.change(to,count,selected);}return true;
            case MotionEvent.ACTION_UP:if(!moved){int start=firstVisible();int index=start+(int)((e.getX()-dp(6))/Math.max(1,(getWidth()-dp(54))/Math.max(1,end-start)));listener.change(end,count,Math.max(start,Math.min(end-1,index)));}if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);performClick();return true;
            case MotionEvent.ACTION_CANCEL:if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);return true;
            default:return true;
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
