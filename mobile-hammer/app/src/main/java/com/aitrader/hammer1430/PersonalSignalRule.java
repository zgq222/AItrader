package com.aitrader.hammer1430;

import java.util.*;

/** Calendar-aligned personal signals, using the chart's exact solid behavior frames. */
final class PersonalSignalRule {
    static final int VERSION=2;
    static final class Decision {
        String tradeDate,previousDate;
        DailyBar previous,beforePrevious;
        boolean minuteCurrent,baselineReady;
        MinuteBehavior.Zone firstAttack,secondAttack,singleAttack;
        final List<MinuteBehavior.Zone> reductions=new ArrayList<>();
        boolean bearish(){return previous!=null&&previous.close<previous.open;}
        boolean falling(){return previous!=null&&beforePrevious!=null&&previous.close<beforePrevious.close;}
        boolean eligible(){return bearish()||falling();}
        boolean qualifies(){return eligible()&&secondAttack!=null;}
        boolean qualifiesSingle(){return eligible()&&singleAttack!=null;}
    }
    static Decision evaluate(List<DailyBar> daily,List<String> calendar,List<DailyBar> minutes,long now){
        if(calendar.size()<2)throw new IllegalArgumentException("交易日历不足两个交易日");
        Decision d=new Decision();d.tradeDate=calendar.get(calendar.size()-1);d.previousDate=calendar.get(calendar.size()-2);
        for(DailyBar b:daily)if(b.date.equals(d.previousDate)&&Double.isFinite(b.open)&&Double.isFinite(b.close)&&b.open>0&&b.close>0)d.previous=b;
        if(calendar.size()>=3){String reference=calendar.get(calendar.size()-3);for(DailyBar b:daily)if(b.date.equals(reference)&&Double.isFinite(b.close)&&b.close>0)d.beforePrevious=b;}
        for(DailyBar b:minutes)if(b.date.startsWith(d.tradeDate+" ")&&MinuteBehavior.epoch(b.date)<=now)d.minuteCurrent=true;
        Map<String,Double> baselines=MinuteBehavior.baselines(minutes,now);
        d.baselineReady=baselines.getOrDefault(d.tradeDate,0.0)>0;
        MinuteBehavior.Zone last=null;
        for(MinuteBehavior.Zone z:MinuteBehavior.find(minutes,now,baselines)){
            if(!minutes.get(z.start).date.startsWith(d.tradeDate+" ")||z.contained||z.kind==MinuteBehavior.VOLUME)continue;
            if(z.kind==MinuteBehavior.REDUCE)d.reductions.add(z);
            if(z.kind==MinuteBehavior.ATTACK)d.singleAttack=z;
            if(z.kind==MinuteBehavior.ATTACK&&last!=null&&last.kind==MinuteBehavior.ATTACK){d.firstAttack=last;d.secondAttack=z;}
            last=z;
        }
        return d;
    }
}
