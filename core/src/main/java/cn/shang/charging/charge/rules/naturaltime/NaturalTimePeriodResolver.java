package cn.shang.charging.charge.rules.naturaltime;

import cn.shang.charging.charge.rules.compositetime.NaturalPeriod;

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
        int begin = period.getBeginMinute();
        int end = period.getEndMinute();

        if (begin < end) {
            // 不跨天的时段
            return minute >= begin && minute < end;
        } else {
            // 跨天时段（begin > end，如 22:00 到次日 06:00）
            return minute >= begin || minute < end;
        }
    }

    /**
     * 查找下一个时段边界
     */
    int findNextPeriodBoundary(int currentMinute, List<NaturalPeriod> periods) {
        NaturalPeriod current = findPeriodByMinute(currentMinute, periods);
        return current.getEndMinute();
    }
}