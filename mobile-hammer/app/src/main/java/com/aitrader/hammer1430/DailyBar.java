package com.aitrader.hammer1430;

/** Shared daily candle model for quotes, selections and charts. */
final class DailyBar {
    final String date;
    final double open,high,low,close,volume;
    DailyBar(String date,double open,double high,double low,double close,double volume){
        this.date=date;this.open=open;this.high=high;this.low=low;this.close=close;this.volume=volume;
    }
}
