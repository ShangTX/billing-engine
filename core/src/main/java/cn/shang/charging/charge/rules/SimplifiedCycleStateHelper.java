package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.SimplifiedUnitMeta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Simplification 周期状态处理的公共工具方法。
 * <p>
 * 提炼自 RelativeTimeSimplifiedCycleStateManager 与 CompositeTimeSimplifiedCycleStateManager，
 * 两个规则的实现完全一致。
 */
public final class SimplifiedCycleStateHelper {

    /**
     * 从 BillingUnit 中提取周期索引（非简化单元）。
     */
    public static int extractCycleIndex(BillingUnit unit) {
        if (unit.getRuleData() instanceof Integer cycleIndex) {
            return cycleIndex;
        }
        return 0;
    }

    /**
     * 从简化单元中获取起始周期索引。
     */
    public static int getSimplifiedCycleIndex(BillingUnit unit) {
        SimplifiedUnitMeta meta = SimplifiedUnitMeta.from(unit);
        return meta != null ? meta.cycleIndex() : 0;
    }

    /**
     * 应用简化计算结果的周期封顶（考虑结转累计金额）。
     * <p>
     * 核心逻辑：
     * 1. 按周期索引分组单元
     * 2. 简化单元跳过（已达封顶）
     * 3. 对非简化周期应用封顶，按比例削减或置零
     *
     * @param units             计费单元列表（会被修改）
     * @param maxCharge         周期封顶金额
     * @param carryOverAccumulated 结转累计金额
     * @param simplifiedChecker 判断是否为简化单元的函数
     */
    public static void applyCapWithCarryOverForSimplified(
            List<BillingUnit> units,
            BigDecimal maxCharge,
            BigDecimal carryOverAccumulated,
            Function<BillingUnit, Boolean> simplifiedChecker) {

        Map<Integer, List<BillingUnit>> cycleGroups = new LinkedHashMap<>();
        for (BillingUnit unit : units) {
            int cycleIndex = simplifiedChecker.apply(unit)
                    ? getSimplifiedCycleIndex(unit)
                    : extractCycleIndex(unit);
            cycleGroups.computeIfAbsent(cycleIndex, key -> new ArrayList<>()).add(unit);
        }

        BigDecimal accumulated = carryOverAccumulated;

        for (Map.Entry<Integer, List<BillingUnit>> entry : cycleGroups.entrySet()) {
            List<BillingUnit> cycleUnits = entry.getValue();

            // 简化单元已达封顶，重置累计后跳过
            if (cycleUnits.size() == 1 && simplifiedChecker.apply(cycleUnits.get(0))) {
                accumulated = BigDecimal.ZERO;
                continue;
            }

            BigDecimal cycleAmount = cycleUnits.stream()
                    .map(BillingUnit::getChargedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal totalAccumulated = accumulated.add(cycleAmount);

            if (totalAccumulated.compareTo(maxCharge) > 0) {
                BigDecimal maxAllowed = maxCharge.subtract(accumulated).max(BigDecimal.ZERO);
                if (maxAllowed.compareTo(BigDecimal.ZERO) <= 0) {
                    // 已达封顶，全部置零
                    for (BillingUnit unit : cycleUnits) {
                        if (!unit.isFree()) {
                            unit.setChargedAmount(BigDecimal.ZERO);
                            unit.setFree(true);
                            unit.setFreePromotionId("CYCLE_CAP");
                        }
                    }
                } else {
                    // 按比例削减
                    BigDecimal ratio = maxAllowed.divide(cycleAmount, 6, RoundingMode.HALF_UP);
                    for (BillingUnit unit : cycleUnits) {
                        if (!unit.isFree()) {
                            BigDecimal newAmount = unit.getChargedAmount().multiply(ratio)
                                    .setScale(2, RoundingMode.HALF_UP);
                            unit.setChargedAmount(newAmount);
                            if (newAmount.compareTo(BigDecimal.ZERO) == 0) {
                                unit.setFree(true);
                                unit.setFreePromotionId("CYCLE_CAP");
                            }
                        }
                    }
                }
                accumulated = maxCharge;
            } else {
                accumulated = totalAccumulated;
            }

            // 新周期重置
            accumulated = BigDecimal.ZERO;
        }
    }

    private SimplifiedCycleStateHelper() {
    }
}