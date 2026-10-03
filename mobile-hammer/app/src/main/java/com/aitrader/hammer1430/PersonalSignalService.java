package com.aitrader.hammer1430;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PersonalSignalService extends Service {
    private static final AtomicBoolean RUNNING=new AtomicBoolean();
    static void refresh(Context c){if(!RUNNING.get())c.startForegroundService(new Intent(c,PersonalSignalService.class));}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(!RUNNING.compareAndSet(false,true))return START_NOT_STICKY;
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);String channel="personal_signal_scan";
        manager.createNotificationChannel(new NotificationChannel(channel,"自选延伸检查",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,6,new Intent(this,MainActivity.class).putExtra("module",6),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification progress=new Notification.Builder(this,channel).setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle("正在检查自选延伸与持仓减仓")
                .setContentText("按15分钟实线行为框计算").setContentIntent(open).setOngoing(true).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(1432,progress,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(1432,progress);
        PersonalSignalStore.prefs(this).edit().putBoolean("busy",true).putString("status","正在同步15分钟行为…").remove("error").apply();
        new Thread(()->{try{PersonalSignalRepository.load(this);PersonalSignalStore.prefs(this).edit().putString("status","信号检查完成").apply();}
            catch(Exception e){PersonalSignalStore.prefs(this).edit().putString("error",String.valueOf(e.getMessage())).putString("status","检查失败，保留上次信号").apply();}
            finally{RUNNING.set(false);PersonalSignalStore.prefs(this).edit().putBoolean("busy",false).apply();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}},"personal-signals").start();
        return START_NOT_STICKY;
    }
}
