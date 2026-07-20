package cn.shang.charging.charge.rules.naturaltime;

import cn.shang.charging.charge.rules.compositetime.NaturalPeriod;

import java.time.LocalDateTime;
import java.util.List;

/**
 * `naturalTime` 规则的自然时段定位器。
 * <p>
 * 复用 compositeTime 的时段定位逻辑，但简化为单层时段结构。
 */
final class NaturalTimePeriodResolver {

    /**
     * 根据分钟数查找所属自然时段
     */
    NaturalPeriod findPeriodByMinute(int minuteOfDay, List<NaturalPeriod> periods) {
        for (NaturalPeriod period : periods) {
            if (isInPeriod(minuteOfDay, period)) {
                return period;
            }
        }
        return periods.get(0);
    }

    /**
     * 判断是否在时段内
     */
    boolean isInPeriod(int minute, NaturalPeriod period) {
        return NaturalPeriodSupport.containsMinute(minute, period);
    }

    /**
     * 查找下一个时段边界
     */
    int findNextPeriodBoundary(int currentMinute, List<NaturalPeriod> periods) {
        NaturalPeriod current = findPeriodByMinute(currentMinute, periods);
        return NaturalPeriodSupport.boundaryEndMinute(current);
    }

    /**
     * 查找当前时间之后最近的自然时段边界。
     */
    LocalDateTime findNextPeriodBoundary(LocalDateTime current, List<NaturalPeriod> periods) {
        int currentMinute = current.getHour() * 60 + current.getMinute();
        int periodEnd = findNextPeriodBoundary(currentMinute, periods);
        LocalDateTime boundary = current.toLocalDate().atStartOfDay().plusMinutes(periodEnd);
        if (!boundary.isAfter(current)) {
            boundary = boundary.plusDays(1);
        }
        return boundary;
    }
}
