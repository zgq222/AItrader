package com.aitrader.hammer1430;

public final class RefreshScheduleTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        RefreshSchedule schedule=new RefreshSchedule();
        check(schedule.due(1000),"first open refreshes immediately");
        schedule.started(1000);
        check(!schedule.due(600999),"do not refresh before ten minutes");
        check(schedule.delay(301000)==300000,"page switch preserves remaining five minutes");
        check(schedule.due(601000),"exact ten minute boundary refreshes");
        check(schedule.delay(601000)==0,"due delay is zero");
        schedule.started(601000);
        check(!schedule.due(601001),"no duplicate request in same cycle");
        check(schedule.due(5000000),"returning after missed periods refreshes immediately");
        check(schedule.delay(5000000)==0,"missed periods never create negative delay");
        schedule.started(5000000);
        check(!schedule.due(5000001),"catch-up does not run missed jobs repeatedly");
        check(schedule.delay(5000001)==599999,"catch-up schedules the next ten minute period");
        long opening=java.time.ZonedDateTime.of(2026,10,2,9,0,0,0,java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        check(!RefreshSchedule.inWindow(opening-1),"no auto refresh before 09:00 Shanghai");
        check(RefreshSchedule.inWindow(opening),"09:00 belongs to window");
        check(RefreshSchedule.inWindow(opening+390*60000L),"15:30 belongs to window");
        check(!RefreshSchedule.inWindow(opening+391*60000L),"15:31 pauses auto refresh");
        check(RefreshSchedule.inWindow(opening+180*60000L),"lunch is included in requested 09:00-15:30 window");
        check(!schedule.automaticDue(9000000,opening-1),"elapsed due cannot run outside window");
        check(schedule.automaticDelay(9000000,opening-60000)==60000,"wait exactly until next morning opening");
        check(schedule.automaticDelay(9000000,opening+391*60000L)==(24*60-391)*60000L,"after window waits until next 09:00");
        check(schedule.automaticDue(9000000,opening),"returning in window catches up immediately");
        check(schedule.automaticDelay(9000000,opening)>=1000,"cannot spin with zero handler delay");
        System.out.println("Passed "+checks+" refresh checks.");
    }
}
