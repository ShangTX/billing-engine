package cn.shang.charging.charge.rules.naturaltime;

import cn.shang.charging.charge.rules.compositetime.NaturalPeriod;

import java.util.List;

/**
 * Natural-period helpers for the naturalTime rule family.
 * <p>
 * NaturalTime treats the day as a circular timeline. A period whose end is
 * smaller than its begin crosses midnight. The special period 00:00-00:00 is
 * normalized as a full-day 0-1440 period.
 */
final class NaturalPeriodSupport {

    static final int MINUTES_PER_DAY = 1440;

    private NaturalPeriodSupport() {
    }

    static void validateFullDayCoverage(List<NaturalPeriod> periods) {
        int totalCovered = 0;
        int prevEnd = 0;

        for (int i = 0; i < periods.size(); i++) {
            NaturalPeriod period = periods.get(i);
            validateRange(period, i);

            if (i > 0 && period.getBeginMinute() != prevEnd) {
                throw new IllegalArgumentException("时段不连续");
            }

            totalCovered += durationMinutes(period);
            prevEnd = continuityEndMinute(period);
        }

        if (totalCovered != MINUTES_PER_DAY) {
            throw new IllegalArgumentException("时段未覆盖全天");
        }
    }

    static boolean containsMinute(int minute, NaturalPeriod period) {
        int begin = period.getBeginMinute();
        int end = period.getEndMinute();

        if (isZeroToZeroFullDay(period)) {
            return true;
        }
        if (begin < end) {
            return minute >= begin && minute < end;
        }
        if (begin > end) {
            return minute >= begin || minute < end;
        }
        return false;
    }

    static int boundaryEndMinute(NaturalPeriod period) {
        return isZeroToZeroFullDay(period) ? MINUTES_PER_DAY : period.getEndMinute();
    }

    static String periodLabel(NaturalPeriod period) {
        return period.getBeginMinute() + "-" + boundaryEndMinute(period);
    }

    private static void validateRange(NaturalPeriod period, int index) {
        int begin = period.getBeginMinute();
        int end = period.getEndMinute();

        if (begin < 0 || begin >= MINUTES_PER_DAY) {
            throw new IllegalArgumentException("Invalid period " + index + ": beginMinute must be in [0,1439]");
        }
        if (end < 0 || end > MINUTES_PER_DAY) {
            throw new IllegalArgumentException("Invalid period " + index + ": endMinute must be in [0,1440]");
        }
        if (begin == end && !isZeroToZeroFullDay(period)) {
            throw new IllegalArgumentException("Invalid period " + index + ": beginMinute equals endMinute only supports 00:00-00:00");
        }
    }

    private static int durationMinutes(NaturalPeriod period) {
        int begin = period.getBeginMinute();
        int end = period.getEndMinute();

        if (isZeroToZeroFullDay(period)) {
            return MINUTES_PER_DAY;
        }
        if (begin < end) {
            return end - begin;
        }
        return MINUTES_PER_DAY - begin + end;
    }

    private static int continuityEndMinute(NaturalPeriod period) {
        int end = boundaryEndMinute(period);
        return end == MINUTES_PER_DAY ? 0 : end;
    }

    private static boolean isZeroToZeroFullDay(NaturalPeriod period) {
        return period.getBeginMinute() == 0 && period.getEndMinute() == 0;
    }
}
