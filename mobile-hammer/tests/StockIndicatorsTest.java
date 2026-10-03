package com.aitrader.hammer1430;

import java.util.ArrayList;
import java.util.List;

/** Pure-Java checks for the daily indicators shown beside the APK candles. */
public final class StockIndicatorsTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void near(double actual,double expected,String message){
        check(Math.abs(actual-expected)<1e-8,message+": "+actual+" != "+expected);
    }
    public static void main(String[] args){
        List<DailyBar> rising=new ArrayList<>();
        for(int i=0;i<40;i++){
            double close=10+i;
            rising.add(new DailyBar(String.format("2026-08-%02d",i+1),close-.5,close+1,close-1,close,100+i));
        }
        StockIndicators indicator=new StockIndicators(rising);
        check(Double.isNaN(indicator.ma5[3]),"MA5 needs five closes");
        near(indicator.ma5[4],12,"MA5 uses five closing prices");
        near(indicator.ma20[19],19.5,"MA20 uses 20 closing prices");
        near(indicator.bollMid[19],indicator.ma20[19],"BOLL middle is MA20");
        check(indicator.bollUpper[19]>indicator.bollMid[19]&&indicator.bollLower[19]<indicator.bollMid[19],"BOLL envelopes close");
        check(indicator.dif[39]>indicator.dea[39],"MACD trend rises");
        near(indicator.rsi14[39],100,"RSI14 reaches 100 with gains only");
        check(Double.isNaN(indicator.viPlus[13])&&Double.isFinite(indicator.viPlus[14]),"VI14 needs 14 movements");
        check(indicator.viPlus[39]>indicator.viMinus[39],"VI14 positive trend dominates");
        List<DailyBar> flat=new ArrayList<>();
        for(int i=0;i<30;i++)flat.add(new DailyBar("2026-08-"+i,10,11,9,10,100));
        StockIndicators sideways=new StockIndicators(flat);
        near(sideways.rsi14[29],50,"RSI14 is neutral with no gains or losses");
        near(sideways.macd[29],0,"MACD remains zero at a constant close");
        near(sideways.viPlus[29],sideways.viMinus[29],"VI14 balances symmetric movement");
        System.out.println("StockIndicatorsTest: "+checks+" checks passed");
    }
}
