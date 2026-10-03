package com.aitrader.hammer1430;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.widget.Button;
import java.util.ArrayList;
import java.util.List;

/** Landscape is a per-chart choice, never enabled by merely turning the phone. */
final class ChartOrientation {
    private static final String STATE="chart_landscape";
    private final Activity activity;
    private boolean landscape;
    private final List<Button> buttons=new ArrayList<>();

    ChartOrientation(Activity activity,Bundle saved){
        this.activity=activity;landscape=saved!=null&&saved.getBoolean(STATE,false);apply();
    }
    boolean landscape(){return landscape;}
    void save(Bundle out){out.putBoolean(STATE,landscape);}
    Button button(){return button(false);}
    Button button(boolean atChart){
        Button button=Ui.button(activity,"横屏",false);button.setTag(atChart?"chart-orientation:plot":"chart-orientation:toolbar");button.setMinHeight(0);button.setMinimumHeight(0);
        button.setMaxLines(1);button.setHorizontallyScrolling(false);button.setPadding(Ui.dp(activity,6),Ui.dp(activity,3),Ui.dp(activity,6),Ui.dp(activity,3));
        button.setAutoSizeTextTypeUniformWithConfiguration(10,13,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        buttons.add(button);updateButtons();button.setOnClickListener(v->{landscape=!landscape;updateButtons();apply();});return button;
    }
    private void updateButtons(){for(Button button:buttons){boolean atChart="chart-orientation:plot".equals(button.getTag());button.setText(landscape?atChart?"返回竖屏":"竖屏":atChart?"横屏查看":"横屏");button.setContentDescription(landscape?"返回竖屏查看K线":"横屏查看K线");}}
    private void apply(){activity.setRequestedOrientation(landscape?ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);}
}
