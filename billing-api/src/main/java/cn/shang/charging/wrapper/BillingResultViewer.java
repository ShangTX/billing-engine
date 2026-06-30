package cn.shang.charging.wrapper;

import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.value.FixedValueSpec;
import cn.shang.charging.billing.value.UnitValueEvaluator;
import cn.shang.charging.billing.value.UnitValueProjection;
import cn.shang.charging.billing.value.UnitValueSpec;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 计费结果视图处理器。
 */
public class BillingResultViewer {

    /**
     * 创建查询摘要。
     */
    public QuerySummary createQuerySummary(BillingResult result, LocalDateTime queryTime) {
        if (result == null || queryTime == null) {
            throw new IllegalArgumentException("result 和 queryTime 不能为 null");
        }
        if (result.getCalculationEndTime() != null && queryTime.isAfter(result.getCalculationEndTime())) {
            throw new IllegalArgumentException("queryTime 超出 calculationEndTime");
        }

        List<BillingUnit> units = result.getUnits();
        if (units == null || units.isEmpty()) {
            return QuerySummary.builder()
                    .unitIndex(-1)
                    .amount(BigDecimal.ZERO)
                    .queryTime(queryTime)
                    .promotionUsages(List.of())
                    .build();
        }

        LocalDateTime firstBeginTime = units.get(0).getBeginTime();
        if (queryTime.compareTo(firstBeginTime) <= 0) {
            throw new IllegalArgumentException(
                    "查询时间过早，无对应计费单元。queryTime=" + queryTime +
                            ", firstUnitBeginTime=" + firstBeginTime);
        }

        int unitIndex = findUnitIndex(units, queryTime);
        BigDecimal amount = BigDecimal.ZERO;
        LocalDateTime effectiveTo = null;
        if (unitIndex >= 0) {
            BillingUnit unit = units.get(unitIndex);
            if (unit.getEndTime() != null && queryTime.isAfter(unit.getEndTime())) {
                amount = unit.getAccumulatedAmount() != null ? unit.getAccumulatedAmount() : BigDecimal.ZERO;
                effectiveTo = unit.getEndTime();
            } else {
                UnitValueProjection projection = projectUnitValue(unit, queryTime);
                BigDecimal accumulated = unit.getAccumulatedAmount() != null ? unit.getAccumulatedAmount() : BigDecimal.ZERO;
                BigDecimal charged = unit.getChargedAmount() != null ? unit.getChargedAmount() : BigDecimal.ZERO;
                // `accumulatedAmount` is the prefix total after the full unit settles.
                // Replace the full-unit amount with the unit's query-time projection.
                amount = accumulated.subtract(charged).add(projection.currentAmount());
                effectiveTo = projection.nextChangeTime();
            }
        }

        return QuerySummary.builder()
                .unitIndex(unitIndex)
                .amount(amount)
                .effectiveFrom(units.get(0).getBeginTime())
                .effectiveTo(effectiveTo)
                .queryTime(queryTime)
                .promotionUsages(filterUsages(result.getPromotionUsages(), queryTime))
                .build();
    }

    /**
     * 返回指定时间点的视图。
     */
    public BillingResult viewAtTime(BillingResult result, LocalDateTime queryTime) {
        if (queryTime == null || result == null) {
            return result;
        }

        List<BillingUnit> filteredUnits = filterUnits(result.getUnits(), queryTime);
        List<PromotionUsage> filteredUsages = filterUsages(result.getPromotionUsages(), queryTime);
        BigDecimal filteredAmount = calculateAmount(filteredUnits);
        LocalDateTime effectiveFrom = calculateEffectiveFrom(filteredUnits);
        LocalDateTime effectiveTo = calculateEffectiveTo(filteredUnits);

        LocalDateTime queryCalcEndTime = result.getCalculationEndTime();
        if (queryCalcEndTime == null || queryTime.isBefore(queryCalcEndTime)) {
            queryCalcEndTime = queryTime;
        }

        return BillingResult.builder()
                .units(filteredUnits)
                .promotionUsages(filteredUsages)
                .finalAmount(filteredAmount)
                .effectiveFrom(effectiveFrom)
                .effectiveTo(effectiveTo)
                .carryOver(result.getCarryOver())
                .calculationEndTime(queryCalcEndTime)
                .build();
    }

