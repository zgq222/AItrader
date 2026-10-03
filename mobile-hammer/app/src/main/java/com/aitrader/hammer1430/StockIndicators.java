package com.aitrader.hammer1430;

import java.util.List;

/** Daily indicators calculated from the same unadjusted bars used by the APK screens. */
final class StockIndicators {
    final double[] ma5, ma10, ma20, bollMid, bollUpper, bollLower;
    final double[] dif, dea, macd, k, d, j, rsi14, viPlus, viMinus;

    StockIndicators(List<DailyBar> bars) {
        int count=bars.size();
        ma5=empty(count);ma10=empty(count);ma20=empty(count);
        bollMid=empty(count);bollUpper=empty(count);bollLower=empty(count);
        dif=empty(count);dea=empty(count);macd=empty(count);
        k=empty(count);d=empty(count);j=empty(count);rsi14=empty(count);
        viPlus=empty(count);viMinus=empty(count);
        double ema12=Double.NaN,ema26=Double.NaN,signal=Double.NaN;
        double priorK=50,priorD=50,avgGain=0,avgLoss=0;
        double[] trueRange=empty(count),positive=empty(count),negative=empty(count);
        for(int index=0;index<count;index++) {
            DailyBar bar=bars.get(index);
            ma5[index]=meanClose(bars,index,5);
            ma10[index]=meanClose(bars,index,10);
            ma20[index]=meanClose(bars,index,20);
            if(index>=19) {
                double squared=0;
                for(int at=index-19;at<=index;at++) {
                    double diff=bars.get(at).close-ma20[index];squared+=diff*diff;
                }
                bollMid[index]=ma20[index];
                double deviation=Math.sqrt(squared/20.0);
                bollUpper[index]=ma20[index]+2*deviation;
                bollLower[index]=ma20[index]-2*deviation;
            }
            ema12=Double.isNaN(ema12)?bar.close:ema12+(bar.close-ema12)*2.0/13.0;
            ema26=Double.isNaN(ema26)?bar.close:ema26+(bar.close-ema26)*2.0/27.0;
            dif[index]=ema12-ema26;
            signal=Double.isNaN(signal)?dif[index]:signal+(dif[index]-signal)*2.0/10.0;
            dea[index]=signal;macd[index]=2*(dif[index]-signal);
            double high=Double.NEGATIVE_INFINITY,low=Double.POSITIVE_INFINITY;
            for(int at=Math.max(0,index-8);at<=index;at++) {
                high=Math.max(high,bars.get(at).high);
                low=Math.min(low,bars.get(at).low);
            }
            double rsv=high>low?(bar.close-low)/(high-low)*100:50;
            priorK=(2*priorK+rsv)/3;priorD=(2*priorD+priorK)/3;
            k[index]=priorK;d[index]=priorD;j[index]=3*priorK-2*priorD;
            if(index>0) {
                DailyBar previous=bars.get(index-1);
                double difference=bar.close-previous.close;
                double gain=Math.max(0,difference),loss=Math.max(0,-difference);
                if(index<=14) {avgGain+=gain;avgLoss+=loss;}
                else {avgGain=(avgGain*13+gain)/14;avgLoss=(avgLoss*13+loss)/14;}
                if(index>=14) {
                    double averageGain=index==14?avgGain/14:avgGain;
                    double averageLoss=index==14?avgLoss/14:avgLoss;
                    if(index==14){avgGain=averageGain;avgLoss=averageLoss;}
                    rsi14[index]=averageLoss==0?(averageGain==0?50:100)
                            :100-100/(1+averageGain/averageLoss);
                }
                trueRange[index]=Math.max(bar.high-bar.low,Math.max(Math.abs(bar.high-previous.close),Math.abs(bar.low-previous.close)));
                positive[index]=Math.abs(bar.high-previous.low);
                negative[index]=Math.abs(bar.low-previous.high);
                if(index>=14) {
                    double rangeSum=0,plusSum=0,minusSum=0;
                    for(int at=index-13;at<=index;at++) {
                        rangeSum+=trueRange[at];plusSum+=positive[at];minusSum+=negative[at];
                    }
                    if(rangeSum>0) {viPlus[index]=plusSum/rangeSum;viMinus[index]=minusSum/rangeSum;}
                }
            }
        }
    }

    private static double[] empty(int count) {
        double[] values=new double[count];
        java.util.Arrays.fill(values,Double.NaN);return values;
    }
    private static double meanClose(List<DailyBar> bars,int index,int period) {
        if(index+1<period)return Double.NaN;
        double sum=0;
        for(int at=index-period+1;at<=index;at++)sum+=bars.get(at).close;
        return sum/period;
    }
}
