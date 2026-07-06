package cn.shang.charging.charge.rules.compositetime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
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
 */
final class CompositeTimeSemantics implements RuleSemantics<CompositeTimeConfig> {

    private static final int MINUTES_PER_CYCLE = 1440;

    private final CompositeTimePeriodResolver periodResolver = new CompositeTimePeriodResolver();

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

    private CompositePeriod resolvePeriod(LocalDateTime time, CompositeTimeConfig config, LocalDateTime cycleOrigin) {
        long minutesFromOrigin = Duration.between(cycleOrigin, time).toMinutes();
        int positionInCycle = (int) (((minutesFromOrigin % MINUTES_PER_CYCLE) + MINUTES_PER_CYCLE) % MINUTES_PER_CYCLE);
        List<CompositePeriod> periods = config.getPeriods();
        return periodResolver.findPeriodForMinute(positionInCycle, periods);
    }

    @Override
    public BigDecimal cycleCap(CompositeTimeConfig config) {
        return config.getMaxChargeOneCycle();
    }

    @Override
    public BConstants.IncompleteUnitChargeMode incompleteMode(CompositeTimeConfig config) {
        return config.getIncompleteUnitChargeMode();
    }

    @Override
    public Integer thresholdMinutes(CompositeTimeConfig config) {
        return config.getThresholdMinutes();
    }

    @Override
    public BigDecimal thresholdRatio(CompositeTimeConfig config) {
        return config.getThresholdRatio();
    }
}
