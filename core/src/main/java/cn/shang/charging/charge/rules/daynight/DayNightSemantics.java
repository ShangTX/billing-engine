package cn.shang.charging.charge.rules.daynight;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.charge.rules.BoundaryProvider;
import cn.shang.charging.charge.rules.HomogeneousSegment;
import cn.shang.charging.charge.rules.RuleSemantics;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * `dayNight` 规则族语义：24h 固定循环周期 + 全局单元 + 每周期封顶。
 * <p>
 * 周期从计费起点（cycleOrigin = beginTime）起算，每 1440 分钟一个周期，边界始终对齐
 * cycleOrigin + N × 1440（与 RelativeTime 一致，非自然日）。cap 标记 "DAILY_CAP"。
 * <p>
 * TODO-20260706-002 阶段3。
 * TODO-20260706-002 阶段4：加 priceAt / periodBoundaryProvider / periodLabel，时长模式通用化。
 */
final class DayNightSemantics implements RuleSemantics<DayNightConfig> {

    private static final int MINUTES_PER_CYCLE = 1440;

    private final DayNightPriceResolver priceResolver = new DayNightPriceResolver();

    @Override
    public LocalDateTime cycleOrigin(BillingContext context) {
        // 周期从计费起点起算的 24h 固定循环（与 RelativeTime 一致）
        return context.getBeginTime();
    }

    @Override
    public LocalDateTime initialCycleBoundary(LocalDateTime cycleOrigin, LocalDateTime calcBegin) {
        // calcBegin 所在 24h 周期的结束边界 = cycleOrigin + ceil((calcBegin-cycleOrigin)/1440) × 1440
        long calcBeginOffset = Duration.between(cycleOrigin, calcBegin).toMinutes();
        long nextCycleBoundaryOffset = ((calcBeginOffset / MINUTES_PER_CYCLE) + 1) * MINUTES_PER_CYCLE;
        return cycleOrigin.plusMinutes(nextCycleBoundaryOffset);
    }

    @Override
    public boolean isCycleBoundary(HomogeneousSegment seg, LocalDateTime currentCycleBoundary, LocalDateTime cycleOrigin) {
        // seg 越过当前 24h 周期边界（seg.endTime >= currentCycleBoundary）
        return !seg.getEndTime().isBefore(currentCycleBoundary);
    }

    @Override
    public LocalDateTime nextCycleBoundary(LocalDateTime segEndTime, LocalDateTime currentCycleBoundary, LocalDateTime cycleOrigin) {
        // 固定 24h 推进：始终对齐 cycleOrigin + N × 1440
        long offsetFromOrigin = Duration.between(cycleOrigin, segEndTime).toMinutes();
        long nextOffset = ((offsetFromOrigin / MINUTES_PER_CYCLE) + 1) * MINUTES_PER_CYCLE;
        return cycleOrigin.plusMinutes(nextOffset);
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
