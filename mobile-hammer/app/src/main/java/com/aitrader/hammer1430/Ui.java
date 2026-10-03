package com.aitrader.hammer1430;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

/** Shared light, inset-grouped visual language for the native app. */
final class Ui {
    static final int BG=Color.rgb(242,242,247),WHITE=Color.WHITE,INK=Color.rgb(28,28,30),SECONDARY=Color.rgb(110,110,115);
    static final int LINE=Color.rgb(229,229,234),BLUE=Color.rgb(0,122,255),TINT=Color.rgb(237,245,255);
    static final int RED=Color.rgb(211,57,67),GREEN=Color.rgb(37,145,104),AMBER=Color.rgb(172,107,20);
    static final int STOCK_BG=0xFFF8F8FA,PINK=0xFFFFDCE9,PINK_SOFT=0xFFFFEEF4,PINK_INK=0xFFAD2859;
    static int dp(Context c,float value){return Math.round(value*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable shape(Context c,int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;}
    static void install(Activity activity,View root){
        activity.getWindow().setStatusBarColor(BG);activity.getWindow().setNavigationBarColor(WHITE);
        if(Build.VERSION.SDK_INT>=29)activity.getWindow().setNavigationBarContrastEnforced(false);
        activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        root.requestApplyInsets();
    }
    static LinearLayout column(Context c){LinearLayout box=new LinearLayout(c);box.setOrientation(LinearLayout.VERTICAL);return box;}
    static LinearLayout row(Context c){LinearLayout box=new LinearLayout(c);box.setOrientation(LinearLayout.HORIZONTAL);box.setGravity(Gravity.CENTER_VERTICAL);return box;}
    static TextView text(Context c,String value,int size,int color,boolean bold){
        TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));t.setLineSpacing(dp(c,3),1);
        return t;
    }
    static TextView number(Context c,String value,int size,int color){
        TextView t=text(c,value,size,color,true);t.setFontFeatureSettings("tnum");t.setMaxLines(1);t.setHorizontallyScrolling(false);
        t.setMinHeight(Math.round((size+4)*c.getResources().getDisplayMetrics().scaledDensity));
        t.setAutoSizeTextTypeUniformWithConfiguration(Math.min(16,size),size,1,android.util.TypedValue.COMPLEX_UNIT_SP);return t;
    }
    static void add(LinearLayout parent,View view){parent.addView(view,new LinearLayout.LayoutParams(-1,-2));}
    static void gap(LinearLayout parent,int height){View gap=new View(parent.getContext());parent.addView(gap,new LinearLayout.LayoutParams(1,dp(parent.getContext(),height)));}
    static void line(LinearLayout parent){View line=new View(parent.getContext());line.setBackgroundColor(LINE);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,1);p.setMargins(0,dp(parent.getContext(),14),0,dp(parent.getContext(),14));parent.addView(line,p);}
    static LinearLayout card(LinearLayout parent){Context c=parent.getContext();LinearLayout box=column(c);box.setPadding(dp(c,18),dp(c,18),dp(c,18),dp(c,18));
        box.setBackground(shape(c,WHITE,20));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(c,12));parent.addView(box,p);return box;}
    static TextView section(LinearLayout parent,String title,String detail){Context c=parent.getContext();LinearLayout row=row(c);
        TextView heading=text(c,title,20,INK,true);row.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        if(detail!=null)row.addView(text(c,detail,12,SECONDARY,false));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(c,3),dp(c,14),dp(c,3),dp(c,12));parent.addView(row,p);return heading;}
    static TextView pill(Context c,String value,int color,int background){TextView t=text(c,value,12,color,true);t.setPadding(dp(c,9),dp(c,5),dp(c,9),dp(c,5));t.setBackground(shape(c,background,8));return t;}
    static Button button(Context c,String value,boolean primary){Button b=new Button(c);b.setText(value);b.setAllCaps(false);b.setTextSize(14);
        b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setIncludeFontPadding(false);b.setMinWidth(0);b.setMinimumWidth(0);
        b.setMinHeight(dp(c,44));b.setMinimumHeight(dp(c,44));b.setPadding(dp(c,12),dp(c,9),dp(c,12),dp(c,9));b.setElevation(0);b.setStateListAnimator(null);
        b.setBackgroundTintList(null);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18007AFF),shape(c,primary?BLUE:TINT,12),null));
        b.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_enabled},{}},new int[]{primary?WHITE:BLUE,SECONDARY}));return b;}
    static Icon iconButton(Context c,String name,String description){Icon icon=new Icon(c,name,BLUE);icon.setContentDescription(description);icon.setClickable(true);icon.setFocusable(true);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        icon.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18007AFF),shape(c,WHITE,14),null));icon.setMinimumWidth(dp(c,44));icon.setMinimumHeight(dp(c,44));return icon;}
    static void segment(Button button,boolean active){Context c=button.getContext();button.setTextColor(active?BLUE:SECONDARY);
        button.setBackgroundTintList(null);button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18007AFF),shape(c,active?WHITE:BG,9),null));}
    static LinearLayout stat(Context c,String title,String value,String note,int color){LinearLayout box=column(c);
        add(box,text(c,title,12,SECONDARY,false));gap(box,6);add(box,number(c,value,24,color));
        if(note!=null&&!note.isEmpty()){gap(box,5);add(box,text(c,note,11,SECONDARY,false));}return box;}
    static void pair(LinearLayout parent,View left,View right){Context c=parent.getContext();LinearLayout row=row(c);row.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(0,0,dp(c,8),0);row.addView(left,p);
        p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(c,8),0,0,0);row.addView(right,p);add(parent,row);}
    static void compact(TextView text){text.setSingleLine(true);text.setEllipsize(TextUtils.TruncateAt.END);}
    static final class Icon extends View {
        private final String name;private int color;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(Context c,String name,int color){super(c);this.name=name;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void tint(int color){this.color=color;invalidate();}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);canvas.save();float side=dp(getContext(),23);
            canvas.translate((getWidth()-side)/2,(getHeight()-side)/2);canvas.scale(side/24,side/24);
            paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.65f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
            Path p=new Path();
            switch(name){
                case "refresh":canvas.drawArc(4,4,20,20,45,285,false,paint);p.moveTo(20,4);p.lineTo(20,9);p.lineTo(15,9);break;
                case "back":p.moveTo(15,5);p.lineTo(8,12);p.lineTo(15,19);break;
                case "down":p.moveTo(7,9);p.lineTo(12,14);p.lineTo(17,9);break;
                case "up":p.moveTo(7,15);p.lineTo(12,10);p.lineTo(17,15);break;
                case "plus":p.moveTo(12,5);p.lineTo(12,19);p.moveTo(5,12);p.lineTo(19,12);break;
                case "help":canvas.drawCircle(12,12,9,paint);p.moveTo(12,11);p.lineTo(12,17);p.moveTo(12,7);p.lineTo(12,7.2f);break;
                case "sectors":for(int x:new int[]{4,14})for(int y:new int[]{4,14})canvas.drawRoundRect(x,y,x+6,y+6,1.5f,1.5f,paint);break;
                case "stocks":canvas.drawCircle(10,10,6.5f,paint);p.moveTo(15,15);p.lineTo(21,21);p.moveTo(7,11);p.lineTo(9,8);p.lineTo(12,10);break;
                case "gain":p.moveTo(4,19);p.lineTo(4,13);p.moveTo(10,19);p.lineTo(10,9);p.moveTo(16,19);p.lineTo(16,5);p.moveTo(3,20);p.lineTo(21,20);break;
                case "holdings":canvas.drawRoundRect(3,7,21,21,2,2,paint);canvas.drawRoundRect(8,3,16,7,1,1,paint);p.moveTo(3,12);p.lineTo(21,12);p.moveTo(10,12);p.lineTo(14,12);break;
                case "star":for(int i=0;i<10;i++){double a=-Math.PI/2+i*Math.PI/5;float r=i%2==0?9:4.3f;float x=12+(float)Math.cos(a)*r,y=12+(float)Math.sin(a)*r;if(i==0)p.moveTo(x,y);else p.lineTo(x,y);}p.close();break;
                default:p.moveTo(3,17);p.lineTo(8,12);p.lineTo(12,15);p.lineTo(21,5);p.moveTo(16,5);p.lineTo(21,5);p.lineTo(21,10);
            }
            canvas.drawPath(p,paint);canvas.restore();
        }
    }
    static final class RangeBar extends View {
        private final double fraction;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        RangeBar(Context c,double percent){super(c);fraction=Math.max(0,Math.min(1,percent/100));setContentDescription("最近30日区间位置 "+Math.round(percent)+"%");}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float y=getHeight()/2f,h=dp(getContext(),4),inset=dp(getContext(),7),w=getWidth()-2*inset;
            paint.setColor(LINE);c.drawRoundRect(inset,y-h,inset+w,y+h,h,h,paint);paint.setColor(0xFFB8DAFF);c.drawRoundRect(inset,y-h,inset+w*.1f,y+h,h,h,paint);
            paint.setColor(0xFFFFD9A6);c.drawRoundRect(inset+w*.9f,y-h,inset+w,y+h,h,h,paint);
            float x=inset+(float)fraction*w;paint.setColor(WHITE);c.drawCircle(x,y,dp(getContext(),7),paint);paint.setColor(BLUE);c.drawCircle(x,y,dp(getContext(),5),paint);
        }
    }
}
