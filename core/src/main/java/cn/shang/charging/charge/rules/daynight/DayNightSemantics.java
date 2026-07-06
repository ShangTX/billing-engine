package cn.shang.charging.charge.rules.daynight;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.charge.rules.BoundaryProvider;
import cn.shang.charging.charge.rules.HomogeneousSegment;
import cn.shang.charging.charge.rules.RuleSemantics;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * `dayNight` 规则族语义：自然日周期 + 全局单元 + 每日封顶。
 * <p>
 * 周期切换按自然日 00:00（currentCycleBoundary = calcBegin 次日 00:00，固定推进）。
 * cap 标记 "DAILY_CAP"。
 * <p>
 * TODO-20260706-002 阶段3。
 * TODO-20260706-002 阶段4：加 priceAt / periodBoundaryProvider / periodLabel，时长模式通用化。
 */
final class DayNightSemantics implements RuleSemantics<DayNightConfig> {

    private final DayNightPriceResolver priceResolver = new DayNightPriceResolver();

    @Override
    public LocalDateTime cycleOrigin(BillingContext context) {
        // DayNight 封顶按自然日，cycleOrigin 仅用于边界 provider，封顶切换不依赖它
        return context.getBeginTime();
    }

    @Override
    public LocalDateTime initialCycleBoundary(LocalDateTime cycleOrigin, LocalDateTime calcBegin) {
        // calcBegin 所在自然日的次日 00:00
        return calcBegin.toLocalDate().atStartOfDay().plusDays(1);
    }

    @Override
    public boolean isCycleBoundary(HomogeneousSegment seg, LocalDateTime currentCycleBoundary, LocalDateTime cycleOrigin) {
        // seg 越过自然日边界（seg.endTime >= 次日 00:00）
        return !seg.getEndTime().isBefore(currentCycleBoundary);
    }

    @Override
    public LocalDateTime nextCycleBoundary(LocalDateTime segEndTime, LocalDateTime currentCycleBoundary, LocalDateTime cycleOrigin) {
        // 固定自然日推进（次日 00:00 + 1 day）
        return currentCycleBoundary.plusDays(1);
    }

    @Override
    public int unitMinutes(LocalDateTime time, DayNightConfig config, LocalDateTime cycleOrigin) {
        return config.getUnitMinutes();
    }

    @Override
    public BigDecimal priceAt(LocalDateTime begin, LocalDateTime end, DayNightConfig config, LocalDateTime cycleOrigin) {
        return priceResolver.determineUnitPriceForContinuous(begin, end, config);
    }

    /**
     * 日夜时段边界 provider：检查今天和明天两天的 dayBegin/dayEnd，覆盖 current 到 end 的范围。
     * 从原 DayNightDurationStrategy.calculate 的 dayNightBoundary lambda 搬来。
     */
    @Override
    public BoundaryProvider periodBoundaryProvider(DayNightConfig config, LocalDateTime cycleOrigin) {
        return (current, end) -> {
            List<LocalDateTime> result = new ArrayList<>();
            LocalDateTime day = current.toLocalDate().atStartOfDay();
            // 检查今天和明天两天的 dayBegin/dayEnd，覆盖 current 到 end 的范围
            for (int d = 0; d <= 1; d++) {
                LocalDateTime dayBegin = day.plusMinutes(config.getDayBeginMinute());
                LocalDateTime dayEnd = day.plusMinutes(config.getDayEndMinute());
                if (dayBegin.isAfter(current) && !dayBegin.isAfter(end)) result.add(dayBegin);
                if (dayEnd.isAfter(current) && !dayEnd.isAfter(end)) result.add(dayEnd);
                day = day.plusDays(1);
            }
            return result;
        };
    }

    /**
     * day/night 人类可读标签（按时段配置判断，跨周期返回相同值）。
     * 与原 DayNightDurationStrategy.PeriodResolver.getPeriodLabel 一致。
     */
    @Override
    public String periodLabel(LocalDateTime time, DayNightConfig config, LocalDateTime cycleOrigin) {
        return isInDay(time, config) ? "day" : "night";
    }

    /**
     * period 唯一标识：day/night（与 periodLabel 一致，用于周期内时段切换跟踪）。
     * 与原 DayNightDurationStrategy.PeriodResolver.getPeriodIndex（0=day/1=night）等价。
     */
    @Override
    public String periodKey(LocalDateTime time, DayNightConfig config, LocalDateTime cycleOrigin) {
        return isInDay(time, config) ? "day" : "night";
    }

    private static boolean isInDay(LocalDateTime time, DayNightConfig config) {
        int minute = time.getHour() * 60 + time.getMinute();
        int dayBegin = config.getDayBeginMinute();
        int dayEnd = config.getDayEndMinute();
        if (dayBegin < dayEnd) {
            return minute >= dayBegin && minute < dayEnd;
        }
        return minute >= dayBegin || minute < dayEnd;
    }

    @Override
    public BigDecimal cycleCap(DayNightConfig config) {
        return config.getMaxChargeOneDay();
    }

    @Override
    public String cycleCapLabel() {
        return "DAILY_CAP";
    }

    @Override
    public BConstants.IncompleteUnitChargeMode incompleteMode(DayNightConfig config) {
        return config.getIncompleteUnitChargeMode();
    }

    @Override
    public Integer thresholdMinutes(DayNightConfig config) {
        return config.getThresholdMinutes();
    }

    @Override
    public BigDecimal thresholdRatio(DayNightConfig config) {
        return config.getThresholdRatio();
    }
}
