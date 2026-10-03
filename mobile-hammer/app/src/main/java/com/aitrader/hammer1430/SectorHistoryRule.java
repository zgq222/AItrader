package com.aitrader.hammer1430;

import java.util.*;

/** Web-equivalent history on one shared calendar; missing dates never become zeroes. */
final class SectorHistoryRule {
    static final class Row {
        final String date;final double relative5,relative20,outperform,above;
        final int covered5,covered20,maCovered,eligible,market5,market20,marketExpected;
        Row(String d,double r5,double r20,double o,double a,int c5,int c20,int ma,int e,int m5,int m20,int expected){date=d;relative5=r5;relative20=r20;outperform=o;above=a;covered5=c5;covered20=c20;maCovered=ma;eligible=e;market5=m5;market20=m20;marketExpected=expected;}
    }
    private static final class Sector {
        final double[] sum5,sum20;final int[] count5,count20,ma,above;
        final List<double[]> gains=new ArrayList<>();int expected;
        Sector(int n){sum5=new double[n];sum20=new double[n];count5=new int[n];count20=new int[n];ma=new int[n];above=new int[n];}
    }
    static Map<String,List<Row>> calculate(List<SectorRotationRule.Stock> stocks,List<String> dates,Collection<String> names){
        return calculate(stocks,dates,names,null);
    }
    static Map<String,List<Row>> calculate(List<SectorRotationRule.Stock> stocks,List<String> dates,Collection<String> names,Map<String,List<String>> mapping){
        int n=dates.size(),expected=0;Map<String,Sector> sectors=new TreeMap<>();for(String name:names)sectors.put(name,new Sector(n));
        double[] marketSum5=new double[n],marketSum20=new double[n];int[] marketCount5=new int[n],marketCount20=new int[n];
        for(SectorRotationRule.Stock stock:stocks){
            if(!SelectionRule.eligible(stock.code,stock.name)||stock.name.isEmpty())continue;expected++;
            List<Sector> members=new ArrayList<>();for(String name:SectorRotationRule.memberships(stock,mapping)){Sector s=sectors.get(name);if(s!=null){s.expected++;members.add(s);}}
            Map<String,Double> byDate=new HashMap<>();for(DailyBar b:stock.bars)byDate.put(b.date,b.close);
            double[] c=new double[n],g5=new double[n];Arrays.fill(g5,Double.NaN);double[] sums=new double[n+1];int[] counts=new int[n+1];
            for(int i=0;i<n;i++){double value=byDate.getOrDefault(dates.get(i),Double.NaN);c[i]=Double.isFinite(value)&&value>0?value:Double.NaN;sums[i+1]=sums[i]+(Double.isFinite(c[i])?c[i]:0);counts[i+1]=counts[i]+(Double.isFinite(c[i])?1:0);}
            for(int i=0;i<n;i++){
                if(i>=5&&counts[i+1]-counts[i-5]==6){double g=(c[i]/c[i-5]-1)*100;if(Double.isFinite(g)){g5[i]=g;marketSum5[i]+=g;marketCount5[i]++;for(Sector s:members){s.sum5[i]+=g;s.count5[i]++;}}}
                if(i>=20&&counts[i+1]-counts[i-20]==21){double g=(c[i]/c[i-20]-1)*100;if(Double.isFinite(g)){marketSum20[i]+=g;marketCount20[i]++;for(Sector s:members){s.sum20[i]+=g;s.count20[i]++;}}}
                if(i>=19&&counts[i+1]-counts[i-19]==20){double avg=(sums[i+1]-sums[i-19])/20;for(Sector s:members){s.ma[i]++;if(c[i]>avg+Math.max(Math.abs(avg)*1e-12,1e-12))s.above[i]++;}}
            }for(Sector s:members)s.gains.add(g5);
        }
        Map<String,List<Row>> result=new TreeMap<>();
        for(Map.Entry<String,Sector> entry:sectors.entrySet()){Sector s=entry.getValue();List<Row> rows=new ArrayList<>();
            for(int i=0;i<n;i++){
                double bm5=mean(marketSum5[i],marketCount5[i]),bm20=mean(marketSum20[i],marketCount20[i]);int wins=0;for(double[] g:s.gains)if(g[i]>bm5)wins++;
                rows.add(new Row(dates.get(i),mean(s.sum5[i],s.count5[i])-bm5,mean(s.sum20[i],s.count20[i])-bm20,s.count5[i]>0&&Double.isFinite(bm5)?wins*100.0/s.count5[i]:Double.NaN,s.ma[i]>0?s.above[i]*100.0/s.ma[i]:Double.NaN,s.count5[i],s.count20[i],s.ma[i],s.expected,marketCount5[i],marketCount20[i],expected));
            }result.put(entry.getKey(),rows);
        }return result;
    }
    private static double mean(double sum,int count){return count>0?sum/count:Double.NaN;}
}
