package cn.shang.charging.charge.rules.naturaltime;

import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.charge.rules.BoundaryProvider;
import cn.shang.charging.charge.rules.HomogeneousSegment;
import cn.shang.charging.charge.rules.RuleSemantics;
import cn.shang.charging.charge.rules.compositetime.NaturalPeriod;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * `naturalTime` 规则族语义：滑动 24h 窗口周期 + 全局单元 + 每日封顶。
 * <p>
 * 周期切换按滑动窗口（dayStart = segEndTime，nextCycleBoundary = segEndTime + 1440），
 * 与 DayNight/RelativeTime/CompositeTime 的固定周期不同：seg 可能不对齐 24h 边界，
 * 切换后 dayStart 跳到 seg.endTime。cap 标记 "CYCLE_CAP"。
 * <p>
 * TODO-20260706-002 阶段3。
 * TODO-20260706-002 阶段4：加 priceAt / periodBoundaryProvider，时长模式通用化。
 */
final class NaturalTimeSemantics implements RuleSemantics<NaturalTimeConfig> {

    private static final int MINUTES_PER_CYCLE = 1440;

    private final NaturalTimePeriodResolver periodResolver = new NaturalTimePeriodResolver();
    private final NaturalTimeCrossPeriodPriceResolver priceResolver = new NaturalTimeCrossPeriodPriceResolver();

    @Override
    public LocalDateTime cycleOrigin(BillingContext context) {
        // NaturalTime 封顶用滑动窗口（calcBegin 起算），cycleOrigin 不参与封顶切换
        return context.getBeginTime();
    }

    @Override
    public LocalDateTime initialCycleBoundary(LocalDateTime cycleOrigin, LocalDateTime calcBegin) {
        // dayStart = calcBegin，首个边界 = calcBegin + 1440
        return calcBegin.plusMinutes(MINUTES_PER_CYCLE);
    }

    @Override
    public boolean isCycleBoundary(HomogeneousSegment seg, LocalDateTime currentCycleBoundary, LocalDateTime cycleOrigin) {
        // seg.endTime >= dayStart + 1440
        return !seg.getEndTime().isBefore(currentCycleBoundary);
    }

    @Override
    public LocalDateTime nextCycleBoundary(LocalDateTime segEndTime, LocalDateTime currentCycleBoundary, LocalDateTime cycleOrigin) {
        // 滑动：dayStart = seg.endTime，下个边界 = seg.endTime + 1440
        return segEndTime.plusMinutes(MINUTES_PER_CYCLE);
    }

    @Override
    public int unitMinutes(LocalDateTime time, NaturalTimeConfig config, LocalDateTime cycleOrigin) {
        return config.getUnitMinutes();
    }

    @Override
    public BigDecimal priceAt(LocalDateTime begin, LocalDateTime end, NaturalTimeConfig config, LocalDateTime cycleOrigin) {
        return priceResolver.calculateUnitPrice(begin, end, config.getPeriods());
    }

    /**
     * 自然时段边界 provider：从当前自然日内分钟算到下一个 period.endMinute。
     * 从原 NaturalTimeContinuousStrategy.calculate 的 period 边界 lambda 搬来。
     */
    @Override
    public BoundaryProvider periodBoundaryProvider(NaturalTimeConfig config, LocalDateTime cycleOrigin) {
        List<NaturalPeriod> periods = config.getPeriods();
        return (current, end) -> {
            LocalDateTime periodBoundary = periodResolver.findNextPeriodBoundary(current, periods);
            if (periodBoundary.isAfter(current) && !periodBoundary.isAfter(end)) {
                return periodBoundary;
            }
            return null;
        };
    }

    @Override
    public BigDecimal cycleCap(NaturalTimeConfig config) {
        return config.getMaxChargeOneDay();
    }
}
