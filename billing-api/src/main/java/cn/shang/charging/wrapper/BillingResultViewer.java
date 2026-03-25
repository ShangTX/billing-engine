package cn.shang.charging.wrapper;

import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 计费结果视图处理器
 * 负责按查询时间截取结果
 */
public class BillingResultViewer {

    /**
     * 返回指定时间点的视图
     *
     * @param result    完整计算结果
     * @param queryTime 查询时间点
     * @return 截取后的结果
     */
    public BillingResult viewAtTime(BillingResult result, LocalDateTime queryTime) {
        if (queryTime == null || result == null) {
            return result;
        }

        // 1. 截取计费单元
        List<BillingUnit> filteredUnits = filterUnits(result.getUnits(), queryTime);

        // 2. 截取优惠使用情况
        List<PromotionUsage> filteredUsages = filterUsages(result.getPromotionUsages(), queryTime);

        // 3. 重算金额
        BigDecimal filteredAmount = calculateAmount(filteredUnits);

        // 4. 计算查询结果的有效时间窗口
        LocalDateTime effectiveFrom = calculateEffectiveFrom(filteredUnits);
        LocalDateTime effectiveTo = calculateEffectiveTo(filteredUnits);

        // 5. 计算查询结果的 calculationEndTime
        // 取 queryTime 和原始 calculationEndTime 的较小值
        LocalDateTime queryCalcEndTime = result.getCalculationEndTime();
        if (queryCalcEndTime == null || queryTime.isBefore(queryCalcEndTime)) {
            queryCalcEndTime = queryTime;
        }

        // 6. 构建结果（保留原始 carryOver，用于 CONTINUE）
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

    /**
     * 过滤计费单元
     * 保留 endTime <= queryTime 的单元
     */
    private List<BillingUnit> filterUnits(List<BillingUnit> units, LocalDateTime queryTime) {
        if (units == null) {
            return List.of();
        }
        return units.stream()
            .filter(unit -> unit.getEndTime() != null && !unit.getEndTime().isAfter(queryTime))
            .toList();
    }

    /**
     * 过滤并截取优惠使用情况
     */
    private List<PromotionUsage> filterUsages(List<PromotionUsage> usages, LocalDateTime queryTime) {
        if (usages == null) {
            return List.of();
        }
        return usages.stream()
            .map(usage -> truncateUsage(usage, queryTime))
            .filter(usage -> usage != null && usage.getUsedMinutes() > 0)
            .toList();
    }

    /**
     * 截取单个优惠使用记录
     */
    private PromotionUsage truncateUsage(PromotionUsage usage, LocalDateTime queryTime) {
        if (usage == null || usage.getUsedFrom() == null) {
            return null;
        }

        // 如果使用结束时间超过 queryTime，截取
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