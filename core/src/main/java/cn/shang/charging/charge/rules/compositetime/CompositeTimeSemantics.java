package cn.shang.charging.charge.rules.compositetime;

import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.charge.rules.BoundaryProvider;
import cn.shang.charging.charge.rules.HomogeneousSegment;
import cn.shang.charging.charge.rules.RuleSemantics;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * `compositeTime` 规则族语义：基于分段起点的 24h 周期 + 按 period 单元 + 周期封顶 + 时段独立封顶。
 * <p>
 * 周期切换按 billingOrigin 的固定 24h 周期。唯一带 periodCap 的规则族（时段独立封顶）。
 * cap 标记 "CYCLE_CAP"，periodCap 标记 "PERIOD_CAP"。
 * <p>
 * TODO-20260706-002 阶段3。
 * TODO-20260706-002 阶段4：加 priceAt / periodBoundaryProvider，时长模式通用化。
 */
final class CompositeTimeSemantics implements RuleSemantics<CompositeTimeConfig> {

    private static final int MINUTES_PER_CYCLE = 1440;

    private final CompositeTimePeriodResolver periodResolver = new CompositeTimePeriodResolver();
    private final CompositeTimeCrossPeriodPriceResolver crossPeriodPriceResolver = new CompositeTimeCrossPeriodPriceResolver();

    @Override
    public LocalDateTime cycleOrigin(BillingContext context) {
        return context.getSegment().getBeginTime();
    }

    @Override
    public LocalDateTime initialCycleBoundary(LocalDateTime cycleOrigin, LocalDateTime calcBegin) {
        long calcBeginOffset = Duration.between(cycleOrigin, calcBegin).toMinutes();
        long nextCycleBoundaryOffset = ((calcBeginOffset / MINUTES_PER_CYCLE) + 1) * MINUTES_PER_CYCLE;
        return cycleOrigin.plusMinutes(nextCycleBoundaryOffset);
    }

    @Override
    public boolean isCycleBoundary(HomogeneousSegment seg, LocalDateTime currentCycleBoundary, LocalDateTime cycleOrigin) {
        long offsetFromOrigin = Duration.between(cycleOrigin, seg.getEndTime()).toMinutes();
        return offsetFromOrigin >= Duration.between(cycleOrigin, currentCycleBoundary).toMinutes();
    }

    @Override
    public LocalDateTime nextCycleBoundary(LocalDateTime segEndTime, LocalDateTime currentCycleBoundary, LocalDateTime cycleOrigin) {
        long offsetFromOrigin = Duration.between(cycleOrigin, segEndTime).toMinutes();
        long nextOffset = ((offsetFromOrigin / MINUTES_PER_CYCLE) + 1) * MINUTES_PER_CYCLE;
        return cycleOrigin.plusMinutes(nextOffset);
    }

    @Override
    public int unitMinutes(LocalDateTime time, CompositeTimeConfig config, LocalDateTime cycleOrigin) {
        return resolvePeriod(time, config, cycleOrigin).getUnitMinutes();
    }

