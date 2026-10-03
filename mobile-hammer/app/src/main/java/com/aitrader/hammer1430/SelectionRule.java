package com.aitrader.hammer1430;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Mobile selection rules. The desktop pullback window intentionally differs. */
final class SelectionRule {
    static final class Pullback {
        final String start,end,limitUp;
        final double ma20,previousMa20;
        Pullback(String start,String end,String limitUp,double ma20,double previousMa20){
            this.start=start;this.end=end;this.limitUp=limitUp;this.ma20=ma20;this.previousMa20=previousMa20;
        }
    }
    static final class Gain {
        final String lowDate,endDate;
        final double low,close,percent;
        Gain(String lowDate,String endDate,double low,double close){
            this.lowDate=lowDate;this.endDate=endDate;this.low=low;this.close=close;
            percent=(close/low-1.0)*100.0;
        }
    }
    static final class IndustryGain {
        final String code,industry;
        final Gain gain;
        int globalRank,sectorRank;
        IndustryGain(String code,String industry,Gain gain){
            this.code=code;this.industry=industry;this.gain=gain;
        }
    }
    static boolean eligible(String code,String name){
        return code.matches("(000|001|002|003|600|601|603|605)\\d{3}")
                && !name.toUpperCase(Locale.ROOT).contains("ST");
    }
    static Pullback fiveMinute(List<DailyBar> bars,List<String> dates){
        if(dates.size()<30||bars.size()<21)return null;
        String latest=dates.get(dates.size()-1);
        if(!latest.equals(bars.get(bars.size()-1).date))return null;
        double current=0,previous=0;
        for(int i=bars.size()-20;i<bars.size();i++)current+=bars.get(i).close;
        for(int i=bars.size()-21;i<bars.size()-1;i++)previous+=bars.get(i).close;
        current/=20;previous/=20;
        if(!Double.isFinite(current)||!Double.isFinite(previous)||current<=previous)return null;
        // The third day must be the market session immediately before latest.
        List<String> pullbackDates=dates.subList(dates.size()-4,dates.size()-1);
        Map<String,Integer> positions=new HashMap<>();
        for(int i=0;i<bars.size();i++)positions.put(bars.get(i).date,i);
        for(String day:pullbackDates){
            Integer index=positions.get(day);if(index==null||index<1)return null;
            DailyBar bar=bars.get(index),prior=bars.get(index-1);
            if(!Double.isFinite(bar.open)||!Double.isFinite(bar.close)||!Double.isFinite(prior.close)
                    || !(bar.close<bar.open||bar.close<prior.close))return null;
        }
        Set<String> recent=new HashSet<>(dates.subList(dates.size()-30,dates.size()));
        String limitUp=null;
        for(int i=1;i<bars.size();i++){
            DailyBar bar=bars.get(i),prior=bars.get(i-1);
            if(!recent.contains(bar.date)||!Double.isFinite(prior.close)||prior.close<=0)continue;
            double limit=BigDecimal.valueOf(prior.close).multiply(new BigDecimal("1.10"))
                    .setScale(2,RoundingMode.HALF_UP).doubleValue();
            if(Math.abs(bar.close-limit)<0.005)limitUp=bar.date;
        }
        if(limitUp==null)return null;
        return new Pullback(pullbackDates.get(0),pullbackDates.get(2),limitUp,current,previous);
    }
    static Gain thirtyDayGain(List<DailyBar> bars,List<String> dates){
        if(dates.size()<30||bars.isEmpty())return null;
        String latest=dates.get(dates.size()-1);
        DailyBar last=bars.get(bars.size()-1);
        if(!latest.equals(last.date)||!Double.isFinite(last.close)||last.close<=0)return null;
        Set<String> recent=new HashSet<>(dates.subList(dates.size()-30,dates.size()));
        double low=Double.POSITIVE_INFINITY;String lowDate=null;
        for(DailyBar bar:bars)if(recent.contains(bar.date)&&Double.isFinite(bar.low)&&bar.low>0&&bar.low<low){
            low=bar.low;lowDate=bar.date;
        }
        return lowDate==null?null:new Gain(lowDate,latest,low,last.close);
    }
    static List<IndustryGain> gainIndustryLeaders(List<IndustryGain> candidates){
        List<IndustryGain> ranked=new ArrayList<>(candidates);
        ranked.sort((a,b)->{
            int order=Double.compare(b.gain.percent,a.gain.percent);
            return order!=0?order:a.code.compareTo(b.code);
        });
        LinkedHashSet<String> seedIndustries=new LinkedHashSet<>();
        for(int i=0;i<Math.min(150,ranked.size());i++)seedIndustries.add(ranked.get(i).industry);
        LinkedHashMap<String,List<IndustryGain>> leaders=new LinkedHashMap<>();
        for(String industry:seedIndustries)leaders.put(industry,new ArrayList<>());
        for(int i=0;i<ranked.size();i++){
            IndustryGain item=ranked.get(i);
            List<IndustryGain> sector=leaders.get(item.industry);
            if(sector==null||sector.size()>=50||item.gain.percent<=0)continue;
            item.globalRank=i+1;item.sectorRank=sector.size()+1;sector.add(item);
        }
        List<IndustryGain> result=new ArrayList<>();
        for(List<IndustryGain> sector:leaders.values())result.addAll(sector);
        return result;
    }
}
