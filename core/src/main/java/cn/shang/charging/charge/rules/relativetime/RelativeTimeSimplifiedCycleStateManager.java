package cn.shang.charging.charge.rules.relativetime;

import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.charge.rules.AbstractTimeBasedRule.RuleState;
import cn.shang.charging.charge.rules.SimplifiedCycleStateHelper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * `relativeTime` 规则中 simplification 相关的周期状态处理器。
 */
final class RelativeTimeSimplifiedCycleStateManager {

    int extractCycleIndex(BillingUnit unit) {
        return SimplifiedCycleStateHelper.extractCycleIndex(unit);
    }

    int getSimplifiedCycleIndex(BillingUnit unit) {
        return SimplifiedCycleStateHelper.getSimplifiedCycleIndex(unit);
    }

    void applyCapWithCarryOverForSimplified(List<BillingUnit> units,
                                            RelativeTimeConfig config,
                                            BigDecimal carryOverAccumulated,
                                            Function<BillingUnit, Boolean> simplifiedChecker) {
        SimplifiedCycleStateHelper.applyCapWithCarryOverForSimplified(
                units,
                config.getMaxChargeOneCycle(),
                carryOverAccumulated,
                simplifiedChecker
        );
    }

    void updateStateAfterSimplified(List<BillingUnit> allUnits,
                                    RuleState state,
                                    int totalCycles,
                                    BigDecimal cycleCapAmount,
                                    LocalDateTime calcBegin,
                                    int bubbleExtension,
                                    LocalDateTime fallbackCycleEnd,
                                    BiFunction<Integer, LocalDateTime, LocalDateTime> cycleBoundaryResolver,
                                    Function<BillingUnit, Boolean> simplifiedChecker) {
        if (!allUnits.isEmpty()) {
            BillingUnit lastUnit = allUnits.get(allUnits.size() - 1);
            if (simplifiedChecker.apply(lastUnit)) {
                cn.shang.charging.billing.pojo.SimplifiedUnitMeta meta =
                        cn.shang.charging.billing.pojo.SimplifiedUnitMeta.from(lastUnit);
                if (meta != null) {
                    state.setCycleAccumulated(meta.simplifiedCycleAmount());
                    state.setCycleIndex(meta.cycleIndex() + meta.simplifiedCycleCount() - 1);
                    if (state.getCycleBoundary() != null) {
                        state.setCycleBoundary(state.getCycleBoundary().plusMinutes(bubbleExtension));
                    } else {
                        state.setCycleBoundary(cycleBoundaryResolver.apply(meta.cycleIndex() + meta.simplifiedCycleCount(), calcBegin).plusMinutes(bubbleExtension));
                    }
                }
            } else {
                state.setCycleAccumulated(BigDecimal.ZERO);
                state.setCycleIndex(state.getCycleIndex() + totalCycles - 1);
                if (state.getCycleBoundary() != null) {
                    state.setCycleBoundary(state.getCycleBoundary().plusMinutes(bubbleExtension));
                } else {
                    state.setCycleBoundary(fallbackCycleEnd.plusMinutes(bubbleExtension));
                }
            }
        } else {
            state.setCycleIndex(totalCycles);
            state.setCycleAccumulated(cycleCapAmount);
            state.setCycleBoundary(cycleBoundaryResolver.apply(totalCycles + 1, calcBegin));
        }
    }
}
