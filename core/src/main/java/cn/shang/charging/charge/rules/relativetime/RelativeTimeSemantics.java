package cn.shang.charging.charge.rules.relativetime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.charge.rules.HomogeneousSegment;
import cn.shang.charging.charge.rules.RuleSemantics;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * `relativeTime` 规则族语义：基于计费起点的 24h 周期 + 按 period 单元 + 每周期封顶。
 * <p>
 * 周期切换按 cycleOrigin 的固定 24h 周期（offset 越过 nextCycleBoundary）。
 * cap 标记 "CYCLE_CAP"。
 * <p>
 * TODO-20260706-002 阶段3。
 */
final class RelativeTimeSemantics implements RuleSemantics<RelativeTimeConfig> {

    private static final int MINUTES_PER_CYCLE = 1440;

    private final RelativeTimePeriodResolver periodResolver = new RelativeTimePeriodResolver();

    @Override
    public LocalDateTime cycleOrigin(BillingContext context) {
        return context.getBeginTime();
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
    public int unitMinutes(LocalDateTime time, RelativeTimeConfig config, LocalDateTime cycleOrigin) {
        long minutesFromOrigin = Duration.between(cycleOrigin, time).toMinutes();
        int positionInCycle = (int) (((minutesFromOrigin % MINUTES_PER_CYCLE) + MINUTES_PER_CYCLE) % MINUTES_PER_CYCLE);
        List<RelativeTimePeriod> periods = config.getPeriods();
        return periodResolver.findPeriodForMinute(positionInCycle, periods).getUnitMinutes();
    }

    @Override
    public BigDecimal cycleCap(RelativeTimeConfig config) {
        return config.getMaxChargeOneCycle();
    }

    @Override
    public BConstants.IncompleteUnitChargeMode incompleteMode(RelativeTimeConfig config) {
        return config.getIncompleteUnitChargeMode();
    }

    @Override
    public Integer thresholdMinutes(RelativeTimeConfig config) {
        return config.getThresholdMinutes();
    }

    @Override
    public BigDecimal thresholdRatio(RelativeTimeConfig config) {
        return config.getThresholdRatio();
    }
}
