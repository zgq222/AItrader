package com.aitrader.hammer1430;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Run with the JDK, independently of Android's org.json stubs. */
public final class SelectionRuleTest {
    private static int checks=0;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void near(double actual,double expected,String message){check(Math.abs(actual-expected)<1e-8,message+": "+actual+" != "+expected);}
    private static List<DailyBar> fixture(){
        List<DailyBar> result=new ArrayList<>();LocalDate day=LocalDate.of(2026,8,1);
        while(result.size()<40){
            if(day.getDayOfWeek()!=DayOfWeek.SATURDAY&&day.getDayOfWeek()!=DayOfWeek.SUNDAY){
                int i=result.size();double close=10+i*.2;
                if(i==15)close=14.08; // Previous close 12.80, exactly +10%.
                double open=i>=36&&i<=38?close+.1:close-.1;
                result.add(new DailyBar(day.toString(),open,Math.max(open,close)+.1,Math.min(open,close)-.1,close,100));
            }
            day=day.plusDays(1);
        }
        return result;
    }
    private static List<String> dates(List<DailyBar> bars){List<String> days=new ArrayList<>();for(DailyBar bar:bars)days.add(bar.date);return days.subList(days.size()-30,days.size());}
    private static void replace(List<DailyBar> bars,int index,double open,double low,double close){
        DailyBar old=bars.get(index);bars.set(index,new DailyBar(old.date,open,Math.max(open,close)+.1,low,close,old.volume));
    }
    private static void rules(){
        check(SelectionRule.eligible("605058","澳弘电子"),"main board accepted");
        check(!SelectionRule.eligible("300001","创业板"),"growth board rejected");
        check(!SelectionRule.eligible("600001","*st示例"),"ST rejected case-insensitively");
        List<DailyBar> bars=fixture();List<String> days=new ArrayList<>(dates(bars));
        SelectionRule.Pullback hit=SelectionRule.fiveMinute(bars,days);
        check(hit!=null,"latest previous-session triple accepted");
        check(hit.end.equals(days.get(28)),"third day is previous market session");
        check(hit.start.equals(days.get(26)),"uses exactly the three preceding sessions");
        check(hit.ma20>hit.previousMa20,"MA20 strictly rising");
        // A bullish candle whose close is below its prior close also qualifies.
        replace(bars,37,16.9,16.8,17.0);
        check(SelectionRule.fiveMinute(bars,days)!=null,"bullish but down close accepted");
        bars=fixture();bars.remove(37);
        check(SelectionRule.fiveMinute(bars,days)==null,"missing market day cannot be bridged");
        bars=fixture();replace(bars,36,17.0,16.9,17.2);replace(bars,39,18,17.6,17.8);
        check(SelectionRule.fiveMinute(bars,days)==null,"triple ending on latest day is rejected");
        bars=fixture();for(int i=30;i<33;i++)replace(bars,i,bars.get(i).close+.1,bars.get(i).low,bars.get(i).close);
        replace(bars,38,17.5,17.4,17.6);
        check(SelectionRule.fiveMinute(bars,days)==null,"an older triple cannot substitute for previous-session triple");
        bars=fixture();replace(bars,39,10,9.9,10);
        check(SelectionRule.fiveMinute(bars,days)==null,"falling MA20 rejected");
        bars=fixture();replace(bars,15,12.9,12.8,13.0);
        check(SelectionRule.fiveMinute(bars,days)==null,"no recent limit-up rejected");
        bars=fixture();replace(bars,3,10.5,1,10.6);replace(bars,12,12.3,5,12.4);replace(bars,13,12.5,5,12.6);
        SelectionRule.Gain gain=SelectionRule.thirtyDayGain(bars,days);
        near(gain.low,5,"uses lowest intraday LOW within window");
        near(gain.percent,(17.8/5-1)*100,"lowest LOW to latest CLOSE gain");
        check(gain.lowDate.equals(bars.get(12).date),"earliest date wins tied lows");
        bars.remove(bars.size()-1);
        check(SelectionRule.thirtyDayGain(bars,days)==null,"stale latest candle rejected");
        check(SelectionRule.thirtyDayGain(fixture(),days.subList(1,30))==null,"incomplete global 30-session calendar rejected");
    }
    private static void industryLeaders(){
        List<SelectionRule.IndustryGain> candidates=new ArrayList<>();
        for(int i=0;i<240;i++)candidates.add(new SelectionRule.IndustryGain(
                String.format(Locale.ROOT,"%06d",i),"行业"+(i%4),
                new SelectionRule.Gain("2026-08-20","2026-09-29",8,300-i)));
        List<SelectionRule.IndustryGain> selected=SelectionRule.gainIndustryLeaders(candidates);
        check(selected.size()==200,"top 150 seeds four industries, each contributes 50 stocks");
        check(selected.stream().mapToInt(item->item.globalRank).max().orElse(0)==200,
                "selected stocks can have global ranks after 150");
        for(int industry=0;industry<4;industry++){
            final String name="行业"+industry;
            List<SelectionRule.IndustryGain> sector=new ArrayList<>();
            for(SelectionRule.IndustryGain item:selected)if(item.industry.equals(name))sector.add(item);
            check(sector.size()==50,"industry contains at most 50 leaders");
            for(int i=0;i<sector.size();i++){
                check(sector.get(i).sectorRank==i+1,"industry rank remains descending");
                check(sector.get(i).gain.percent>0,"industry leader gain is positive");
            }
        }
        List<SelectionRule.IndustryGain> mixed=Arrays.asList(
                new SelectionRule.IndustryGain("600001","甲",new SelectionRule.Gain("a","b",8,9)),
                new SelectionRule.IndustryGain("600002","乙",new SelectionRule.Gain("a","b",8,10)),
                new SelectionRule.IndustryGain("600003","甲",new SelectionRule.Gain("a","b",8,8)),
                new SelectionRule.IndustryGain("600004","乙",new SelectionRule.Gain("a","b",8,7)));
        List<SelectionRule.IndustryGain> positive=SelectionRule.gainIndustryLeaders(mixed);
        check(positive.size()==2,"zero and negative gains excluded");
        check(positive.get(0).code.equals("600002")&&positive.get(1).code.equals("600001"),
                "industries follow first occurrence in global ranking");
    }
    private static void desktopFixture(Path folder)throws Exception{
        List<String> calendar=Arrays.asList(Files.readString(folder.resolve("calendar.txt"),StandardCharsets.UTF_8).trim().split(","));
        Map<String,List<DailyBar>> histories=new TreeMap<>();Map<String,String> names=new HashMap<>();
        for(String line:Files.readAllLines(folder.resolve("bars.tsv"),StandardCharsets.UTF_8)){
            String[] p=line.split("\t");names.put(p[0],p[1]);
            histories.computeIfAbsent(p[0],key->new ArrayList<>()).add(new DailyBar(p[2],Double.parseDouble(p[3]),Double.parseDouble(p[4]),Double.parseDouble(p[5]),Double.parseDouble(p[6]),100));
        }
        List<String> five=new ArrayList<>();Map<String,SelectionRule.Gain> gains=new HashMap<>();
        for(Map.Entry<String,List<DailyBar>> stock:histories.entrySet()){
            if(!SelectionRule.eligible(stock.getKey(),names.get(stock.getKey())))continue;
            if(SelectionRule.fiveMinute(stock.getValue(),calendar)!=null)five.add(stock.getKey());
            SelectionRule.Gain gain=SelectionRule.thirtyDayGain(stock.getValue(),calendar);if(gain!=null)gains.put(stock.getKey(),gain);
        }
        check(five.equals(Files.readAllLines(folder.resolve("five.txt"),StandardCharsets.UTF_8)),"mobile previous-session screen matches independent Python calculation on local data");
        List<String> ranked=new ArrayList<>(gains.keySet());ranked.sort((a,b)->{int order=Double.compare(gains.get(b).percent,gains.get(a).percent);return order!=0?order:a.compareTo(b);});
        List<String> expected=Files.readAllLines(folder.resolve("gain.tsv"),StandardCharsets.UTF_8);
        check(expected.size()==150,"reference contains 150 results");
        for(int i=0;i<150;i++){String[] row=expected.get(i).split("\t");check(ranked.get(i).equals(row[0]),"gain rank "+(i+1));
            SelectionRule.Gain gain=gains.get(row[0]);near(gain.percent,Double.parseDouble(row[1]),"ranked gain");
            check(gain.lowDate.equals(row[2]),"ranked low date");near(gain.low,Double.parseDouble(row[3]),"ranked low price");
        }
        Map<String,String> industries=new HashMap<>();
        for(String line:Files.readAllLines(folder.resolve("industries.tsv"),StandardCharsets.UTF_8)){
            String[] row=line.split("\t",-1);industries.put(row[0],row[1]);
        }
        List<SelectionRule.IndustryGain> candidates=new ArrayList<>();
        for(String code:ranked)candidates.add(new SelectionRule.IndustryGain(code,industries.getOrDefault(code,"待分类"),gains.get(code)));
        List<SelectionRule.IndustryGain> selected=SelectionRule.gainIndustryLeaders(candidates);
        List<String> expectedLeaders=Files.readAllLines(folder.resolve("leaders.tsv"),StandardCharsets.UTF_8);
        check(selected.size()==expectedLeaders.size(),"industry leader count matches independent Python ranking");
        for(int i=0;i<selected.size();i++){
            String[] row=expectedLeaders.get(i).split("\t");SelectionRule.IndustryGain item=selected.get(i);
            check(item.code.equals(row[0]),"leader code at "+i);
            near(item.gain.percent,Double.parseDouble(row[1]),"leader gain at "+i);
            check(item.industry.equals(row[2]),"leader industry at "+i);
            check(item.globalRank==Integer.parseInt(row[3]),"global rank at "+i);
            check(item.sectorRank==Integer.parseInt(row[4]),"industry rank at "+i);
        }
        System.out.println("Local data verified: "+histories.size()+" stocks, "+five.size()+" mobile five-minute candidates, "+selected.size()+" industry leaders.");
    }
    public static void main(String[] args)throws Exception{rules();industryLeaders();if(args.length>0)desktopFixture(Path.of(args[0]));System.out.println("Passed "+checks+" checks.");}
}
