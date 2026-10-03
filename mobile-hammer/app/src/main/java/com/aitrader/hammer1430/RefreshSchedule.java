package com.aitrader.hammer1430;

import java.time.*;

/** Uses elapsed time, so changing the phone clock cannot delay refreshes. */
final class RefreshSchedule {
    static final long INTERVAL_MS=10*60*1000L;
    private long next=0;
    boolean due(long now){return now>=next;}
    void started(long now){next=now+INTERVAL_MS;}
    long delay(long now){return Math.max(0,next-now);}
    static boolean inWindow(long wallTime){
        LocalTime time=Instant.ofEpochMilli(wallTime).atZone(ZoneId.of("Asia/Shanghai")).toLocalTime();
        return !time.isBefore(LocalTime.of(9,0))&&time.isBefore(LocalTime.of(15,31));
    }
    boolean automaticDue(long elapsed,long wallTime){return inWindow(wallTime)&&due(elapsed);}
    long automaticDelay(long elapsed,long wallTime){
        if(inWindow(wallTime))return Math.max(1000,Math.min(INTERVAL_MS,delay(elapsed)));
        ZonedDateTime now=Instant.ofEpochMilli(wallTime).atZone(ZoneId.of("Asia/Shanghai"));
        ZonedDateTime opening=now.toLocalDate().atTime(9,0).atZone(now.getZone());
        if(!opening.isAfter(now))opening=opening.plusDays(1);
        return Math.max(1000,Duration.between(now,opening).toMillis());
    }
}
