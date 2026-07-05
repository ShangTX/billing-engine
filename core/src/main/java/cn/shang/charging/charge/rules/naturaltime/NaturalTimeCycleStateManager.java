package cn.shang.charging.charge.rules.naturaltime;

import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.charge.rules.SimplifiedCycleStateHelper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * `naturalTime` 规则的周期状态与封顶处理器。
 */
final class NaturalTimeCycleStateManager {

    int extractCycleIndex(BillingUnit unit) {
        return SimplifiedCycleStateHelper.extractCycleIndex(unit);
    }

    int getSimplifiedCycleIndex(BillingUnit unit) {
        return SimplifiedCycleStateHelper.getSimplifiedCycleIndex(unit);
    }

    /**
     * 应用每日封顶（带结转）
     */
    BigDecimal applyDailyCapWithCarryOver(List<BillingUnit> units,
                                          BigDecimal maxCharge,
                                          BigDecimal carryOverAccumulated,
                                          LocalDateTime calcBegin) {
        if (carryOverAccumulated.compareTo(maxCharge) >= 0) {
            // 已超过封顶，全部免费
            return handleAllFree(units, maxCharge);
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

        // 封顶截断
        units.get(capIndex).setChargedAmount(lastChargeAmount.setScale(2, RoundingMode.HALF_UP));
        if (units.get(capIndex).getChargedAmount().compareTo(BigDecimal.ZERO) == 0) {
            units.get(capIndex).setFree(true);
            units.get(capIndex).setFreePromotionId("DAILY_CAP");
        }

        // 封顶后的单元合并为免费
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
                    .build();

            units.subList(capIndex + 1, units.size()).clear();
            units.add(mergedFreeUnit);
        }

        return maxCharge;
    }

    private BigDecimal handleAllFree(List<BillingUnit> units, BigDecimal maxCharge) {
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
                    .build();
            units.add(mergedFreeUnit);
        }
        return maxCharge;
    }
}