package cn.shang.charging.charge.rules.compositetime;

import java.time.LocalDateTime;
import java.util.List;

/**
 * `compositeTime` 规则的相对时间段与自然时段定位器。
 */
final class CompositeTimePeriodResolver {

    CompositePeriod findPeriodForMinute(int minute, List<CompositePeriod> periods) {
        for (CompositePeriod period : periods) {
            if (minute >= period.getBeginMinute() && minute < period.getEndMinute()) {
                return period;
            }
        }
        return periods.get(periods.size() - 1);
    }

    NaturalPeriod findNaturalPeriod(int minute, List<NaturalPeriod> naturalPeriods) {
        for (NaturalPeriod np : naturalPeriods) {
            if (isInNaturalPeriod(minute, np)) {
                return np;
            }
        }
        return naturalPeriods.get(0);
    }

    LocalDateTime findNextNaturalPeriodBoundary(LocalDateTime current, List<NaturalPeriod> naturalPeriods) {
        int currentMinuteOfDay = current.getHour() * 60 + current.getMinute();

        for (NaturalPeriod np : naturalPeriods) {
            if (isInNaturalPeriod(currentMinuteOfDay, np)) {
                return current.plusMinutes(np.getEndMinute() - currentMinuteOfDay);
            }
        }

        return null;
    }

    boolean isInNaturalPeriod(int minute, NaturalPeriod period) {
        int begin = period.getBeginMinute();
        int end = period.getEndMinute();

        if (begin < end) {
            return minute >= begin && minute < end;
        }
        return minute >= begin || minute < end;
    }
}
