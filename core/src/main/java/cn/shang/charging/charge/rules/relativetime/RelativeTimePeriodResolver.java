package cn.shang.charging.charge.rules.relativetime;

import java.time.LocalDateTime;
import java.util.List;

/**
 * `relativeTime` 规则的时段定位器。
 * 负责根据周期内分钟数定位当前时段，并提供时段边界时间。
 */
final class RelativeTimePeriodResolver {

    RelativeTimePeriod findPeriodForMinute(int minute, List<RelativeTimePeriod> periods) {
        for (RelativeTimePeriod period : periods) {
            if (minute >= period.getBeginMinute() && minute < period.getEndMinute()) {
                return period;
            }
        }
        return periods.get(periods.size() - 1);
    }

    LocalDateTime resolvePeriodEnd(LocalDateTime cycleStart, RelativeTimePeriod period) {
        return cycleStart.plusMinutes(period.getEndMinute());
    }
}
