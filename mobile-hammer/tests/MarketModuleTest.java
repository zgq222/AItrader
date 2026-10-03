package com.aitrader.hammer1430;

import org.json.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class MarketModuleTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void near(double actual,double expected,String message){check(Math.abs(actual-expected)<1e-8,message+": "+actual+" != "+expected);}
    private static List<DailyBar> flat(double close,int count){
        List<DailyBar> bars=new ArrayList<>();for(int i=0;i<count;i++)bars.add(new DailyBar(LocalDate.of(2026,1,1).plusDays(i).toString(),3600,3900,3300,i==count-1?close:3600,1));return bars;
    }
    private static void boundaries()throws Exception{
        double[] closes={3300,3359.999,3360,3839.999,3840,3900};String[] bands={"low","low","middle","middle","high","high"};
        for(int i=0;i<closes.length;i++)check(MarketRules.assess(flat(closes[i],90)).getString("band").equals(bands[i]),"position boundary "+closes[i]);
        check(!MarketRules.assess(flat(3500,29)).getBoolean("available"),"missing 30 sessions has no position");
        check(!MarketRules.assess(flat(3500,89)).getJSONObject("flight_height").getBoolean("available"),"missing 90 sessions has no flight");
        List<DailyBar> invalid=flat(3500,90);invalid.set(89,new DailyBar(invalid.get(89).date,3600,3900,3300,4000,1));
        check(!MarketRules.assess(invalid).getBoolean("available"),"invalid OHLC cannot size a position");
        JSONObject flight=MarketRules.assess(flat(3600,90)).getJSONObject("flight_height");
        check(flight.getInt("reference_count")==10&&flight.getInt("level_count")==3,"duplicate references merge into distinct layers");
        near(flight.getJSONObject("first").getJSONObject("support").getDouble("price"),3600,"equal price is a support");
        check(flight.getJSONObject("second").isNull("resistance"),"missing expanded pressure is explicitly null");
        List<DailyBar> reverse=flat(3500,90);Collections.reverse(reverse);reverse.add(flat(3500,90).get(89));
        check(MarketRules.assess(reverse).getString("date").equals(flat(3500,90).get(89).date),"sort and deduplicate dates");
    }
    private static void level(JSONObject actual,JSONObject expected,String key,String message)throws Exception{
        check(actual.isNull(key)==expected.isNull(key),message+" null boundary");
        if(!actual.isNull(key))near(actual.getJSONObject(key).getDouble("price"),expected.getJSONObject(key).getDouble("price"),message);
    }
    private static void reference(Path path)throws Exception{
        JSONObject fixture=new JSONObject(Files.readString(path));List<DailyBar> index=new ArrayList<>();JSONArray rows=fixture.getJSONArray("index");
        for(int i=0;i<rows.length();i++){JSONArray b=rows.getJSONArray(i);index.add(new DailyBar(b.getString(0),b.getDouble(3),b.getDouble(1),b.getDouble(2),b.getDouble(3),1));}
        JSONObject result=MarketRules.assess(index),expected=fixture.getJSONObject("market");
        check(result.getString("band").equals(expected.getString("band")),"desktop position matches");
        for(String key:new String[]{"low","high","close","lower_threshold","upper_threshold","range_percent"})near(result.getDouble(key),expected.getDouble(key),"desktop "+key);
        JSONObject flight=result.getJSONObject("flight_height"),target=expected.getJSONObject("flight_height");
        check(flight.getInt("level_count")==target.getInt("level_count"),"desktop distinct level count");
        for(int i=0;i<10;i++)near(flight.getJSONArray("references").getJSONObject(i).getDouble("price"),target.getJSONArray("references").getJSONObject(i).getDouble("price"),"desktop reference "+i);
        for(String layer:new String[]{"first","second"})for(String key:new String[]{"support","resistance"})level(flight.getJSONObject(layer),target.getJSONObject(layer),key,"desktop "+layer+" "+key);
        List<String> dates=new ArrayList<>();JSONArray calendar=fixture.getJSONArray("dates");for(int i=0;i<calendar.length();i++)dates.add(calendar.getString(i));
        List<SectorRotationRule.Stock> stocks=new ArrayList<>();Map<String,JSONObject> truth=new HashMap<>();rows=fixture.getJSONArray("stocks");
        for(int i=0;i<rows.length();i++){JSONObject item=rows.getJSONObject(i);List<DailyBar> bars=new ArrayList<>();JSONArray daily=item.getJSONArray("bars");
            for(int j=0;j<daily.length();j++){JSONArray b=daily.getJSONArray(j);bars.add(new DailyBar(b.getString(0),b.optDouble(1,Double.NaN),b.optDouble(2,Double.NaN),b.optDouble(3,Double.NaN),b.optDouble(4,Double.NaN),1));}
            stocks.add(new SectorRotationRule.Stock(item.getString("code"),item.getString("name"),item.getString("industry"),bars));truth.put(item.getString("code"),item);
        }
        JSONArray ranking=fixture.getJSONArray("ranking");List<String> industries=new ArrayList<>();for(int i=0;i<ranking.length();i++)industries.add(ranking.getJSONObject(i).getString("name"));
        List<SectorRotationRule.Sector> sectors=SectorRotationRule.calculate(stocks,dates,industries);check(sectors.size()==ranking.length(),"all desktop industries retained");
        int count=0;for(int i=0;i<sectors.size();i++){SectorRotationRule.Sector s=sectors.get(i);JSONObject row=ranking.getJSONObject(i);
            check(s.name.equals(row.getString("name")),"industry order "+i);
            for(String key:new String[]{"relative_5d","relative_20d","outperform_5d_pct","above_ma20_pct","score","strength_score","breadth_score"}){
                double actual=key.equals("relative_5d")?s.relative5:key.equals("relative_20d")?s.relative20:key.equals("outperform_5d_pct")?s.outperform:key.equals("above_ma20_pct")?s.above:key.equals("score")?s.score:key.equals("strength_score")?s.strength:s.breadth;
                if(row.isNull(key))check(!Double.isFinite(actual),s.name+" missing "+key);else near(actual,row.getDouble(key),s.name+" "+key);
            }
            double prior=Double.POSITIVE_INFINITY;boolean missing=false;
            for(SectorRotationRule.Stock stock:s.stocks){JSONObject gain=truth.get(stock.code).optJSONObject("gain");check((gain==null)==(stock.gain==null),"stock gain availability "+stock.code);
                if(gain!=null){near(stock.gain.percent,gain.getDouble("return_30d_pct"),"stock gain "+stock.code);check(!missing&&stock.gain.percent<=prior,"descending constituents "+stock.code);prior=stock.gain.percent;}
                else missing=true;count++;
            }
        }
        System.out.println("Desktop parity: "+sectors.size()+" industries / "+count+" constituents / ten index references and both layers.");
    }
    private static void ranking(){
        SectorRotationRule.Sector a=new SectorRotationRule.Sector("A"),b=new SectorRotationRule.Sector("B"),missing=new SectorRotationRule.Sector("missing");
        a.relative5=a.relative20=a.outperform=a.above=10;b.relative5=b.relative20=b.outperform=b.above=10;
        List<SectorRotationRule.Sector> rows=new ArrayList<>(Arrays.asList(missing,b,a));SectorRotationRule.rank(rows);
        near(a.score,75,"ties use average percentile rank");check(rows.get(0)==a&&rows.get(1)==b&&rows.get(2)==missing,"tie name order / missing last");
        List<DailyBar> shortBars=flat(3500,30);shortBars.remove(20);List<String> dates=new ArrayList<>();for(DailyBar bar:flat(3500,30))dates.add(bar.date);
        List<SectorRotationRule.Sector> result=SectorRotationRule.calculate(Arrays.asList(new SectorRotationRule.Stock("600001","股A","A",shortBars),new SectorRotationRule.Stock("600002","ST股","A",flat(3600,30))),dates,Arrays.asList("A","empty"));
        SectorRotationRule.Sector sector=result.stream().filter(s->s.name.equals("A")).findFirst().get();
        check(sector.stocks.size()==1,"ST excluded while missing history stock retained");check(sector.gains20.isEmpty()&&sector.maCovered==0,"suspension gap is never filled with zero / prior close");
    }
    public static void main(String[] args)throws Exception{boundaries();ranking();if(args.length>0)reference(Path.of(args[0]));System.out.println("Passed "+checks+" market/rotation checks.");}
}