    private int findUnitIndex(List<BillingUnit> units, LocalDateTime queryTime) {
        for (int i = 0; i < units.size(); i++) {
            BillingUnit unit = units.get(i);
            LocalDateTime beginTime = unit.getBeginTime();
            LocalDateTime endTime = unit.getEndTime();

            if (beginTime != null && endTime != null &&
                    beginTime.isBefore(queryTime) && !queryTime.isAfter(endTime)) {
                return i;
            }
        }

        return units.size() - 1;
    }

    private UnitValueProjection projectUnitValue(BillingUnit unit, LocalDateTime queryTime) {
        if (unit.isCompact() && unit.getCount() > 1) {
            return projectCompactUnit(unit, queryTime);
        }
        UnitValueSpec spec = unit.getValueSpec();
        if (spec == null) {
            // Old results may not carry `valueSpec` yet; treat them as stable full-value units.
            spec = new FixedValueSpec(unit.getChargedAmount() != null ? unit.getChargedAmount() : BigDecimal.ZERO);
        }
        return UnitValueEvaluator.evaluate(spec, queryTime, unit.getBeginTime(), unit.getEndTime());
    }

    /**
     * compact 单元的子单元投影：按子单元时长定位 queryTime 落在第 k 个子单元，
     * 累计金额 = (k+1) * 子单元单价，下一个变化点为第 k+1 个子单元结束。
     */
    private UnitValueProjection projectCompactUnit(BillingUnit unit, LocalDateTime queryTime) {
        int count = unit.getCount();
        int totalMinutes = unit.getDurationMinutes();
        int subDuration = count > 0 ? totalMinutes / count : totalMinutes;
        if (subDuration <= 0) {
            subDuration = totalMinutes;
        }
        long elapsed = Duration.between(unit.getBeginTime(), queryTime).toMinutes();
        int k = subDuration > 0 ? (int) (elapsed / subDuration) : 0;
        if (k < 0) k = 0;
        if (k > count - 1) k = count - 1;
        BigDecimal subPrice = unit.getUnitPrice() != null ? unit.getUnitPrice() : BigDecimal.ZERO;
        BigDecimal currentAmount = subPrice.multiply(BigDecimal.valueOf(k + 1));
        LocalDateTime nextChange = unit.getBeginTime().plusMinutes((long) (k + 1) * subDuration);
        if (nextChange.isAfter(unit.getEndTime())) {
            nextChange = unit.getEndTime();
        }
        return new UnitValueProjection(currentAmount, nextChange);
    }

    private List<BillingUnit> filterUnits(List<BillingUnit> units, LocalDateTime queryTime) {
        if (units == null) {
            return List.of();
        }
        return units.stream()
                .filter(unit -> unit.getEndTime() != null && !unit.getEndTime().isAfter(queryTime))
                .toList();
    }

    private List<PromotionUsage> filterUsages(List<PromotionUsage> usages, LocalDateTime queryTime) {
        if (usages == null) {
            return List.of();
        }
        return usages.stream()
                .map(usage -> truncateUsage(usage, queryTime))
                .filter(usage -> usage != null && usage.getUsedMinutes() > 0)
                .toList();
    }

    private PromotionUsage truncateUsage(PromotionUsage usage, LocalDateTime queryTime) {
        if (usage == null || usage.getUsedFrom() == null) {
            return null;
        }

        if (usage.getUsedTo() != null && usage.getUsedTo().isAfter(queryTime)) {
            long truncatedMinutes = Duration.between(usage.getUsedFrom(), queryTime).toMinutes();

            return PromotionUsage.builder()
                    .promotionId(usage.getPromotionId())
                    .type(usage.getType())
                    .grantedMinutes(usage.getGrantedMinutes())
                    .usedMinutes(truncatedMinutes)
                    .usedFrom(usage.getUsedFrom())
                    .usedTo(queryTime)
                    .build();
        }
        return usage;
    }

    private BigDecimal calculateAmount(List<BillingUnit> units) {
        if (units == null || units.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return units.stream()
                .map(unit -> unit.getChargedAmount() != null ? unit.getChargedAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private LocalDateTime calculateEffectiveFrom(List<BillingUnit> units) {
        if (units == null || units.isEmpty()) {
            return null;
        }
        return units.get(0).getBeginTime();
    }

    private LocalDateTime calculateEffectiveTo(List<BillingUnit> units) {
        if (units == null || units.isEmpty()) {
            return null;
        }
        return units.get(units.size() - 1).getEndTime();
    }
}
