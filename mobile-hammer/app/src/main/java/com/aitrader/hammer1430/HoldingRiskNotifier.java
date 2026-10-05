package com.aitrader.hammer1430;

import android.app.*;
import android.content.*;
import android.media.AudioAttributes;
import android.provider.Settings;
import org.json.*;
import java.util.*;

final class HoldingRiskNotifier {
    static final String CHANNEL="holding_reductions_v1";
    static final String FIVE_CHANNEL="holding_reductions_5_v1";
    static Intent chartIntent(Context c,String code,String name,JSONObject frame){
        return chartIntent(c,code,name,frame,15);
    }
    static Intent chartIntent(Context c,String code,String name,JSONObject frame,int period){
        return new Intent(c,StockChartActivity.class).putExtra(StockChartActivity.EXTRA_CODE,code).putExtra(StockChartActivity.EXTRA_NAME,name)
                .putExtra(StockChartActivity.EXTRA_MINUTE,true).putExtra(StockChartActivity.EXTRA_PERIOD,period).putExtra(StockChartActivity.EXTRA_FOCUS,frame.optString("end_time"));
    }
    static void send(Context c,List<JSONObject> events){
        send(c,events,15);
    }
    static void send(Context c,List<JSONObject> events,int period){
        if(events.isEmpty())return;
        NotificationManager manager=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        String channelId=period==5?FIVE_CHANNEL:CHANNEL;
        NotificationChannel channel=new NotificationChannel(channelId,"持仓"+period+"分钟减仓提醒",NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("持仓股最新交易日出现"+period+"分钟实线减仓框时提醒；仅提示音");channel.enableVibration(true);channel.setVibrationPattern(new long[]{0,150,100,150});
        channel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI,new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());manager.createNotificationChannel(channel);
        Map<String,List<JSONObject>> grouped=new LinkedHashMap<>();for(JSONObject event:events)grouped.computeIfAbsent(event.optString("code"),key->new ArrayList<>()).add(event);
        for(Map.Entry<String,List<JSONObject>> entry:grouped.entrySet())try{
            JSONObject last=entry.getValue().get(entry.getValue().size()-1),frame=last.getJSONObject("frame");String code=entry.getKey(),name=last.optString("name",code);
            String detail=last.getString("trade_date")+" · "+frame.getString("start_time").substring(11)+"—"+frame.getString("end_time").substring(11)+" · 新增"+entry.getValue().size()+"个减仓框 · 量价推测";
            PendingIntent open=PendingIntent.getActivity(c,(period==5?1_000_000:0)+Integer.parseInt(code),chartIntent(c,code,name,frame,period).putExtra("holding_risk",true).putExtra("holding_risk_period",period),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            manager.notify((period==5?2_050_000:50_000)+Integer.parseInt(code),new Notification.Builder(c,channelId).setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(name+"（"+code+"）"+period+"分钟减仓提醒").setContentText(detail).setStyle(new Notification.BigTextStyle().bigText(detail))
                    .setContentIntent(open).setCategory(Notification.CATEGORY_ALARM).setAutoCancel(true).build());
        }catch(SecurityException error){(period==5?HoldingFiveStore.prefs(c):PersonalSignalStore.prefs(c)).edit().putString("notification_error","系统通知未获授权，提醒已保留在应用内").apply();}
        catch(Exception error){(period==5?HoldingFiveStore.prefs(c):PersonalSignalStore.prefs(c)).edit().putString("notification_error","通知未发出："+error.getMessage()+"；提醒已保留在应用内").apply();}
    }
}
