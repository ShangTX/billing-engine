package cn.shang.charging.charge.rules.daynight;

import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.SimplifiedUnitMeta;
import cn.shang.charging.charge.rules.AbstractTimeBasedRule.RuleState;
import cn.shang.charging.charge.rules.SimplifiedCycleStateHelper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * `dayNight` 规则的周期状态与封顶处理器。
 * <p>
 * 当前阶段只抽离状态推进与封顶处理，不改变原有计费语义。
 */
final class DayNightCycleStateManager {

    int extractCycleIndex(BillingUnit unit) {
        return SimplifiedCycleStateHelper.extractCycleIndex(unit);
    }

    int getSimplifiedCycleIndex(BillingUnit unit) {
        return SimplifiedCycleStateHelper.getSimplifiedCycleIndex(unit);
    }

    BigDecimal applyDailyCapWithCarryOver(List<BillingUnit> units,
                                          DayNightConfig config,
                                          BigDecimal carryOverAccumulated,
                                          BiFunction<LocalDateTime, LocalDateTime, cn.shang.charging.billing.value.UnitValueSpec> cappedSpecFactory) {
        BigDecimal maxCharge = config.getMaxChargeOneDay();

        if (carryOverAccumulated.compareTo(maxCharge) >= 0) {
            List<BillingUnit> chargeableUnits = units.stream()
                    .filter(u -> !u.isFree())
                    .toList();

            if (!chargeableUnits.isEmpty()) {
                BillingUnit firstChargeable = chargeableUnits.get(0);
                BillingUnit lastChargeable = chargeableUnits.get(chargeableUnits.size() - 1);

                units.removeIf(u -> !u.isFree());

                BillingUnit mergedFreeUnit = BillingUnit.builder()
                        .beginTime(firstChargeable.getBeginTime())
                        .endTime(lastChargeable.getEndTime())
                        .durationMinutes((int) Duration.between(firstChargeable.getBeginTime(), lastChargeable.getEndTime()).toMinutes())
                        .unitPrice(BigDecimal.ZERO)
                        .originalAmount(BigDecimal.ZERO)
                        .free(true)
                        .freePromotionId("DAILY_CAP")
                        .chargedAmount(BigDecimal.ZERO)
                        .valueSpec(new cn.shang.charging.billing.value.FixedValueSpec(BigDecimal.ZERO))
                        .build();
                units.add(mergedFreeUnit);
            }
            return maxCharge;
        }

        BigDecimal accumulated = carryOverAccumulated;
        int capIndex = -1;
        BigDecimal lastChargeAmount = null;

        for (int i = 0; i < units.size(); i++) {
            BillingUnit unit = units.get(i);
            if (unit.isFree()) {
                continue;
            }

            BigDecimal newAccumulated = accumulated.add(unit.getChargedAmount());

            if (newAccumulated.compareTo(maxCharge) >= 0) {
                capIndex = i;
                lastChargeAmount = maxCharge.subtract(accumulated);
                break;
            }

            accumulated = newAccumulated;
        }

        if (capIndex < 0) {
            return accumulated;
        }

        units.get(capIndex).setChargedAmount(lastChargeAmount.setScale(2, RoundingMode.HALF_UP));
        units.get(capIndex).setValueSpec(cappedSpecFactory.apply(
                units.get(capIndex).getBeginTime(),
                units.get(capIndex).getEndTime()
        ));
        if (units.get(capIndex).getChargedAmount().compareTo(BigDecimal.ZERO) == 0) {
            units.get(capIndex).setFree(true);
            units.get(capIndex).setFreePromotionId("DAILY_CAP");
        }

        if (capIndex < units.size() - 1) {
            BillingUnit firstAfterCap = units.get(capIndex + 1);
            BillingUnit lastAfterCap = units.get(units.size() - 1);

            BillingUnit mergedFreeUnit = BillingUnit.builder()
                    .beginTime(firstAfterCap.getBeginTime())
                    .endTime(lastAfterCap.getEndTime())
                    .durationMinutes((int) Duration.between(firstAfterCap.getBeginTime(), lastAfterCap.getEndTime()).toMinutes())
                    .unitPrice(BigDecimal.ZERO)
                    .originalAmount(BigDecimal.ZERO)
                    .free(true)
                    .freePromotionId("DAILY_CAP")
                    .chargedAmount(BigDecimal.ZERO)
                    .valueSpec(new cn.shang.charging.billing.value.FixedValueSpec(BigDecimal.ZERO))
                    .build();

            units.subList(capIndex + 1, units.size()).clear();
            units.add(mergedFreeUnit);
        }

        return maxCharge;
    }

    void updateStateAfterUnitBased(List<BillingUnit> billingUnits, RuleState state) {
        int maxCycleIndex = billingUnits.stream()
                .mapToInt(u -> extractCycleIndex(u))
                .max().orElse(0);
        state.setCycleIndex(maxCycleIndex);
    }

    void updateStateAfterSimplified(List<BillingUnit> billingUnits,
                                    RuleState state,
                                    LocalDateTime calcBegin,
                                    BiFunction<Integer, LocalDateTime, LocalDateTime> cycleBoundaryResolver,
                                    Function<BillingUnit, Boolean> simplifiedChecker) {
        if (!billingUnits.isEmpty()) {
            BillingUnit lastUnit = billingUnits.get(billingUnits.size() - 1);
            if (simplifiedChecker.apply(lastUnit)) {
                SimplifiedUnitMeta meta = SimplifiedUnitMeta.from(lastUnit);
                if (meta != null) {
                    state.setCycleIndex(meta.cycleIndex() + meta.simplifiedCycleCount() - 1);
                    state.setCycleAccumulated(meta.simplifiedCycleAmount());
                    state.setCycleBoundary(cycleBoundaryResolver.apply(meta.cycleIndex() + meta.simplifiedCycleCount(), calcBegin));
                }
            } else {
                int lastCycleIndex = extractCycleIndex(lastUnit);
                state.setCycleIndex(lastCycleIndex);
                final int finalLastCycleIndex = lastCycleIndex;
                BigDecimal lastCycleAccumulated = billingUnits.stream()
                        .filter(u -> !simplifiedChecker.apply(u) && extractCycleIndex(u) == finalLastCycleIndex)
                        .map(BillingUnit::getChargedAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                state.setCycleAccumulated(lastCycleAccumulated);
                state.setCycleBoundary(cycleBoundaryResolver.apply(lastCycleIndex + 1, calcBegin));
            }
        }
    }
}
