package cn.shang.charging.charge.rules.relativetime;

import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.charge.rules.BoundaryProvider;
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
 * TODO-20260706-002 阶段4：加 priceAt / periodBoundaryProvider，时长模式通用化。
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
    public BigDecimal priceAt(LocalDateTime begin, LocalDateTime end, RelativeTimeConfig config, LocalDateTime cycleOrigin) {
        // 时长模式不按单元对齐切断，同质段由 periodBoundaryProvider 切断，priceAt 取段起点的 period 单价
        // （与 CONTINUOUS 段构造 lambda 中 unitPrice = period.getUnitPrice() 一致）
        long minutesFromOrigin = Duration.between(cycleOrigin, begin).toMinutes();
        int positionInCycle = (int) (((minutesFromOrigin % MINUTES_PER_CYCLE) + MINUTES_PER_CYCLE) % MINUTES_PER_CYCLE);
        return periodResolver.findPeriodForMinute(positionInCycle, config.getPeriods()).getUnitPrice();
    }

    @Override
    public String periodKey(LocalDateTime time, RelativeTimeConfig config, LocalDateTime cycleOrigin) {
        RelativeTimePeriod period = resolvePeriod(time, config, cycleOrigin);
        return period.getBeginMinute() + "-" + period.getEndMinute();
    }

    @Override
    public String periodLabel(LocalDateTime time, RelativeTimeConfig config, LocalDateTime cycleOrigin) {
        return periodKey(time, config, cycleOrigin);
    }

    /**
     * period 结束边界 provider：从当前位置算到下一个 period.endMinute。
     * 从原 RelativeTimeContinuousStrategy.calculate 的 period 边界 lambda 搬来。
     */
    @Override
    public BoundaryProvider periodBoundaryProvider(RelativeTimeConfig config, LocalDateTime cycleOrigin) {
        List<RelativeTimePeriod> periods = config.getPeriods();
        return (current, end) -> {
            long minutesFromOrigin = Duration.between(cycleOrigin, current).toMinutes();
            long positionInCycle = ((minutesFromOrigin % MINUTES_PER_CYCLE) + MINUTES_PER_CYCLE) % MINUTES_PER_CYCLE;
            long cycleCount = minutesFromOrigin / MINUTES_PER_CYCLE;
            if (minutesFromOrigin < 0 && minutesFromOrigin % MINUTES_PER_CYCLE != 0) cycleCount--;
            LocalDateTime cycleStart = cycleOrigin.plusMinutes(cycleCount * MINUTES_PER_CYCLE);
            for (RelativeTimePeriod period : periods) {
                long periodEndMinute = period.getEndMinute();
                if (periodEndMinute > positionInCycle) {
                    LocalDateTime boundary = cycleStart.plusMinutes(periodEndMinute);
                    if (boundary.isAfter(current) && !boundary.isAfter(end)) {
                        return boundary;
                    }
                    break;
                }
            }
            return null;
        };
    }

    @Override
    public BigDecimal cycleCap(RelativeTimeConfig config) {
        return config.getMaxChargeOneCycle();
    }

    private RelativeTimePeriod resolvePeriod(LocalDateTime time, RelativeTimeConfig config, LocalDateTime cycleOrigin) {
        long minutesFromOrigin = Duration.between(cycleOrigin, time).toMinutes();
        int positionInCycle = (int) (((minutesFromOrigin % MINUTES_PER_CYCLE) + MINUTES_PER_CYCLE) % MINUTES_PER_CYCLE);
        return periodResolver.findPeriodForMinute(positionInCycle, config.getPeriods());
    }
}
