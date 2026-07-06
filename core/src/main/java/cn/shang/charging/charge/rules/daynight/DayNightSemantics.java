package cn.shang.charging.charge.rules.daynight;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.charge.rules.HomogeneousSegment;
import cn.shang.charging.charge.rules.RuleSemantics;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * `dayNight` 规则族语义：自然日周期 + 全局单元 + 每日封顶。
 * <p>
 * 周期切换按自然日 00:00（currentCycleBoundary = calcBegin 次日 00:00，固定推进）。
 * cap 标记 "DAILY_CAP"。
 * <p>
 * TODO-20260706-002 阶段3。
 */
final class DayNightSemantics implements RuleSemantics<DayNightConfig> {

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