    @Override
    public boolean hasPeriodCap(CompositeTimeConfig config) {
        if (config.getPeriods() == null) return false;
        for (CompositePeriod period : config.getPeriods()) {
            if (period.getMaxCharge() != null && period.getMaxCharge().compareTo(BigDecimal.ZERO) > 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public BigDecimal periodCap(LocalDateTime time, CompositeTimeConfig config, LocalDateTime cycleOrigin) {
        return resolvePeriod(time, config, cycleOrigin).getMaxCharge();
    }

    @Override
    public String periodKey(LocalDateTime time, CompositeTimeConfig config, LocalDateTime cycleOrigin) {
        CompositePeriod period = resolvePeriod(time, config, cycleOrigin);
        return period.getBeginMinute() + "-" + period.getEndMinute();
    }

    @Override
    public String periodLabel(LocalDateTime time, CompositeTimeConfig config, LocalDateTime cycleOrigin) {
        CompositePeriod relativePeriod = resolvePeriod(time, config, cycleOrigin);
        NaturalPeriod naturalPeriod = resolveNaturalPeriod(time, relativePeriod);
        return "r:" + relativePeriod.getBeginMinute() + "-" + relativePeriod.getEndMinute()
                + "|n:" + naturalPeriod.getBeginMinute() + "-" + naturalPeriod.getEndMinute();
    }

    @Override
    public BigDecimal priceAt(LocalDateTime begin, LocalDateTime end, CompositeTimeConfig config, LocalDateTime cycleOrigin) {
        // periodBoundaryProvider 同时切相对时段边界与自然时段边界，priceAt 取段起点自然时段价格。
        CompositePeriod period = resolvePeriod(begin, config, cycleOrigin);
        return crossPeriodPriceResolver.calculateUnitPrice(begin, end, period);
    }

    /**
     * period 边界 provider：返回下一个相对时段边界或自然时段边界中更近的一个。
     * 从原 CompositeTimeContinuousStrategy.calculateBoundaryDriven 的 period 边界 lambda 搬来。
     */
    @Override
    public BoundaryProvider periodBoundaryProvider(CompositeTimeConfig config, LocalDateTime cycleOrigin) {
        List<CompositePeriod> periods = config.getPeriods();
        return (current, end) -> {
            CompositePeriod currentPeriod = resolvePeriod(current, config, cycleOrigin);
            LocalDateTime relativeBoundary = null;
            long minutesFromOrigin = Duration.between(cycleOrigin, current).toMinutes();
            long positionInCycle = ((minutesFromOrigin % MINUTES_PER_CYCLE) + MINUTES_PER_CYCLE) % MINUTES_PER_CYCLE;
            long cycleCount = minutesFromOrigin / MINUTES_PER_CYCLE;
            if (minutesFromOrigin < 0 && minutesFromOrigin % MINUTES_PER_CYCLE != 0) cycleCount--;
            LocalDateTime cycleStart = cycleOrigin.plusMinutes(cycleCount * MINUTES_PER_CYCLE);
            for (CompositePeriod period : periods) {
                long periodEndMinute = period.getEndMinute();
                if (periodEndMinute > positionInCycle) {
                    LocalDateTime boundary = cycleStart.plusMinutes(periodEndMinute);
                    if (boundary.isAfter(current) && !boundary.isAfter(end)) {
                        relativeBoundary = boundary;
                    }
                    break;
                }
            }
            LocalDateTime naturalBoundary = periodResolver.findNextNaturalPeriodBoundary(
                    current, currentPeriod.getNaturalPeriods());
            if (naturalBoundary != null && (!naturalBoundary.isAfter(current) || naturalBoundary.isAfter(end))) {
                naturalBoundary = null;
            }
            return nearer(relativeBoundary, naturalBoundary);
        };
    }

    private LocalDateTime nearer(LocalDateTime first, LocalDateTime second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isBefore(second) ? first : second;
    }

    private CompositePeriod resolvePeriod(LocalDateTime time, CompositeTimeConfig config, LocalDateTime cycleOrigin) {
        long minutesFromOrigin = Duration.between(cycleOrigin, time).toMinutes();
        int positionInCycle = (int) (((minutesFromOrigin % MINUTES_PER_CYCLE) + MINUTES_PER_CYCLE) % MINUTES_PER_CYCLE);
        List<CompositePeriod> periods = config.getPeriods();
        return periodResolver.findPeriodForMinute(positionInCycle, periods);
    }

    private NaturalPeriod resolveNaturalPeriod(LocalDateTime time, CompositePeriod relativePeriod) {
        int minuteOfDay = time.getHour() * 60 + time.getMinute();
        return periodResolver.findNaturalPeriod(minuteOfDay, relativePeriod.getNaturalPeriods());
    }

    @Override
    public BigDecimal cycleCap(CompositeTimeConfig config) {
        return config.getMaxChargeOneCycle();
    }
}
