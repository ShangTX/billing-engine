package cn.shang.charging.charge.rules.compositetime;

import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.SimplifiedUnitMeta;
import cn.shang.charging.charge.rules.AbstractTimeBasedRule.RuleState;
import cn.shang.charging.charge.rules.SimplifiedCycleStateHelper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * `compositeTime` 规则中 simplification 相关的周期状态处理器。
 */
final class CompositeTimeSimplifiedCycleStateManager {

    int extractCycleIndex(BillingUnit unit) {
        return SimplifiedCycleStateHelper.extractCycleIndex(unit);
    }

    int getSimplifiedCycleIndex(BillingUnit unit) {
        return SimplifiedCycleStateHelper.getSimplifiedCycleIndex(unit);
    }

    void applyCapWithCarryOverForSimplified(List<BillingUnit> units,
                                            CompositeTimeConfig config,
                                            BigDecimal carryOverAccumulated,
                                            Function<BillingUnit, Boolean> simplifiedChecker) {
        SimplifiedCycleStateHelper.applyCapWithCarryOverForSimplified(
                units,
                config.getMaxChargeOneCycle(),
                carryOverAccumulated,
                simplifiedChecker
        );
    }

    void updateStateAfterUnitBasedSimplified(List<BillingUnit> allUnits,
                                             RuleState state,
                                             int totalCycles,
                                             BigDecimal cycleCapAmount,
                                             LocalDateTime calcBegin,
                                             BiFunction<Integer, LocalDateTime, LocalDateTime> cycleBoundaryResolver,
                                             Function<BillingUnit, Boolean> simplifiedChecker,
                                             Function<BillingUnit, Integer> normalCycleIndexExtractor) {
        if (!allUnits.isEmpty()) {
            BillingUnit lastUnit = allUnits.get(allUnits.size() - 1);
            if (simplifiedChecker.apply(lastUnit)) {
                SimplifiedUnitMeta meta = SimplifiedUnitMeta.from(lastUnit);
                if (meta != null) {
                    state.setCycleIndex(meta.cycleIndex() + meta.simplifiedCycleCount() - 1);
                    state.setCycleAccumulated(meta.simplifiedCycleAmount());
                    state.setCycleBoundary(cycleBoundaryResolver.apply(meta.cycleIndex() + meta.simplifiedCycleCount(), calcBegin));
                    return;
                }
            }

            int lastCycleIndex = normalCycleIndexExtractor.apply(lastUnit);
            state.setCycleIndex(lastCycleIndex);
            final int finalLastCycleIndex = lastCycleIndex;
            BigDecimal lastCycleAccumulated = allUnits.stream()
                    .filter(unit -> !simplifiedChecker.apply(unit) && normalCycleIndexExtractor.apply(unit) == finalLastCycleIndex)
                    .map(BillingUnit::getChargedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            state.setCycleAccumulated(lastCycleAccumulated);
            state.setCycleBoundary(cycleBoundaryResolver.apply(lastCycleIndex + 1, calcBegin));
        } else {
            state.setCycleIndex(totalCycles);
            state.setCycleAccumulated(cycleCapAmount);
            state.setCycleBoundary(cycleBoundaryResolver.apply(totalCycles + 1, calcBegin));
        }
    }

    void updateStateAfterContinuousSimplified(List<BillingUnit> allUnits,
                                              RuleState state,
                                              int totalCycles,
                                              BigDecimal cycleCapAmount,
                                              int bubbleExtension,
                                              LocalDateTime fallbackCycleEnd,
                                              LocalDateTime calcBegin,
                                              BiFunction<Integer, LocalDateTime, LocalDateTime> cycleBoundaryResolver,
                                              Function<BillingUnit, Boolean> simplifiedChecker) {
        if (!allUnits.isEmpty()) {
            BillingUnit lastUnit = allUnits.get(allUnits.size() - 1);
            if (simplifiedChecker.apply(lastUnit)) {
                SimplifiedUnitMeta meta = SimplifiedUnitMeta.from(lastUnit);
                if (meta != null) {
                    state.setCycleAccumulated(meta.simplifiedCycleAmount());
                    state.setCycleIndex(meta.cycleIndex() + meta.simplifiedCycleCount() - 1);
                    if (state.getCycleBoundary() != null) {
                        state.setCycleBoundary(state.getCycleBoundary().plusMinutes(bubbleExtension));
                    } else {
                        state.setCycleBoundary(cycleBoundaryResolver.apply(meta.cycleIndex() + meta.simplifiedCycleCount(), calcBegin)
                                .plusMinutes(bubbleExtension));
                    }
                    return;
                }
            }

            state.setCycleAccumulated(BigDecimal.ZERO);
            state.setCycleIndex(state.getCycleIndex() + totalCycles - 1);
            if (state.getCycleBoundary() != null) {
                state.setCycleBoundary(state.getCycleBoundary().plusMinutes(bubbleExtension));
            } else {
                state.setCycleBoundary(fallbackCycleEnd.plusMinutes(bubbleExtension));
            }
        } else {
            state.setCycleIndex(totalCycles);
            state.setCycleAccumulated(cycleCapAmount);
            state.setCycleBoundary(cycleBoundaryResolver.apply(totalCycles + 1, calcBegin));
        }
    }

    void updateStateAfterPlainUnitBased(List<BillingUnit> billingUnits,
                                        RuleState state,
                                        int totalCycles,
                                        BigDecimal cycleCapAmount,
                                        LocalDateTime calcBegin,
                                        BiFunction<Integer, LocalDateTime, LocalDateTime> cycleBoundaryResolver,
                                        Function<BillingUnit, Integer> normalCycleIndexExtractor) {
        if (!billingUnits.isEmpty()) {
            BillingUnit lastUnit = billingUnits.get(billingUnits.size() - 1);
            int lastCycleIndex = normalCycleIndexExtractor.apply(lastUnit);
            state.setCycleIndex(lastCycleIndex);
            final int finalLastCycleIndex = lastCycleIndex;
            BigDecimal lastCycleAccumulated = billingUnits.stream()
                    .filter(unit -> normalCycleIndexExtractor.apply(unit) == finalLastCycleIndex)
                    .map(BillingUnit::getChargedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            state.setCycleAccumulated(lastCycleAccumulated);
            state.setCycleBoundary(cycleBoundaryResolver.apply(lastCycleIndex + 1, calcBegin));
        } else {
            state.setCycleIndex(totalCycles);
            state.setCycleAccumulated(cycleCapAmount);
            state.setCycleBoundary(cycleBoundaryResolver.apply(totalCycles + 1, calcBegin));
        }
    }

    void updateStateAfterPlainContinuous(List<?> cycles,
                                         RuleState state,
                                         BigDecimal lastCycleAccumulated,
                                         int bubbleExtension,
                                         Function<Object, LocalDateTime> cycleStartExtractor,
                                         int cycleMinutes) {
        if (!cycles.isEmpty()) {
            state.setCycleIndex(state.getCycleIndex() + cycles.size() - 1);
            if (state.getCycleBoundary() != null) {
                state.setCycleBoundary(state.getCycleBoundary().plusMinutes(bubbleExtension));
            } else {
                Object lastCycle = cycles.get(cycles.size() - 1);
                state.setCycleBoundary(cycleStartExtractor.apply(lastCycle).plusMinutes(cycleMinutes).plusMinutes(bubbleExtension));
            }
            state.setCycleAccumulated(lastCycleAccumulated);
        }
    }
}
