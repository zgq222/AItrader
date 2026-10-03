package com.aitrader.hammer1430;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** One refresh clock shared by all screens; no overlapping scan jobs. */
public final class TraderApplication extends Application implements Application.ActivityLifecycleCallbacks {
    interface Refreshable {void refreshData();}
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final RefreshSchedule schedule=new RefreshSchedule();
    private Activity current;
    private boolean opened;
    private final Runnable tick=()->refreshIfDue();
    @Override public void onCreate(){super.onCreate();registerActivityLifecycleCallbacks(this);
        SharedPreferences prefs=getSharedPreferences("scan",MODE_PRIVATE);
        SharedPreferences.Editor edit=prefs.edit().remove("groups_cache").remove("groups_payload").remove("cache_rule_version");
        if(prefs.getString("status","").contains("倒垂线"))edit.remove("status");
        edit.apply();
        android.app.NotificationManager manager=(android.app.NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.cancel(1431);manager.deleteNotificationChannel("hammer_results");
    }
    private void refreshIfDue(){
        handler.removeCallbacks(tick);
        if(current==null||current.isFinishing()||current.isDestroyed())return;
        long now=SystemClock.elapsedRealtime();
        if(!opened||schedule.automaticDue(now,System.currentTimeMillis())){
            opened=true;
            schedule.started(now);
            MarketDataRepository.refresh(this);
            try{PersonalSignalService.refresh(this);}catch(RuntimeException error){PersonalSignalStore.prefs(this).edit().putString("error","信号检查未启动："+error.getMessage()).apply();}
            if(!ScreenService.isRunning())try{startForegroundService(new Intent(this,ScreenService.class));}
            catch(RuntimeException error){getSharedPreferences("scan",MODE_PRIVATE).edit().putString("status","自动刷新未启动："+error.getMessage()).apply();}
            if(current instanceof Refreshable)((Refreshable)current).refreshData();
        }
        handler.postDelayed(tick,schedule.automaticDelay(SystemClock.elapsedRealtime(),System.currentTimeMillis()));
    }
    @Override public void onActivityResumed(Activity activity){current=activity;refreshIfDue();}
    @Override public void onActivityPaused(Activity activity){if(current==activity){current=null;handler.removeCallbacks(tick);}}
    @Override public void onActivityCreated(Activity activity,Bundle state){}
    @Override public void onActivityStarted(Activity activity){}
    @Override public void onActivityStopped(Activity activity){}
    @Override public void onActivitySaveInstanceState(Activity activity,Bundle state){}
    @Override public void onActivityDestroyed(Activity activity){}
}
