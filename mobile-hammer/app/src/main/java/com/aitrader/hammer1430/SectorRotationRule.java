package com.aitrader.hammer1430;

import java.util.*;

/** Same four complete-window percentile metrics as industry_strength_service.py. */
final class SectorRotationRule {
    static final class Stock {
        final String code,name,industry;
        final List<DailyBar> bars;
        SelectionRule.Gain gain;
        int sectorRank;
        Stock(String code,String name,String industry,List<DailyBar> bars){
            this.code=code;this.name=name;this.industry=industry;this.bars=bars==null?Collections.emptyList():bars;
        }
    }
    static final class Sector {
        final String name;
        final List<Stock> stocks=new ArrayList<>();
        final List<Double> gains5=new ArrayList<>(),gains20=new ArrayList<>();
        double relative5=Double.NaN,relative20=Double.NaN,outperform=Double.NaN,above=Double.NaN,up=Double.NaN;
        double strength=Double.NaN,breadth=Double.NaN,score=Double.NaN;
        int rank,maCovered,aboveCount,dayCovered,upCount;
        Sector(String name){this.name=name;}
        boolean complete(){return Double.isFinite(relative5)&&Double.isFinite(relative20)&&Double.isFinite(outperform)&&Double.isFinite(above);}
        double metric(int field){return field==0?relative5:field==1?relative20:field==2?outperform:above;}
    }
    private static double mean(List<Double> values){double sum=0;for(double value:values)sum+=value;return values.isEmpty()?Double.NaN:sum/values.size();}
    private static boolean valid(double value){return Double.isFinite(value)&&value>0;}
    private static double gain(double[] close,int period){
        if(close.length<=period)return Double.NaN;
        for(int i=close.length-period-1;i<close.length;i++)if(!valid(close[i]))return Double.NaN;
        return (close[close.length-1]/close[close.length-period-1]-1)*100;
    }
    static List<Sector> calculate(List<Stock> stocks,List<String> dates,Collection<String> industries){
        return calculate(stocks,dates,industries,null);
    }
    static Collection<String> memberships(Stock stock,Map<String,List<String>> mapping){
        return mapping==null?Collections.singleton(stock.industry):new LinkedHashSet<>(mapping.getOrDefault(stock.code,Collections.emptyList()));
    }
    static List<Sector> calculate(List<Stock> stocks,List<String> dates,Collection<String> industries,Map<String,List<String>> mapping){
        TreeMap<String,Sector> sectors=new TreeMap<>();for(String name:industries)sectors.put(name,new Sector(name));
        List<Double> market5=new ArrayList<>(),market20=new ArrayList<>();
        for(Stock stock:stocks){
            if(!SelectionRule.eligible(stock.code,stock.name)||stock.name.isEmpty())continue;
            Map<String,Double> byDate=new HashMap<>();for(DailyBar b:stock.bars)byDate.put(b.date,b.close);
            double[] close=new double[dates.size()];for(int i=0;i<dates.size();i++)close[i]=byDate.getOrDefault(dates.get(i),Double.NaN);
            double g5=gain(close,5),g20=gain(close,20);if(Double.isFinite(g5))market5.add(g5);if(Double.isFinite(g20))market20.add(g20);
            for(String member:memberships(stock,mapping)){
            Sector sector=sectors.get(member);if(sector==null)continue;
            stock.gain=SelectionRule.thirtyDayGain(stock.bars,dates);sector.stocks.add(stock);
            if(Double.isFinite(g5))sector.gains5.add(g5);if(Double.isFinite(g20))sector.gains20.add(g20);
            if(close.length>=20){double sum=0;boolean complete=true;
                for(int i=close.length-20;i<close.length;i++){if(!valid(close[i]))complete=false;sum+=close[i];}
                if(complete){sector.maCovered++;double average=sum/20;
                    if(close[close.length-1]>average+Math.max(Math.abs(average)*1e-12,1e-12))sector.aboveCount++;}
            }
            if(close.length>=2&&valid(close[close.length-1])&&valid(close[close.length-2])){
                sector.dayCovered++;if(close[close.length-1]>close[close.length-2])sector.upCount++;
            }
            }
        }
        double benchmark5=mean(market5),benchmark20=mean(market20);
        for(Sector sector:sectors.values()){
            sector.relative5=mean(sector.gains5)-benchmark5;sector.relative20=mean(sector.gains20)-benchmark20;
            if(!sector.gains5.isEmpty()&&Double.isFinite(benchmark5)){
                int count=0;for(double value:sector.gains5)if(value>benchmark5)count++;
                sector.outperform=(double)count/sector.gains5.size()*100;
            }
            if(sector.maCovered>0)sector.above=(double)sector.aboveCount/sector.maCovered*100;
            if(sector.dayCovered>0)sector.up=(double)sector.upCount/sector.dayCovered*100;
            sector.stocks.sort((a,b)->{if(a.gain==null||b.gain==null)return a.gain==b.gain?a.code.compareTo(b.code):a.gain==null?1:-1;
                int cmp=Double.compare(b.gain.percent,a.gain.percent);return cmp==0?a.code.compareTo(b.code):cmp;});
            for(int i=0;i<sector.stocks.size();i++)sector.stocks.get(i).sectorRank=i+1;
        }
        List<Sector> result=new ArrayList<>(sectors.values());rank(result);return result;
    }
    static void rank(List<Sector> sectors){
        List<Sector> complete=new ArrayList<>();for(Sector s:sectors){s.strength=s.breadth=s.score=Double.NaN;s.rank=0;if(s.complete())complete.add(s);}
        double[][] scores=new double[complete.size()][4];
        for(int field=0;field<4;field++)for(int i=0;i<complete.size();i++){
            double value=complete.get(i).metric(field);int less=0,equal=0;
            for(Sector other:complete){int cmp=Double.compare(other.metric(field),value);if(cmp<0)less++;else if(cmp==0)equal++;}
            scores[i][field]=(less+(equal+1)/2.0)/complete.size()*100;
        }
        for(int i=0;i<complete.size();i++){Sector s=complete.get(i);s.strength=(scores[i][0]+scores[i][1])/2;
            s.breadth=(scores[i][2]+scores[i][3])/2;s.score=(s.strength+s.breadth)/2;}
        sectors.sort((a,b)->{if(!Double.isFinite(a.score)||!Double.isFinite(b.score))return a.complete()==b.complete()?a.name.compareTo(b.name):a.complete()?-1:1;
            int cmp=Double.compare(b.score,a.score);return cmp==0?a.name.compareTo(b.name):cmp;});
        for(int i=0;i<sectors.size();i++)if(sectors.get(i).complete())sectors.get(i).rank=i+1;
    }
}
