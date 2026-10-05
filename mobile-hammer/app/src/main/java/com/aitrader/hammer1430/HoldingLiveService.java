package com.aitrader.hammer1430;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import android.speech.tts.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-visible tracking survives leaving the page; spoken content is exclusively the selected gain. */
public class HoldingLiveService extends Service {
    static final String SPEAK="holding.speak",STOP_SPEECH="holding.stop_speech",CHANNEL="holding_live_v1";
    private static final AtomicBoolean RUNNING=new AtomicBoolean();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService quoteWorker=Executors.newSingleThreadExecutor(),signalWorker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean quoteBusy=new AtomicBoolean(),signalBusy=new AtomicBoolean();
    private final HoldingFiveRepository five=new HoldingFiveRepository();
    private TextToSpeech voice;private AudioManager audio;private AudioFocusRequest focus;private PowerManager.WakeLock wake;
    private volatile boolean destroyed;private boolean ready,audioPaused,finalQuoteRequested;private String spokenCode="";private long untilElapsed,lastSignal;private int voiceGeneration;private String utterance="";
    private final Runnable quoteTick=this::tick,speechTick=this::report;
    private final SharedPreferences.OnSharedPreferenceChangeListener holdingsChanged=(p,k)->{if("stocks".equals(k))handler.post(()->{try{if(!spokenCode.isEmpty()&&HoldingsStore.find(this,spokenCode)==null)finishSpeech("该股票已移出持仓，播报已停止");if(HoldingsStore.stocks(this).isEmpty())stopTracking();}catch(Exception e){stopTracking();}});};
    static void ensure(Context c){try{if(!RUNNING.get()&&HoldingLive.window(System.currentTimeMillis())&&!HoldingsStore.stocks(c).isEmpty())c.startForegroundService(new Intent(c,HoldingLiveService.class));}catch(RuntimeException|org.json.JSONException e){HoldingLive.prefs(c).edit().putString("tracking_error","实时追踪未启动："+e.getMessage()).apply();}catch(Exception e){HoldingLive.prefs(c).edit().putString("tracking_error",String.valueOf(e.getMessage())).apply();}}
    static void startSpeech(Context c,String code,int minutes){c.startForegroundService(new Intent(c,HoldingLiveService.class).setAction(SPEAK).putExtra("code",code).putExtra("minutes",minutes));}
    static void stopSpeech(Context c){if(RUNNING.get())c.startService(new Intent(c,HoldingLiveService.class).setAction(STOP_SPEECH));else HoldingLive.prefs(c).edit().putBoolean("speech_active",false).apply();}
    protected long wallNow(){return System.currentTimeMillis();}
    protected long elapsedNow(){return SystemClock.elapsedRealtime();}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onCreate(){super.onCreate();RUNNING.set(true);HoldingLive.prefs(this).edit().remove("tracking_error").remove("tracking_status").apply();audio=(AudioManager)getSystemService(AUDIO_SERVICE);getSharedPreferences("holdings",0).registerOnSharedPreferenceChangeListener(holdingsChanged);
        NotificationChannel ch=new NotificationChannel(CHANNEL,"持仓实时追踪",NotificationManager.IMPORTANCE_LOW);ch.setSound(null,null);ch.enableVibration(false);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);wake=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"AItrader:holding-live");wake.acquire(6*60*60*1000L+120_000);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        boolean speak=intent!=null&&SPEAK.equals(intent.getAction());foreground(speak);
        if(intent!=null&&STOP_SPEECH.equals(intent.getAction()))finishSpeech("播报已停止");
        if(speak)beginSpeech(intent.getStringExtra("code"),intent.getIntExtra("minutes",0));
        handler.removeCallbacks(quoteTick);handler.post(quoteTick);return START_NOT_STICKY;
    }
    private Notification notification(){String description=spokenCode.isEmpty()?"9:00—15:00每3秒更新报价 · 5分钟实线减仓提示音":"正在播报所选持仓涨幅 · 每5秒一次";
        PendingIntent open=PendingIntent.getActivity(this,1433,new Intent(this,MainActivity.class).putExtra("module",5),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle("AItrader · 持仓实时追踪").setContentText(description).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true);
        if(!spokenCode.isEmpty()){PendingIntent stop=PendingIntent.getService(this,1434,new Intent(this,HoldingLiveService.class).setAction(STOP_SPEECH),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);b.addAction(new Notification.Action.Builder(null,"停止播报",stop).build());}return b.build();
    }
    private void foreground(boolean speaking){if(Build.VERSION.SDK_INT>=29){int type=ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;if(speaking||!spokenCode.isEmpty())type|=ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;startForeground(1433,notification(),type);}else startForeground(1433,notification());}
    private void tick(){if(destroyed)return;long now=wallNow();boolean open=HoldingLive.window(now),closing=HoldingLive.closing(now);
        if(!open&&!closing){stopTracking();return;}try{if(HoldingsStore.stocks(this).isEmpty()){stopTracking();return;}}catch(Exception e){stopTracking();return;}
        if(!open&&!spokenCode.isEmpty())finishSpeech("已收盘，播报已停止");
        if((open||!finalQuoteRequested)&&quoteBusy.compareAndSet(false,true)){if(!open)finalQuoteRequested=true;quoteWorker.submit(()->{try{List<String> codes=new ArrayList<>();for(JSONObject item:HoldingsStore.stocks(this))codes.add(item.getString("code"));List<TencentClient.Quote> quotes=TencentClient.quotes(codes);if(!destroyed)HoldingLive.publish(this,quotes,wallNow());}catch(Exception e){if(!destroyed)HoldingLive.prefs(this).edit().putString("quote_error","实时行情未更新："+e.getMessage()).apply();}finally{quoteBusy.set(false);}});}
        if(now-lastSignal>=15_000&&signalBusy.compareAndSet(false,true)){lastSignal=now;signalWorker.submit(()->{try{five.scan(this,wallNow());}catch(Exception e){if(!destroyed)HoldingLive.prefs(this).edit().putString("tracking_error","5分钟检查未完成："+e.getMessage()).apply();}finally{signalBusy.set(false);}});}
        handler.postDelayed(quoteTick,HoldingLive.QUOTE_MS);
    }
    private void beginSpeech(String code,int minutes){finishSpeech("");try{
        if(!HoldingLive.duration(minutes)||HoldingsStore.find(this,code)==null)throw new Exception("请选择一只持仓股和30分钟、1小时或2小时");
        if(!HoldingLive.window(wallNow()))throw new Exception("请在9:00—15:00期间开启实时涨幅播报");
        spokenCode=code;untilElapsed=elapsedNow()+minutes*60_000L;HoldingLive.prefs(this).edit().putBoolean("speech_active",true).putString("speech_code",code).putLong("speech_until",wallNow()+minutes*60_000L).putInt("speech_minutes",minutes).putString("speech_status","正在准备中文语音…").apply();foreground(true);
        int generation=++voiceGeneration;voice=new TextToSpeech(this,result->handler.post(()->{if(destroyed||generation!=voiceGeneration||voice==null)return;if(result!=TextToSpeech.SUCCESS||voice.setLanguage(Locale.SIMPLIFIED_CHINESE)<0){finishSpeech("手机中文语音不可用，请在系统文字转语音设置中安装中文语音");return;}
            Set<Voice> voices=voice.getVoices();if(voices!=null)for(Voice v:voices)if("zh".equals(v.getLocale().getLanguage())&&!v.isNetworkConnectionRequired()){voice.setVoice(v);break;}
            voice.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());voice.setSpeechRate(1f);voice.setOnUtteranceProgressListener(new UtteranceProgressListener(){public void onStart(String id){}public void onDone(String id){handler.post(()->{if(generation==voiceGeneration&&id.equals(utterance)){utterance="";if(focus!=null){audio.abandonAudioFocusRequest(focus);focus=null;}}});}public void onError(String id){handler.post(()->{if(generation==voiceGeneration)finishSpeech("语音播放失败，请检查手机中文语音设置");});}});
            ready=true;handler.removeCallbacks(speechTick);handler.post(speechTick);
        }));handler.post(speechTick);
    }catch(Exception e){finishSpeech(String.valueOf(e.getMessage()));}}
    private void report(){if(destroyed||spokenCode.isEmpty())return;if(elapsedNow()>=untilElapsed||!HoldingLive.window(wallNow())){finishSpeech("播报时长已到或已收盘");return;}
        try{if(HoldingsStore.find(this,spokenCode)==null){finishSpeech("该股票已移出持仓，播报已停止");return;}
            JSONObject q=HoldingLive.quotes(this).optJSONObject(spokenCode);String message=HoldingLive.fresh(q,wallNow())?HoldingLive.spoken(q.getDouble("change")):null;
            if(message==null)HoldingLive.prefs(this).edit().putString("speech_status","等待今日实时行情，暂停播报旧报价").apply();
            else if(ready&&!audioPaused&&!voice.isSpeaking()){
                final int generation=voiceGeneration;if(focus==null)focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setOnAudioFocusChangeListener(value->handler.post(()->{if(destroyed||generation!=voiceGeneration||spokenCode.isEmpty())return;if(value==AudioManager.AUDIOFOCUS_LOSS)finishSpeech("其他音频占用，播报已停止");else {audioPaused=value==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;if(audioPaused&&voice!=null)voice.stop();}})).build();
                if(audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){HoldingLive.prefs(this).edit().putString("speech_status","播报中 · 每5秒一次").apply();utterance="gain:"+spokenCode+":"+elapsedNow();if(voice.speak(message,TextToSpeech.QUEUE_FLUSH,null,utterance)==TextToSpeech.ERROR){finishSpeech("语音播放失败，请检查手机中文语音设置");return;}}
            }
        }catch(Exception e){finishSpeech("播报未完成："+e.getMessage());return;}
        handler.postDelayed(speechTick,HoldingLive.SPEECH_MS);
    }
    private void finishSpeech(String status){handler.removeCallbacks(speechTick);voiceGeneration++;ready=false;audioPaused=false;spokenCode="";if(voice!=null){voice.stop();voice.shutdown();voice=null;}if(focus!=null&&audio!=null){audio.abandonAudioFocusRequest(focus);focus=null;}
        HoldingLive.prefs(this).edit().putBoolean("speech_active",false).putString("speech_status",status).remove("speech_until").apply();if(!destroyed)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1433,notification());
    }
    private void stopTracking(){HoldingLive.prefs(this).edit().putString("tracking_status","实时追踪已暂停；交易时段打开应用会继续").apply();finishSpeech("实时追踪已停止");stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    @Override public void onTimeout(int startId,int type){HoldingLive.prefs(this).edit().putString("tracking_error","系统已暂停后台追踪，请重新打开应用").apply();stopTracking();}
    @Override public void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);finishSpeech("播报已停止");quoteWorker.shutdownNow();signalWorker.shutdownNow();getSharedPreferences("holdings",0).unregisterOnSharedPreferenceChangeListener(holdingsChanged);if(wake!=null&&wake.isHeld())wake.release();RUNNING.set(false);super.onDestroy();}
}
