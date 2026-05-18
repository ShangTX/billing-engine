package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.pojo.BillingUnit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;

/**
 * 连续模式封顶处理器公共基类。
 * <p>
 * 提炼自 RelativeTimeContinuousCapHandler 与 CompositeTimeContinuousCapHandler，
 * 核心逻辑完全一致：累计判断、封顶截断、合并免费单元。
 */
public abstract class AbstractContinuousCapHandler {

    /**
     * 应用周期封顶（考虑结转的累计金额）。
     *
     * @param units             计费单元列表（会被修改）
     * @param maxCharge         周期封顶金额
     * @param carryOverAccumulated 结转累计金额
     * @return 封顶后的累计金额
     */
    public BigDecimal applyWithCarryOver(List<BillingUnit> units, BigDecimal maxCharge, BigDecimal carryOverAccumulated) {
        if (maxCharge == null || maxCharge.compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal total = carryOverAccumulated;
            for (BillingUnit unit : units) {
                if (!unit.isFree()) {
                    total = total.add(unit.getChargedAmount());
                }
            }
            return total;
        }

        if (carryOverAccumulated.compareTo(maxCharge) >= 0) {
            mergeAllChargeableAsFreeCapUnit(units);
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
        if (units.get(capIndex).getChargedAmount().compareTo(BigDecimal.ZERO) == 0) {
            units.get(capIndex).setFree(true);
            units.get(capIndex).setFreePromotionId(getCapPromotionId());
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
                    .freePromotionId(getCapPromotionId())
                    .chargedAmount(BigDecimal.ZERO)
                    .build();

            units.subList(capIndex + 1, units.size()).clear();
            units.add(mergedFreeUnit);
        }

        return maxCharge;
    }

    /**
     * 合并所有可收费单元为一个免费封顶单元。
     */
    protected void mergeAllChargeableAsFreeCapUnit(List<BillingUnit> units) {
        List<BillingUnit> chargeableUnits = units.stream()
                .filter(unit -> !unit.isFree())
                .toList();
        if (chargeableUnits.isEmpty()) {
            return;
        }

        BillingUnit firstChargeable = chargeableUnits.get(0);
        BillingUnit lastChargeable = chargeableUnits.get(chargeableUnits.size() - 1);
        units.removeIf(unit -> !unit.isFree());

        BillingUnit mergedFreeUnit = BillingUnit.builder()
                .beginTime(firstChargeable.getBeginTime())
                .endTime(lastChargeable.getEndTime())
                .durationMinutes((int) Duration.between(firstChargeable.getBeginTime(), lastChargeable.getEndTime()).toMinutes())
                .unitPrice(BigDecimal.ZERO)
                .originalAmount(BigDecimal.ZERO)
                .free(true)
                .freePromotionId(getCapPromotionId())
                .chargedAmount(BigDecimal.ZERO)
                .build();
        units.add(mergedFreeUnit);
    }

    /**
     * 封顶标识（子类可覆盖）。
     */
    protected String getCapPromotionId() {
        return "CYCLE_CAP";
    }
}