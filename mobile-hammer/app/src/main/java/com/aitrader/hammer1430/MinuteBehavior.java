package com.aitrader.hammer1430;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Same volume-first frames and containment as the web chart, using native 15-minute bars. */
final class MinuteBehavior {
    static final int MINUTES=15,ATTACK=0,REDUCE=1,VOLUME=2;
    static final long INTERVAL_MS=MINUTES*60_000L;
    static final String RULES="与网页5分钟图相同的放量框选规则，周期使用15分钟。\n\n基准为当前日之前20个完整交易日中，全部15分钟K线成交量的中位数（共320根）。量比≥2.5即框选，无实体幅度、突破或收盘位置限制。当前日不参与自身基准；不足20个完整日、基准为0时不判断。\n\n相邻放量K线合成一框，仅同日且相隔15分钟合并，午休、隔日和缺根不合并。按整框首根开盘价与末根收盘价判断：上涨为红框主力进攻，下跌为绿框主力减仓，持平为黄框放量持平。框覆盖全部成员K线的最高与最低价。\n\n与网页相同，相邻有效行为框有价格包含关系时，内层框改为虚线，仍保留全部框选；持平框不参与包含比较。图上文字只显示最近4项或所选项，但框选不限制数量。\n\n只确认已结束K线。K线时间为结束时间，例如09:45代表09:30—09:45。15分钟合并了更短周期的波动，因此结果不会与5分钟逐根相同。进攻、减仓是量价方向描述，不代表真实账户行为。";
    static final DateTimeFormatter FORMAT=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT);
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    static final class Zone {
        final int start,end,kind;final boolean attack;final double ratio,move,low,high;
        boolean contained;
        Zone(int s,int e,int k,double r,double m,double l,double h){start=s;end=e;kind=k;attack=k==ATTACK;ratio=r;move=m;low=l;high=h;}
        String chartLabel(){return kind==VOLUME?"放量持平":attack?"主力进攻":"主力减仓";}
        String label(){return chartLabel()+"（量价推测）"+(contained?" · 包含框":"");}
        String description(List<DailyBar> bars){return bars.get(start).date+"—"+bars.get(end).date.substring(11)+String.format(Locale.CHINA," · 最大量比 %.2f · 区间 %+.2f%%",ratio,move);}
    }
    static long epoch(String stamp){try{return LocalDateTime.parse(stamp,FORMAT).atZone(ZONE).toInstant().toEpochMilli();}catch(Exception e){return Long.MAX_VALUE;}}
    static boolean isBarTime(LocalTime time){int minute=time.getHour()*60+time.getMinute();return minute%MINUTES==0&&((minute>=570+MINUTES&&minute<=690)||(minute>=780+MINUTES&&minute<=900));}
    private static boolean validTime(String stamp){try{return isBarTime(LocalDateTime.parse(stamp,FORMAT).toLocalTime());}catch(Exception e){return false;}}
    private static boolean validBar(DailyBar b){return Double.isFinite(b.open)&&Double.isFinite(b.high)&&Double.isFinite(b.low)&&Double.isFinite(b.close)&&Double.isFinite(b.volume)&&b.low>0&&b.volume>=0&&b.high>=Math.max(b.open,b.close)&&b.low<=Math.min(b.open,b.close);}
    static Map<String,Double> baselines(List<DailyBar> bars,long now){
        SortedMap<String,SortedMap<String,Double>> days=new TreeMap<>();
        for(DailyBar b:bars)if(validTime(b.date)&&validBar(b)&&epoch(b.date)<=now)
            days.computeIfAbsent(b.date.substring(0,10),key->new TreeMap<>()).put(b.date,b.volume);
        List<double[]> complete=new ArrayList<>();Map<String,Double> result=new HashMap<>();
        for(Map.Entry<String,SortedMap<String,Double>> day:days.entrySet()){
            if(complete.size()>=20){
                double[] sample=new double[20*16];int at=0;
                for(int i=complete.size()-20;i<complete.size();i++)for(double v:complete.get(i))sample[at++]=v;
                Arrays.sort(sample);result.put(day.getKey(),(sample[159]+sample[160])/2);
            }
            if(day.getValue().size()==16)complete.add(day.getValue().values().stream().mapToDouble(Double::doubleValue).toArray());
        }
        return result;
    }
    static List<Zone> find(List<DailyBar> bars,long now){return find(bars,now,baselines(bars,now));}
    static List<Zone> find(List<DailyBar> bars,long now,Map<String,Double> baselines){
        List<Zone> zones=new ArrayList<>();
        for(int i=0;i<bars.size();i++){
            DailyBar b=bars.get(i);if(!validTime(b.date)||!validBar(b)||epoch(b.date)>now)continue;
            double baseline=baselines.getOrDefault(b.date.substring(0,10),0.0),ratio=b.volume/baseline;
            if(baseline<=0||ratio<2.5)continue;
            Zone last=zones.isEmpty()?null:zones.get(zones.size()-1);
            boolean merge=last!=null&&last.end==i-1&&b.date.substring(0,10).equals(bars.get(last.end).date.substring(0,10))&&epoch(b.date)-epoch(bars.get(last.end).date)==INTERVAL_MS;
            int start=merge?last.start:i;double open=bars.get(start).open,move=(b.close/open-1)*100;
            int kind=b.close>open?ATTACK:b.close<open?REDUCE:VOLUME;
            Zone zone=new Zone(start,i,kind,merge?Math.max(last.ratio,ratio):ratio,move,merge?Math.min(last.low,b.low):b.low,merge?Math.max(last.high,b.high):b.high);
            if(merge)zones.set(zones.size()-1,zone);else zones.add(zone);
        }
        List<Zone> cluster=new ArrayList<>();double low=0,high=0;
        for(Zone z:zones){
            if(z.kind==VOLUME)continue;
            if(cluster.isEmpty()){cluster.add(z);low=z.low;high=z.high;}
            else if(z.low>=low&&z.high<=high){z.contained=true;cluster.add(z);}
            else if(low>=z.low&&high<=z.high){for(Zone member:cluster)member.contained=true;cluster.add(z);low=z.low;high=z.high;}
            else {cluster.clear();cluster.add(z);low=z.low;high=z.high;}
        }
        return zones;
    }
}
