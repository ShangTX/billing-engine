package cn.shang.charging.charge.rules.naturaltime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.charge.rules.HomogeneousSegment;
import cn.shang.charging.charge.rules.RuleSemantics;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * `naturalTime` 规则族语义：滑动 24h 窗口周期 + 全局单元 + 每日封顶。
 * <p>
 * 周期切换按滑动窗口（dayStart = segEndTime，nextCycleBoundary = segEndTime + 1440），
 * 与 DayNight/RelativeTime/CompositeTime 的固定周期不同：seg 可能不对齐 24h 边界，
 * 切换后 dayStart 跳到 seg.endTime。cap 标记 "CYCLE_CAP"。
 * <p>
 * TODO-20260706-002 阶段3。
 */
final class NaturalTimeSemantics implements RuleSemantics<NaturalTimeConfig> {

    private static final int MINUTES_PER_CYCLE = 1440;

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
    public BigDecimal cycleCap(NaturalTimeConfig config) {
        return config.getMaxChargeOneDay();
    }

    @Override
    public BConstants.IncompleteUnitChargeMode incompleteMode(NaturalTimeConfig config) {
        return config.getIncompleteUnitChargeMode();
    }

    @Override
    public Integer thresholdMinutes(NaturalTimeConfig config) {
        return config.getThresholdMinutes();
    }

    @Override
    public BigDecimal thresholdRatio(NaturalTimeConfig config) {
        return config.getThresholdRatio();
    }
}
