package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.RuleConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * CONTINUOUS 模式通用策略（层 2）：持有唯一一份 applyCapAndAccumulate，消除 4 规则族重复。
 * <p>
 * 周期切换通过 {@link RuleSemantics#isCycleBoundary} / {@link RuleSemantics#nextCycleBoundary} 注入
 * （含 NaturalTime 滑动窗口）；periodCap 通过 {@link RuleSemantics#hasPeriodCap} /
 * {@link RuleSemantics#periodCap} / {@link RuleSemantics#periodKey} 注入（仅 CompositeTime）；
 * cap 标记通过 {@link RuleSemantics#cycleCapLabel} 注入；unitMinutes 通过
 * {@link RuleSemantics#unitMinutes} 注入（全局/按 period）。
 * <p>
 * TODO-20260706-002 阶段3：4 份 applyCapAndAccumulate 合并为 1 份。
 */
public final class ContinuousStrategy {

    private ContinuousStrategy() {
    }

    /**
     * 把同质段列表转换为 BillingUnit 列表，并应用周期封顶、时段独立封顶、累计金额、compact 合并、截断标记。
     * <p>
     * 通用流程（原 4 份 applyCapAndAccumulate 的公共骨架）：
     * <ol>
     *   <li>subCount / isTruncated / cycleCapped / incompleteFree 判定</li>
     *   <li>periodCap：时段切换时对前一 period 应用独立封顶（仅 hasPeriodCap）</li>
     *   <li>charged 计算（免费/截断/预算封顶）</li>
     *   <li>累计 + 周期切换（isCycleBoundary → 重置 cycleAccumulated + 推进 currentCycleBoundary）</li>
     * </ol>
     * 末尾对最后一个 period 应用 periodCap + 重算累计（hasPeriodCap 时）。
     */
    public static <C extends RuleConfig> List<BillingUnit> applyCapAndAccumulate(
            List<HomogeneousSegment> segments,
            RuleSemantics<C> semantics,
            BillingContext context,
            C config,
            LocalDateTime cycleOrigin,
            LocalDateTime calcBegin,
            BigDecimal carryOverAccumulated) {

        List<BillingUnit> units = new ArrayList<>();
        if (segments.isEmpty()) {
            return units;
        }

        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();
        BigDecimal maxCharge = semantics.cycleCap(config);
        String capLabel = semantics.cycleCapLabel();
        boolean hasPeriodCap = semantics.hasPeriodCap(config);

        BigDecimal cycleAccumulated = carryOverAccumulated != null ? carryOverAccumulated : BigDecimal.ZERO;
        LocalDateTime currentCycleBoundary = semantics.initialCycleBoundary(cycleOrigin, calcBegin);

        // periodCap 跟踪：当前 period key 及其在 units 列表的起始索引
        String currentPeriodKey = null;
        BigDecimal currentPeriodCap = null;
        int periodStartIndex = 0;

        BigDecimal accumulated = BigDecimal.ZERO;

        for (int i = 0; i < segments.size(); i++) {
            HomogeneousSegment seg = segments.get(i);
            boolean isLast = (i == segments.size() - 1);
            int segMinutes = seg.durationMinutes();

            int unitMinutes = semantics.unitMinutes(seg.getBeginTime(), config, cycleOrigin);
            int subCount = unitMinutes > 0 ? segMinutes / unitMinutes : 1;
            if (subCount < 1) subCount = 1;

            boolean isTruncated = isLast
                    && unitMinutes > 0
                    && segMinutes < unitMinutes
                    && seg.getEndTime().equals(calcEnd);

            // periodCap：时段切换时对前一 period 应用独立封顶
            if (hasPeriodCap) {
                String periodKey = semantics.periodKey(seg.getBeginTime(), config, cycleOrigin);
                BigDecimal periodCap = semantics.periodCap(seg.getBeginTime(), config, cycleOrigin);
                if (currentPeriodKey == null) {
                    currentPeriodKey = periodKey;
                    currentPeriodCap = periodCap;
                    periodStartIndex = units.size();
                } else if (!periodKey.equals(currentPeriodKey)) {
                    applyPeriodCapToUnits(units, periodStartIndex, currentPeriodCap);
                    currentPeriodKey = periodKey;
                    currentPeriodCap = periodCap;
                    periodStartIndex = units.size();
                }
            }

            boolean cycleCapped = false;
            if (maxCharge != null && maxCharge.compareTo(BigDecimal.ZERO) > 0
                    && !seg.isFree() && cycleAccumulated.compareTo(maxCharge) >= 0) {
                cycleCapped = true;
            }

            boolean incompleteFree = isTruncated && !seg.isFree() && !cycleCapped
                    && AbstractTimeBasedRule.isIncompleteFree(segMinutes, unitMinutes,
                            semantics.incompleteMode(config),
                            semantics.thresholdMinutes(config),
                            semantics.thresholdRatio(config));

            BigDecimal originalPerSub = seg.getOriginalAmount() != null
                    ? seg.getOriginalAmount() : BigDecimal.ZERO;
            BigDecimal unitPrice = seg.getUnitPrice() != null ? seg.getUnitPrice() : BigDecimal.ZERO;

            BigDecimal charged;
            if (seg.isFree() || cycleCapped || incompleteFree) {
                charged = BigDecimal.ZERO;
            } else if (isTruncated) {
                charged = AbstractTimeBasedRule.computeIncompleteCharge(unitPrice, segMinutes, unitMinutes,
                        semantics.incompleteMode(config),
                        semantics.thresholdMinutes(config),
                        semantics.thresholdRatio(config));
            } else {
                BigDecimal budget = maxCharge != null
                        ? maxCharge.subtract(cycleAccumulated)
                        : null;
                if (budget != null && budget.signum() < 0) budget = BigDecimal.ZERO;
                BigDecimal fullTotal = originalPerSub.multiply(BigDecimal.valueOf(subCount));
                if (budget != null && fullTotal.compareTo(budget) > 0) {
                    charged = budget.setScale(2, RoundingMode.HALF_UP);
                } else {
                    charged = fullTotal;
                }
            }

            accumulated = accumulated.add(charged);
            if (!seg.isFree() && !cycleCapped && !incompleteFree) {
                cycleAccumulated = cycleAccumulated.add(charged);
            }

            boolean isCompact = !isTruncated && subCount > 1;

            BillingUnit unit = BillingUnit.builder()
                    .beginTime(seg.getBeginTime())
                    .endTime(seg.getEndTime())
                    .durationMinutes(segMinutes)
                    .unitPrice(unitPrice)
                    .originalAmount(originalPerSub.multiply(BigDecimal.valueOf(subCount)))
                    .free(seg.isFree() || cycleCapped || incompleteFree)
                    .freePromotionId(cycleCapped ? capLabel
                            : (incompleteFree ? "INCOMPLETE_FREE" : seg.getFreePromotionId()))
                    .chargedAmount(charged)
                    .accumulatedAmount(accumulated)
                    .ruleData(seg.getRuleData())
                    .compact(isCompact)
                    .count(isCompact ? subCount : 1)
                    .isTruncated(isTruncated)
                    .build();
            units.add(unit);

            // 周期切换：seg 越过当前周期边界时重置累计 + 推进边界
            if (semantics.isCycleBoundary(seg, currentCycleBoundary, cycleOrigin)) {
                currentCycleBoundary = semantics.nextCycleBoundary(seg.getEndTime(), currentCycleBoundary, cycleOrigin);
                cycleAccumulated = BigDecimal.ZERO;
            }
        }

        // periodCap：对最后一个 period 应用独立封顶 + 重算累计
        if (hasPeriodCap && currentPeriodKey != null) {
            applyPeriodCapToUnits(units, periodStartIndex, currentPeriodCap);
            recomputeAccumulatedAmounts(units);
        }

        return units;
    }

    /**
     * 对 units 列表中 [startIndex, end) 范围内的收费单元应用时段独立封顶。
     * 从最后一个收费单元开始削减，削减为 0 标记 free + PERIOD_CAP。
     * 削减会破坏 compact 合并前提，命中单元标记为非 compact。
     */
    public static void applyPeriodCapToUnits(List<BillingUnit> units, int startIndex, BigDecimal maxCharge) {
        if (maxCharge == null || maxCharge.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (startIndex >= units.size()) {
            return;
        }
        List<BillingUnit> periodUnits = units.subList(startIndex, units.size());
        List<BillingUnit> chargeableUnits = new ArrayList<>(periodUnits.stream()
                .filter(u -> !u.isFree())
                .toList());

        if (chargeableUnits.isEmpty()) {
            return;
        }

        BigDecimal totalCharge = chargeableUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalCharge.compareTo(maxCharge) <= 0) {
            return;
        }

        BigDecimal excess = totalCharge.subtract(maxCharge);

        for (int i = chargeableUnits.size() - 1; i >= 0 && excess.compareTo(BigDecimal.ZERO) > 0; i--) {
            BillingUnit unit = chargeableUnits.get(i);
            BigDecimal charged = unit.getChargedAmount();

            if (charged.compareTo(excess) >= 0) {
                unit.setChargedAmount(charged.subtract(excess).setScale(2, RoundingMode.HALF_UP));
                if (unit.getChargedAmount().compareTo(BigDecimal.ZERO) == 0) {
                    unit.setFree(true);
                    unit.setFreePromotionId("PERIOD_CAP");
                }
                excess = BigDecimal.ZERO;
            } else {
                unit.setChargedAmount(BigDecimal.ZERO);
                unit.setFree(true);
                unit.setFreePromotionId("PERIOD_CAP");
                excess = excess.subtract(charged);
            }
            // 削减破坏 compact 合并前提，标记为非 compact
            if (unit.isCompact()) {
                unit.setCompact(false);
                unit.setCount(1);
            }
        }
    }

    /**
     * 时段封顶削减后重新计算 accumulatedAmount（削减只改变 chargedAmount，需重算前缀累计）。
     */
    public static void recomputeAccumulatedAmounts(List<BillingUnit> units) {
        BigDecimal accumulated = BigDecimal.ZERO;
        for (BillingUnit unit : units) {
            accumulated = accumulated.add(unit.getChargedAmount());
            unit.setAccumulatedAmount(accumulated);
        }
    }

    /**
     * 计算时间点相对周期起点的偏移分钟数（供 Semantics 实现复用）。
     */
    public static long minutesFromOrigin(LocalDateTime cycleOrigin, LocalDateTime time) {
        return Duration.between(cycleOrigin, time).toMinutes();
    }
}
